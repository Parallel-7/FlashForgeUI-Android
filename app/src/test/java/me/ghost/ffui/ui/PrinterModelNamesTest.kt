package me.ghost.ffui.ui

import me.ghost.ffapi.PrinterModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the shared pid → model display-name mapping (the wave-1 unification): every supported
 * pid — Creator 5 / Creator 5 Pro (40/41) included — labels identically on every surface.
 * Model *detection* stays in the library; this is presentation only.
 */
class PrinterModelNamesTest {

    @Test
    fun `every supported pid maps to its short label`() {
        assertEquals("5M", PrinterModelNames.shortName(PrinterModel.PID_5M))
        assertEquals("5M Pro", PrinterModelNames.shortName(PrinterModel.PID_5M_PRO))
        assertEquals("AD5X", PrinterModelNames.shortName(PrinterModel.PID_AD5X))
        assertEquals("Creator 5", PrinterModelNames.shortName(PrinterModel.PID_CREATOR_5))
        assertEquals("Creator 5 Pro", PrinterModelNames.shortName(PrinterModel.PID_CREATOR_5_PRO))
    }

    @Test
    fun `every supported pid maps to its full label`() {
        assertEquals("Adventurer 5M", PrinterModelNames.fullName(PrinterModel.PID_5M))
        assertEquals("Adventurer 5M Pro", PrinterModelNames.fullName(PrinterModel.PID_5M_PRO))
        assertEquals("AD5X", PrinterModelNames.fullName(PrinterModel.PID_AD5X))
        assertEquals("Creator 5", PrinterModelNames.fullName(PrinterModel.PID_CREATOR_5))
        assertEquals("Creator 5 Pro", PrinterModelNames.fullName(PrinterModel.PID_CREATOR_5_PRO))
    }

    @Test
    fun `pid constants stay pinned to the documented values`() {
        // 35=5M, 36=5M Pro, 38=AD5X, 40=Creator 5, 41=Creator 5 Pro (docs-wiki Printer-PIDs).
        assertEquals(35, PrinterModel.PID_5M)
        assertEquals(36, PrinterModel.PID_5M_PRO)
        assertEquals(38, PrinterModel.PID_AD5X)
        assertEquals(40, PrinterModel.PID_CREATOR_5)
        assertEquals(41, PrinterModel.PID_CREATOR_5_PRO)
    }

    @Test
    fun `unknown and missing pids degrade gracefully`() {
        assertEquals("", PrinterModelNames.shortName(99))
        assertEquals("Unknown (pid 99)", PrinterModelNames.fullName(99))
        assertEquals("", PrinterModelNames.shortName(null))
        assertEquals("—", PrinterModelNames.fullName(null))
    }
}
