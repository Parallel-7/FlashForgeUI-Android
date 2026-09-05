package me.ghost.ffui.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Extension property that creates a single DataStore instance scoped to the app. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * What to do on app startup regarding previously-connected printers.
 *
 * Persisted as the enum name in DataStore and surfaced on the global Settings screen.
 */
enum class StartupReconnect {
    /** Reopen every printer that was connected at last exit. */
    ALL,
    /** Reopen only the printer whose dashboard tab was last active. */
    LAST_ACTIVE,
    /** Do nothing — the user connects manually each time. */
    OFF
}

/**
 * Which single usage metric a spool card shows on its stat line. Picking one (instead of showing
 * both percentage and weight) keeps the card compact.
 */
enum class SpoolStatStyle {
    /** "27% left · 73% used". */
    PERCENT,
    /** "135 g left · 365 g used". */
    WEIGHT
}

/**
 * Thin wrapper around Jetpack [DataStore] for global (non–per-printer) application preferences.
 *
 * All writers are `suspend`; all readers are cold [Flow]s collected by the UI.
 */
class SettingsDataStore(private val context: Context) {

    // ---- Keys ----
    private object Keys {
        val STARTUP_RECONNECT = stringPreferencesKey("startup_reconnect")
        val LAST_CONNECTED_SERIALS = stringSetPreferencesKey("last_connected_serials")
        val LAST_ACTIVE_SERIAL = stringPreferencesKey("last_active_serial")
        val HIDE_SERIALS = booleanPreferencesKey("hide_serials")
        val BACKGROUND_MONITORING = booleanPreferencesKey("background_monitoring")
        val BACKGROUND_THROTTLE = booleanPreferencesKey("background_throttle")
        val BACKGROUND_THROTTLE_SECONDS = intPreferencesKey("background_throttle_seconds")
        val SPOOLMAN_ENABLED = booleanPreferencesKey("spoolman_enabled")
        val SPOOLMAN_BASE_URL = stringPreferencesKey("spoolman_base_url")
        val SPOOL_STAT_STYLE = stringPreferencesKey("spool_stat_style")
        val NFC_ENABLED = booleanPreferencesKey("nfc_enabled")
        val NFC_WRITE_URL = booleanPreferencesKey("nfc_write_url")
        val NFC_TAGGED_SPOOLS = stringPreferencesKey("nfc_tagged_spools")
        val NFC_TAGGED_BOXES = stringPreferencesKey("nfc_tagged_boxes")
    }

    /** Allowed range for the background-throttle poll interval, in seconds. */
    companion object {
        const val THROTTLE_MIN_SECONDS = 10
        const val THROTTLE_MAX_SECONDS = 60
        const val THROTTLE_DEFAULT_SECONDS = 30
    }

    // ---- Readers ----

    /** The startup-reconnect mode (defaults to [StartupReconnect.OFF]). */
    val startupReconnect: Flow<StartupReconnect> = context.dataStore.data.map { prefs ->
        prefs[Keys.STARTUP_RECONNECT]?.let { name ->
            try { StartupReconnect.valueOf(name) } catch (_: IllegalArgumentException) { StartupReconnect.OFF }
        } ?: StartupReconnect.OFF
    }

