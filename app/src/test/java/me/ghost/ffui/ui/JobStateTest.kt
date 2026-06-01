package me.ghost.ffui.ui

import me.ghost.ffui.api.PrinterDetailResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [jobStateOf] — the single source of truth that gates job controls on both the
 * dashboard and the Controls tab. Covers the modern HTTP states plus the legacy tokens that
 * `GenericLegacyBackend` normalizes into them.
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
    fun `unrecognized status is all-false`() {
        val s = stateFor("ready")
        assertFalse(s.isPrinting)
        assertFalse(s.isCompleted)
        assertFalse(s.isActiveJob)
    }
}
