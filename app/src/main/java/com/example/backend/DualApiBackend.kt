package com.example.backend

import com.example.api.FlashForgeHttpApi
import com.example.api.FlashForgeTcpClient
import com.example.api.PrinterCapabilities
import com.example.api.PrinterDetailResponse
import com.example.api.Product
import com.example.data.PrinterEntity

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
            filtrationControl = product.internalFanCtrlState != 0 && product.externalFanCtrlState != 0
        )
    }
}
