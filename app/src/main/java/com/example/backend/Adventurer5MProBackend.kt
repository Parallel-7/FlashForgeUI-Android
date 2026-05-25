package com.example.backend

import com.example.api.FlashForgeHttpApi
import com.example.api.FlashForgeTcpClient
import com.example.api.PrinterModel
import com.example.data.PrinterEntity

/**
 * Adventurer 5M Pro: modern HTTP API with factory LEDs, air filtration, and an enclosed chamber.
 * LED and filtration availability are resolved from the `/product` flags by [DualApiBackend].
 */
class Adventurer5MProBackend(
    printer: PrinterEntity,
    http: FlashForgeHttpApi,
    tcp: FlashForgeTcpClient
) : DualApiBackend(printer, http, tcp) {

    override val model = PrinterModel.ADVENTURER_5M_PRO
}
