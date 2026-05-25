package com.example.backend

import com.example.api.FlashForgeHttpApi
import com.example.api.FlashForgeTcpClient
import com.example.api.MatlStationInfo
import com.example.api.PrinterCapabilities
import com.example.api.PrinterModel
import com.example.api.PrinterDetailResponse
import com.example.api.Product
import com.example.data.PrinterEntity

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
