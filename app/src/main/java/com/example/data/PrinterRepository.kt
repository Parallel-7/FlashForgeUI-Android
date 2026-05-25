package com.example.data

import com.example.api.FlashForgeHttpApi
import com.example.api.FlashForgeTcpClient
import com.example.api.PrinterDetailResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PrinterRepository(private val dao: PrinterDao) {
    val savedPrinters = dao.getAllPriters()

    suspend fun savePrinter(printer: PrinterEntity) {
        dao.insert(printer)
    }

    suspend fun deletePrinter(serialNumber: String) {
        dao.delete(serialNumber)
    }
}

class ActivePrinterSession(
    val printer: PrinterEntity,
    private val scope: CoroutineScope
) {
    val httpApi = FlashForgeHttpApi(printer.ipAddress)
    val tcpClient = FlashForgeTcpClient(printer.ipAddress, scope)
    
    private val _status = MutableStateFlow<PrinterDetailResponse?>(null)
    val status: StateFlow<PrinterDetailResponse?> = _status
    
    private val _matlStation = MutableStateFlow<com.example.api.MatlStationInfo?>(null)
    val matlStation: StateFlow<com.example.api.MatlStationInfo?> = _matlStation
    
    private var isPolling = false

    fun startSession() {
        tcpClient.connect()
        isPolling = true
        scope.launch(Dispatchers.IO) {
            while (isActive && isPolling) {
                httpApi.getDetail(printer.serialNumber, printer.checkCode).onSuccess { res ->
                    _status.value = res
                    if (res.hasMatlStation == true || res.machineType?.contains("5X") == true) {
                        httpApi.getMatlStation(printer.serialNumber, printer.checkCode).onSuccess { matl ->
                            _matlStation.value = matl
                        }
                    }
                }.onFailure {
                    // Update connection state maybe
                }
                
                val currentStatus = _status.value?.status?.lowercase() ?: "idle"
                val delayTime = when {
                    currentStatus == "printing" || currentStatus == "busy" -> 1500L
                    currentStatus == "paused" -> 2500L
                    currentStatus == "error" -> 10000L
                    else -> 5000L
                }
                delay(delayTime)
            }
        }
    }

    fun stopSession() {
        isPolling = false
        tcpClient.disconnect()
    }
}
