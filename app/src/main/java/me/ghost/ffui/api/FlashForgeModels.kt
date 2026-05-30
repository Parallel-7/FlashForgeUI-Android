package me.ghost.ffui.api

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
    val macAddr: String? = null,
    // Auto-shutdown ("open"/"close"; time in minutes after a completed print).
    val autoShutdown: String? = null,
    val autoShutdownTime: Float? = null,
    // Lifetime stats (see "Printer Info" screen). cumulativeFilament is meters, cumulativePrintTime
    // is minutes (verified against ff-5mp-api-ts MachineInfo); remainingDiskSpace is GB free —
    // confirmed on an AD5X as a fractional value (~4.94), so it MUST be Float? not Long? (the
    // firmware serializes it as a decimal and a decimal literal won't parse into an integer type).
    val cumulativeFilament: Float? = null,
    val cumulativePrintTime: Float? = null,
    val remainingDiskSpace: Float? = null,
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
data class StateCtrlArgs(val action: String)

/** Args for `circulateCtl_cmd` (5M Pro air filtration); values are `"open"`/`"close"`. */
@Serializable
data class CirculateCtlArgs(val internal: String, val external: String)

@Serializable
data class JobCtlArgs(val jobID: String = "", val action: String)

/** Args for `msConfig_cmd` (AD5X IFS slot metadata). [slot] is 1-based; [rgb] is hex WITHOUT `#`. */
@Serializable
data class MsConfigArgs(val slot: Int, val mt: String, val rgb: String)

/** Args for `ms_cmd` (AD5X IFS load/unload/cancel). [action]: 0=load, 1=unload, 2=cancel. */
@Serializable
data class MsCtlArgs(val slot: Int, val action: Int)

/** Args for `reName_cmd` (changes the printer's display name). */
@Serializable
data class ReNameArgs(val name: String)

/** Args for `delayClose_cmd` (auto-shutdown). [automaticShutdown] is `"open"`/`"close"`. */
@Serializable
data class DelayCloseArgs(val automaticShutdown: String, val shutdownAfterTime: Int)

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

// ── File management (Phase 4) ─────────────────────────────────────────────────

/**
 * Per-tool material info inside a multi-color G-code file (AD5X `gcodeListDetail`). Mirrors
 * ff-5mp-api-ts `FFGcodeToolData`. [toolId] is 0-based (0-3); [slotId] is the file's *suggested*
 * station slot (0 when none). Numeric weight is `Float?` per the firmware numeric-type gotcha.
 */
@Serializable
data class FFGcodeToolData(
    val toolId: Int = 0,
    val slotId: Int = 0,
    val materialName: String = "",
    val materialColor: String = "",
    val filamentWeight: Float? = null
)

/**
 * One entry in the `/gcodeList` response. AD5X (and newer) populate [gcodeToolDatas] for
 * multi-color files; older printers return only [gcodeFileName]. [printingTime] is seconds.
 */
@Serializable
data class FFGcodeFileEntry(
    val gcodeFileName: String = "",
    val printingTime: Float? = null,
    val gcodeToolCnt: Int? = null,
    val gcodeToolDatas: List<FFGcodeToolData>? = null,
    val totalFilamentWeight: Float? = null,
    val useMatlStation: Boolean? = null
) {
    /** A file needs material matching when it declares more than one tool. */
    val isMultiColor: Boolean get() = (gcodeToolDatas?.size ?: 0) > 1
}

/**
 * `/gcodeList` wrapper. [gcodeListDetail] is the rich AD5X form (with tool data); [gcodeList] is
 * the legacy form and may be either a JSON array of strings or of objects — left as raw
 * [JsonElement]s and normalized by [FlashForgeHttpApi.getRecentFileList].
 */
@Serializable
data class GcodeListWrapper(
    val code: Int = 0,
    val message: String? = null,
    val gcodeList: List<JsonElement>? = null,
    val gcodeListDetail: List<FFGcodeFileEntry>? = null
)

/** `/gcodeThumb` wrapper — [imageData] is a base64-encoded PNG. */
@Serializable
data class GcodeThumbWrapper(
    val code: Int = 0,
    val message: String? = null,
    val imageData: String? = null
)

/** `/gcodeThumb` request body. */
@Serializable
data class GcodeThumbRequest(
    val serialNumber: String,
    val checkCode: String,
    val fileName: String
)

/**
 * A tool→slot assignment for an AD5X multi-color print. Sent as a *raw JSON array* in the
 * `/printGcode` body (unlike upload, which base64-encodes it). [toolId] 0-based, [slotId] 1-based;
 * colors must be `#RRGGBB`.
 */
@Serializable
data class AD5XMaterialMapping(
    val toolId: Int,
    val slotId: Int,
    val materialName: String,
    val toolMaterialColor: String,
    val slotMaterialColor: String
)

/**
 * `/printGcode` request body. The printer accepts a superset across firmware versions: pre-3.1.3
 * machines ignore the material-station fields, while ≥3.1.3 and AD5X require them. We always send
 * the full shape and let [useMatlStation]/[materialMappings] drive behavior (matching the reference
 * lib's new-firmware payload).
 */
@Serializable
data class PrintGcodeRequest(
    val serialNumber: String,
    val checkCode: String,
    val fileName: String,
    val levelingBeforePrint: Boolean = false,
    val flowCalibration: Boolean = false,
    val firstLayerInspection: Boolean = false,
    val timeLapseVideo: Boolean = false,
    val useMatlStation: Boolean = false,
    val gcodeToolCnt: Int = 0,
    val materialMappings: List<AD5XMaterialMapping> = emptyList()
)

/** Minimal `/printGcode` body for pre-3.1.3 firmware (no material-station fields). */
@Serializable
data class PrintGcodeRequestLegacy(
    val serialNumber: String,
    val checkCode: String,
    val fileName: String,
    val levelingBeforePrint: Boolean = false
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
