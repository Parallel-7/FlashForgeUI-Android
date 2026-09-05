package me.ghost.ffui.ui

import me.ghost.ffapi.models.FFPrinterDetail as PrinterDetailResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [jobStateOf] and [friendlyStateLabel] — the single source of truth that gates job
 * controls on both the dashboard and the Controls tab. Both derive from the library's
 * `MachineState` mapping, so these tests pin the app's enum→JobState translation on top of it:
 * the firmware's two paused spellings, the busy-class tokens ("downloading", fw-5.x slicing
 * states, "canceling"), and the legacy tokens `GenericLegacyBackend` normally normalizes away.
 */
class JobStateTest {

    private fun stateFor(status: String?) = jobStateOf(status?.let { PrinterDetailResponse(status = it) })

    @Test
    fun `null status is all-false`() {
        val s = jobStateOf(null)
        assertFalse(s.isPrinting)
        assertFalse(s.isPrepping)
        assertFalse(s.isPaused)
        assertFalse(s.isPausing)
        assertFalse(s.isCompleted)
        assertFalse(s.isActiveJob)
    }

    @Test
    fun `printing and its legacy alias both count as printing`() {
        assertTrue(stateFor("printing").isPrinting)
        assertTrue(stateFor("building_from_sd").isPrinting)
        assertTrue(stateFor("busy").isPrinting)
    }

    @Test
    fun `status matching is case-insensitive`() {
        assertTrue(stateFor("PRINTING").isPrinting)
        assertTrue(stateFor("Paused").isPaused)
    }

    @Test
    fun `both firmware paused spellings keep the job controls`() {
        // The Creator 5 Pro sends "pause" where the docs say "paused" — both are paused.
        assertTrue(stateFor("paused").isPaused)
        assertTrue(stateFor("pause").isPaused)
        assertTrue(stateFor("pause").isActiveJob)
        assertTrue(stateFor("pause").isPrinting.not())
    }

    @Test
    fun `busy-class transient states stay active jobs`() {
        // "downloading" (file transfer), the transient "canceling", a cancel in flight, and the
        // fw-5.x slicing states must not drop the pause/resume/cancel controls.
        listOf("downloading", "canceling", "cancel", "cloud_slicing", "sending", "unzipping").forEach {
            assertTrue(it, stateFor(it).isActiveJob)
        }
    }

    @Test
    fun `prepping covers warm-up states`() {
        assertTrue(stateFor("heating").isPrepping)
        assertTrue(stateFor("calibrate_doing").isPrepping)
        assertFalse(stateFor("heating").isPrinting)
    }

    @Test
    fun `paused and pausing are distinct`() {
        assertTrue(stateFor("paused").isPaused)
        assertFalse(stateFor("paused").isPausing)
        assertTrue(stateFor("pausing").isPausing)
        assertFalse(stateFor("pausing").isPaused)
    }

    @Test
    fun `completed and its legacy alias both count as completed`() {
        assertTrue(stateFor("completed").isCompleted)
        assertTrue(stateFor("building_completed").isCompleted)
    }

    @Test
    fun `isActiveJob is true for in-flight jobs and false otherwise`() {
        assertTrue(stateFor("printing").isActiveJob)
        assertTrue(stateFor("heating").isActiveJob)
        assertTrue(stateFor("paused").isActiveJob)
        assertTrue(stateFor("pausing").isActiveJob)
        assertFalse(stateFor("completed").isActiveJob)
        assertFalse(stateFor("ready").isActiveJob)
    }

    @Test
    fun `unrecognized status with a value reads as busy, blank reads as idle`() {
        assertTrue(stateFor("some_future_fw6_state").isPrinting)
        assertFalse(jobStateOf(PrinterDetailResponse(status = "")).isActiveJob)
        assertFalse(jobStateOf(PrinterDetailResponse(status = null)).isActiveJob)
    }

    @Test
    fun `friendly labels never leak raw firmware tokens`() {
        assertEquals("Paused", friendlyStateLabel(PrinterDetailResponse(status = "pause")))
        assertEquals("Paused", friendlyStateLabel(PrinterDetailResponse(status = "paused")))
        assertEquals("Downloading", friendlyStateLabel(PrinterDetailResponse(status = "downloading")))
        assertEquals("Printing", friendlyStateLabel(PrinterDetailResponse(status = "printing")))
        assertEquals("Heating", friendlyStateLabel(PrinterDetailResponse(status = "heating")))
        assertEquals("Completed", friendlyStateLabel(PrinterDetailResponse(status = "completed")))
        assertEquals("Ready", friendlyStateLabel(PrinterDetailResponse(status = "ready")))
        assertEquals("Error", friendlyStateLabel(PrinterDetailResponse(status = "error")))
        assertEquals("—", friendlyStateLabel(null))
        // Unrecognized tokens render humanized, not as raw snake_case.
        assertEquals("Cloud slicing", friendlyStateLabel(PrinterDetailResponse(status = "cloud_slicing")))
    }
}
