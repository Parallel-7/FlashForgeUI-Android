package me.ghost.ffui.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Controls how (and whether) the TCP keep-alive heartbeat runs after [connect].
 *
 * - [MODERN] — light `~M27` heartbeat every 5 s to hold the `~M601` control lock. Modern printers
 *   get all status from HTTP `/detail`; TCP is control-only.
 * - [LEGACY_POLL] — no automatic heartbeat. The legacy backend drives status polling explicitly via
 *   [sendCommandWithResponse] each tick, which implicitly keeps the connection alive.
 * - [NONE] — no heartbeat at all (used during short identification probes).
 */
enum class KeepAliveMode {
    MODERN,
    LEGACY_POLL,
    NONE
}

class FlashForgeTcpClient(private val ipAddress: String, private val scope: CoroutineScope) {
    private companion object {
        /** Characters that are NOT valid in a printer filename — used to trim M661 binary framing. */
        val INVALID_FILENAME_CHARS = Regex("""[^\w\s\-.()+%,@\[\]{}:;!#$^&*=<>?/]""")
        /** PNG file signature bytes, used to locate thumbnail data in M662 responses. */
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        /** Poll interval while waiting for a command response to accumulate. */
        const val RESPONSE_POLL_MS = 40L
        /**
         * Idle gap after the last response line before a sentinel-less reply (e.g. legacy `~M115` /
         * `~M119`, which end with no `"ok"`) is considered complete.
         */
        const val RESPONSE_SETTLE_MS = 300L
    }

    /** Controls the keep-alive behaviour. Change before calling [connect]. */
    var keepAliveMode: KeepAliveMode = KeepAliveMode.MODERN

    private var socket: Socket? = null
    private var outWriter: PrintWriter? = null
    private var inReader: BufferedReader? = null

    private var connectionJob: Job? = null
    private var keepAliveJob: Job? = null
    private var readLoopJob: Job? = null

    /** Set to `true` by [disconnect] to suppress automatic reconnect. */
    @Volatile
    private var manuallyDisconnected = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // Exposed parsed state from TCP telemetry (populated by the background read loop in MODERN mode)
    data class TcpTelemetry(
        val extCurrentTemp: Float = 0f,
        val extTargetTemp: Float = 0f,
        val bedCurrentTemp: Float = 0f,
        val bedTargetTemp: Float = 0f,
        val machineStatus: String = "READY"
    )

    private val _telemetry = MutableStateFlow(TcpTelemetry())
    val telemetry: StateFlow<TcpTelemetry> = _telemetry

    // ---- Synchronous command/response machinery ----

    /** Serializes command writes so only one command/response exchange is in-flight at a time. */
    private val commandMutex = Mutex()

    /**
     * When `true`, the read loop accumulates incoming lines into [responseBuffer] (response mode)
     * instead of parsing them as background telemetry.
     */
    @Volatile
    private var awaitingResponse = false

    /**
     * Set by the read loop when it sees a hard terminator — a line that is exactly `"ok"` or starts
     * with `"ok "` (the inline-`ok` form some legacy replies like `~M105` use). Lets the awaiter
     * finish immediately instead of waiting out the idle-settle window.
     */
    @Volatile
    private var responseHardComplete = false

    /** Wall-clock time the most recent response line arrived; drives idle-settle completion. */
    @Volatile
    private var lastResponseLineAt = 0L

    /** Accumulates multi-line response text while [awaitingResponse] is set. */
    private val responseBuffer = StringBuilder()

