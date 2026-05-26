package com.example.data

import com.example.api.AD5XMaterialMapping
import com.example.api.FFGcodeFileEntry
import com.example.api.FlashForgeHttpApi
import com.example.api.FlashForgeTcpClient
import com.example.api.MatlStationInfo
import com.example.api.PrinterCapabilities
import com.example.api.PrinterModel
import com.example.api.PrinterDetailResponse
import com.example.backend.FiltrationMode
import com.example.backend.PrinterBackend
import com.example.backend.PrinterBackendFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PrinterRepository(private val dao: PrinterDao) {
    val savedPrinters: Flow<List<PrinterEntity>> = dao.getAllPriters()

    suspend fun savePrinter(printer: PrinterEntity) = dao.insert(printer)
    suspend fun updatePrinter(printer: PrinterEntity) = dao.update(printer)
    suspend fun getPrinter(serialNumber: String): PrinterEntity? = dao.getPrinter(serialNumber)
    suspend fun deletePrinter(serialNumber: String) = dao.delete(serialNumber)

    /** Persists identity/capability fields learned from the first successful /detail. */
    suspend fun updateIdentity(serialNumber: String, pid: Int?, firmware: String?, cameraUrl: String?) =
        dao.updateIdentity(serialNumber, pid, firmware, cameraUrl)
}

/** Connection lifecycle for a single [ActivePrinterSession]. */
sealed interface ConnectionState {
    /** Establishing the connection / identifying the model. */
    data object Connecting : ConnectionState
    /** Polling successfully. */
    data object Connected : ConnectionState
    /** Transient network failure; the poll loop keeps retrying. */
    data class Offline(val reason: String?) : ConnectionState
    /** Credentials were rejected by the printer; retrying won't help. */
    data class AuthFailed(val reason: String?) : ConnectionState
}

/**
 * Owns the live connection to one printer: a [PrinterBackend] (selected by detected model) plus
 * the HTTP poll loop that drives it. For modern printers status comes entirely from HTTP `/detail`;
 * the TCP client is held open only for low-level control (custom LEDs, homing).
 *
 * State is exposed as [StateFlow]s for Compose: [connectionState], [status], [matlStation],
 * [capabilities].
 *
 * @param onIdentity invoked once after the model is identified, to persist pid/firmware/camera.
 */
class ActivePrinterSession(
    val printer: PrinterEntity,
    private val scope: CoroutineScope,
    private val onIdentity: suspend (pid: Int?, firmware: String?, cameraUrl: String?) -> Unit = { _, _, _ -> }
) {
    val httpApi = FlashForgeHttpApi(printer.ipAddress)
    val tcpClient = FlashForgeTcpClient(printer.ipAddress, scope)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _status = MutableStateFlow<PrinterDetailResponse?>(null)
    val status: StateFlow<PrinterDetailResponse?> = _status

    private val _matlStation = MutableStateFlow<MatlStationInfo?>(null)
    val matlStation: StateFlow<MatlStationInfo?> = _matlStation

    private val _capabilities = MutableStateFlow(PrinterCapabilities())
    val capabilities: StateFlow<PrinterCapabilities> = _capabilities

    @Volatile
    var backend: PrinterBackend? = null
        private set

    private var pollJob: Job? = null

    fun startSession() {
        if (pollJob?.isActive == true) return
        _connectionState.value = ConnectionState.Connecting
        // TCP is control-only for modern printers; connect it so LED/homing are ready on demand.
        tcpClient.connect()
        pollJob = scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            if (backend == null) {
                identify()
            } else {
                pollOnce()
            }
            delay(nextDelayMs())
        }
    }

    /** First contact: fetch /detail, pick the backend by model, resolve capabilities. */
    private suspend fun identify() {
        httpApi.getDetail(printer.serialNumber, printer.checkCode)
            .onSuccess { detail ->
                val model = when {
                    printer.forceLegacy -> PrinterModel.GENERIC_LEGACY
                    else -> PrinterModel.fromDetail(detail)
                }
                val newBackend = PrinterBackendFactory.create(model, printer, httpApi, tcpClient)
                // /product also validates credentials; on failure we keep baseline capabilities.
                newBackend.initialize()
                backend = newBackend
                _capabilities.value = newBackend.capabilities
                _status.value = detail
                _matlStation.value = newBackend.materialStation(detail)
                _connectionState.value = ConnectionState.Connected
                onIdentity(detail.pid, detail.firmwareVersion, detail.cameraStreamUrl)
            }
            .onFailure { e -> applyFailure(e) }
    }

    private suspend fun pollOnce() {
        val b = backend ?: return
        b.pollStatus()
            .onSuccess { detail ->
                _status.value = detail
                _matlStation.value = b.materialStation(detail)
                _connectionState.value = ConnectionState.Connected
            }
            .onFailure { e -> applyFailure(e) }
    }

    /** Credential rejection (a non-zero API code) is fatal; everything else is transient. */
    private fun applyFailure(e: Throwable) {
        val msg = e.message
        _connectionState.value = if (msg?.startsWith("API Error") == true) {
            ConnectionState.AuthFailed(msg)
        } else {
            ConnectionState.Offline(msg)
        }
    }

    private fun nextDelayMs(): Long = when (_connectionState.value) {
        is ConnectionState.AuthFailed -> 15_000L
        is ConnectionState.Offline -> 3_000L
        else -> when (_status.value?.status?.lowercase()) {
            "printing", "busy", "building_from_sd" -> 1_500L
            "paused", "pausing" -> 2_500L
            "error" -> 10_000L
            else -> 5_000L
        }
    }

    // ---- Control passthrough (capability-aware via the backend) ----
    suspend fun setLight(on: Boolean) = backend?.setLight(on)
    suspend fun setNozzleTemp(celsius: Int) = backend?.setNozzleTemp(celsius)
    suspend fun setBedTemp(celsius: Int) = backend?.setBedTemp(celsius)
    suspend fun setFiltration(mode: FiltrationMode) = backend?.setFiltration(mode)
    suspend fun pause() = backend?.pause()
    suspend fun resume() = backend?.resume()
    suspend fun cancel() = backend?.cancel()
    suspend fun clearPlatform() = backend?.clearPlatform()

    // ---- File management (Phase 4) ----
    private fun notReady() = Result.failure<Nothing>(IllegalStateException("Printer not connected"))
    suspend fun listRecentFiles(): Result<List<FFGcodeFileEntry>> = backend?.listRecentFiles() ?: notReady()
    suspend fun listLocalFiles(): Result<List<String>> = backend?.listLocalFiles() ?: notReady()
    suspend fun getThumbnail(fileName: String): Result<ByteArray?> = backend?.getThumbnail(fileName) ?: notReady()
    suspend fun startPrint(fileName: String, leveling: Boolean, mappings: List<AD5XMaterialMapping> = emptyList()): Result<Unit> =
        backend?.startPrint(fileName, leveling, mappings) ?: notReady()

    fun stopSession() {
        pollJob?.cancel()
        pollJob = null
        backend = null
        tcpClient.disconnect()
    }
}
