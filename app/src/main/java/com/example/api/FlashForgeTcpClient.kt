package com.example.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket

class FlashForgeTcpClient(private val ipAddress: String, private val scope: CoroutineScope) {
    private var socket: Socket? = null
    private var outWriter: PrintWriter? = null
    private var inReader: BufferedReader? = null
    
    private var connectionJob: Job? = null
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // Exposed parsed state from TCP telemetry
    data class TcpTelemetry(
        val xName: String = "", 
        val extCurrentTemp: Float = 0f, 
        val extTargetTemp: Float = 0f,
        val bedCurrentTemp: Float = 0f,
        val bedTargetTemp: Float = 0f,
        val machineStatus: String = "READY"
    )
    
    private val _telemetry = MutableStateFlow(TcpTelemetry())
    val telemetry: StateFlow<TcpTelemetry> = _telemetry

    fun connect() {
        if (connectionJob?.isActive == true) return
        
        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                socket = Socket(ipAddress, 8899).apply {
                    soTimeout = 10000 // 10s read timeout
                }
                outWriter = PrintWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.US_ASCII), true)
                inReader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.US_ASCII))
                
                // Acquire lock
                sendCommand("~M601 S1")
                
                _isConnected.value = true
                
                // Read loop
                launch(Dispatchers.IO) {
                    try {
                        while (isActive && socket?.isClosed == false) {
                            val line = inReader?.readLine() ?: break
                            parseLine(line)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        _isConnected.value = false
                    }
                }
                
                // Keep-alive loop
                launch(Dispatchers.IO) {
                    while (isActive && _isConnected.value) {
                        sendCommand("~M27") // status
                        delay(2000)
                        sendCommand("~M105") // temp
                        delay(2000)
                        sendCommand("~M119") // machine state
                        delay(1000)
                    }
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                _isConnected.value = false
                disconnect()
            }
        }
    }

    private fun parseLine(line: String) {
        val trimmed = line.trim()
        if (trimmed == "ok" || trimmed.isEmpty()) return
        
        // Custom parsing
        // T0:25.0/200.0 B:25.0/0.0
        if (trimmed.startsWith("T0:") || trimmed.startsWith("B:")) {
            parseTemps(trimmed)
        } else if (trimmed.startsWith("MachineStatus:")) {
            _telemetry.update { it.copy(machineStatus = trimmed.substringAfter("MachineStatus:").trim()) }
        }
    }
    
    private fun parseTemps(line: String) {
        // T0:25.0/200.0 B:25.0/0.0
        // Or idle: T0:25.0 B:25.0
        var eCur = _telemetry.value.extCurrentTemp
        var eTar = _telemetry.value.extTargetTemp
        var bCur = _telemetry.value.bedCurrentTemp
        var bTar = _telemetry.value.bedTargetTemp

        val parts = line.split(" ")
        for (part in parts) {
            if (part.startsWith("T0:")) {
                val tempStr = part.substring(3)
                if (tempStr.contains("/")) {
                    val s = tempStr.split("/")
                    eCur = s[0].toFloatOrNull() ?: eCur
                    eTar = s[1].toFloatOrNull() ?: eTar
                } else {
                    eCur = tempStr.toFloatOrNull() ?: eCur
                }
            } else if (part.startsWith("B:")) {
                val tempStr = part.substring(2)
                if (tempStr.contains("/")) {
                    val s = tempStr.split("/")
                    bCur = s[0].toFloatOrNull() ?: bCur
                    bTar = s[1].toFloatOrNull() ?: bTar
                } else {
                    bCur = tempStr.toFloatOrNull() ?: bCur
                }
            }
        }
        _telemetry.update { 
            it.copy(extCurrentTemp = eCur, extTargetTemp = eTar, bedCurrentTemp = bCur, bedTargetTemp = bTar) 
        }
    }

    fun sendCommand(cmd: String) {
        scope.launch(Dispatchers.IO) {
            try {
                if (_isConnected.value) {
                    outWriter?.print("\$cmd\\r\\n")
                    outWriter?.flush()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            try {
                if (_isConnected.value) {
                    sendCommand("~M602") // Release lock
                }
            } catch (e: Exception) {}
            
            try { outWriter?.close() } catch (e: Exception) {}
            try { inReader?.close() } catch (e: Exception) {}
            try { socket?.close() } catch (e: Exception) {}
            
            connectionJob?.cancel()
            _isConnected.value = false
        }
    }
}
