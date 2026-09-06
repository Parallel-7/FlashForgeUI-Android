package me.ghost.ffui.data

import me.ghost.ffapi.models.FFPrinterDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PollCadence] — the pure adaptive-poll decision extracted from
 * [ActivePrinterSession]. Pins the cadence table (printing 1.5 s, paused 2.5 s, offline 3 s,
 * idle 5 s, error 10 s, auth-failed 15 s), the completed fast-poll window, and the
 * background-throttle floor math. Status tokens are classified through the library's
 * `MachineState` mapping, so both paused spellings and Busy-class "downloading" are pinned here.
 */
class PollCadenceTest {

    private fun detail(status: String?) = FFPrinterDetail(status = status)

    private fun baseDelay(status: String?, state: ConnectionState = ConnectionState.Connected) =
        PollCadence.baseDelayMs(state, detail(status), completedSinceMs = 0L, nowMs = 0L)

    @Test
    fun `active print states poll at 1_5s`() {
        listOf("printing", "busy", "heating", "calibrate_doing", "downloading", "canceling", "cloud_slicing")
            .forEach { status -> assertEquals(status, 1_500L, baseDelay(status)) }
    }

    @Test
    fun `both paused spellings and transition states poll at 2_5s`() {
        // "pause" is the Creator 5 Pro's self-paused spelling; "paused" is the documented one.
        listOf("pause", "paused", "pausing", "cancel").forEach { status ->
            assertEquals(status, 2_500L, baseDelay(status))
        }
    }

    @Test
    fun `connection failures dominate the status`() {
        assertEquals(3_000L, baseDelay("printing", ConnectionState.Offline("boom")))
        assertEquals(15_000L, baseDelay("printing", ConnectionState.AuthFailed("bad checkCode")))
    }

    @Test
    fun `ready and blank statuses idle at 5s`() {
        assertEquals(5_000L, baseDelay("ready"))
        assertEquals(5_000L, baseDelay(""))
        assertEquals(5_000L, baseDelay(null))
    }

    @Test
    fun `error polls slowly at 10s`() {
        assertEquals(10_000L, baseDelay("error"))
    }

    @Test
    fun `completed stays fast for 30s then relaxes to idle`() {
        val state = ConnectionState.Connected
        val completed = detail("completed")
        // Still inside the platform-clear window.
        assertEquals(2_500L, PollCadence.baseDelayMs(state, completed, completedSinceMs = 10_000L, nowMs = 35_000L))
        // Exactly at the boundary (30 s elapsed) → relaxed.
        assertEquals(5_000L, PollCadence.baseDelayMs(state, completed, completedSinceMs = 10_000L, nowMs = 40_000L))
        // Long past it.
        assertEquals(5_000L, PollCadence.baseDelayMs(state, completed, completedSinceMs = 10_000L, nowMs = 500_000L))
    }

    @Test
    fun `connecting with no snapshot polls at idle cadence`() {
        assertEquals(5_000L, PollCadence.baseDelayMs(ConnectionState.Connecting, null, 0L, 0L))
    }

    @Test
    fun `throttle floor only ever slows polling down`() {
        assertEquals(10_000L, PollCadence.throttledDelayMs(baseMs = 1_500L, floorMs = 10_000L))
        assertEquals(5_000L, PollCadence.throttledDelayMs(baseMs = 5_000L, floorMs = 3_000L))
        // 0 floor = unrestricted (the foreground value).
        assertEquals(1_500L, PollCadence.throttledDelayMs(baseMs = 1_500L, floorMs = 0L))
    }
}
