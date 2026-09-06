package me.ghost.ffui.nfc

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.ghost.ffui.R
import me.ghost.ffui.data.SettingsDataStore
import java.time.Instant

/**
 * What the next detected NFC tag should do. The UI sets this when the user opens a scan/write
 * dialog; [NfcManager.handleTag] reads it when a tag arrives.
 */
sealed interface NfcMode {
    /** Not waiting for a tag — taps are ignored. */
    data object Idle : NfcMode
    /** Waiting to read a tag and resolve it to a spool or box. */
    data object Reading : NfcMode
    /** Waiting to write [spoolId] to a tag. */
    data class Writing(val spoolId: Int) : NfcMode
    /** Waiting to write a box (location) to a tag. */
    data class WritingBox(val location: String) : NfcMode
}

/** Result of a read attempt, surfaced to the UI once and then consumed. */
sealed interface NfcReadResult {
    /** Tag carried a `SPOOL:<id>` record. The UI resolves [spoolId] against the loaded list. */
    data class Found(val spoolId: Int) : NfcReadResult
    /** Tag carried a `BOX:<location>` record. The UI resolves [location] against derived boxes. */
    data class BoxFound(val location: String) : NfcReadResult
    /** Tag was readable but had no recognisable spool or box payload. */
    data class Unknown(val rawText: String?) : NfcReadResult
    /** The tag could not be read. */
    data class Error(val message: String) : NfcReadResult
}

/** Result of a write attempt, surfaced to the UI once and then consumed. */
sealed interface NfcWriteResult {
    data class Success(val spoolId: Int) : NfcWriteResult
    data class BoxSuccess(val location: String) : NfcWriteResult
    data class Error(val message: String) : NfcWriteResult
}

/**
 * Process-lifetime owner of the NFC scan/write flow. Constructed once in
 * [FfuiApplication][me.ghost.ffui.FfuiApplication] (no DI), alongside the session manager and
 * Spoolman repository.
 *
 * The [MainActivity][me.ghost.ffui.MainActivity] routes foreground-dispatched tag intents here via
 * [handleTag]; Compose screens drive [mode] and observe [readResult] / [writeResult]. The canonical
 * tag payloads are:
 * - **Spool:** a single NDEF text record `SPOOL:<id>`; an optional URI record
 *   (`<spoolman-url>/spool/show/<id>`) is appended when [SettingsDataStore.nfcWriteUrl] is on,
 *   but is never read back.
 * - **Box:** a single NDEF text record `BOX:<location>`. No URI record.
 *
 * @property appContext Application context used to resolve user-facing error strings.
 * @property settings Source of the write-URL flag, the Spoolman base URL, and the local tagged maps.
 * @property scope Process-lifetime scope used for the deferred settings writes (mark-as-tagged).
 */
