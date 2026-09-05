package me.ghost.ffui.ui

import me.ghost.ffapi.PrinterModel

/**
 * Shared pid → model display-name mapping (35=5M, 36=5M Pro, 38=AD5X, 40=Creator 5,
 * 41=Creator 5 Pro). One source for every UI surface that labels a printer by model — the
 * dashboard tab bar, the printer info screen, and the per-printer settings header — so a newly
 * supported model can't show up labeled in one screen but unnamed in another. Model *detection*
 * stays in the library (`PrinterModel.fromDetail`); this mapping is presentation only.
 */
object PrinterModelNames {

    /** Short label for compact surfaces (tab subtitles); blank when the model is unknown. */
    fun shortName(pid: Int?): String = when (pid) {
        PrinterModel.PID_5M -> "5M"
        PrinterModel.PID_5M_PRO -> "5M Pro"
        PrinterModel.PID_AD5X -> "AD5X"
        PrinterModel.PID_CREATOR_5 -> "Creator 5"
        PrinterModel.PID_CREATOR_5_PRO -> "Creator 5 Pro"
        else -> ""
    }

    /** Full label for detail surfaces (info rows); `—` for no pid, `Unknown (pid N)` if unrecognized. */
    fun fullName(pid: Int?): String = when (pid) {
        PrinterModel.PID_5M -> "Adventurer 5M"
        PrinterModel.PID_5M_PRO -> "Adventurer 5M Pro"
        PrinterModel.PID_AD5X -> "AD5X"
        PrinterModel.PID_CREATOR_5 -> "Creator 5"
        PrinterModel.PID_CREATOR_5_PRO -> "Creator 5 Pro"
        null -> "—"
        else -> "Unknown (pid $pid)"
    }
}
