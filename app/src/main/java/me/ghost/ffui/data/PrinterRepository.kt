package me.ghost.ffui.data

import me.ghost.ffapi.PrinterCapabilities
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.backend.FiltrationMode
import me.ghost.ffapi.backend.PrinterBackend
import me.ghost.ffapi.backend.PrinterBackendFactory
import me.ghost.ffapi.backend.SlotAction
import me.ghost.ffapi.error.AuthException
import me.ghost.ffapi.models.AD5XMaterialMapping
import me.ghost.ffapi.models.FFGcodeFileEntry
import me.ghost.ffapi.models.FFPrinterDetail as PrinterDetailResponse
import me.ghost.ffapi.models.MatlStationInfo
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
                if (e is AuthException) {
                    // Credential rejection — fatal, no TCP fallback.
                    applyFailure(e)
                } else {
                    // Connection-level error — printer may be legacy (no HTTP API). Try TCP.
                    identifyViaTcp()
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
                _connectionState.value = ConnectionState.Connected
                detectEvents(detail)
            }
            .onFailure { e -> applyFailure(e) }
    }

    // ---- Notification event detection ----
    // Baselines captured on first observation so connecting to an already-completed or already-
    // errored printer doesn't fire a spurious alert. All opt-in flags are re-read from the live
    // `printer` at emit time, so toggling a notification in settings takes effect without reconnect.
    private var seenFirstDetail = false
    private var prevStatusKey: String? = null
    private var prevErrorCode: String? = null
    private var awaitingCooldown = false

    private fun detectEvents(detail: PrinterDetailResponse) {
        val p = printer
        val status = detail.status?.lowercase()
        val error = detail.errorCode?.takeIf { it.isNotBlank() && it != "0" }

        if (!seenFirstDetail) {
            seenFirstDetail = true
            prevStatusKey = status
            prevErrorCode = error
            return
        }

        // Bed cooled below the safe-to-remove threshold (only after a completion we witnessed).
        if (awaitingCooldown) {
            val bed = detail.platTemp
            when {
                status in ACTIVE_PRINT_STATES -> awaitingCooldown = false   // new job started; abandon
                bed != null && bed < BED_SAFE_TEMP_C -> {
                    awaitingCooldown = false
                    if (p.notifyOnCooled) onEvent(p, PrinterEvent.PrintCooled)
                }
            }
        }

        // Print just finished.
        if (status == "completed" && prevStatusKey != "completed") {
            if (p.notifyOnComplete) onEvent(p, PrinterEvent.PrintCompleted)
            awaitingCooldown = true   // always arm; cooled fires on a later poll, gated then
        }

        // A new error code appeared.
        if (error != null && error != prevErrorCode && p.notifyOnError) {
            onEvent(p, PrinterEvent.PrinterError(error))
        }

        prevStatusKey = status
        prevErrorCode = error
    }

    /** Credential rejection ([AuthException]) is fatal; everything else is transient. */
    private fun applyFailure(e: Throwable) {
        if (e is AuthException) {
            // HTTP checkCode rejected: drop TCP so its keep-alive stops holding the ~M601 lock.
            if (_connectionState.value !is ConnectionState.AuthFailed) {
                tcpClient.disconnect()
            }
            _connectionState.value = ConnectionState.AuthFailed(e.message)
        } else {
            _connectionState.value = ConnectionState.Offline(e.message)
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
     * the library's `GenericLegacyBackend` before they reach this point.
     */
    private fun nextDelayMs(): Long {
        val base = baseDelayMs()
        // The background-throttle floor never speeds polling up, only slows it down.
        return maxOf(base, pollFloorMs)
    }

    /** The adaptive cadence before any background-throttle floor is applied. */
    private fun baseDelayMs(): Long {
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
    // Creator 5 series: per-tool and heated-chamber temperature control (HTTP-only transport on the
    // backend). Pure delegation — capability gating lives in the UI; the backend is the source of truth.
    suspend fun setToolTemp(toolIndex: Int, celsius: Int) = backend?.setToolTemp(toolIndex, celsius)
    suspend fun cancelToolTemp(toolIndex: Int) = backend?.cancelToolTemp(toolIndex)
    suspend fun setChamberTemp(celsius: Int) = backend?.setChamberTemp(celsius)
    suspend fun cancelChamberTemp() = backend?.cancelChamberTemp()
    // Canonical bed heater-off: over HTTP (Creator 5) this sends the TEMP_OFF=-100 cancel sentinel
    // rather than a target of 0 (which setBedTemp(0) does). Used by the Creator 5 temperature card.
    suspend fun cancelBedTemp() = backend?.cancelBedTemp()
    suspend fun home() = backend?.home()
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
        /** Bed temp (°C) below which a finished print is considered safe to remove. */
        const val BED_SAFE_TEMP_C = 40f
        /** Wire statuses that mean a job is actively running (cancels a pending cooldown watch). */
        val ACTIVE_PRINT_STATES = setOf("printing", "working", "busy", "heating")
        /**
         * How long [stopSession]'s watcher waits for a possibly-still-blocking TCP connect to land
         * so it can release the `~M601` lock it acquires. Longer than any OS-level SYN retry
         * window (~2 min worst case on a filtered port); the watcher is one suspended coroutine.
         */
        const val LATE_CONNECT_RELEASE_MS = 180_000L
    }
}
