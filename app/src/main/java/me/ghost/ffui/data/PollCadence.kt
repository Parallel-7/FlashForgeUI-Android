package me.ghost.ffui.data

import me.ghost.ffapi.models.FFPrinterDetail
import me.ghost.ffapi.models.MachineInfo
import me.ghost.ffapi.models.MachineState

/**
 * Pure decision logic for the session poll loop's adaptive cadence, extracted from
 * [ActivePrinterSession] so it is unit-testable without sockets or clocks.
 *
 * Cadence classes follow the library's [MachineState] mapping (the same one `jobStateOf` uses) so
 * polling speed and control gating can never disagree about what the printer is doing — both
 * firmware paused spellings ("pause"/"paused") and the Busy-class "downloading" included.
 */
internal object PollCadence {

    /**
     * The adaptive cadence before any background-throttle floor is applied, keyed off the
     * connection state first, then the wire-level `status` (see the HTTP REST "Machine States").
     *
     * @param state Current connection lifecycle state.
     * @param detail Latest `/detail` snapshot, or null before the first one.
     * @param completedSinceMs Monotonic timestamp of the `printing → completed` transition (0 when
     *   the session has not witnessed one) — keeps the post-completion window fast.
     * @param nowMs Current monotonic time, same clock as [completedSinceMs].
     */
    fun baseDelayMs(
        state: ConnectionState,
        detail: FFPrinterDetail?,
        completedSinceMs: Long,
        nowMs: Long,
    ): Long {
        when (state) {
            is ConnectionState.AuthFailed -> return 15_000L
            is ConnectionState.Offline -> return 3_000L
            else -> {}
        }
        val status = detail?.status?.lowercase()
        return when (detail?.let { MachineInfo().fromDetail(it)?.machineState }) {
            // Active / user-watched operations — climb fast. The Busy class also covers
            // "downloading"; an unrecognized non-blank status is busy-class per the docs.
            MachineState.Printing, MachineState.Busy,
            MachineState.Heating, MachineState.Calibrating -> 1_500L
            // Paused or a transient end-of-job dialog the user is likely interacting with
            // ("pause"/"paused", "pausing", a cancel being processed).
            MachineState.Paused, MachineState.Pausing, MachineState.Cancelled -> 2_500L
            // Just finished: stay responsive for ~30s (platform-clear), then fall to idle.
            MachineState.Completed ->
                if (nowMs - completedSinceMs < 30_000L) 2_500L else 5_000L
            MachineState.Error -> 10_000L
            // Docs: an unrecognized non-blank status means busy-class work in progress
            // (fw-5.x cloud_slicing / sending / unzipping, the transient "canceling").
            MachineState.Unknown -> if (!status.isNullOrBlank()) 1_500L else 5_000L
            // `ready`, or no snapshot yet.
            MachineState.Ready, null -> 5_000L
        }
    }

    /** Applies the background-throttle floor: it never speeds polling up, only slows it down. */
    fun throttledDelayMs(baseMs: Long, floorMs: Long): Long = maxOf(baseMs, floorMs)
}
