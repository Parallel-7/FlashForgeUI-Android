package me.ghost.ffui.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PrinterModel] detection: the firmware-stable `pid` path (modern printers), the
 * name/material-station heuristics fallback, and the TCP `~M115` `Machine Type:` path (legacy).
 */
class PrinterModelTest {

    // ---- fromDetail: pid takes priority over everything ----

    @Test
    fun `pid resolves modern models`() {
        assertEquals(PrinterModel.ADVENTURER_5M, PrinterModel.fromDetail(PrinterDetailResponse(pid = 35)))
        assertEquals(PrinterModel.ADVENTURER_5M_PRO, PrinterModel.fromDetail(PrinterDetailResponse(pid = 36)))
        assertEquals(PrinterModel.AD5X, PrinterModel.fromDetail(PrinterDetailResponse(pid = 38)))
    }

    @Test
    fun `pid wins even when the name disagrees`() {
        // A 5M Pro pid with an "AD5X"-ish name must still resolve by pid.
        val detail = PrinterDetailResponse(pid = 36, name = "AD5X")
        assertEquals(PrinterModel.ADVENTURER_5M_PRO, PrinterModel.fromDetail(detail))
    }

    // ---- fromDetail: heuristics when pid is absent/unknown ----

    @Test
    fun `material station presence implies AD5X`() {
        assertEquals(
            PrinterModel.AD5X,
            PrinterModel.fromDetail(PrinterDetailResponse(hasMatlStation = true))
        )
        assertEquals(
            PrinterModel.AD5X,
            PrinterModel.fromDetail(
                PrinterDetailResponse(matlStationInfo = MatlStationInfo(slotCnt = 4))
            )
        )
    }

    @Test
    fun `name heuristics resolve when pid missing`() {
        assertEquals(PrinterModel.AD5X, PrinterModel.fromDetail(PrinterDetailResponse(name = "My AD5X")))
        assertEquals(PrinterModel.ADVENTURER_5M_PRO, PrinterModel.fromDetail(PrinterDetailResponse(name = "Adventurer 5M Pro")))
        assertEquals(PrinterModel.ADVENTURER_5M, PrinterModel.fromDetail(PrinterDetailResponse(name = "Adventurer 5M")))
    }

    @Test
    fun `unrecognized detail is UNKNOWN`() {
        assertEquals(PrinterModel.UNKNOWN, PrinterModel.fromDetail(PrinterDetailResponse(name = "Mystery Printer")))
        assertEquals(PrinterModel.UNKNOWN, PrinterModel.fromDetail(PrinterDetailResponse()))
    }

    // ---- fromMachineType: legacy TCP path ----

    @Test
    fun `machine type resolves legacy models case-insensitively`() {
        assertEquals(PrinterModel.ADVENTURER_3, PrinterModel.fromMachineType("Adventurer 3"))
        assertEquals(PrinterModel.ADVENTURER_3, PrinterModel.fromMachineType("flashforge adventurer III"))
        assertEquals(PrinterModel.ADVENTURER_4, PrinterModel.fromMachineType("Adventurer 4"))
    }

    @Test
    fun `unknown machine type falls back to generic legacy`() {
        assertEquals(PrinterModel.GENERIC_LEGACY, PrinterModel.fromMachineType("Creator Pro"))
        assertEquals(PrinterModel.GENERIC_LEGACY, PrinterModel.fromMachineType(""))
    }

    // ---- isModern ----

    @Test
    fun `isModern is true only for the 5M family`() {
        assertEquals(true, PrinterModel.ADVENTURER_5M.isModern)
        assertEquals(true, PrinterModel.ADVENTURER_5M_PRO.isModern)
        assertEquals(true, PrinterModel.AD5X.isModern)
        assertEquals(false, PrinterModel.ADVENTURER_3.isModern)
        assertEquals(false, PrinterModel.GENERIC_LEGACY.isModern)
        assertEquals(false, PrinterModel.UNKNOWN.isModern)
    }
}
