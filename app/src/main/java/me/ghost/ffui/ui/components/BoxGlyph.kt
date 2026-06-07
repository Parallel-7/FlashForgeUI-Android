package me.ghost.ffui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A visual "box" glyph — a rounded square with up to 6 deduplicated color swatches
 * arranged in a 3×2 grid, drawn from the box's spools' filament colors in first-encountered
 * order. At 7+ distinct colors, shows 5 swatches + a "+N" cell.
 *
 * Reuses [parseHexColor] from [SpoolDisc] for hex color parsing. Each swatch gets a
 * hairline border for legibility against both light and dark backgrounds.
 *
 * @param colorHexes List of hex color strings from the box's member spools. Duplicates are
 *   removed in first-encountered order.
 * @param size Outer dimension of the box glyph square. Defaults to 54dp (matches [SpoolDisc]).
 * @param dimmed When true, reduces opacity (e.g. for visual consistency).
 */
@Composable
fun BoxGlyph(
    colorHexes: List<String?>,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
    dimmed: Boolean = false
) {
    val dedupedColors = remember(colorHexes) {
        colorHexes
            .mapNotNull { it?.trim()?.ifBlank { null } }
            .fold(mutableListOf<String>()) { acc, hex ->
                // Dedupe by lowercase comparison
                if (acc.none { it.equals(hex, ignoreCase = true) }) acc.add(hex)
                acc
            }
    }

    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier
            .size(size)
            .alpha(if (dimmed) 0.5f else 1f)
            .background(surfaceColor, RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (dedupedColors.isEmpty()) {
                // Empty box — show a faint center line to read as "empty box"
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = (size.value * 0.28f).dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = (size.value * 0.5f).dp, height = 1.dp)
                            .background(borderColor.copy(alpha = 0.4f))
                    )
                }
            } else {
                val maxSwatches = 6
                val showOverflow = dedupedColors.size > maxSwatches
                val displayColors = if (showOverflow) {
                    dedupedColors.take(maxSwatches - 1)
                } else {
                    dedupedColors
                }
                val remainingCount = dedupedColors.size - (maxSwatches - 1)

                val swatchSize = ((size.value - 12f - 6f) / 3f).dp // 3 cols, 6dp padding, 3dp gaps

                // Split into rows of up to 3
                val allCells = displayColors.toMutableList()
                if (showOverflow) {
                    // Add the overflow count as a special marker
                    allCells.add("+$remainingCount")
                }

                val rows = allCells.chunked(3)
                rows.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        row.forEach { cell ->
                            if (cell.startsWith("+")) {
                                // Overflow cell: "+N" text
                                Box(
                                    modifier = Modifier
                                        .size(swatchSize)
                                        .background(
                                            borderColor.copy(alpha = 0.3f),
                                            RoundedCornerShape(3.dp)
                                        )
                                        .border(
                                            0.5.dp,
                                            borderColor,
                                            RoundedCornerShape(3.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = cell,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                val color = parseHexColor(cell)
                                Swatch(
                                    color = color,
                                    size = swatchSize
                                )
                            }
                        }
                        // Pad empty columns so rows align
                        repeat(3 - row.size) {
                            Box(modifier = Modifier.size(swatchSize))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A small rounded-square color swatch with a hairline border for legibility.
 */
@Composable
private fun Swatch(
    color: Color?,
    size: Dp
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val fallbackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    Box(
        modifier = Modifier
            .size(size)
            .background(
                color ?: fallbackColor,
                RoundedCornerShape(3.dp)
            )
            .border(
                0.5.dp,
                borderColor,
                RoundedCornerShape(3.dp)
            )
    )
}
