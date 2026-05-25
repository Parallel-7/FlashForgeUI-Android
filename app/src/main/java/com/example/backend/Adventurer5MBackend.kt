package com.example.backend

import com.example.api.FlashForgeHttpApi
import com.example.api.FlashForgeTcpClient
import com.example.api.PrinterCapabilities
import com.example.api.PrinterModel
import com.example.data.PrinterEntity

/**
 * Adventurer 5M: modern HTTP API, no factory LED, no filtration, no material station. LEDs are
 * only available if the user wired their own and enabled "Custom LEDs" for this printer, in which
 * case they're driven over TCP `~M146`.
 */
class Adventurer5MBackend(
    printer: PrinterEntity,
    http: FlashForgeHttpApi,
    tcp: FlashForgeTcpClient
) : DualApiBackend(printer, http, tcp) {

    override val model = PrinterModel.ADVENTURER_5M

    override fun baselineCapabilities() = PrinterCapabilities(
        model = model,
        ledControl = printer.customLedEnabled,
        ledViaHttp = false
    )
}
