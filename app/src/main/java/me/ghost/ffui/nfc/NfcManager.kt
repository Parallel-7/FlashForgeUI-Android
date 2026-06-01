package me.ghost.ffui.nfc

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
import me.ghost.ffui.data.SettingsDataStore
import java.time.Instant

/**
 * What the next detected NFC tag should do. The UI sets this when the user opens a scan/write
 * dialog; [NfcManager.handleTag] reads it when a tag arrives.
 */
sealed interface NfcMode {
    /** Not waiting for a tag — taps are ignored. */
    data object Idle : NfcMode
    /** Waiting to read a tag and resolve it to a spool. */
    data object Reading : NfcMode
    /** Waiting to write [spoolId] to a tag. */
    data class Writing(val spoolId: Int) : NfcMode
}

/** Result of a read attempt, surfaced to the UI once and then consumed. */
sealed interface NfcReadResult {
    /** Tag carried a `SPOOL:<id>` record. The UI resolves [spoolId] against the loaded list. */
    data class Found(val spoolId: Int) : NfcReadResult
    /** Tag was readable but had no recognisable spool payload. */
    data class Unknown(val rawText: String?) : NfcReadResult
    /** The tag could not be read. */
    data class Error(val message: String) : NfcReadResult
}

/** Result of a write attempt, surfaced to the UI once and then consumed. */
sealed interface NfcWriteResult {
    data class Success(val spoolId: Int) : NfcWriteResult
    data class Error(val message: String) : NfcWriteResult
}

/**
 * Process-lifetime owner of the NFC scan/write flow. Constructed once in
 * [FfuiApplication][me.ghost.ffui.FfuiApplication] (no DI), alongside the session manager and
 * Spoolman repository.
 *
 * The [MainActivity][me.ghost.ffui.MainActivity] routes foreground-dispatched tag intents here via
 * [handleTag]; Compose screens drive [mode] and observe [readResult] / [writeResult]. The canonical
 * tag payload is a single NDEF text record `SPOOL:<id>`; an optional second URI record
 * (`<spoolman-url>/spool/show/<id>`) is appended when [SettingsDataStore.nfcWriteUrl] is on, but is
 * never read back — scanning always relies on the `SPOOL:<id>` record.
 *
 * @property settings Source of the write-URL flag, the Spoolman base URL, and the local tagged map.
 * @property scope Process-lifetime scope used for the deferred settings writes (mark-as-tagged).
 */
class NfcManager(
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
                _readResult.value = readSpool(tag)
            }
            is NfcMode.Writing -> {
                _mode.value = NfcMode.Idle
                _writeResult.value = writeSpool(tag, current.spoolId)
            }
        }
    }

    // ---- Read ----

    private fun readSpool(tag: Tag): NfcReadResult {
        val ndef = Ndef.get(tag) ?: return NfcReadResult.Unknown(null)
        val text = try {
            ndef.use {
                it.connect()
                it.ndefMessage ?: it.cachedNdefMessage
            }?.records?.firstNotNullOfOrNull { record -> record.toText() }
        } catch (e: Exception) {
            Log.e(TAG, "Read failed", e)
            return NfcReadResult.Error(e.message ?: "Failed to read tag")
        }
        val spoolId = parseSpoolId(text)
        return if (spoolId != null) NfcReadResult.Found(spoolId) else NfcReadResult.Unknown(text)
    }

    // ---- Write ----

    private fun writeSpool(tag: Tag, spoolId: Int): NfcWriteResult {
        val message = buildMessage(spoolId)
        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.use {
                    it.connect()
                    if (!it.isWritable) {
                        return NfcWriteResult.Error("This tag is read-only.")
                    }
                    if (it.maxSize < message.byteArrayLength) {
                        return NfcWriteResult.Error("Tag is too small for this data.")
                    }
                    it.writeNdefMessage(message)
                }
            } else {
                // Fresh / unformatted tag — format it with the message in one shot.
                val formatable = NdefFormatable.get(tag)
                    ?: return NfcWriteResult.Error("This tag doesn't support NDEF.")
                formatable.use {
                    it.connect()
                    it.format(message)
                }
            }
            markTaggedLocally(spoolId)
            NfcWriteResult.Success(spoolId)
        } catch (e: Exception) {
            Log.e(TAG, "Write failed", e)
            NfcWriteResult.Error(e.message ?: "Failed to write tag")
        }
    }

    private fun buildMessage(spoolId: Int): NdefMessage {
        val records = mutableListOf(NdefRecord.createTextRecord(null, "$SPOOL_PREFIX$spoolId"))
        val url = spoolmanBaseUrl.trim().trimEnd('/')
        if (writeUrlEnabled && url.isNotBlank()) {
            try {
                records.add(NdefRecord.createUri("$url/spool/show/$spoolId"))
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Skipping URL record for malformed base URL: $url", e)
            }
        }
        return NdefMessage(records.toTypedArray())
    }

    private fun markTaggedLocally(spoolId: Int) {
        scope.launch { settings.markSpoolTagged(spoolId, Instant.now().toString()) }
    }

    companion object {
        private const val TAG = "NfcManager"
        private const val SPOOL_PREFIX = "SPOOL:"

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

        /** Decode a well-known RTD_TEXT record into its UTF-8 string, or null if not a text record. */
        private fun NdefRecord.toText(): String? {
            if (tnf != NdefRecord.TNF_WELL_KNOWN || !type.contentEquals(NdefRecord.RTD_TEXT)) {
                return null
            }
            val data = payload
            if (data.isEmpty()) return null
            val languageCodeLength = data[0].toInt() and 0x3F
            return String(
                data,
                1 + languageCodeLength,
                data.size - 1 - languageCodeLength,
                Charsets.UTF_8
            )
        }
    }
}
