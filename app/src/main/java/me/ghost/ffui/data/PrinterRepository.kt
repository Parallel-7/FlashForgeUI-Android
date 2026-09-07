package me.ghost.ffui.data

import me.ghost.ffui.R

import android.content.Context

import android.os.SystemClock
import me.ghost.ffapi.PrinterCapabilities
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.backend.FiltrationMode
import me.ghost.ffapi.backend.PrinterBackend
import me.ghost.ffapi.backend.PrinterBackendFactory
import me.ghost.ffapi.backend.SlotAction
import me.ghost.ffapi.error.ApiErrorException
import me.ghost.ffapi.error.AuthException
import me.ghost.ffapi.models.AD5XMaterialMapping
import me.ghost.ffapi.models.FFGcodeFileEntry
import me.ghost.ffapi.models.FFPrinterDetail as PrinterDetailResponse
import me.ghost.ffapi.models.MachineInfo
import me.ghost.ffapi.models.MatlStationInfo
import me.ghost.ffapi.models.PrintGcodeRequest
import me.ghost.ffapi.tcpapi.FlashForgeClient
import me.ghost.ffapi.tcpapi.KeepAliveMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PrinterRepository(private val dao: PrinterDao) {
    val savedPrinters: Flow<List<PrinterEntity>> = dao.getAllPrinters()

    suspend fun savePrinter(printer: PrinterEntity) = dao.insert(printer)
    suspend fun updatePrinter(printer: PrinterEntity) = dao.update(printer)
    suspend fun getPrinter(serialNumber: String): PrinterEntity? = dao.getPrinter(serialNumber)
    suspend fun deletePrinter(serialNumber: String) = dao.delete(serialNumber)

    /** Persists identity/capability fields learned from the first successful /detail. */
    suspend fun updateIdentity(serialNumber: String, pid: Int?, firmware: String?, cameraUrl: String?) =
        dao.updateIdentity(serialNumber, pid, firmware, cameraUrl)

    /**
     * Persists a freshly resolved address (discovery-first connect or the address-retry dialog).
     * Narrow on purpose — a new IP must not touch identity/capability fields.
     */
    suspend fun updateAddress(serialNumber: String, ip: String) = dao.updateAddress(serialNumber, ip)
}

/**
 * A notifiable state transition detected by the poll loop. The session only emits an event after
 * the *first* observation (so connecting to an already-completed / already-errored printer stays
 * quiet) and re-checks the per-printer opt-in flag at emit time.
 */
sealed interface PrinterEvent {
    /** Status transitioned into `completed`. */
    data object PrintCompleted : PrinterEvent
    /** Bed temperature fell below the safe-to-remove threshold after a witnessed completion. */
    data object PrintCooled : PrinterEvent
    /** `/detail` reported a new non-zero error code. */
    data class PrinterError(val code: String) : PrinterEvent
}

/** Connection lifecycle for a single [ActivePrinterSession]. */
sealed interface ConnectionState {
    /** Establishing the connection / identifying the model. */
    data object Connecting : ConnectionState
    /** Polling successfully. */
    data object Connected : ConnectionState
    /** Transient network failure; the poll loop keeps retrying. [transportFailure] is true only
     * for connection-level failures (unreachable host, timeouts) — the printer answered nothing.
     * A firmware error envelope is NOT a transport failure: the address is fine. */
    data class Offline(val reason: String?, val transportFailure: Boolean = false) : ConnectionState
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
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val onIdentity: suspend (pid: Int?, firmware: String?, cameraUrl: String?) -> Unit = { _, _, _ -> },
    private val onEvent: (printer: PrinterEntity, event: PrinterEvent) -> Unit = { _, _ -> }
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

    /**
     * Minimum poll interval (ms) the loop is allowed to use. 0 means "no floor" (the adaptive
     * cadence runs unrestricted). The session manager raises this to the background-throttle
     * interval while the app is backgrounded so polling slows to save battery, and drops it back to
     * 0 in the foreground. Read live in [nextDelayMs]; takes effect on the next loop tick.
     */
    @Volatile
    var pollFloorMs: Long = 0L

