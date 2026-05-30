package me.ghost.ffui.backend

import me.ghost.ffui.api.FlashForgeHttpApi
import me.ghost.ffui.api.FlashForgeTcpClient
import me.ghost.ffui.api.PrinterCapabilities
import me.ghost.ffui.api.PrinterDetailResponse
import me.ghost.ffui.api.PrinterModel
import me.ghost.ffui.api.Product
import me.ghost.ffui.data.PrinterEntity

/**
 * Shared base for the modern 5M / 5M Pro / AD5X series, which all speak the HTTP REST API.
 * Status comes from `POST /detail`; TCP is control-only. Equivalent to `DualAPIBackend` in
 * FlashForgeUI-Electron.
 *
 * LED and filtration availability are resolved from the `/product` capability flags, which works
 * uniformly across models (e.g. a real AD5X reports `lightCtrlState:1` and is driven over HTTP).
 * The per-printer "custom LEDs" toggle ([baselineCapabilities] sets `ledControl` on 5M/AD5X) acts
 * as a TCP `~M146` fallback for printers whose firmware doesn't expose `lightControl_cmd`.
 */
abstract class DualApiBackend(
    printer: PrinterEntity,
    http: FlashForgeHttpApi,
    tcp: FlashForgeTcpClient
) : PrinterBackend(printer, http, tcp) {

    override suspend fun pollStatus(): Result<PrinterDetailResponse> =
        http.getDetail(printer.serialNumber, printer.checkCode)

    override fun applyProduct(base: PrinterCapabilities, product: Product): PrinterCapabilities {
        val httpLed = product.lightCtrlState != 0
        return base.copy(
            // Prefer HTTP when the firmware reports it; otherwise keep the custom-TCP baseline.
            ledControl = httpLed || base.ledControl,
            ledViaHttp = httpLed,
            // Air filtration is a 5M Pro feature. The /product fan-ctrl flags are unreliable on the
            // plain 5M (its firmware reports both non-zero despite having no filtration), so gate on
            // the model as well rather than trusting the flags alone.
            filtrationControl = model == PrinterModel.ADVENTURER_5M_PRO &&
                product.internalFanCtrlState != 0 && product.externalFanCtrlState != 0
        )
    }
}
