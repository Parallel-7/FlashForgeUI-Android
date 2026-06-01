package me.ghost.ffui.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import me.ghost.ffui.FfuiApplication
import me.ghost.ffui.api.DiscoveredPrinter
import me.ghost.ffui.api.UdpDiscovery
import me.ghost.ffui.data.ActivePrinterSession
import me.ghost.ffui.data.PrinterEntity
import me.ghost.ffui.data.PrinterSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Thin UI-facing facade over the process-lifetime [PrinterSessionManager]. Session state and
 * control delegate straight to the manager so connections survive this ViewModel (and the Activity)
 * being destroyed — that's what lets background monitoring keep running. The ViewModel only owns
 * UI-scoped concerns: printer discovery, and the foreground-only teardown in [onCleared].
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionManager: PrinterSessionManager =
        (application as FfuiApplication).sessionManager

    // Exposed for screens that still reach through the ViewModel (settings, info, delete).
    val repository = sessionManager.repository
    val settingsDataStore = sessionManager.settings

    val savedPrinters = repository.savedPrinters.stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )

    private val _discoveredPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val discoveredPrinters: StateFlow<List<DiscoveredPrinter>> = _discoveredPrinters

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    // ---- Session registry (delegated to the manager) ----

    /** All live printer sessions, keyed by serial number. */
    val sessions: StateFlow<Map<String, ActivePrinterSession>> get() = sessionManager.sessions

    /** Serial number of the printer whose dashboard tab is currently active (visible). */
    val activeSerial: StateFlow<String?> get() = sessionManager.activeSerial

    /** The session the user is currently looking at. */
    val activeSession: StateFlow<ActivePrinterSession?> get() = sessionManager.activeSession

    init {
        // Reconnect previously-connected printers when the UI opens (per the startup-reconnect
        // setting). Idempotent against any sessions the manager already holds.
        sessionManager.reconnectOnAppOpen()
    }

    // ---- Discovery ----

    fun discoverPrinters() {
        if (_isDiscovering.value) return
        _isDiscovering.value = true
        viewModelScope.launch {
            val printers = UdpDiscovery.discover(getApplication())
            _discoveredPrinters.value = printers
            _isDiscovering.value = false
        }
    }

    // ---- Session management (delegated) ----

    fun saveAndConnect(printer: PrinterEntity) = sessionManager.saveAndConnect(printer)

    fun connectToPrinter(printer: PrinterEntity) = sessionManager.connectToPrinter(printer)

    fun disconnect(serial: String) = sessionManager.disconnect(serial)

    fun disconnect() = sessionManager.disconnect()

    fun disconnectAll() = sessionManager.disconnectAll()

    fun setActive(serial: String) = sessionManager.setActive(serial)

    fun updatePrinterSettings(updated: PrinterEntity) = sessionManager.updatePrinterSettings(updated)

    fun reconnectSession(serial: String) = sessionManager.reconnectSession(serial)

    // ---- Lifecycle ----

    override fun onCleared() {
        super.onCleared()
        // With background monitoring OFF, sessions are bound to the app: tear them down when the
        // Activity goes away, exactly as before this manager existed. With it ON, leave the sessions
        // running — the foreground service keeps the process (and polling) alive.
        if (!sessionManager.isBackgroundMonitoringEnabled) {
            sessionManager.disconnectAll()
        }
    }
}