    val httpApi = FlashForgeHttpApi(printer.ipAddress)
    val tcpClient = FlashForgeClient(printer.ipAddress, scope)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _status = MutableStateFlow<PrinterDetailResponse?>(null)
    val status: StateFlow<PrinterDetailResponse?> = _status

    private val _matlStation = MutableStateFlow<MatlStationInfo?>(null)
    val matlStation: StateFlow<MatlStationInfo?> = _matlStation

    private val _capabilities = MutableStateFlow(PrinterCapabilities())
    val capabilities: StateFlow<PrinterCapabilities> = _capabilities

    /** Library transform used for state/capability derivations off each `/detail` snapshot. */
    private val machineInfo = MachineInfo()

    @Volatile
    var backend: PrinterBackend? = null
        private set

    private var pollJob: Job? = null

    /**
     * Set by [stopSession]; once true the session never initiates further work (poll ticks, TCP
     * connects). Volatile because [stopSession] and the poll loop observe it across coroutines.
     */
    @Volatile
    private var stopped = false

    fun startSession() {
        if (pollJob?.isActive == true) return
        if (stopped) return
        _connectionState.value = ConnectionState.Connecting
        // TCP is control-only for modern printers; it is connected AFTER the model is identified
        // and gated on the backend's `httpOnly` (Creator 5 has no usable TCP channel, so it is
        // never connected — an eager connect here would hang on a dead 8899). The `forceLegacy`
        // path identifies over TCP, so it eager-connects here.
        if (printer.forceLegacy) tcpClient.connect()
        pollJob = scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        // The coroutine's own liveness, not the (never-cancelled) process scope.
        while (currentCoroutineContext().isActive && !stopped) {
            if (backend == null) {
                identify()
            } else {
                pollOnce()
            }
            delay(nextDelayMs())
        }
    }

    /**
     * First contact: try HTTP `/detail` first (modern printers), then fall through to TCP `~M115`
     * identification for legacy printers that have no HTTP API. The `forceLegacy` toggle skips
     * HTTP entirely and goes straight to TCP.
     */
    private suspend fun identify() {
        if (stopped) return
        if (printer.forceLegacy) {
            identifyViaTcp()
            return
        }

        httpApi.getDetail(printer.serialNumber, printer.checkCode)
            .onSuccess { detail ->
                val model = PrinterModel.fromDetail(detail)
                val newBackend = PrinterBackendFactory.create(model, printer.toConfig(), httpApi, tcpClient)
                // /product also validates credentials; on failure we keep baseline capabilities.
                newBackend.initialize()
                backend = newBackend
                _capabilities.value = newBackend.capabilities
                syncCapabilityTruth(detail)
                // HTTP-only models (Creator 5) expose no usable TCP control channel — never
                // connect it, or the socket hangs/times out on a dead 8899. Modern dual-API
                // printers connect here so LED/homing/temp controls are ready on demand (idempotent
                // if already up, e.g. a forceLegacy reconnect that refined into a modern model).
                if (!newBackend.httpOnly && !stopped) tcpClient.connect()
                _status.value = detail
                _matlStation.value = newBackend.materialStation(detail)
                _connectionState.value = ConnectionState.Connected
                detectEvents(detail)
                _printer.value = _printer.value.copy(
                    modelPid = detail.pid ?: _printer.value.modelPid,
                    firmwareVersion = detail.firmwareVersion ?: _printer.value.firmwareVersion,
                    cameraStreamUrl = detail.cameraStreamUrl ?: _printer.value.cameraStreamUrl
                )
                onIdentity(detail.pid, detail.firmwareVersion, detail.cameraStreamUrl)
            }
            .onFailure { e ->
                when (e) {
                    is AuthException -> applyFailure(e)   // credentials rejected — fatal, no TCP fallback
                    // An API envelope error means the HTTP API *answered* — this is not a legacy
                    // printer without HTTP, so don't pointlessly probe 8899; surface the firmware's
                    // own message (e.g. the Creator 5 LAN-mode gate) instead.
                    is ApiErrorException -> applyFailure(e)
                    else -> identifyViaTcp()               // connection-level error — printer may be legacy
                }
            }
    }

