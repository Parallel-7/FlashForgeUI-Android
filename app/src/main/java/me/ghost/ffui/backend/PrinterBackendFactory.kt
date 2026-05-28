package me.ghost.ffui.backend

import me.ghost.ffui.api.FlashForgeHttpApi
import me.ghost.ffui.api.FlashForgeTcpClient
import me.ghost.ffui.api.PrinterModel
import me.ghost.ffui.data.PrinterEntity

/** Builds the [PrinterBackend] strategy for a detected [PrinterModel]. */
object PrinterBackendFactory {
    fun create(
        model: PrinterModel,
        printer: PrinterEntity,
        http: FlashForgeHttpApi,
        tcp: FlashForgeTcpClient
    ): PrinterBackend = when (model) {
        PrinterModel.ADVENTURER_5M -> Adventurer5MBackend(printer, http, tcp)
        PrinterModel.ADVENTURER_5M_PRO -> Adventurer5MProBackend(printer, http, tcp)
        PrinterModel.AD5X -> AD5XBackend(printer, http, tcp)
        PrinterModel.GENERIC_LEGACY -> GenericLegacyBackend(printer, http, tcp)
        // Default unknown machines to the modern 5M backend; the next /detail refines it.
        PrinterModel.UNKNOWN -> Adventurer5MBackend(printer, http, tcp)
    }
}
