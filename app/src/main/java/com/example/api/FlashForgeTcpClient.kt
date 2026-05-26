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
                
                _isConnected.value = true

                // Acquire the control lock first. Written synchronously (we're already on
                // Dispatchers.IO) so it is guaranteed to reach the printer before the
                // keep-alive loop below starts firing commands.
                writeLine("~M601 S1")

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
                
                // Keep-alive heartbeat. Modern 5M/AD5X printers get all status over HTTP /detail,
                // so TCP only needs a light ping to hold the control lock open. Status/temp polling
                // over TCP (~M105/~M119) is reserved for the legacy backend.
                launch(Dispatchers.IO) {
                    while (isActive && _isConnected.value) {
                        sendCommand("~M27")
                        delay(5000)
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

    /**
     * Writes a single command line to the socket. Must be called on [Dispatchers.IO].
     * FlashForge's TCP protocol expects each command terminated with CRLF.
     */
    private fun writeLine(cmd: String) {
        try {
            outWriter?.print("$cmd\r\n")
            outWriter?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendCommand(cmd: String) {
        if (!_isConnected.value) return
        scope.launch(Dispatchers.IO) {
            writeLine(cmd)
        }
    }

    /** Turns custom LEDs full-white via `~M146` (5M / AD5X custom-LED path). */
    fun ledOn() = sendCommand("~M146 r255 g255 b255 F0")

    /** Turns custom LEDs off via `~M146` (5M / AD5X custom-LED path). */
    fun ledOff() = sendCommand("~M146 r0 g0 b0 F0")

    /** Homes all axes (`~G28`). Low-level motion control — only available over TCP. */
    fun homeAxes() = sendCommand("~G28")

    /**
     * Sets the nozzle/extruder target temperature via `~M104 S<celsius>` (pass 0 to cancel
     * heating). The reference ff-5mp-api-ts lib sets temps over TCP G-code, not HTTP.
     */
    fun setNozzleTemp(celsius: Int) = sendCommand("~M104 S$celsius")

    /** Sets the bed/platform target temperature via `~M140 S<celsius>` (pass 0 to cancel). */
    fun setBedTemp(celsius: Int) = sendCommand("~M140 S$celsius")

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            try {
                if (_isConnected.value) {
                    // Release the control lock synchronously before closing the socket,
                    // otherwise the close can race ahead of the write.
                    writeLine("~M602")
                }
            } catch (e: Exception) {}

            // Stop the read / keep-alive loops before tearing down the streams.
            _isConnected.value = false

            try { outWriter?.close() } catch (e: Exception) {}
            try { inReader?.close() } catch (e: Exception) {}
            try { socket?.close() } catch (e: Exception) {}

            connectionJob?.cancel()
        }
    }
}
