package me.ghost.ffui.ui.components

import androidx.compose.ui.graphics.Color
import me.ghost.ffapi.api.controls.PaletteSnap
import me.ghost.ffapi.api.controls.ad5x.Ad5xPalette
import me.ghost.ffapi.api.controls.creator5.Creator5Palette

/**
 * Model-aware view over the firmware palettes the library ships, shared between the dashboard
 * slot editor and the "Files → multi-color start" matching flow.
 *
 * The colors, materials, and the CIEDE2000 snap all live in `ff-5mp-api-kt`
 * ([Ad5xPalette] / [Creator5Palette] over the shared [PaletteSnap] machinery) — the single source
 * of truth, so the app can never drift from what the backend actually sends. This object only
 * adds the UI concerns: Compose [Color] values for swatches, model dispatch, and the Spoolman
 * material-name matching that has no wire counterpart.
 *
 * Freeform values are still allowed as an escape hatch (the firmware accepts arbitrary material
 * strings and hex colors), but off-list values won't render an icon on the printer's screen.
 */
object IfsPalette {

    /** A named, UI-recognized color. [hex] is `#RRGGBB`; [color] is its Compose form for swatches. */
    data class PaletteColor(val name: String, val hex: String) {
        /** Parsed once at construction; hand-parsed so this stays pure-JVM unit-testable. */
        val color: Color = hexToCompose(hex)
    }

    /** Material types the AD5X printer UI renders. The editor adds a free-text sink for the rest. */
    val MATERIALS: List<String> = Ad5xPalette.AD5X_MATERIALS

    /** The 24 colors the AD5X printer UI shows a proper icon for (library palette, exact hexes). */
    val COLORS: List<PaletteColor> = Ad5xPalette.AD5X_PALETTE.map { PaletteColor(it.name, it.hex) }

    /** Whether [hex] (case-insensitive, with or without `#`) is one of the UI-recognized palette colors. */
    fun isRecognizedColor(hex: String): Boolean {
        val norm = "#" + hex.removePrefix("#").uppercase()
        return COLORS.any { it.hex.equals(norm, ignoreCase = true) }
    }

    // ── Nearest-match (for snapping arbitrary Spoolman values onto the printer's fixed lists) ──

    /**
     * The AD5X palette color perceptually closest to [hex] (`#RRGGBB`, with or without `#`), or
     * `null` if [hex] is null/unparseable. Delegates to the library's CIEDE2000 snap — the same
     * code path the backend uses when writing a slot, so the swatch shown here is the value the
     * printer will actually store. Pure math, no Android dependencies.
     */
    fun nearestColor(hex: String?): PaletteColor? {
        val norm = normalizeHex(hex) ?: return null
        return Ad5xPalette.snapToAd5xPalette(norm).let { PaletteColor(it.name, it.hex) }
    }

    /**
     * The recognized [MATERIALS] entry that best matches [raw], or `null` if none does (the caller
     * should then keep whatever material is already selected). Tries a case/whitespace-insensitive
     * exact match first, then a normalized-token match where the chosen material's normalized form is
     * the same as [raw]'s **leading token** (the part before the first space) — so `"PLA Matte" → PLA`,
     * `"PLA+" → PLA`, `"PETG-CF Pro" → PETG-CF`. A leading-token (rather than longest-prefix) rule
     * deliberately keeps oddball names like `"PCTG"` or `"PA6"` from snapping to a chemically-unrelated
     * type (`PC`, `PA`); those fall through to `null` so the caller keeps the current selection.
     *
     * App-side on purpose: the wire material strings are freeform and the library ships only the
     * fixed firmware lists — nothing protocol-related here.
     */
    fun nearestMaterial(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // Exact match on the whole normalized string ("PLA-CF", "PLA+" → PLA-CF / PLA).
        val whole = normalizeMaterial(trimmed)
        MATERIALS.firstOrNull { normalizeMaterial(it) == whole }?.let { return it }
        // Else the leading token before the first space ("PLA Matte" → PLA, "PETG-CF Pro" → PETG-CF).
        val firstToken = normalizeMaterial(trimmed.substringBefore(' '))
        if (firstToken.isEmpty()) return null
        return MATERIALS.firstOrNull { normalizeMaterial(it) == firstToken }
    }

