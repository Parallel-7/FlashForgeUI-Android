package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.DiscoveredPrinter
import com.example.api.UdpDiscovery
import com.example.data.ActivePrinterSession
import com.example.data.AppDatabase
import com.example.data.PrinterEntity
import com.example.data.PrinterRepository
import com.example.data.SettingsDataStore
import com.example.data.StartupReconnect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    val repository = PrinterRepository(db.printerDao())
    val settingsDataStore = SettingsDataStore(application)

    val savedPrinters = repository.savedPrinters.stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )

    private val _discoveredPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val discoveredPrinters: StateFlow<List<DiscoveredPrinter>> = _discoveredPrinters

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    // ---- Multi-printer session registry ----

    private val _sessions = MutableStateFlow<Map<String, ActivePrinterSession>>(emptyMap())
    /** All live printer sessions, keyed by serial number. */
    val sessions: StateFlow<Map<String, ActivePrinterSession>> = _sessions

    private val _activeSerial = MutableStateFlow<String?>(null)
    /** Serial number of the printer whose dashboard tab is currently active (visible). */
    val activeSerial: StateFlow<String?> = _activeSerial

    /**
     * Derived convenience: the session the user is currently looking at. Compose screens can
     * collect this the same way they collected the old single `activeSession`.
     */
    val activeSession: StateFlow<ActivePrinterSession?> = combine(
        _sessions, _activeSerial
    ) { map, serial ->
        serial?.let { map[it] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- Startup reconnect ----

    init {
        viewModelScope.launch {
            val mode = settingsDataStore.startupReconnect.first()
            when (mode) {
                StartupReconnect.ALL -> {
                    val serials = settingsDataStore.lastConnectedSerials.first()
                    val lastActive = settingsDataStore.lastActiveSerial.first()
                    for (serial in serials) {
                        repository.getPrinter(serial)?.let { connectToPrinter(it) }
                    }
                    // Restore the last-active tab if it was among the reconnected set.
                    if (lastActive != null && _sessions.value.containsKey(lastActive)) {
                        setActive(lastActive)
                    }
                }
                StartupReconnect.LAST_ACTIVE -> {
                    val lastActive = settingsDataStore.lastActiveSerial.first()
                    if (lastActive != null) {
                        repository.getPrinter(lastActive)?.let { connectToPrinter(it) }
                    }
                }
                StartupReconnect.OFF -> { /* manual connect only */ }
            }
        }
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

    // ---- Session management ----

    fun saveAndConnect(printer: PrinterEntity) {
        viewModelScope.launch {
            repository.savePrinter(printer)
            connectToPrinter(printer)
        }
    }

    /**
     * Opens a live session for [printer]. If a session for this serial already exists the call
     * just switches the active tab to it (no duplicate connections).
     */
    fun connectToPrinter(printer: PrinterEntity) {
        if (_sessions.value.containsKey(printer.serialNumber)) {
            setActive(printer.serialNumber)
            return
        }
        val session = ActivePrinterSession(
            printer = printer,
            scope = viewModelScope,
            onIdentity = { pid, firmware, cameraUrl ->
                repository.updateIdentity(printer.serialNumber, pid, firmware, cameraUrl)
            }
        )
        _sessions.update { it + (printer.serialNumber to session) }
        setActive(printer.serialNumber)
        session.startSession()
    }

    /** Disconnects a single printer by serial number. */
    fun disconnect(serial: String) {
        _sessions.value[serial]?.stopSession()
        _sessions.update { it - serial }
        // If the closed tab was active, switch to the next available (or null).
        if (_activeSerial.value == serial) {
            _activeSerial.value = _sessions.value.keys.firstOrNull()
        }
    }

    /** Convenience overload: disconnect whatever printer is currently active. */
    fun disconnect() {
        _activeSerial.value?.let { disconnect(it) }
    }

    /** Stops and removes every session. */
    fun disconnectAll() {
        _sessions.value.values.forEach { it.stopSession() }
        _sessions.value = emptyMap()
        _activeSerial.value = null
    }

    /** Switches the visible dashboard tab to [serial]. */
    fun setActive(serial: String) {
        _activeSerial.value = serial
    }

    /**
     * Reconnects a session that was already connected — used when per-printer settings change
     * (e.g. toggling forceLegacy or customLedEnabled) so the backend re-resolves capabilities.
     */
    fun reconnectSession(serial: String) {
        viewModelScope.launch {
            val wasActive = _activeSerial.value == serial
            disconnect(serial)
            repository.getPrinter(serial)?.let { entity ->
                connectToPrinter(entity)
                if (wasActive) setActive(serial)
            }
        }
    }

    // ---- Lifecycle ----

    override fun onCleared() {
        super.onCleared()
        // Persist connection state for startup-reconnect before tearing down.
        viewModelScope.launch {
            settingsDataStore.setLastConnectedSerials(_sessions.value.keys)
            settingsDataStore.setLastActiveSerial(_activeSerial.value)
        }
        disconnectAll()
    }

    // ---- MutableStateFlow.update helper ----
    private fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
        value = transform(value)
    }
}
