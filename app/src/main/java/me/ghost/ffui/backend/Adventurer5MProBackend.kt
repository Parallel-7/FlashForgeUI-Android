package me.ghost.ffui.backend

import me.ghost.ffui.api.FlashForgeHttpApi
import me.ghost.ffui.api.FlashForgeTcpClient
import me.ghost.ffui.api.PrinterModel
import me.ghost.ffui.data.PrinterEntity

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