    /**
     * Identifies the printer model via TCP `~M115` probe. Used when HTTP `/detail` fails (legacy
     * printers have no HTTP API) or when `forceLegacy` is set. Reuses the session's already-connected
     * TCP client.
     */
    private suspend fun identifyViaTcp() {
        if (stopped) return
        // Ensure the TCP channel is open: startSession only eager-connects for `forceLegacy`, so the
        // HTTP-failure fallback can arrive here without a prior connect. Idempotent. HTTP-only
        // models don't reach this on the happy path (their /detail succeeds); if their HTTP is down
        // the probe below simply fails fast (clean Offline via applyFailure) rather than crashing.
        tcpClient.connect()
        // connect() is async (socket + ~M601 login run on Dispatchers.IO); give it a moment to come
        // up so legacy identify succeeds on the first tick rather than self-healing on the next one.
        // No-op when already connected; returns null on timeout, after which the ~M115 probe fails fast.
        withTimeoutOrNull(3_000) { tcpClient.isConnected.first { it } }
        if (stopped) return   // stopSession landed while we waited — teardown is already in flight
        tcpClient.sendRawCommand("~M115", timeoutMs = 3_000)
            .onSuccess { response ->
                // Strip A3-specific ack:/echo: prefixes from each line before searching.
                val cleanLines = response.lineSequence()
                    .map { it.trim().removePrefix("echo: ").removePrefix("ack: ") }
                    .toList()
                val machineType = cleanLines
                    .find { it.startsWith("Machine Type:") }
                    ?.substringAfter("Machine Type:")?.trim().orEmpty()
                val firmware = cleanLines
                    .find { it.startsWith("Firmware:") }
                    ?.substringAfter("Firmware:")?.trim()
                val model = PrinterModel.fromMachineType(machineType)

                // Switch TCP to legacy polling mode (no automatic keep-alive).
                tcpClient.keepAliveMode = KeepAliveMode.LEGACY_POLL

                val newBackend = PrinterBackendFactory.create(model, printer.toConfig(), httpApi, tcpClient)
                newBackend.initialize()
                backend = newBackend
                _capabilities.value = newBackend.capabilities
                _connectionState.value = ConnectionState.Connected

                _printer.value = _printer.value.copy(
                    firmwareVersion = firmware ?: _printer.value.firmwareVersion
                )
                onIdentity(null, firmware, null)

                tcpClient.resetReconnectBackoff()
            }
            .onFailure { e ->
                applyFailure(e)
            }
    }

    private suspend fun pollOnce() {
        val b = backend ?: return
        b.pollStatus()
            .onSuccess { detail ->
                _status.value = detail
                _matlStation.value = b.materialStation(detail)
                syncCapabilityTruth(detail)
                _connectionState.value = ConnectionState.Connected
                detectEvents(detail)
            }
            .onFailure { e -> applyFailure(e) }
    }

    /**
     * Reconciles capability flags that depend on live hardware truth the backend baselines can't
     * know:
     *  - The heated chamber is a Creator 5 series *option*, and the library's C5 baseline reports
     *    `chamberTempControl` for the whole family. A chamber-less unit answers with the `-108`
     *    sentinel, which the library's `MachineInfo.fromDetail` normalizes to "no sensor" — so
     *    gate the capability on `hasChamberSensor` and the chamber cell (and its Set/Off
     *    commands, which such units silently ACK) never render.
     *  - `circulateCtl_cmd` actuates nothing on the Creator 5 series (the Pro has filtration
     *    hardware but it is not API-controllable), so the library's forced Pro baseline would
     *    render a silent no-op card. Drop the capability there; the 5M Pro (where the command
     *    works) keeps it.
     */
    private fun syncCapabilityTruth(detail: PrinterDetailResponse) {
        val caps = _capabilities.value
        if (!caps.model.isCreator5) return
        val info = machineInfo.fromDetail(detail) ?: return
        val updated = caps.copy(
            chamberTempControl = caps.chamberTempControl && info.hasChamberSensor,
            filtrationControl = false,
        )
        if (updated != caps) _capabilities.value = updated
    }

    // ---- Notification event detection ----
    // Baselines captured on first observation so connecting to an already-completed or already-
    // errored printer doesn't fire a spurious alert. All opt-in flags are re-read from the live
    // `printer` at emit time, so toggling a notification in settings takes effect without reconnect.
    private val eventDetector = PrintEventDetector()

