package me.ghost.ffui.api

/**
 * The FlashForge printer models this app knows how to talk to. The reliable identifier is the
 * firmware-stable [PrinterDetailResponse.pid]; the printer name is only a fallback because users
 * can rename their machines.
 */
enum class PrinterModel {
    /** Adventurer 5M — modern HTTP API, no factory LED/filtration, no material station. */
    ADVENTURER_5M,

    /** Adventurer 5M Pro — modern HTTP API, factory LED + air filtration + chamber. */
    ADVENTURER_5M_PRO,

    /** AD5X — modern HTTP API + independent material station (IFS, 4 slots). */
    AD5X,

    /** Adventurer 3 / 4 and other older machines — TCP G-code only (no modern HTTP API). */
    GENERIC_LEGACY,

    /** Could not be determined yet (e.g. before first /detail). */
    UNKNOWN;

    /** Whether this model uses the modern HTTP REST API (port 8898) as its primary transport. */
    val isModern: Boolean
        get() = this == ADVENTURER_5M || this == ADVENTURER_5M_PRO || this == AD5X

    companion object {
        // Firmware-stable product IDs (see ff-5mp-api-ts MachineInfo + BASE_BLUEPRINT.md).
        const val PID_5M = 35
        const val PID_5M_PRO = 36
        const val PID_AD5X = 38

        /**
         * Resolves the model from a `/detail` response, preferring [PrinterDetailResponse.pid] and
         * falling back to name/capability heuristics that mirror ff-5mp-api-ts's `MachineInfo`.
         */
        fun fromDetail(detail: PrinterDetailResponse): PrinterModel {
            when (detail.pid) {
                PID_5M -> return ADVENTURER_5M
                PID_5M_PRO -> return ADVENTURER_5M_PRO
                PID_AD5X -> return AD5X
            }

            val hasStation = detail.hasMatlStation == true ||
                (detail.matlStationInfo?.slotCnt ?: 0) > 0 ||
                (detail.matlStationInfo?.slotInfos?.isNotEmpty() == true)
            val name = detail.name.orEmpty()

            return when {
                hasStation || name.equals("AD5X", ignoreCase = true) ||
                    name.contains("5X", ignoreCase = true) -> AD5X
                name.contains("Pro", ignoreCase = true) -> ADVENTURER_5M_PRO
                name.contains("5M", ignoreCase = true) -> ADVENTURER_5M
                else -> UNKNOWN
            }
        }
    }
}

/**
 * What a connected printer can actually do, resolved at connect time from [PrinterModel] plus the
 * `/product` capability flags. UI controls are gated on these so unsupported cards never render.
 *
 * @property ledControl whether LED on/off is available at all (factory LEDs, or custom LEDs the
 *   user enabled per-printer for a 5M / AD5X).
 * @property ledViaHttp `true` to drive the LED over the HTTP `lightControl_cmd` (5M Pro factory
 *   LEDs); `false` to drive it over TCP `~M146` (custom LEDs on 5M / AD5X).
 * @property filtrationControl whether air filtration (internal/external/off) can be controlled
 *   (5M Pro only).
 * @property hasMaterialStation whether an independent material station (IFS) is present (AD5X).
 */
data class PrinterCapabilities(
    val model: PrinterModel = PrinterModel.UNKNOWN,
    val ledControl: Boolean = false,
    val ledViaHttp: Boolean = false,
    val filtrationControl: Boolean = false,
    val hasMaterialStation: Boolean = false,
    val hasCamera: Boolean = false,
    val cameraStreamUrl: String? = null
)