    /** Serial numbers of every printer that was connected when the app last exited. */
    val lastConnectedSerials: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_CONNECTED_SERIALS] ?: emptySet()
    }

    /** Serial number of the printer whose dashboard tab was active at last exit. */
    val lastActiveSerial: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_ACTIVE_SERIAL]
    }

    /** When true, serial numbers are masked in the UI (for screenshots / screen recordings). */
    val hideSerials: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HIDE_SERIALS] ?: false
    }

    /**
     * When true, connected printers keep being monitored (and can raise completion/cooled/error
     * alerts) via a foreground service even after the app is closed. When false, monitoring stops
     * with the app — the legacy behaviour.
     */
    val backgroundMonitoringEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BACKGROUND_MONITORING] ?: false
    }

    /**
     * When true (and [backgroundMonitoringEnabled] is on), the poll loop slows to
     * [backgroundThrottleSeconds] while the app is in the background, trading alert latency for
     * battery. Only meaningful while background monitoring is enabled.
     */
    val backgroundThrottleEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BACKGROUND_THROTTLE] ?: false
    }

    /** Background poll interval (seconds) used when throttling is active. Clamped to 10..60. */
    val backgroundThrottleSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[Keys.BACKGROUND_THROTTLE_SECONDS] ?: THROTTLE_DEFAULT_SECONDS)
            .coerceIn(THROTTLE_MIN_SECONDS, THROTTLE_MAX_SECONDS)
    }

    /** Whether the Spoolman integration is enabled (gates the Spools tab). */
    val spoolmanEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SPOOLMAN_ENABLED] ?: false
    }

    /** The user-configured Spoolman server base URL (e.g. `http://192.168.1.50:7912`). */
    val spoolmanBaseUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SPOOLMAN_BASE_URL] ?: ""
    }

    /** Which usage metric the spool cards show (defaults to [SpoolStatStyle.PERCENT]). */
    val spoolStatStyle: Flow<SpoolStatStyle> = context.dataStore.data.map { prefs ->
        prefs[Keys.SPOOL_STAT_STYLE]?.let { name ->
            try { SpoolStatStyle.valueOf(name) } catch (_: IllegalArgumentException) { SpoolStatStyle.PERCENT }
        } ?: SpoolStatStyle.PERCENT
    }

    /** Whether the NFC tag scan/write feature is enabled (gates the Spools NFC icons). */
    val nfcEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NFC_ENABLED] ?: false
    }

    /**
     * When true, a tag write also stores a Spoolman web-link URI record (`<url>/spool/show/<id>`)
     * alongside the canonical `SPOOL:<id>` record. Off by default — the URL is a snapshot of the
     * current server address and is never read back by the app, so a changed address can't break
     * scanning. It only lets a generic phone open the spool's web page.
     */
    val nfcWriteUrl: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NFC_WRITE_URL] ?: false
    }

    /**
     * Locally-tracked map of `spoolId → ISO-8601 timestamp` recording which spools have had an NFC
     * tag written from this device, and when. Device-local only (not synced to Spoolman); the
     * physical tag remains the real source of truth. Drives the "Tagged" card badge and filter.
     */
    val nfcTaggedSpools: Flow<Map<Int, String>> = context.dataStore.data.map { prefs ->
        decodeTaggedSpools(prefs[Keys.NFC_TAGGED_SPOOLS])
    }

    /**
     * Locally-tracked map of `location → ISO-8601 timestamp` recording which boxes have had an
     * NFC tag written from this device. Mirrors [nfcTaggedSpools] but keyed by location string.
     */
    val nfcTaggedBoxes: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        decodeTaggedBoxes(prefs[Keys.NFC_TAGGED_BOXES])
    }

    // ---- Writers ----

    suspend fun setStartupReconnect(mode: StartupReconnect) {
        context.dataStore.edit { it[Keys.STARTUP_RECONNECT] = mode.name }
    }

    suspend fun setLastConnectedSerials(serials: Set<String>) {
        context.dataStore.edit { it[Keys.LAST_CONNECTED_SERIALS] = serials }
    }

    suspend fun setLastActiveSerial(serial: String?) {
        context.dataStore.edit { prefs ->
            if (serial != null) prefs[Keys.LAST_ACTIVE_SERIAL] = serial
            else prefs.remove(Keys.LAST_ACTIVE_SERIAL)
        }
    }

    suspend fun setHideSerials(hidden: Boolean) {
        context.dataStore.edit { it[Keys.HIDE_SERIALS] = hidden }
    }

    suspend fun setBackgroundMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKGROUND_MONITORING] = enabled }
    }

    suspend fun setBackgroundThrottleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKGROUND_THROTTLE] = enabled }
    }

    suspend fun setBackgroundThrottleSeconds(seconds: Int) {
        context.dataStore.edit {
            it[Keys.BACKGROUND_THROTTLE_SECONDS] =
                seconds.coerceIn(THROTTLE_MIN_SECONDS, THROTTLE_MAX_SECONDS)
        }
    }

    suspend fun setSpoolmanEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SPOOLMAN_ENABLED] = enabled }
    }

    suspend fun setSpoolmanBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.SPOOLMAN_BASE_URL] = url }
    }

    suspend fun setSpoolStatStyle(style: SpoolStatStyle) {
        context.dataStore.edit { it[Keys.SPOOL_STAT_STYLE] = style.name }
    }

    suspend fun setNfcEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NFC_ENABLED] = enabled }
    }

    suspend fun setNfcWriteUrl(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NFC_WRITE_URL] = enabled }
    }

    /** Records that [spoolId] was tagged at [timestamp] (defaults to now, ISO-8601). */
    suspend fun markSpoolTagged(spoolId: Int, timestamp: String) {
        context.dataStore.edit { prefs ->
            val current = decodeTaggedSpools(prefs[Keys.NFC_TAGGED_SPOOLS]).toMutableMap()
            current[spoolId] = timestamp
            prefs[Keys.NFC_TAGGED_SPOOLS] = encodeTaggedSpools(current)
        }
    }

    /** Forgets the local "tagged" record for [spoolId] (does not affect the physical tag). */
    suspend fun unmarkSpoolTagged(spoolId: Int) {
        context.dataStore.edit { prefs ->
            val current = decodeTaggedSpools(prefs[Keys.NFC_TAGGED_SPOOLS]).toMutableMap()
            current.remove(spoolId)
            prefs[Keys.NFC_TAGGED_SPOOLS] = encodeTaggedSpools(current)
        }
    }

    /** Records that [location] was tagged at [timestamp] (defaults to now, ISO-8601). */
    suspend fun markBoxTagged(location: String, timestamp: String) {
        context.dataStore.edit { prefs ->
            val current = decodeTaggedBoxes(prefs[Keys.NFC_TAGGED_BOXES]).toMutableMap()
            current[location] = timestamp
            prefs[Keys.NFC_TAGGED_BOXES] = encodeTaggedBoxes(current)
        }
    }

    /** Forgets the local "tagged" record for [location] (does not affect the physical tag). */
    suspend fun unmarkBoxTagged(location: String) {
        context.dataStore.edit { prefs ->
            val current = decodeTaggedBoxes(prefs[Keys.NFC_TAGGED_BOXES]).toMutableMap()
            current.remove(location)
            prefs[Keys.NFC_TAGGED_BOXES] = encodeTaggedBoxes(current)
        }
    }
}

/** JSON for the tagged-spools map; keys are stringified ints (DataStore stores a single string). */
private val taggedSpoolsJson = Json { ignoreUnknownKeys = true }

private fun decodeTaggedSpools(raw: String?): Map<Int, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        taggedSpoolsJson.decodeFromString<Map<String, String>>(raw)
            .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
            .toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun encodeTaggedSpools(map: Map<Int, String>): String =
    taggedSpoolsJson.encodeToString(map.mapKeys { it.key.toString() })

private fun decodeTaggedBoxes(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        taggedSpoolsJson.decodeFromString<Map<String, String>>(raw)
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun encodeTaggedBoxes(map: Map<String, String>): String =
    taggedSpoolsJson.encodeToString(map)

/** Mask for a serial number when [hide] is set; fixed length so it doesn't leak the real one. */
fun maskSerial(serial: String, hide: Boolean): String = if (hide) "•••••••••" else serial