    private fun detectEvents(detail: PrinterDetailResponse) {
        val p = printer
        eventDetector.detect(detail, p.notifyOnComplete, p.notifyOnCooled, p.notifyOnError)
            .forEach { onEvent(p, it) }
    }

    /**
     * Credential rejection ([AuthException]) is fatal; everything else is transient. With the
     * library's 0.4.0 auth split, a typed [ApiErrorException] is *not* an auth failure: `-2` is
     * the Creator 5 LAN-mode gate (the printer sits in cloud mode — the fix is switching it to
     * LAN mode, not retyping credentials), `-1` a parameter error. Both land in
     * [ConnectionState.Offline] with the firmware's message surfaced, never in
     * [ConnectionState.AuthFailed].
     */
    private fun applyFailure(e: Throwable) {
        if (e is AuthException) {
            // HTTP checkCode rejected: drop TCP so its keep-alive stops holding the ~M601 lock.
            if (_connectionState.value !is ConnectionState.AuthFailed) {
                tcpClient.disconnect()
            }
            _connectionState.value = ConnectionState.AuthFailed(e.message)
        } else {
            val reason = if (e is ApiErrorException && e.code == LAN_MODE_ERROR_CODE) {
                appContext.getString(R.string.printers_lan_mode_reason)
            } else {
                e.message
            }
            _connectionState.value =
                ConnectionState.Offline(reason, transportFailure = e !is ApiErrorException)
        }
    }

    // Tracks when the printer first entered `completed` so we can poll fast briefly (the user often
    // clears the platform right after) then relax to idle cadence. Monotonic clock — wall-clock
    // shifts (NTP, manual) must not stretch or skip the fast window.
    private var lastStatusKey: String? = null
    private var completedSinceMs: Long = 0L

    /**
     * Adaptive poll cadence keyed off the connection state, then the wire-level `status` (see the
     * HTTP REST "Machine States"). The `status` set here is purely the modern HTTP state machine —
     * legacy-only strings like `building_from_sd` are normalized to modern equivalents by
     * the library's `GenericLegacyBackend` before they reach this point.
     */
    private fun nextDelayMs(): Long {
        // The background-throttle floor never speeds polling up, only slows it down.
        return PollCadence.throttledDelayMs(baseDelayMs(), pollFloorMs)
    }

    /** The adaptive cadence before any background-throttle floor is applied. */
    private fun baseDelayMs(): Long {
        val detail = _status.value
        val status = detail?.status?.lowercase()
        if (status != lastStatusKey) {
            lastStatusKey = status
            if (status == "completed") completedSinceMs = SystemClock.elapsedRealtime()
        }
        return PollCadence.baseDelayMs(
            state = _connectionState.value,
            detail = detail,
            completedSinceMs = completedSinceMs,
            nowMs = SystemClock.elapsedRealtime(),
        )
    }