    // ── Creator 5 (model-aware overlay) ────────────────────────────
    //
    // The Creator 5 firmware only renders a color icon when the slot's rgb field is an EXACT,
    // case-sensitive #RRGGBB match against its own 24-color palette (Blue is #4CAAF8 here vs
    // #45A8F9 on the AD5X), so the slot editor must render the right swatches per model. Colors
    // come straight from the library's Creator5Palette; the material list below is app-side
    // (the library ships AD5X materials only, and C5 materials are freeform on the wire).

    /**
     * The 21 materials the Creator 5 UI renders (firmware order, sourced from the FFUI Electron
     * reference `creator5-palette.ts`). Kept app-side: the library ships the AD5X material list
     * only, and the C5 wire format treats materials as freeform strings.
     */
    val CREATOR5_MATERIALS: List<String> = listOf(
        "PLA", "PETG", "PLA-CF", "PETG-CF", "ABS", "ASA", "SILK", "PET-CF",
        "PAHT-CF", "S-PAHT", "S-Multi", "PA-CF", "HIPS", "PVA", "TPU-90A",
        "TPU-95A", "TPU-64D", "PC", "PA", "PC-ABS", "PPS-CF"
    )

    /** The Creator 5 24-color palette (library hexes, uppercase `#RRGGBB`). */
    val CREATOR5_COLORS: List<PaletteColor> =
        Creator5Palette.CREATOR5_PALETTE.map { PaletteColor(it.name, it.hex) }

    /** The material list the slot editor should offer for [isCreator5] vs the AD5X default. */
    fun materialsFor(isCreator5: Boolean): List<String> =
        if (isCreator5) CREATOR5_MATERIALS else MATERIALS

    /** The color palette the slot editor should render for [isCreator5] vs the AD5X default. */
    fun colorsFor(isCreator5: Boolean): List<PaletteColor> =
        if (isCreator5) CREATOR5_COLORS else COLORS

    /**
     * The palette color the printer will actually store for [hex] (`#RRGGBB`, with or without `#`),
     * snapped through the right palette for the model — both snaps are the library's, so this is
     * byte-for-byte what the backend will send. A null/unparseable [hex] yields null so callers
     * can report "no color set" (the library's own fallback-to-White only applies to non-null
     * garbage, and an absent color is a different thing).
     */
    fun nearestColorFor(isCreator5: Boolean, hex: String?): PaletteColor? {
        val norm = normalizeHex(hex) ?: return null
        return if (isCreator5) {
            Creator5Palette.snapToCreator5Palette(norm).let { PaletteColor(it.name, it.hex) }
        } else {
            Ad5xPalette.snapToAd5xPalette(norm).let { PaletteColor(it.name, it.hex) }
        }
    }

    /**
     * Normalizes a caller-supplied hex to a form the library snap accepts: strips `#`, drops an
     * alpha channel (Spoolman colors can be `RRGGBBAA`; the printer palettes are opaque), and
     * re-prefixes `#`. Returns null when the result still isn't a parseable color — that keeps the
     * app's "absent/unparseable → null" contract on top of the library's snap (which would
     * otherwise fall back to White).
     */
    private fun normalizeHex(hex: String?): String? {
        if (hex == null) return null
        val clean = hex.trim().removePrefix("#")
        val rgb = when (clean.length) {
            8 -> clean.substring(0, 6) // RRGGBBAA → drop alpha
            else -> clean
        }
        return PaletteSnap.hexToRgb(rgb)?.let { "#$rgb" }
    }

    /** Uppercase, alphanumerics only — so "PETG-CF" and "petg cf" both become "PETGCF". */
    private fun normalizeMaterial(s: String): String =
        s.uppercase().filter { it.isLetterOrDigit() }

    /** `#RRGGBB` → opaque ARGB-packed [Color] (pure Kotlin, no android.graphics). */
    private fun hexToCompose(hex: String): Color {
        val v = hex.removePrefix("#").toLongOrNull(16) ?: 0L
        return Color(0xFF000000L or v)
    }
}
