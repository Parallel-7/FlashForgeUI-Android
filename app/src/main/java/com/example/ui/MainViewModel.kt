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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val repository = PrinterRepository(db.printerDao())

    val savedPrinters = repository.savedPrinters.stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )

    private val _discoveredPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val discoveredPrinters: StateFlow<List<DiscoveredPrinter>> = _discoveredPrinters

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private val _activeSession = MutableStateFlow<ActivePrinterSession?>(null)
    val activeSession: StateFlow<ActivePrinterSession?> = _activeSession

    fun discoverPrinters() {
        if (_isDiscovering.value) return
        _isDiscovering.value = true
        viewModelScope.launch {
            val printers = UdpDiscovery.discover(getApplication())
            _discoveredPrinters.value = printers
            _isDiscovering.value = false
        }
    }

    fun saveAndConnect(printer: PrinterEntity) {
        viewModelScope.launch {
            repository.savePrinter(printer)
            connectToPrinter(printer)
        }
    }

    fun connectToPrinter(printer: PrinterEntity) {
        _activeSession.value?.stopSession()
        val session = ActivePrinterSession(
            printer = printer,
            scope = viewModelScope,
            onIdentity = { pid, firmware, cameraUrl ->
                repository.updateIdentity(printer.serialNumber, pid, firmware, cameraUrl)
            }
        )
        _activeSession.value = session
        session.startSession()
    }

    fun disconnect() {
        _activeSession.value?.stopSession()
        _activeSession.value = null
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