    // ---- Control passthrough (capability-aware via the backend) ----
    // One failure contract across the whole session surface: a missing backend yields the same
    // typed "not ready" failure the file APIs use — never a silent null that reads as a dead
    // button in the UI.
    suspend fun setLight(on: Boolean) = backend?.setLight(on) ?: notReady()
    suspend fun setNozzleTemp(celsius: Int) = backend?.setNozzleTemp(celsius) ?: notReady()
    suspend fun setBedTemp(celsius: Int) = backend?.setBedTemp(celsius) ?: notReady()
    // Creator 5 series: per-tool and heated-chamber temperature control (HTTP-only transport on the
    // backend). Pure delegation — capability gating lives in the UI; the backend is the source of truth.
    suspend fun setToolTemp(toolIndex: Int, celsius: Int) = backend?.setToolTemp(toolIndex, celsius) ?: notReady()
    suspend fun cancelToolTemp(toolIndex: Int) = backend?.cancelToolTemp(toolIndex) ?: notReady()
    suspend fun setChamberTemp(celsius: Int) = backend?.setChamberTemp(celsius) ?: notReady()
    suspend fun cancelChamberTemp() = backend?.cancelChamberTemp() ?: notReady()
    // Canonical bed heater-off: over HTTP (Creator 5) this sends the TEMP_OFF=-100 cancel sentinel
    // rather than a target of 0 (which setBedTemp(0) does). Used by the Creator 5 temperature card.
    suspend fun cancelBedTemp() = backend?.cancelBedTemp() ?: notReady()
    suspend fun home() = backend?.home() ?: notReady()
    suspend fun setFiltration(mode: FiltrationMode) = backend?.setFiltration(mode) ?: notReady()
    suspend fun setSlotMaterial(slot: Int, materialName: String, hexRgb: String) = backend?.setSlotMaterial(slot, materialName, hexRgb) ?: notReady()
    suspend fun slotAction(slot: Int, action: SlotAction) = backend?.slotAction(slot, action) ?: notReady()
    suspend fun pause() = backend?.pause() ?: notReady()
    suspend fun resume() = backend?.resume() ?: notReady()
    suspend fun cancel() = backend?.cancel() ?: notReady()
    suspend fun clearPlatform() = backend?.clearPlatform() ?: notReady()
    suspend fun rename(name: String) = backend?.rename(name) ?: notReady()
    suspend fun setAutoShutdown(enabled: Boolean, minutes: Int) = backend?.setAutoShutdown(enabled, minutes) ?: notReady()

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
    suspend fun startPrint(fileName: String, leveling: Boolean, mappings: List<AD5XMaterialMapping> = emptyList()): Result<Unit> {
        val b = backend ?: return notReady()
        // Single-color AD5X start: the docs define `gcodeToolCnt` as the number of tool channels
        // in the gcode (1-4) — a single-color file has exactly one (T0). The library's generic
        // empty-mappings path sends 0, which is outside that documented range, so build the
        // request here (matching the TS reference's single-tool shape) until the library carries
        // the fix. Multi-color starts and every other model keep the backend path.
        if (mappings.isEmpty() && b.model == PrinterModel.AD5X) {
            return httpApi.printGcode(
                PrintGcodeRequest(
                    serialNumber = printer.serialNumber,
                    checkCode = printer.checkCode,
                    fileName = fileName,
                    levelingBeforePrint = leveling,
                    useMatlStation = false,
                    gcodeToolCnt = 1,
                )
            )
        }
        return b.startPrint(fileName, leveling, mappings)
    }

    /**
     * Tears the session down for good: stops the poll loop, drops the backend, and closes the TCP
     * control channel (releasing the `~M601` lock).
     *
     * The library's `connect()`/`disconnect()` are unsynchronized fire-and-forget coroutines and
     * `connect()` uses a blocking socket constructor with **no connect timeout** — so a connect
     * initiated just before this call can *complete after* the first disconnect below (the closer
     * sees `connected == false` and no-ops), leaving a `~M601`-locked socket on a session nobody
     * owns anymore (audit-A HIGH-1). The durable fix is library-side; app-side we guarantee
     * teardown on every exit by (1) setting [stopped] so this session never initiates another
     * connect, (2) re-issuing the disconnect after the cancelled poll job settles, and (3) keeping
     * a bounded watcher that releases the lock if a late-landing connect flips the client back to
     * connected. All three run on the manager's scope, which outlives the session.
     */
    fun stopSession() {
        stopped = true
        val job = pollJob
        pollJob?.cancel()
        pollJob = null
        backend = null
        tcpClient.disconnect()
        scope.launch {
            job?.join()
            tcpClient.disconnect()
            val lateConnect = withTimeoutOrNull(LATE_CONNECT_RELEASE_MS) {
                tcpClient.isConnected.first { it }
            }
            if (lateConnect != null) tcpClient.disconnect()
        }
    }

    private companion object {
        /** Firmware envelope code the Creator 5 series returns while in cloud mode. */
        const val LAN_MODE_ERROR_CODE = -2
        /**
         * How long [stopSession]'s watcher waits for a possibly-still-blocking TCP connect to land
         * so it can release the `~M601` lock it acquires. Longer than any OS-level SYN retry
         * window (~2 min worst case on a filtered port); the watcher is one suspended coroutine.
         */
        const val LATE_CONNECT_RELEASE_MS = 180_000L
    }
}
