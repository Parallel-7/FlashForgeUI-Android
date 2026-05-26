package com.example.backend

import com.example.api.AD5XMaterialMapping
import com.example.api.FlashForgeHttpApi
import com.example.api.FlashForgeTcpClient
import com.example.api.MatlStationInfo
import com.example.api.PrintGcodeRequest
import com.example.api.PrinterCapabilities
import com.example.api.PrinterModel
import com.example.api.PrinterDetailResponse
import com.example.data.PrinterEntity

/**
 * AD5X: modern HTTP API plus the independent material station (IFS, 4 slots) reported inline on
 * `/detail` via `matlStationInfo`. No factory LEDs (custom-only over TCP) and no air filtration.
 */
class AD5XBackend(
    printer: PrinterEntity,
    http: FlashForgeHttpApi,
    tcp: FlashForgeTcpClient
) : DualApiBackend(printer, http, tcp) {

    override val model = PrinterModel.AD5X

    override fun baselineCapabilities() = PrinterCapabilities(
        model = model,
        ledControl = printer.customLedEnabled,
        ledViaHttp = false,
        hasMaterialStation = true
    )

    override fun materialStation(detail: PrinterDetailResponse): MatlStationInfo? = detail.matlStationInfo

    /**
     * AD5X always sends the full `/printGcode` payload (regardless of firmware): a non-empty
     * [mappings] is a multi-color job (`useMatlStation=true`, tool count from the mappings); an empty
     * one is a single-color job that bypasses the station.
     */
    override suspend fun startPrint(
        fileName: String,
        leveling: Boolean,
        mappings: List<AD5XMaterialMapping>
    ): Result<Unit> {
        val req = PrintGcodeRequest(
            serialNumber = printer.serialNumber,
            checkCode = printer.checkCode,
            fileName = fileName,
            levelingBeforePrint = leveling,
            useMatlStation = mappings.isNotEmpty(),
            gcodeToolCnt = mappings.size,
            materialMappings = mappings
        )
        return http.printGcode(req)
    }
}
