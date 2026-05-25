package com.example.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PrinterDetailRequest(
    val serialNumber: String,
    val checkCode: String
)

@Serializable
data class PrinterDetailWrapper(
    val code: Int = 0,
    val message: String? = null,
    val detail: PrinterDetailResponse? = null
)

/**
 * Raw `/detail` response. Field names mirror the printer's native JSON keys (verified against the
 * ff-5mp-api-ts `FFPrinterDetail` shape and a live AD5X). For the modern 5M / 5M Pro / AD5X series
 * this is the single source of truth for status — including the material station, reported inline
 * via [matlStationInfo] (NOT a separate endpoint).
 *
 * IMPORTANT: the firmware is **inconsistent** about numeric types — it serializes some numbers as
 * decimals (`estimatedTime:0.0`, `printSpeedAdjust:0.0`, `platTemp:17.52`) and others as ints
 * (`printDuration:0`, `slotCnt:4`). kotlinx.serialization parses a JSON integer literal into a
 * `Float` fine, but fails to parse a decimal literal into an `Int`. So every field that could
 * arrive fractional is typed `Float?`; only genuinely-integer ids/counts (`pid`) stay `Int?`.
 */
@Serializable
data class PrinterDetailResponse(
    val status: String? = null,
    // Temperatures — right* is the single/primary nozzle, plat* is the bed.
    val rightTemp: Float? = null,
    val rightTargetTemp: Float? = null,
    val platTemp: Float? = null,
    val platTargetTemp: Float? = null,
    val chamberTemp: Float? = null,
    val chamberTargetTemp: Float? = null,
    // Job progress / timing.
    val printProgress: Float? = null,
    val estimatedTime: Float? = null,
    val printDuration: Float? = null,
    val printFileName: String? = null,
    val printFileThumbUrl: String? = null,
    val printLayer: Float? = null,
    val targetPrintLayer: Float? = null,
    val currentPrintSpeed: Float? = null,
    val printSpeedAdjust: Float? = null,
    val zAxisCompensation: Float? = null,
    val fillAmount: Float? = null,
    val estimatedRightLen: Float? = null,
    val estimatedRightWeight: Float? = null,
    val totalFilamentLength: Float? = null,
    val totalFilamentWeight: Float? = null,
    // Fans / filtration (5M Pro) — "open"/"close" strings.
    val internalFanStatus: String? = null,
    val externalFanStatus: String? = null,
    val chamberFanSpeed: Float? = null,
    val coolingFanSpeed: Float? = null,
    val coolingFanLeftSpeed: Float? = null,
    val tvoc: Float? = null,
    // Identity / hardware.
    val machineType: String? = null,
    val pid: Int? = null,
    val nozzleModel: String? = null,
    val nozzleCnt: Float? = null,
    val doorStatus: String? = null,
    val lightStatus: String? = null,
    val errorCode: String? = null,
    val cameraStreamUrl: String? = null,
    val name: String? = null,
    val firmwareVersion: String? = null,
    // Material station (AD5X) — reported inline on /detail.
    val hasMatlStation: Boolean? = null,
    val matlStationInfo: MatlStationInfo? = null,
    val indepMatlInfo: IndepMatlInfo? = null
)

@Serializable
data class ControlRequest(
    val serialNumber: String,
    val checkCode: String,
    val payload: ControlPayload
)

@Serializable
data class ControlPayload(
    val cmd: String,
    val args: JsonElement
)

@Serializable
data class LightControlArgs(val status: String)

@Serializable
data class TemperatureCtlArgs(val rightTemp: Int = -200, val leftTemp: Int = -200, val platTemp: Int = -200, val chamberTemp: Int = -200)

@Serializable
data class StateCtrlArgs(val action: String)

@Serializable
data class JobCtlArgs(val jobID: String = "", val action: String)

@Serializable
data class MatlStationInfo(
    val currentLoadSlot: Int = 0,
    val currentSlot: Int = 0,
    val slotCnt: Int = 0,
    val slotInfos: List<MatlSlotInfo> = emptyList(),
    val stateAction: Int = 0,
    val stateStep: Int = 0
)

@Serializable
data class MatlSlotInfo(
    val hasFilament: Boolean = false,
    val materialColor: String = "",
    val materialName: String = "",
    val slotId: Int = 0
)

/**
 * Independent material loading info (AD5X single-extruder-with-station flow). [materialName] may
 * be `"?"` when unknown.
 */
@Serializable
data class IndepMatlInfo(
    val materialColor: String = "",
    val materialName: String = "",
    val stateAction: Int = 0,
    val stateStep: Int = 0
)

/** `/product` response wrapper. */
@Serializable
data class ProductWrapper(
    val code: Int = 0,
    val message: String? = null,
    val product: Product? = null
)

/**
 * Capability flags from `POST /product`. A value of `0` means the feature is unavailable/off;
 * non-zero means available. Used to gate UI controls per the printer's actual hardware.
 */
@Serializable
data class Product(
    val chamberTempCtrlState: Int = 0,
    val externalFanCtrlState: Int = 0,
    val internalFanCtrlState: Int = 0,
    val lightCtrlState: Int = 0,
    val nozzleTempCtrlState: Int = 0,
    val platformTempCtrlState: Int = 0
)
