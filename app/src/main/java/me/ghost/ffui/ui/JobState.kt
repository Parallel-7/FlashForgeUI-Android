package me.ghost.ffui.ui

import me.ghost.ffapi.models.FFPrinterDetail as PrinterDetailResponse

/**
 * Normalized view of the printer's current job, derived from the raw `/detail` `status` string.
 * Single source of truth for "which job controls show/enable" — both the dashboard and the Controls
 * tab derive their button gating from here so the two surfaces can never drift (they previously
 * hand-rolled identical `state in listOf(...)` checks).
 *
 * The raw strings are the modern HTTP "Machine States"; legacy-only tokens are already normalized to
 * these by the library's `GenericLegacyBackend` before they reach the UI.
 */
data class JobState(
    val isPrinting: Boolean,
    val isPrepping: Boolean,
    val isPaused: Boolean,
    val isPausing: Boolean,
    val isCompleted: Boolean,
) {
    /** An in-flight job the user can pause/resume/cancel (printing, warming up, paused, or pausing). */
    val isActiveJob: Boolean get() = isPrinting || isPrepping || isPaused || isPausing
}

/** Derives the [JobState] from a status snapshot (null snapshot → all-false). */
fun jobStateOf(status: PrinterDetailResponse?): JobState {
    val state = (status?.status ?: "").lowercase()
    return JobState(
        isPrinting = state in listOf("printing", "building_from_sd", "busy"),
        isPrepping = state in listOf("heating", "calibrate_doing"), // pre-print warm-up
        isPaused = state == "paused",
        isPausing = state == "pausing",
        isCompleted = state in listOf("completed", "building_completed"),
    )
}
