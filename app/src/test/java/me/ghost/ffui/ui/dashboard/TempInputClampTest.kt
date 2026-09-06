package me.ghost.ffui.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Boundary tests for the temperature-dialog input sanitizers — the wave-1 client-side clamps to
 * the firmware ceilings ([NOZZLE_MAX_TEMP] 265, [BED_MAX_TEMP] 100, chamber 80). Garbage, signs,
 * and mixed digit runs must normalize cleanly; values above the ceiling clamp to it, never above.
 */
class TempInputClampTest {

    // ── sanitizeTempInput: what the field displays ───────────────────────────────

    @Test
    fun `normal values pass through untouched`() {
        assertEquals("210", sanitizeTempInput("210", NOZZLE_MAX_TEMP))
        assertEquals("60", sanitizeTempInput("60", BED_MAX_TEMP))
        assertEquals("80", sanitizeTempInput("80", 80))
    }

    @Test
    fun `empty and garbage input render empty`() {
        assertEquals("", sanitizeTempInput("", NOZZLE_MAX_TEMP))
        assertEquals("", sanitizeTempInput("abc", NOZZLE_MAX_TEMP))
        assertEquals("", sanitizeTempInput("   ", NOZZLE_MAX_TEMP))
    }

    @Test
    fun `negative input loses its sign — the dialog cannot express negatives`() {
        assertEquals("50", sanitizeTempInput("-50", NOZZLE_MAX_TEMP))
        assertEquals("0", sanitizeTempInput("-0", NOZZLE_MAX_TEMP))
    }

    @Test
    fun `zero is a valid entry (heater-off via set 0)`() {
        assertEquals("0", sanitizeTempInput("0", NOZZLE_MAX_TEMP))
    }

    @Test
    fun `values above the ceiling clamp to it`() {
        assertEquals("265", sanitizeTempInput("266", NOZZLE_MAX_TEMP))
        assertEquals("265", sanitizeTempInput("999", NOZZLE_MAX_TEMP))
        assertEquals("100", sanitizeTempInput("101", BED_MAX_TEMP))
        assertEquals("80", sanitizeTempInput("9999", 80))
    }

    @Test
    fun `at-the-ceiling values are kept`() {
        assertEquals("265", sanitizeTempInput("265", NOZZLE_MAX_TEMP))
        assertEquals("100", sanitizeTempInput("100", BED_MAX_TEMP))
    }

    @Test
    fun `non-digit characters are stripped mid-entry`() {
        assertEquals("265", sanitizeTempInput("26x5", NOZZLE_MAX_TEMP))
        assertEquals("215", sanitizeTempInput("21.5", NOZZLE_MAX_TEMP)) // decimal point stripped, digits kept
        assertEquals("200", sanitizeTempInput(" 200 ", NOZZLE_MAX_TEMP))
    }

    @Test
    fun `leading zeros normalize away once parseable`() {
        assertEquals("7", sanitizeTempInput("007", NOZZLE_MAX_TEMP))
        assertEquals("0", sanitizeTempInput("000", NOZZLE_MAX_TEMP))
    }

    @Test
    fun `null maxTemp disables the clamp`() {
        assertEquals("999", sanitizeTempInput("999", null))
        assertEquals("", sanitizeTempInput("abc", null)) // garbage still reduces to its (empty) digit run
    }

    // ── clampTempValue: what the Set button sends ────────────────────────────────

    @Test
    fun `set resolves displayed value clamped to the ceiling`() {
        assertEquals(210, clampTempValue("210", NOZZLE_MAX_TEMP))
        assertEquals(265, clampTempValue("999", NOZZLE_MAX_TEMP))
        assertEquals(100, clampTempValue("150", BED_MAX_TEMP))
        assertEquals(80, clampTempValue("80", 80))
    }

    @Test
    fun `set sends nothing for empty or garbage display`() {
        assertNull(clampTempValue("", NOZZLE_MAX_TEMP))
        assertNull(clampTempValue("abc", NOZZLE_MAX_TEMP))
    }

    @Test
    fun `set without a ceiling sends the raw value`() {
        assertEquals(999, clampTempValue("999", null))
    }
}
