package me.ghost.ffui.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [IfsPalette]'s Spoolman-facing helpers — the snap-to-palette plumbing the
 * scan-to-set-slot flow relies on. The CIEDE2000 math and the palettes themselves live in the
 * library (`Ad5xPalette`/`PaletteSnap`, covered 1:1 by its own tests); these tests pin what
 * remains app-side: the null/unparseable contract layered over the library snap, RRGGBBAA
 * alpha-dropping, the model dispatch, and the Spoolman material-name matching.
 *
 * Several color cases are real fixtures pulled from a live Spoolman library (see
 * `liveSpoolmanColors`), so this doubles as a regression guard that the delegated snap still
 * resolves real-world data the same way.
 */
class IfsPaletteMatchingTest {

    // ── nearestColor: full 24-swatch coverage ──────────────────────────────────

    @Test
    fun `every palette color is its own nearest match`() {
        // Hits all 24 swatches: each recognized color must resolve to itself.
        IfsPalette.COLORS.forEach { pc ->
            assertEquals("exact ${pc.name}", pc.name, IfsPalette.nearestColor(pc.hex)?.name)
        }
    }

    @Test
    fun `a small neighborhood around each swatch snaps back to it`() {
        // For all 24 swatches, nudge every channel a little (min inter-swatch ΔE2000 is ~10.4, so
        // ±6 stays safely inside each basin) and confirm it still resolves to that swatch.
        IfsPalette.COLORS.forEach { pc ->
            listOf(6, -6).forEach { d ->
                val shifted = shift(pc.hex, d)
                assertEquals("$shifted near ${pc.name}", pc.name, IfsPalette.nearestColor(shifted)?.name)
            }
        }
    }

    @Test
    fun `synthetic primaries snap as expected`() {
        // Verified against the CIEDE2000 matcher. Note pure cyan #00FFFF lands on Light Blue, not the
        // printer's green-leaning "Cyan" swatch (#0DE2A0) — that's correct perceptually.
        val cases = mapOf(
            "#FF0000" to "Red",
            "#00FF00" to "Green",
            "#0000FF" to "Dark Blue",   // saturated blue → Dark Blue, NOT Violet (the ΔE76 failure)
            "#FFFF00" to "Yellow",
            "#00FFFF" to "Light Blue",
            "#FF00FF" to "Magenta",
            "#808080" to "Gray",
            "#FF8000" to "Orange",
            "#FFFFFF" to "White",
            "#000000" to "Black"
        )
        cases.forEach { (hex, expected) ->
            assertEquals(hex, expected, IfsPalette.nearestColor(hex)?.name)
        }
    }

    @Test
    fun `hash is optional, alpha is ignored, and shorthand expands`() {
        assertEquals("Red", IfsPalette.nearestColor("FF0000")?.name)
        assertEquals("Red", IfsPalette.nearestColor("#FF0000FF")?.name) // RRGGBBAA
        // 3-digit shorthand now expands through the library snap (FFF → White).
        assertEquals("White", IfsPalette.nearestColor("#FFF")?.name)
    }

    @Test
    fun `unparseable colors return null`() {
        assertNull(IfsPalette.nearestColor(null))
        assertNull(IfsPalette.nearestColor(""))
        assertNull(IfsPalette.nearestColor("nothex"))
    }

    @Test
    fun `model dispatch snaps through the right palette`() {
        // Blue is the palette differentiator: #45A8F9 on the AD5X vs #4CAAF8 on the Creator 5.
        // Snapping the C5 blue on the AD5X palette yields the AD5X hex — never cross-wired.
        assertEquals("#45A8F9", IfsPalette.nearestColorFor(isCreator5 = false, hex = "#4CAAF8")?.hex)
        assertEquals("#4CAAF8", IfsPalette.nearestColorFor(isCreator5 = true, hex = "#4CAAF8")?.hex)
        assertEquals("#45A8F9", IfsPalette.nearestColorFor(isCreator5 = false, hex = "#45A8F9")?.hex)
        // Absent color stays null on both models (no silent White).
        assertNull(IfsPalette.nearestColorFor(isCreator5 = true, hex = null))
        assertNull(IfsPalette.nearestColorFor(isCreator5 = false, hex = null))
    }

