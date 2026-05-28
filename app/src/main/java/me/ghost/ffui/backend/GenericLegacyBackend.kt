package me.ghost.ffui.backend

import me.ghost.ffui.api.FlashForgeHttpApi
import me.ghost.ffui.api.FlashForgeTcpClient
import me.ghost.ffui.api.PrinterModel
import me.ghost.ffui.api.PrinterDetailResponse
import me.ghost.ffui.data.PrinterEntity

/**
 * Fallback for older machines (Adventurer 3 / 4) that lack the modern HTTP REST API. Status is
 * polled over the TCP G-code transport and mapped onto [PrinterDetailResponse] so the rest of the
 * app can treat all backends uniformly.
 *
 * NOTE: legacy hardware is not the focus of this port and this path is not yet reached by the
 * connect flow (which currently identifies printers via `/detail`'s `pid`). The backend exists to
 * keep the model-strategy hierarchy complete; full legacy telemetry parsing is future work.
 */
class GenericLegacyBackend(
    printer: PrinterEntity,
    http: FlashForgeHttpApi,
    tcp: FlashForgeTcpClient
) : PrinterBackend(printer, http, tcp) {

    override val model = PrinterModel.GENERIC_LEGACY
    override val pollsOverTcp = true

    override suspend fun pollStatus(): Result<PrinterDetailResponse> {
        if (!tcp.isConnected.value) {
            return Result.failure(IllegalStateException("Legacy TCP transport not connected"))
        }
        // Map whatever the TCP keep-alive has parsed so far into the shared status shape.
        val t = tcp.telemetry.value
        return Result.success(
            PrinterDetailResponse(
                status = t.machineStatus,
                rightTemp = t.extCurrentTemp,
                rightTargetTemp = t.extTargetTemp,
                platTemp = t.bedCurrentTemp,
                platTargetTemp = t.bedTargetTemp,
                name = printer.name
            )
        )
    }
}