class NfcManager(
    private val appContext: Context,
    private val settings: SettingsDataStore,
    private val scope: CoroutineScope
) {
    private val _mode = MutableStateFlow<NfcMode>(NfcMode.Idle)
    val mode: StateFlow<NfcMode> = _mode.asStateFlow()

    private val _readResult = MutableStateFlow<NfcReadResult?>(null)
    val readResult: StateFlow<NfcReadResult?> = _readResult.asStateFlow()

    private val _writeResult = MutableStateFlow<NfcWriteResult?>(null)
    val writeResult: StateFlow<NfcWriteResult?> = _writeResult.asStateFlow()

    // Latest settings values, cached so handleTag (called from the Activity) stays synchronous.
    @Volatile private var writeUrlEnabled = false
    @Volatile private var spoolmanBaseUrl = ""

    init {
        scope.launch { settings.nfcWriteUrl.collect { writeUrlEnabled = it } }
        scope.launch { settings.spoolmanBaseUrl.collect { spoolmanBaseUrl = it } }
    }

    /** Enter read mode (clears any stale result). Call when opening the scan dialog. */
    fun beginRead() {
        _readResult.value = null
        _mode.value = NfcMode.Reading
    }

    /** Enter write mode for [spoolId] (clears any stale result). Call when opening the write dialog. */
    fun beginWrite(spoolId: Int) {
        _writeResult.value = null
        _mode.value = NfcMode.Writing(spoolId)
    }

    /** Enter write mode for a box at [location] (clears any stale result). */
    fun beginWriteBox(location: String) {
        _writeResult.value = null
        _mode.value = NfcMode.WritingBox(location)
    }

    /** Leave read/write mode (dialog dismissed or cancelled). */
    fun cancel() {
        _mode.value = NfcMode.Idle
    }

    /** Acknowledge a surfaced read result so it isn't reprocessed. */
    fun consumeReadResult() {
        _readResult.value = null
    }

    /** Acknowledge a surfaced write result so it isn't reprocessed. */
    fun consumeWriteResult() {
        _writeResult.value = null
    }

    /**
     * Process a foreground-dispatched tag according to the current [mode]. Runs on the caller's
     * (main) thread — NFC tech I/O is fast and the Android NFC stack expects it from the dispatch
     * callback. No-op in [NfcMode.Idle].
     */
    fun handleTag(tag: Tag) {
        when (val current = _mode.value) {
            is NfcMode.Idle -> Unit
            is NfcMode.Reading -> {
                _mode.value = NfcMode.Idle
                _readResult.value = readTag(tag)
            }
            is NfcMode.Writing -> {
                _mode.value = NfcMode.Idle
                _writeResult.value = writeSpool(tag, current.spoolId)
            }
            is NfcMode.WritingBox -> {
                _mode.value = NfcMode.Idle
                _writeResult.value = writeBox(tag, current.location)
            }
        }
    }

    // ---- Read ----

    private fun readTag(tag: Tag): NfcReadResult {
        val ndef = Ndef.get(tag) ?: return NfcReadResult.Unknown(null)
        val text = try {
            ndef.use {
                it.connect()
                it.ndefMessage ?: it.cachedNdefMessage
            }?.records?.firstNotNullOfOrNull { record -> record.toText() }
        } catch (e: Exception) {
            Log.e(TAG, "Read failed", e)
            return NfcReadResult.Error(e.message ?: appContext.getString(R.string.nfc_error_read_failed))
        }
        // Try spool first, then box
        val spoolId = parseSpoolId(text)
        if (spoolId != null) return NfcReadResult.Found(spoolId)
        val boxLocation = parseBoxLocation(text)
        if (boxLocation != null) return NfcReadResult.BoxFound(boxLocation)
        return NfcReadResult.Unknown(text)
    }

    // ---- Write: Spool ----

    private fun writeSpool(tag: Tag, spoolId: Int): NfcWriteResult {
        val message = buildSpoolNdefMessage(spoolId, spoolmanBaseUrl, writeUrlEnabled)
        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.use {
                    it.connect()
                    if (!it.isWritable) {
                        return NfcWriteResult.Error(appContext.getString(R.string.nfc_error_read_only))
                    }
                    if (it.maxSize < message.byteArrayLength) {
                        return NfcWriteResult.Error(appContext.getString(R.string.nfc_error_too_small))
                    }
                    it.writeNdefMessage(message)
                }
            } else {
                // Fresh / unformatted tag — format it with the message in one shot.
                val formatable = NdefFormatable.get(tag)
                    ?: return NfcWriteResult.Error(appContext.getString(R.string.nfc_error_no_ndef))
                formatable.use {
                    it.connect()
                    it.format(message)
                }
            }
            markTaggedLocally(spoolId)
            NfcWriteResult.Success(spoolId)
        } catch (e: Exception) {
            Log.e(TAG, "Write failed", e)
            NfcWriteResult.Error(e.message ?: appContext.getString(R.string.nfc_error_write_failed))
        }
    }

    private fun markTaggedLocally(spoolId: Int) {
        scope.launch { settings.markSpoolTagged(spoolId, Instant.now().toString()) }
    }

    // ---- Write: Box ----

    private fun writeBox(tag: Tag, location: String): NfcWriteResult {
        val message = buildBoxNdefMessage(location)
        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.use {
                    it.connect()
                    if (!it.isWritable) {
                        return NfcWriteResult.Error(appContext.getString(R.string.nfc_error_read_only))
                    }
                    if (it.maxSize < message.byteArrayLength) {
                        return NfcWriteResult.Error(appContext.getString(R.string.nfc_error_too_small))
                    }
                    it.writeNdefMessage(message)
                }
            } else {
                val formatable = NdefFormatable.get(tag)
                    ?: return NfcWriteResult.Error(appContext.getString(R.string.nfc_error_no_ndef))
                formatable.use {
                    it.connect()
                    it.format(message)
                }
            }
            markBoxTaggedLocally(location)
            NfcWriteResult.BoxSuccess(location)
        } catch (e: Exception) {
            Log.e(TAG, "Box write failed", e)
            NfcWriteResult.Error(e.message ?: appContext.getString(R.string.nfc_error_write_failed))
        }
    }

    private fun markBoxTaggedLocally(location: String) {
        scope.launch { settings.markBoxTagged(location, Instant.now().toString()) }
    }

    companion object {
        private const val TAG = "NfcManager"
        private const val SPOOL_PREFIX = "SPOOL:"
        private const val BOX_PREFIX = "BOX:"

        /**
         * Builds the canonical spool tag payload: one NDEF text record `SPOOL:<id>`, plus an
         * optional URI record (`<baseUrl>/spool/show/<id>`) appended **only** when [writeUrlEnabled]
         * is on and [baseUrl] is non-blank. Pure (no tag I/O) so the wire shape is unit-testable.
         */
        internal fun buildSpoolNdefMessage(spoolId: Int, baseUrl: String, writeUrlEnabled: Boolean): NdefMessage {
            val records = mutableListOf(NdefRecord.createTextRecord(null, "$SPOOL_PREFIX$spoolId"))
            val url = baseUrl.trim().trimEnd('/')
            if (writeUrlEnabled && url.isNotBlank()) {
                try {
                    records.add(NdefRecord.createUri("$url/spool/show/$spoolId"))
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Skipping URL record for malformed base URL: $url", e)
                }
            }
            return NdefMessage(records.toTypedArray())
        }

        /** Builds the canonical box tag payload: a single NDEF text record `BOX:<location>`. */
        internal fun buildBoxNdefMessage(location: String): NdefMessage =
            NdefMessage(arrayOf(NdefRecord.createTextRecord(null, "$BOX_PREFIX$location")))

        /** Parse a `SPOOL:<id>` payload (first matching line) into a spool ID, or null. */
        fun parseSpoolId(payload: String?): Int? {
            if (payload.isNullOrBlank()) return null
            return payload.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith(SPOOL_PREFIX, ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
        }

        /** Parse a `BOX:<location>` payload (first matching line) into a location string, or null. */
        fun parseBoxLocation(payload: String?): String? {
            if (payload.isNullOrBlank()) return null
            return payload.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith(BOX_PREFIX, ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.ifBlank { null }
        }

        /**
         * Decode a well-known RTD_TEXT record. Honors the status byte's UTF-16 flag — tags
         * written by other apps in UTF-16 decode correctly instead of as mojibake (our own
         * writes are always UTF-8).
         */
        internal fun NdefRecord.toText(): String? {
            if (tnf != NdefRecord.TNF_WELL_KNOWN || !type.contentEquals(NdefRecord.RTD_TEXT)) {
                return null
            }
            val data = payload
            if (data.isEmpty()) return null
            val status = data[0].toInt()
            val utf16 = status and 0x80 != 0
            val languageCodeLength = status and 0x3F
            val textStart = 1 + languageCodeLength
            if (textStart > data.size) return null
            return String(
                data,
                textStart,
                data.size - textStart,
                if (utf16) Charsets.UTF_16 else Charsets.UTF_8
            )
        }
    }
}
