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

    // ── Nearest-match (for snapping arbitrary Spoolman values onto the printer's fixed lists) ──

    /** Each palette color's CIELAB coordinates, computed once. Parallel to [COLORS]. */
    private val colorsLab: List<Triple<Double, Double, Double>> by lazy {
        COLORS.map { hexToLab(it.hex)!! } // palette hexes are all valid #RRGGBB
    }

    /**
     * The palette color perceptually closest to [hex] (`#RRGGBB`, with or without `#`), or `null` if
     * [hex] can't be parsed. Distance is **CIEDE2000** (ΔE2000) — the modern perceptual metric, which
     * (unlike plain Euclidean ΔE76) correctly handles the saturated blue/red regions, so e.g. pure
     * blue `#0000FF` snaps to Dark Blue rather than Violet and a burgundy snaps to Red rather than
     * Coral. Verified against the live Spoolman library. Pure math, no dependencies.
     */
    fun nearestColor(hex: String?): PaletteColor? {
        val lab = hex?.let { hexToLab(it) } ?: return null
        return COLORS.indices.minByOrNull { i -> ciede2000(lab, colorsLab[i]) }?.let { COLORS[it] }
    }

    /**
     * The recognized [MATERIALS] entry that best matches [raw], or `null` if none does (the caller
     * should then keep whatever material is already selected). Tries a case/whitespace-insensitive
     * exact match first, then a normalized-token match where the chosen material's normalized form is
     * the same as [raw]'s **leading token** (the part before the first space) — so `"PLA Matte" → PLA`,
     * `"PLA+" → PLA`, `"PETG-CF Pro" → PETG-CF`. A leading-token (rather than longest-prefix) rule
     * deliberately keeps oddball names like `"PCTG"` or `"PA6"` from snapping to a chemically-unrelated
     * type (`PC`, `PA`); those fall through to `null` so the caller keeps the current selection.
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

    /** Uppercase, alphanumerics only — so "PETG-CF" and "petg cf" both become "PETGCF". */
    private fun normalizeMaterial(s: String): String =
        s.uppercase().filter { it.isLetterOrDigit() }

    /**
     * Parse `#RRGGBB` (or `RRGGBBAA`, with or without `#`) and convert sRGB → CIELAB (D65). Null if
     * unparseable. Parses hex by hand (no `android.graphics.Color`) so the matching logic stays
     * pure-JVM unit-testable.
     */
    private fun hexToLab(hex: String): Triple<Double, Double, Double>? {
        val clean = hex.trim().removePrefix("#")
        val rgb = when (clean.length) {
            6 -> clean
            8 -> clean.substring(0, 6) // RRGGBBAA → drop alpha
            else -> return null
        }
        val value = rgb.toLongOrNull(16)?.toInt() ?: return null
        val r = ((value shr 16) and 0xFF) / 255.0
        val g = ((value shr 8) and 0xFF) / 255.0
        val b = (value and 0xFF) / 255.0
        // sRGB companding → linear RGB.
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val rl = lin(r); val gl = lin(g); val bl = lin(b)
        // Linear RGB → XYZ (D65), then normalize by the reference white.
        val x = (rl * 0.4124 + gl * 0.3576 + bl * 0.1805) / 0.95047
        val y = (rl * 0.2126 + gl * 0.7152 + bl * 0.0722) / 1.00000
        val z = (rl * 0.0193 + gl * 0.1192 + bl * 0.9505) / 1.08883
        // XYZ → Lab.
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else (7.787 * t) + (16.0 / 116.0)
        val fx = f(x); val fy = f(y); val fz = f(z)
        val l = (116.0 * fy) - 16.0
        val a = 500.0 * (fx - fy)
        val bb = 200.0 * (fy - fz)
        return Triple(l, a, bb)
    }

    /**
     * CIEDE2000 colour difference between two CIELAB colours (as `Triple(L, a, b)`). Returns the
     * **squared** ΔE2000 — we only ever compare these for an argmin, so skipping the final `sqrt` is
     * cheaper and order-preserving. Standard formula (Sharma et al. 2005).
     */
    private fun ciede2000(
        lab1: Triple<Double, Double, Double>,
        lab2: Triple<Double, Double, Double>
    ): Double {
        val (l1, a1, b1) = lab1
        val (l2, a2, b2) = lab2
        val pow25_7 = Math.pow(25.0, 7.0)

        val c1 = Math.hypot(a1, b1)
        val c2 = Math.hypot(a2, b2)
        val cBar = (c1 + c2) / 2.0
        val cBar7 = Math.pow(cBar, 7.0)
        val g = 0.5 * (1 - Math.sqrt(cBar7 / (cBar7 + pow25_7)))

        val a1p = (1 + g) * a1
        val a2p = (1 + g) * a2
        val c1p = Math.hypot(a1p, b1)
        val c2p = Math.hypot(a2p, b2)
        val h1p = atan2Deg(b1, a1p)
        val h2p = atan2Deg(b2, a2p)

        val dLp = l2 - l1
        val dCp = c2p - c1p
        val dhp = when {
            c1p * c2p == 0.0 -> 0.0
            Math.abs(h2p - h1p) <= 180 -> h2p - h1p
            h2p - h1p > 180 -> h2p - h1p - 360
            else -> h2p - h1p + 360
        }
        val dHp = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dhp) / 2)

        val lBarp = (l1 + l2) / 2
        val cBarp = (c1p + c2p) / 2
        val hBarp = when {
            c1p * c2p == 0.0 -> h1p + h2p
            Math.abs(h1p - h2p) <= 180 -> (h1p + h2p) / 2
            h1p + h2p < 360 -> (h1p + h2p + 360) / 2
            else -> (h1p + h2p - 360) / 2
        }
        val t = 1 - 0.17 * Math.cos(Math.toRadians(hBarp - 30)) +
            0.24 * Math.cos(Math.toRadians(2 * hBarp)) +
            0.32 * Math.cos(Math.toRadians(3 * hBarp + 6)) -
            0.20 * Math.cos(Math.toRadians(4 * hBarp - 63))
        val dTheta = 30 * Math.exp(-Math.pow((hBarp - 275) / 25, 2.0))
        val cBarp7 = Math.pow(cBarp, 7.0)
        val rc = 2 * Math.sqrt(cBarp7 / (cBarp7 + pow25_7))
        val sl = 1 + (0.015 * Math.pow(lBarp - 50, 2.0)) / Math.sqrt(20 + Math.pow(lBarp - 50, 2.0))
        val sc = 1 + 0.045 * cBarp
        val sh = 1 + 0.015 * cBarp * t
        val rt = -Math.sin(Math.toRadians(2 * dTheta)) * rc

        val termL = dLp / sl
        val termC = dCp / sc
        val termH = dHp / sh
        return termL * termL + termC * termC + termH * termH + rt * termC * termH
    }

    /** `atan2` in degrees, normalized to `[0, 360)`. */
    private fun atan2Deg(y: Double, x: Double): Double {
        val d = Math.toDegrees(Math.atan2(y, x))
        return if (d < 0) d + 360 else d
    }
}
