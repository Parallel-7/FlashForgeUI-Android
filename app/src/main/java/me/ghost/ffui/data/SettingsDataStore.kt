package me.ghost.ffui.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
}
