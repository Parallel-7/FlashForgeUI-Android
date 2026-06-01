package me.ghost.ffui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Parses a `#RRGGBB` (or bare `RRGGBB`) color string; `null` when unparseable. */
fun parseHexColor(hex: String): Color? {
    val s = if (hex.startsWith("#")) hex else "#$hex"
    return try { Color(android.graphics.Color.parseColor(s)) } catch (e: Exception) { null }
}

/**
 * A colored disc representing a filament spool — a filled circle with a border ring and a center
 * hub hole. Extracted from [IfsStationCard][me.ghost.ffui.ui.dashboard.IfsStationCard] so the
 * Spools screen can reuse the same renderer.
 *
 * @param colorHex Hex color string (e.g. `"FF0000"` or `"#FF0000"`). `null` renders a placeholder.
 * @param modifier Outer modifier.
 * @param size Diameter of the spool disc.
 * @param ringColor Override for the border ring. Defaults to `outlineVariant`.
 * @param dimmed When `true`, reduces opacity (e.g. for archived spools).
 */
@Composable
fun SpoolDisc(
    colorHex: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    ringColor: Color? = null,
    dimmed: Boolean = false
) {
    val parsedColor = colorHex?.let { parseHexColor(it) }
    val bodyColor = parsedColor ?: MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .size(size)
            .alpha(if (dimmed) 0.5f else 1f),
        contentAlignment = Alignment.Center
    ) {
        // Spool body
        Box(
            modifier = Modifier
                .size(size)
                .background(bodyColor, CircleShape)
                .border(
                    width = 3.dp,
                    color = ringColor ?: MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Center hub
            Box(
                modifier = Modifier
                    .size((size.value * 0.27f).dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        CircleShape
                    )
                    .border(1.dp, Color.Black.copy(alpha = 0.15f), CircleShape)
            )
        }
    }
}
