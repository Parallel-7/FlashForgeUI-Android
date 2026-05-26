package com.example.backend

import com.example.api.AD5XMaterialMapping
import com.example.api.FFGcodeFileEntry
import com.example.api.FlashForgeHttpApi
import com.example.api.FlashForgeTcpClient
import com.example.api.MatlStationInfo
import com.example.api.PrintGcodeRequest
import com.example.api.PrinterCapabilities
import com.example.api.PrinterModel
import com.example.api.PrinterDetailResponse
import com.example.api.Product
import com.example.data.PrinterEntity

/** Air-filtration mode for the 5M Pro circulation fans (mapped to internal/external open/close). */
enum class FiltrationMode { EXTERNAL, INTERNAL, OFF }

/**
 * A per-model strategy that knows how to talk to one connected printer. Mirrors the
 * `BasePrinterBackend → DualAPIBackend → {model}` hierarchy from FlashForgeUI-Electron.
 *
 * For the modern 5M / 5M Pro / AD5X series, status is polled over the HTTP `/detail` endpoint and
 * TCP is reserved for low-level control (custom LEDs, homing). Only [GenericLegacyBackend] polls
 * over TCP. Subclasses customize capability resolution and the material-station view; shared job
 * and LED control live here.
 */
abstract class PrinterBackend(
    protected val printer: PrinterEntity,
    val http: FlashForgeHttpApi,
    val tcp: FlashForgeTcpClient
) {
    /** The model this backend serves. */
    abstract val model: PrinterModel

    /** `true` when status must be polled over TCP (legacy machines); modern backends use HTTP. */
    open val pollsOverTcp: Boolean = false

    /**
     * Resolved capabilities. Empty until [initialize] runs (don't reference the abstract [model]
     * here — it isn't assigned until the subclass constructor completes).
     */
    var capabilities: PrinterCapabilities = PrinterCapabilities()
        protected set

    /** Per-model defaults before `/product` flags are known. */
    protected open fun baselineCapabilities(): PrinterCapabilities =
        PrinterCapabilities(model = model)

    /** Refine [baselineCapabilities] once the `/product` flags are available. */
    protected open fun applyProduct(base: PrinterCapabilities, product: Product): PrinterCapabilities = base

    /**
     * Validates credentials and resolves [capabilities]. For modern printers this fetches
     * `/product` (which also doubles as the credential check). Returns failure when credentials
     * are rejected so the caller can surface an auth error instead of retrying forever.
     */
    open suspend fun initialize(): Result<PrinterCapabilities> {
        capabilities = baselineCapabilities()
        if (model.isModern) {
            http.getProduct(printer.serialNumber, printer.checkCode)
                .onSuccess { product -> capabilities = applyProduct(capabilities, product) }
                .onFailure { return Result.failure(it) }
        }
        return Result.success(capabilities)
    }

    /** Fetches a fresh status snapshot. */
    abstract suspend fun pollStatus(): Result<PrinterDetailResponse>

    /** The material-station view for a status snapshot (AD5X only; null elsewhere). */
    open fun materialStation(detail: PrinterDetailResponse): MatlStationInfo? = null

    // ---- File management (Phase 4) ----

    /** Recent-files list over HTTP `/gcodeList` (rich entries on AD5X, names elsewhere). */
    open suspend fun listRecentFiles(): Result<List<FFGcodeFileEntry>> =
        http.getRecentFileList(printer.serialNumber, printer.checkCode)

    /** Local on-disk file names over TCP `~M661` (5M / 5M Pro). */
    open suspend fun listLocalFiles(): Result<List<String>> = tcp.getFileList()

    /** A file's thumbnail PNG bytes over HTTP `/gcodeThumb` (null when the file has none). */
    open suspend fun getThumbnail(fileName: String): Result<ByteArray?> =
        http.getGcodeThumbnail(printer.serialNumber, printer.checkCode, fileName)

    /**
     * Starts a print of a file already on the printer. The base path serves 5M / 5M Pro / legacy:
     * full payload on firmware ≥3.1.3, minimal payload below that. [mappings] are ignored here
     * (no material station); [AD5XBackend] overrides to honor them.
     */
    open suspend fun startPrint(
        fileName: String,
        leveling: Boolean,
        mappings: List<AD5XMaterialMapping> = emptyList()
    ): Result<Unit> = if (isNewFirmware()) {
        http.printGcode(PrintGcodeRequest(printer.serialNumber, printer.checkCode, fileName, leveling))
    } else {
        http.printGcodeLegacy(printer.serialNumber, printer.checkCode, fileName, leveling)
    }

    /** True when the printer's firmware is ≥ 3.1.3 (selects the richer `/printGcode` payload). */
    protected fun isNewFirmware(): Boolean {
        val parts = (printer.firmwareVersion ?: return false)
            .split(".")
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val min = listOf(3, 1, 3)
        for (i in 0..2) {
            val cur = parts.getOrElse(i) { 0 }
            if (cur > min[i]) return true
            if (cur < min[i]) return false
        }
        return true
    }

    // ---- Shared temperature control (TCP G-code) ----
    /**
     * Sets the nozzle target temperature over TCP `~M104` (pass 0 to cancel heating). Routed over
     * TCP, not HTTP — the reference lib leaves the HTTP temperatureCtl_cmd path commented out as
     * unverified.
     */
    open suspend fun setNozzleTemp(celsius: Int): Result<Unit> {
        tcp.setNozzleTemp(celsius)
        return Result.success(Unit)
    }

    /** Sets the bed target temperature over TCP `~M140` (pass 0 to cancel heating). */
    open suspend fun setBedTemp(celsius: Int): Result<Unit> {
        tcp.setBedTemp(celsius)
        return Result.success(Unit)
    }

    // ---- Filtration control (HTTP; 5M Pro only) ----
    /**
     * Switches air filtration. Only meaningful when [PrinterCapabilities.filtrationControl] is set;
     * fails otherwise. Maps the high-level [mode] to the printer's internal/external fan pair.
     */
    open suspend fun setFiltration(mode: FiltrationMode): Result<Unit> {
        if (!capabilities.filtrationControl) {
            return Result.failure(IllegalStateException("Filtration control not available for $model"))
        }
        val (internal, external) = when (mode) {
            FiltrationMode.EXTERNAL -> "close" to "open"
            FiltrationMode.INTERNAL -> "open" to "close"
            FiltrationMode.OFF -> "close" to "close"
        }
        return http.controlFiltration(printer.serialNumber, printer.checkCode, internal, external)
    }

    // ---- Shared job control (HTTP) ----
    suspend fun pause() = http.pauseJob(printer.serialNumber, printer.checkCode)
    suspend fun resume() = http.resumeJob(printer.serialNumber, printer.checkCode)
    suspend fun cancel() = http.cancelJob(printer.serialNumber, printer.checkCode)
    suspend fun clearPlatform() = http.clearPlatform(printer.serialNumber, printer.checkCode)

    /**
     * Turns the LED on/off, routing to HTTP `lightControl_cmd` (factory LEDs) or TCP `~M146`
     * (custom LEDs) per [PrinterCapabilities.ledViaHttp]. Fails if LED control isn't available.
     */
    open suspend fun setLight(on: Boolean): Result<Unit> {
        val caps = capabilities
        if (!caps.ledControl) {
            return Result.failure(IllegalStateException("LED control not available for $model"))
        }
        return if (caps.ledViaHttp) {
            http.controlLight(printer.serialNumber, printer.checkCode, on)
        } else {
            if (on) tcp.ledOn() else tcp.ledOff()
            Result.success(Unit)
        }
    }
}