    @Test
    fun `live Spoolman library colors snap to sensible swatches`() {
        // Real (material, color_hex, expected swatch) triples from the seeded Spoolman library,
        // verified perceptually against the CIEDE2000 output. Locks behavior on real-world data.
        val cases = listOf(
            Triple("PLA - Black",        "0B0203", "Black"),
            Triple("Black",              "000000", "Black"),
            Triple("Basic Beige",        "D4B996", "Tan"),
            Triple("Almond",             "CFBCAE", "Tan"),
            Triple("Graphite black",     "101010", "Black"),
            Triple("Blue (PETG)",        "2578D8", "Dark Blue"),
            Triple("Alpine Green",       "556B2F", "Dark Green"),
            Triple("Black (ABS)",        "25282A", "Black"),
            Triple("Original BLACK",     "424344", "Black"),
            Triple("Blue (ASA)",         "2140B4", "Dark Blue"),
            Triple("PC Clear",           "F4FAFC", "White"),
            Triple("Arctic White",       "ffffff", "White"),
            Triple("Black (HIPS)",       "2f3234", "Black"),
            Triple("Bright Green",       "C3D65F", "Light Green"),
            Triple("Cherry Wood",        "6c4f4c", "Brown"),
            Triple("Rigid X - Black",    "1A1A18", "Black"),
            Triple("Obsidian Blue",      "0000FF", "Dark Blue"),
            Triple("Black (GreenTec)",   "1E1E1E", "Black"),
            Triple("Burgundy Red",       "951e23", "Red")
        )
        cases.forEach { (name, hex, expected) ->
            assertEquals("$name (#$hex)", expected, IfsPalette.nearestColor(hex)?.name)
        }
    }

    // ── nearestMaterial ─────────────────────────────────────────────────────────

    @Test
    fun `exact match ignores case, whitespace, and punctuation`() {
        assertEquals("PLA", IfsPalette.nearestMaterial("pla"))
        assertEquals("PETG", IfsPalette.nearestMaterial("  PETG "))
        assertEquals("PETG-CF", IfsPalette.nearestMaterial("petg-cf"))
        assertEquals("PLA", IfsPalette.nearestMaterial("PLA+"))   // '+' stripped → PLA
    }

    @Test
    fun `leading-token match handles suffixed variant names`() {
        assertEquals("PLA", IfsPalette.nearestMaterial("PLA Matte"))
        assertEquals("PLA", IfsPalette.nearestMaterial("PLA Silk"))
        assertEquals("PETG-CF", IfsPalette.nearestMaterial("PETG-CF Pro"))
        assertEquals("PA-CF", IfsPalette.nearestMaterial("PA-CF Nylon"))
    }

    @Test
    fun `chemically-unrelated lookalikes do not snap and return null`() {
        // "PCTG" must NOT become "PC", "PA6" must NOT become "PA": caller keeps the current material.
        assertNull(IfsPalette.nearestMaterial("PCTG"))
        assertNull(IfsPalette.nearestMaterial("PA6"))
        assertNull(IfsPalette.nearestMaterial("Nylon"))
        assertNull(IfsPalette.nearestMaterial("GreenTec"))
        assertNull(IfsPalette.nearestMaterial(null))
        assertNull(IfsPalette.nearestMaterial(""))
    }

    @Test
    fun `live Spoolman library materials map as expected`() {
        // The full material set from the seeded library, with the documented fallback behavior.
        val cases = mapOf(
            "PLA" to "PLA", "PLA+" to "PLA", "PETG" to "PETG", "ABS" to "ABS",
            "ASA" to null, "TPU" to "TPU", "PC" to "PC", "PCTG" to null,
            "HIPS" to null, "PVB" to null, "WOOD" to null, "PETG-CF" to "PETG-CF",
            "PA6" to null, "GREENTEC" to null, "PLA-CF" to "PLA-CF"
        )
        cases.forEach { (raw, expected) ->
            assertEquals(raw, expected, IfsPalette.nearestMaterial(raw))
        }
    }

    /** Nudge every channel of a `#RRGGBB` hex by [d] (R/G up, B down), clamped, for the basin test. */
    private fun shift(hex: String, d: Int): String {
        val v = hex.removePrefix("#").toInt(16)
        val r = (((v shr 16) and 0xFF) + d).coerceIn(0, 255)
        val g = (((v shr 8) and 0xFF) + d).coerceIn(0, 255)
        val b = ((v and 0xFF) - d).coerceIn(0, 255)
        return "%02X%02X%02X".format(r, g, b)
    }
}
