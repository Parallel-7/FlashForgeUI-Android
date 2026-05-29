package me.ghost.ffui.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Canonical AD5X IFS materials and colors, shared between the dashboard slot editor and the future
 * "Files → multi-color start" matching flow. Single source of truth so the two surfaces present the
 * same lists.
 *
 * Both lists mirror what the printer's own UI recognizes (see the wiki's
 * `AD5X-IFS-Material-Station.md`). The firmware accepts arbitrary material strings and hex colors,
 * so the editor offers a free-text/hex escape hatch in addition to these — but off-list values
 * won't render an icon on the printer's screen.
 */
object IfsPalette {

    /** Material types the printer UI renders correctly. The editor adds a "Custom…" sink for the rest. */
    val MATERIALS: List<String> = listOf(
        "PLA", "PLA-CF", "PETG", "PETG-CF", "ABS", "TPU", "SILK",
        "PA", "PA-CF", "PAHT-CF", "PC", "PC-ABS", "PET-CF", "PPS-CF"
    )

    /** A named, UI-recognized color. [hex] is `#RRGGBB`; [color] is its Compose form for swatches. */
    data class PaletteColor(val name: String, val hex: String) {
        val color: Color get() = Color(android.graphics.Color.parseColor(hex))
    }

    /** The 24 colors the printer UI shows a proper icon for. Free hex entry is allowed as an escape hatch. */
    val COLORS: List<PaletteColor> = listOf(
        PaletteColor("White", "#FFFFFF"),
        PaletteColor("Yellow", "#FEF043"),
        PaletteColor("Light Green", "#DCF478"),
        PaletteColor("Green", "#0ACC38"),
        PaletteColor("Dark Green", "#067749"),
        PaletteColor("Teal", "#0C6283"),
        PaletteColor("Cyan", "#0DE2A0"),
        PaletteColor("Light Blue", "#75D9F3"),
        PaletteColor("Blue", "#45A8F9"),
        PaletteColor("Dark Blue", "#2750E0"),
        PaletteColor("Purple", "#46328E"),
        PaletteColor("Violet", "#A03CF7"),
        PaletteColor("Magenta", "#F330F9"),
        PaletteColor("Pink", "#D4B0DC"),
        PaletteColor("Coral", "#F95D73"),
        PaletteColor("Red", "#F72224"),
        PaletteColor("Brown", "#7C4B00"),
        PaletteColor("Orange", "#F98D33"),
        PaletteColor("Cream", "#FDEBD5"),
        PaletteColor("Tan", "#D3C4A3"),
        PaletteColor("Dark Brown", "#AF7836"),
        PaletteColor("Gray", "#898989"),
        PaletteColor("Light Gray", "#BCBCBC"),
        PaletteColor("Black", "#161616")
    )

    /** Whether [hex] (case-insensitive, with or without `#`) is one of the UI-recognized palette colors. */
    fun isRecognizedColor(hex: String): Boolean {
        val norm = "#" + hex.removePrefix("#").uppercase()
        return COLORS.any { it.hex.equals(norm, ignoreCase = true) }
    }
}
