package me.ghost.ffui.data

import me.ghost.ffui.api.AD5XMaterialMapping
import me.ghost.ffui.api.FFGcodeFileEntry
import me.ghost.ffui.api.FlashForgeHttpApi
import me.ghost.ffui.api.FlashForgeTcpClient
import me.ghost.ffui.api.MatlStationInfo
import me.ghost.ffui.api.PrinterCapabilities
import me.ghost.ffui.api.PrinterModel
import me.ghost.ffui.api.PrinterDetailResponse
import me.ghost.ffui.backend.FiltrationMode
import me.ghost.ffui.backend.SlotAction
import me.ghost.ffui.backend.PrinterBackend
import me.ghost.ffui.backend.PrinterBackendFactory
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
    initialPrinter: PrinterEntity,
    private val scope: CoroutineScope,
    private val onIdentity: suspend (pid: Int?, firmware: String?, cameraUrl: String?) -> Unit = { _, _, _ -> }
) {
    private val _printer = MutableStateFlow(initialPrinter)
    /**
     * The live per-printer entity. Per-printer settings edits (camera prefs, auto-match, name) push
     * here via [updatePrinterSettings] so the dashboard reflects them immediately — no reconnect.
     * Transport-affecting toggles (forceLegacy, customLed) still go through a full reconnect.
     */
    val printerFlow: StateFlow<PrinterEntity> = _printer
    /** Latest entity snapshot for non-Compose reads (`session.printer.xyz`). */
    val printer: PrinterEntity get() = _printer.value

    /** Applies edited settings to the live session without reconnecting. */
    fun updatePrinterSettings(updated: PrinterEntity) { _printer.value = updated }

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
                // Reflect identity learned from /detail in the LIVE entity, not just the DB — otherwise
                // the camera URL (only known after first /detail) stays blank in the running session
                // until a reconnect, so a freshly-paired printer shows "Camera Not Available".
                _printer.value = _printer.value.copy(
                    modelPid = detail.pid ?: _printer.value.modelPid,
                    firmwareVersion = detail.firmwareVersion ?: _printer.value.firmwareVersion,
                    cameraStreamUrl = detail.cameraStreamUrl ?: _printer.value.cameraStreamUrl
                )
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
        if (msg?.startsWith("API Error") == true) {
            // HTTP checkCode rejected: drop TCP so its keep-alive stops holding the ~M601 lock.
            if (_connectionState.value !is ConnectionState.AuthFailed) {
                tcpClient.disconnect()
            }
            _connectionState.value = ConnectionState.AuthFailed(msg)
        } else {
            _connectionState.value = ConnectionState.Offline(msg)
        }
    }

    // Tracks when the printer first entered `completed` so we can poll fast briefly (the user often
    // clears the platform right after) then relax to idle cadence.
    private var lastStatusKey: String? = null
    private var completedSinceMs: Long = 0L

    /**
     * Adaptive poll cadence keyed off the connection state, then the wire-level `status` (see the
     * HTTP REST "Machine States"). The `status` set here is purely the modern HTTP state machine —
     * legacy-only strings like `building_from_sd` are normalized to modern equivalents by
     * [GenericLegacyBackend] before they reach this point.
     */
    private fun nextDelayMs(): Long {
        when (_connectionState.value) {
            is ConnectionState.AuthFailed -> return 15_000L
            is ConnectionState.Offline -> return 3_000L
            else -> {}
        }
        val status = _status.value?.status?.lowercase()
        if (status != lastStatusKey) {
            lastStatusKey = status
            if (status == "completed") completedSinceMs = System.currentTimeMillis()
        }
        return when (status) {
            // Active / user-watched operations — climb fast.
            "printing", "working", "busy", "heating", "calibrate_doing", "canceling" -> 1_500L
            // Paused or a transient end-of-job dialog the user is likely interacting with.
            "paused", "pausing", "cancel" -> 2_500L
            // Just finished: stay responsive for ~30s (platform-clear), then fall to idle.
            "completed" -> if (System.currentTimeMillis() - completedSinceMs < 30_000L) 2_500L else 5_000L
            "error" -> 10_000L
            // `ready` and anything unrecognized.
            else -> 5_000L
        }
    }

    // ---- Control passthrough (capability-aware via the backend) ----
    suspend fun setLight(on: Boolean) = backend?.setLight(on)
    suspend fun setNozzleTemp(celsius: Int) = backend?.setNozzleTemp(celsius)
    suspend fun setBedTemp(celsius: Int) = backend?.setBedTemp(celsius)
    suspend fun setFiltration(mode: FiltrationMode) = backend?.setFiltration(mode)
    suspend fun setSlotMaterial(slot: Int, materialName: String, hexRgb: String) = backend?.setSlotMaterial(slot, materialName, hexRgb)
    suspend fun slotAction(slot: Int, action: SlotAction) = backend?.slotAction(slot, action)
    suspend fun pause() = backend?.pause()
    suspend fun resume() = backend?.resume()
    suspend fun cancel() = backend?.cancel()
    suspend fun clearPlatform() = backend?.clearPlatform()
    suspend fun rename(name: String) = backend?.rename(name)
    suspend fun setAutoShutdown(enabled: Boolean, minutes: Int) = backend?.setAutoShutdown(enabled, minutes)

    // ---- File management (Phase 4) ----
    private fun notReady() = Result.failure<Nothing>(IllegalStateException("Printer not connected"))
    suspend fun listRecentFiles(): Result<List<FFGcodeFileEntry>> = backend?.listRecentFiles() ?: notReady()
    suspend fun listLocalFiles(): Result<List<String>> = backend?.listLocalFiles() ?: notReady()
    suspend fun getThumbnail(fileName: String): Result<ByteArray?> = backend?.getThumbnail(fileName) ?: notReady()

    /**
     * Thumbnail bytes for the *active job*, used by the dashboard's "what am I printing?" tile.
     * Tries the printer's unauthenticated [thumbUrl] (`printFileThumbUrl`) first — cheapest, the
     * printer already serves the PNG there — then falls back to the authenticated `/gcodeThumb`
     * path. Returns `null` when neither source yields an image.
     */
    suspend fun getJobThumbnail(fileName: String, thumbUrl: String?): ByteArray? {
        thumbUrl?.takeIf { it.isNotBlank() }?.let { url ->
            httpApi.getBytes(url)?.let { return it }
        }
        return backend?.getThumbnail(fileName)?.getOrNull()
    }
    suspend fun startPrint(fileName: String, leveling: Boolean, mappings: List<AD5XMaterialMapping> = emptyList()): Result<Unit> =
        backend?.startPrint(fileName, leveling, mappings) ?: notReady()

    fun stopSession() {
        pollJob?.cancel()
        pollJob = null
        backend = null
        tcpClient.disconnect()
    }
}
