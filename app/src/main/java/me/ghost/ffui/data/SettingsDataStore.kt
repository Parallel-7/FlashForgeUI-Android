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
            try { StartupReconnect.valueOf(name) } catch (_: Exception) { StartupReconnect.OFF }
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
}

/** Mask for a serial number when [hide] is set; fixed length so it doesn't leak the real one. */
fun maskSerial(serial: String, hide: Boolean): String = if (hide) "•••••••••" else serial
