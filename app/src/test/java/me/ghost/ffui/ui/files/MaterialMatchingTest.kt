package me.ghost.ffui.ui.files

import me.ghost.ffui.api.FFGcodeToolData
import me.ghost.ffui.api.MatlSlotInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the AD5X material-matching helpers ported from FlashForgeUI-Electron:
 * [materialsMatch], [normalizeHexColor], [colorsDiffer], and the [autoMatchMappings] auto-assigner.
 */
class MaterialMatchingTest {

    // ---- materialsMatch ----

    @Test
    fun `materials match ignoring case and surrounding whitespace`() {
        assertTrue(materialsMatch("PLA", "pla"))
        assertTrue(materialsMatch("  PETG ", "petg"))
    }

    @Test
    fun `blank or differing materials do not match`() {
        assertFalse(materialsMatch("", "PLA"))
        assertFalse(materialsMatch("PLA", ""))
        assertFalse(materialsMatch(null, null))
        assertFalse(materialsMatch("PLA", "ABS"))
    }

    // ---- normalizeHexColor ----

    @Test
    fun `valid hex is preserved and hash is added when missing`() {
        assertEquals("#AABBCC", normalizeHexColor("#AABBCC"))
        assertEquals("#AABBCC", normalizeHexColor("AABBCC"))
        assertEquals("#aabbcc", normalizeHexColor("  aabbcc  "))
    }

    @Test
    fun `invalid hex falls back to white`() {
        assertEquals("#FFFFFF", normalizeHexColor(null))
        assertEquals("#FFFFFF", normalizeHexColor("nothex"))
        assertEquals("#FFFFFF", normalizeHexColor("#FFF")) // 3-digit shorthand is not accepted
    }

    // ---- colorsDiffer ----

    @Test
    fun `colorsDiffer is true for distinct non-blank colors and false otherwise`() {
        assertTrue(colorsDiffer("#FF0000", "#00FF00"))
        assertFalse(colorsDiffer("#FF0000", "#ff0000")) // case-insensitive equal
        assertFalse(colorsDiffer("", "#00FF00"))        // blank tool color → not a mismatch
    }

    // ---- autoMatchMappings ----

    private fun tool(id: Int, material: String, color: String = "#FFFFFF") =
        FFGcodeToolData(toolId = id, materialName = material, materialColor = color)

    private fun slot(id: Int, material: String, loaded: Boolean = true, color: String = "#FFFFFF") =
        MatlSlotInfo(slotId = id, materialName = material, hasFilament = loaded, materialColor = color)

    @Test
    fun `auto-match assigns each tool to the first compatible loaded slot`() {
        val tools = listOf(tool(0, "PLA"), tool(1, "PETG"))
        val slots = listOf(slot(1, "PLA"), slot(2, "PETG"))

        val result = autoMatchMappings(tools, slots)!!
        assertEquals(2, result.size)
        assertEquals(1, result[0].slotId)
        assertEquals(0, result[0].toolId)
        assertEquals(2, result[1].slotId)
    }

    @Test
    fun `a slot is never reused across tools`() {
        // Two PLA tools, two PLA slots — each tool must take a different slot.
        val tools = listOf(tool(0, "PLA"), tool(1, "PLA"))
        val slots = listOf(slot(1, "PLA"), slot(3, "PLA"))

        val result = autoMatchMappings(tools, slots)!!
        assertEquals(setOf(1, 3), result.map { it.slotId }.toSet())
    }

    @Test
    fun `empty slots are skipped`() {
        val tools = listOf(tool(0, "PLA"))
        val slots = listOf(slot(1, "PLA", loaded = false), slot(2, "PLA", loaded = true))

        val result = autoMatchMappings(tools, slots)!!
        assertEquals(1, result.size)
        assertEquals(2, result[0].slotId)
    }

    @Test
    fun `returns null when any tool cannot be satisfied`() {
        val tools = listOf(tool(0, "PLA"), tool(1, "ABS"))
        val slots = listOf(slot(1, "PLA")) // nothing for ABS

        assertNull(autoMatchMappings(tools, slots))
    }

    @Test
    fun `mapping normalizes colors`() {
        val tools = listOf(tool(0, "PLA", color = "112233"))      // missing hash
        val slots = listOf(slot(1, "PLA", color = "bad-color"))   // invalid → white

        val result = autoMatchMappings(tools, slots)!!
        assertEquals("#112233", result[0].toolMaterialColor)
        assertEquals("#FFFFFF", result[0].slotMaterialColor)
    }
}
