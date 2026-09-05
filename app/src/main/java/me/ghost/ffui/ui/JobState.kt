package me.ghost.ffui.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.ghost.ffui.R
import me.ghost.ffapi.models.FFPrinterDetail as PrinterDetailResponse
import me.ghost.ffapi.models.MachineInfo
import me.ghost.ffapi.models.MachineState

/**
 * Normalized view of the printer's current job, derived from the raw `/detail` `status` string via
 * the library's [MachineState] mapping (single source of truth — the same enum the library itself
 * uses, so a firmware token the library learns is one the UI understands too).
 *
 * Both surfaces that gate job controls (dashboard and Controls tab) derive their buttons from here
 * so they can never drift.
 *
 * Legacy-only tokens (`building_from_sd` and friends) are normally normalized to modern
 * equivalents by the library's `GenericLegacyBackend` before they reach the UI; the raw-string
 * handling below keeps them sensible even if one slips through.
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
    if (status == null) return JobState(
        isPrinting = false, isPrepping = false, isPaused = false, isPausing = false, isCompleted = false,
    )
    val raw = (status.status ?: "").lowercase()
    // Legacy completion token the enum mapping doesn't know (the library's legacy backend
    // normally normalizes it to "completed" before it gets here).
    if (raw == "building_completed") return JobState(
        isPrinting = false, isPrepping = false, isPaused = false, isPausing = false, isCompleted = true,
    )
    return when (machineStateOf(status)) {
        // Busy class: printing plus "busy" and "downloading" (file transfer), which the library
        // maps onto Busy. A cancel in flight ("cancel") keeps the controls visible too.
        MachineState.Printing, MachineState.Busy, MachineState.Cancelled -> printing()
        MachineState.Heating, MachineState.Calibrating -> prepping()
        MachineState.Paused -> paused()   // covers BOTH firmware spellings: "pause" and "paused"
        MachineState.Pausing -> pausing()
        MachineState.Completed -> completed()
        MachineState.Ready, MachineState.Error -> idle()
        // Docs: an unrecognized status ⇒ treat as BUSY, keep polling (fw-5.x cloud_slicing /
        // sending / unzipping, the transient "canceling", and future tokens). A blank status
        // carries no information — read it as idle, not busy.
        MachineState.Unknown -> if (raw.isNotBlank()) printing() else idle()
    }
}

/**
 * Resource ID of the friendly, user-facing label for [status] ("Paused", "Downloading", …), or
 * null when the label must be derived dynamically — a null snapshot renders "—" and an
 * unrecognized firmware token renders prettified ([prettifyStateToken]). Pure so the enum→label
 * mapping stays unit-testable; [friendlyStateLabel] resolves it for Compose.
 */
fun friendlyStateLabelRes(status: PrinterDetailResponse?): Int? {
    if (status == null) return null
    val raw = (status.status ?: "").lowercase()
    if (raw == "building_completed") return R.string.common_state_completed
    return when (machineStateOf(status)) {
        MachineState.Printing -> R.string.common_state_printing
        MachineState.Busy -> if (raw == "downloading") R.string.common_state_downloading else R.string.common_state_busy
        MachineState.Heating -> R.string.common_state_heating
        MachineState.Calibrating -> R.string.common_state_calibrating
        MachineState.Paused -> R.string.common_state_paused
        MachineState.Pausing -> R.string.common_state_pausing
        MachineState.Cancelled -> R.string.common_state_cancelling
        MachineState.Completed -> R.string.common_state_completed
        MachineState.Error -> R.string.common_state_error
        MachineState.Ready -> R.string.common_state_ready
        MachineState.Unknown -> null
    }
}

/** Prettifies an unrecognized firmware token ("cloud_slicing" → "Cloud slicing"); blank → "—". */
fun prettifyStateToken(raw: String): String =
    if (raw.isBlank()) "—" else raw.replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * Friendly, user-facing label for the current status — used wherever the dashboard or Controls tab
 * shows the printer's state, so no surface renders a raw firmware token like "pause". Known
 * states resolve from string resources via [friendlyStateLabelRes]; [status] null or an
 * unrecognized token falls back to "—" / [prettifyStateToken].
 */
@Composable
fun friendlyStateLabel(status: PrinterDetailResponse?): String {
    friendlyStateLabelRes(status)?.let { return stringResource(it) }
    if (status == null) return "—"
    return prettifyStateToken((status.status ?: "").lowercase())
}

/** Maps a snapshot onto the library's [MachineState] (Unknown when null/blank/unrecognized). */
private fun machineStateOf(status: PrinterDetailResponse): MachineState =
    MachineInfo().fromDetail(status)?.machineState ?: MachineState.Unknown

private fun printing() = JobState(true, false, false, false, false)
private fun prepping() = JobState(false, true, false, false, false)
private fun paused() = JobState(false, false, true, false, false)
private fun pausing() = JobState(false, false, false, true, false)
private fun completed() = JobState(false, false, false, false, true)
private fun idle() = JobState(false, false, false, false, false)