    /**
     * Sends [cmd] and waits for its multi-line response, returning the accumulated text on success.
     *
     * Completion is detected two ways, because the legacy A3 wire formats are inconsistent: some
     * replies end with a `"ok"` / `"ok …"` terminator (`~M27`, `~M105`, `~M601`), while others
     * (`~M115`, `~M119`) just stop sending with no sentinel. So we finish on the first hard
     * terminator, or — failing that — once no new line has arrived for [settleMs].
     *
     * Runs its socket I/O on [Dispatchers.IO] itself, so it is safe to call from any dispatcher
     * (the legacy poll loop resumes on the main thread between `delay`s — writing the socket there
     * would throw `NetworkOnMainThreadException`).
     */
    suspend fun sendCommandWithResponse(
        cmd: String,
        timeoutMs: Long = 5_000,
        settleMs: Long = RESPONSE_SETTLE_MS
    ): Result<String> = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            if (!_isConnected.value) {
                return@withLock Result.failure(IllegalStateException("TCP not connected"))
            }
            responseBuffer.clear()
            responseHardComplete = false
            lastResponseLineAt = 0L
            awaitingResponse = true
            writeLine(cmd)
            try {
                withTimeout(timeoutMs) {
                    while (true) {
                        if (!_isConnected.value) break          // connection dropped mid-read
                        if (responseHardComplete) break          // saw an "ok" / "ok …" terminator
                        val last = lastResponseLineAt
                        if (last != 0L &&
                            responseBuffer.isNotEmpty() &&
                            System.currentTimeMillis() - last >= settleMs
                        ) {
                            break                                // no new line for settleMs -> complete
                        }
                        delay(RESPONSE_POLL_MS)
                    }
                }
                if (responseBuffer.isNotEmpty()) {
                    Result.success(responseBuffer.toString())
                } else {
                    Result.failure(IllegalStateException("No response to $cmd"))
                }
            } catch (e: Exception) {
                // Timeout or cancellation: salvage a partial reply if we got one, else surface the error.
                if (responseBuffer.isNotEmpty()) Result.success(responseBuffer.toString())
                else Result.failure(e)
            } finally {
                awaitingResponse = false
            }
        }
    }

    // ---- Connection lifecycle ----

    /**
     * Opens the TCP socket, acquires the `~M601` control lock, starts the read loop and (for
     * [KeepAliveMode.MODERN]) the keep-alive heartbeat. Returns the M601 login result so the
     * caller can detect a failed lock acquisition.
     */
    fun connect() {
        if (connectionJob?.isActive == true) return
        manuallyDisconnected = false

        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                socket = Socket(ipAddress, 8899).apply {
                    soTimeout = 10000 // 10 s read timeout
                }
                outWriter = PrintWriter(
                    OutputStreamWriter(socket!!.getOutputStream(), Charsets.US_ASCII),
                    true
                )
                inReader = BufferedReader(
                    InputStreamReader(socket!!.getInputStream(), Charsets.US_ASCII)
                )
                _isConnected.value = true

                // Read loop must start BEFORE the login handshake: sendCommandWithResponse awaits a
                // deferred that only readLoop() completes, so the M601 reply can't be parsed unless
                // the loop is already running.
                readLoopJob = launch(Dispatchers.IO) { readLoop() }

                // Acquire the control lock synchronously.
                val loginResult = sendCommandWithResponse("~M601 S1", timeoutMs = 3_000)
                if (loginResult.isFailure) {
                    _isConnected.value = false
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                    return@launch
                }

                // Keep-alive (MODERN mode only; LEGACY_POLL and NONE skip it).
                if (keepAliveMode == KeepAliveMode.MODERN) {
                    keepAliveJob = launch(Dispatchers.IO) {
                        while (scope.isActive && _isConnected.value && keepAliveMode == KeepAliveMode.MODERN) {
                            sendCommand("~M27")
                            delay(5000)
                        }
                    }
                }
            } catch (e: Exception) {
                _isConnected.value = false
            }
        }
    }

    /**
     * Continuous read loop. In background mode (no pending command) it parses telemetry lines into
     * [telemetry]. In response mode ([awaitingResponse] set) it accumulates lines into
     * [responseBuffer] and flags [responseHardComplete] when a terminating `"ok"` line arrives.
     */
    private suspend fun readLoop() {
        try {
            while (scope.isActive && socket?.isClosed == false) {
                val line = inReader?.readLine() ?: break
                val trimmed = line.trim()

                if (awaitingResponse) {
                    // Response mode: accumulate lines; the awaiter decides when the reply is done.
                    if (responseBuffer.isNotEmpty()) responseBuffer.append('\n')
                    responseBuffer.append(trimmed)
                    lastResponseLineAt = System.currentTimeMillis()
                    if (trimmed == "ok" || trimmed.startsWith("ok ")) {
                        responseHardComplete = true
                    }
                } else {
                    // Background mode: parse telemetry lines for the modern keep-alive path.
                    parseLine(trimmed)
                }
            }
        } catch (_: Exception) {
            // Socket closed or read error — handled below.
        } finally {
            val wasConnected = _isConnected.value
            // Any in-flight awaiter polls _isConnected and bails out on its own once this clears.
            _isConnected.value = false
            // Auto-reconnect if the disconnect was not manual.
            if (wasConnected && !manuallyDisconnected) {
                scheduleReconnect()
            }
        }
    }

    // ---- Auto-reconnect with exponential backoff ----

    private var reconnectDelayMs = 1_000L
    private var reconnectJob: Job? = null

    private fun scheduleReconnect() {
        if (manuallyDisconnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15_000L)
            if (!manuallyDisconnected) {
                connect()
            }
        }
    }

    /** Resets the reconnect backoff to its initial value. Call after a successful interaction. */
    fun resetReconnectBackoff() {
        reconnectDelayMs = 1_000L
    }

    // ---- Background telemetry parsing (MODERN keep-alive path) ----

    private fun parseLine(line: String) {
        if (line.isEmpty()) return

        // T0:25.0/200.0 B:25.0/0.0
        if (line.startsWith("T0:") || line.startsWith("B:")) {
            parseTemps(line)
        } else if (line.startsWith("MachineStatus:")) {
            _telemetry.update {
                it.copy(machineStatus = line.substringAfter("MachineStatus:").trim())
            }
        }
    }

    private fun parseTemps(line: String) {
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
            it.copy(
                extCurrentTemp = eCur, extTargetTemp = eTar,
                bedCurrentTemp = bCur, bedTargetTemp = bTar
            )
        }
    }

    // ---- Fire-and-forget command sending ----

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

    /**
     * Fire-and-forget command — writes [cmd] to the socket without waiting for a response.
     * Used for control commands (LED, homing, temps) that don't need response parsing.
     */
    fun sendCommand(cmd: String) {
        if (!_isConnected.value) return
        scope.launch(Dispatchers.IO) {
            commandMutex.withLock {
                writeLine(cmd)
            }
        }
    }

    // ---- File list via ~M661 (second socket) ----

    /**
     * Parses the raw `~M661` reply into clean filenames.
     *
     * For A4/generic models: segments are split on `::`; within each, the text after `/data/`
     * is the path, trimmed at the first invalid filename character.
     *
     * For A3: lines after `info_list.size: N` contain one filename per line.
     */
    private fun parseFileListResponse(response: String): List<String> {
        // Check for A3 info_list.size format first.
        val a3Match = Regex("""info_list\.size:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(response)
        if (a3Match != null) {
            val count = a3Match.groupValues[1].toIntOrNull() ?: 0
            val afterSize = response.substring(a3Match.range.last + 1)
            return afterSize.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "ok" && !it.startsWith("CMD ") }
                .take(count)
                .toList()
        }

        // Generic/A4 format: :: delimited with /data/ paths.
        val files = mutableListOf<String>()
        for (segment in response.split("::")) {
            val dataIndex = segment.indexOf("/data/")
            if (dataIndex == -1) continue
            var filename = segment.substring(dataIndex + "/data/".length)
            val invalid = INVALID_FILENAME_CHARS.find(filename)
            if (invalid != null) filename = filename.substring(0, invalid.range.first)
            if (filename.isNotBlank()) files.add(filename)
        }
        return files
    }

    // ---- Control shortcuts (fire-and-forget) ----

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

    // ---- File list (~M661) via dedicated second socket ----

    /**
     * Lists local G-code files via `~M661`. The M661 reply is a binary blob (file paths embedded
     * after `/data/`, segments split by `::`) that doesn't fit the persistent line-reader, so this
     * runs on its own short-lived socket doing raw byte reads. Completion follows the reference lib:
     * stop once `ok` has been seen *and* the stream has been quiet for ~1.2s (trailing data settle),
     * with a hard 10s cap.
     *
     * NOTE: this opens a second socket on 8899 without acquiring the `~M601` control lock (file
     * listing is a read-only query). Unverified against live hardware — see the TCP known-rough-edge.
     */
    suspend fun getFileList(): Result<List<String>> = withContext(Dispatchers.IO) {
        var sock: Socket? = null
        try {
            sock = Socket(ipAddress, 8899).apply { soTimeout = 500 }
            val out = sock.getOutputStream()
            val input = sock.getInputStream()
            out.write("~M661\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()

            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            val deadline = System.currentTimeMillis() + 10_000
            var lastDataAt = System.currentTimeMillis()
            var completionSeen = false

            while (System.currentTimeMillis() < deadline) {
                val n = try {
                    input.read(chunk)
                } catch (e: SocketTimeoutException) {
                    if (completionSeen && System.currentTimeMillis() - lastDataAt >= 1200) break
                    continue
                }
                if (n == -1) break
                if (n > 0) {
                    buffer.write(chunk, 0, n)
                    lastDataAt = System.currentTimeMillis()
                    if (!completionSeen && buffer.toString("ISO-8859-1").contains("ok")) {
                        completionSeen = true
                    }
                }
                if (completionSeen && System.currentTimeMillis() - lastDataAt >= 1200) break
            }
            Result.success(parseFileListResponse(buffer.toString("ISO-8859-1")))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    // ---- File thumbnail via ~M662 (second socket) ----

    /**
     * Retrieves a file thumbnail via `~M662 <path>`. Opens a separate short-lived socket, sends the
     * command, waits for the `ok` text marker, then reads raw bytes and extracts PNG data by locating
     * the PNG signature. Returns `null` if no thumbnail data is found.
     */
    suspend fun getFileThumbnail(fileName: String): Result<ByteArray?> = withContext(Dispatchers.IO) {
        var sock: Socket? = null
        try {
            sock = Socket(ipAddress, 8899).apply { soTimeout = 500 }
            val out = sock.getOutputStream()
            val input = sock.getInputStream()
            out.write("~M662 /data/$fileName\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()

            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            val deadline = System.currentTimeMillis() + 10_000
            var lastDataAt = System.currentTimeMillis()
            var okSeen = false

            while (System.currentTimeMillis() < deadline) {
                val n = try {
                    input.read(chunk)
                } catch (e: SocketTimeoutException) {
                    if (okSeen && System.currentTimeMillis() - lastDataAt >= 1200) break
                    continue
                }
                if (n == -1) break
                if (n > 0) {
                    buffer.write(chunk, 0, n)
                    lastDataAt = System.currentTimeMillis()
                    if (!okSeen) {
                        val text = buffer.toString("ISO-8859-1")
                        if (text.contains("Error: File not exists") || text.contains("Error")) {
                            return@withContext Result.success(null)
                        }
                        if (text.contains("ok")) okSeen = true
                    }
                }
                if (okSeen && System.currentTimeMillis() - lastDataAt >= 1200) break
            }

            // Locate PNG data in the accumulated bytes.
            // A3 uses a custom wrapper: magic 0xa2 0xa2 0x2a 0x2a + 4-byte BE length + PNG.
            // A4/generic uses raw PNG after the "ok" text marker.
            val bytes = buffer.toByteArray()

            // Try A3 magic header first.
            val a3Start = findA3ThumbnailMagic(bytes)
            if (a3Start >= 0 && bytes.size >= a3Start + 8) {
                val length = ((bytes[a3Start + 4].toLong() and 0xFF) shl 24) or
                    ((bytes[a3Start + 5].toLong() and 0xFF) shl 16) or
                    ((bytes[a3Start + 6].toLong() and 0xFF) shl 8) or
                    (bytes[a3Start + 7].toLong() and 0xFF)
                val dataStart = a3Start + 8
                if (bytes.size >= dataStart + length) {
                    return@withContext Result.success(bytes.copyOfRange(dataStart, dataStart + length.toInt()))
                }
            }

            // Fall back to generic: locate PNG signature in the raw bytes.
            val pngStart = findPngSignature(bytes)
            if (pngStart >= 0) {
                Result.success(bytes.copyOfRange(pngStart, bytes.size))
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    /** Finds the start index of the PNG signature in [data], or -1 if not found. */
    private fun findPngSignature(data: ByteArray): Int {
        if (data.size < PNG_SIGNATURE.size) return -1
        outer@ for (i in 0..(data.size - PNG_SIGNATURE.size)) {
            for (j in PNG_SIGNATURE.indices) {
                if (data[i + j] != PNG_SIGNATURE[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * Finds the A3 thumbnail magic header `0xa2 0xa2 0x2a 0x2a` in [data], or -1 if not found.
     * This 4-byte magic precedes a 4-byte big-endian length and then the PNG image data.
     */
    private fun findA3ThumbnailMagic(data: ByteArray): Int {
        if (data.size < 8) return -1
        val magic = byteArrayOf(0xa2.toByte(), 0xa2.toByte(), 0x2a.toByte(), 0x2a.toByte())
        outer@ for (i in 0..(data.size - magic.size)) {
            for (j in magic.indices) {
                if (data[i + j] != magic[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // ---- Disconnect ----

    fun disconnect() {
        manuallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        scope.launch(Dispatchers.IO) {
            try {
                if (_isConnected.value) {
                    // Release the control lock synchronously before closing the socket.
                    writeLine("~M602")
                }
            } catch (e: Exception) {}

            _isConnected.value = false

            try { outWriter?.close() } catch (e: Exception) {}
            try { inReader?.close() } catch (e: Exception) {}
            try { socket?.close() } catch (e: Exception) {}

            keepAliveJob?.cancel()
            keepAliveJob = null
            readLoopJob?.cancel()
            readLoopJob = null
            connectionJob?.cancel()
            connectionJob = null
        }
    }
}
