package me.ghost.ffui.ui.spools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ghost.ffui.api.SpoolmanSpool
import me.ghost.ffui.ui.components.SpoolDisc
import me.ghost.ffui.ui.components.parseHexColor
import kotlin.math.roundToInt

/**
 * A compact card representing one spool in the Spools grid. Follows the same rounded-card
 * pattern as the printer and IFS cards.
 *
 * @param spool The spool to display.
 * @param onInfoClick Callback for the (i) info button.
 * @param onEditClick Callback for the cog/edit button.
 * @param modifier Outer modifier.
 */
@Composable
fun SpoolCard(
    spool: SpoolmanSpool,
    onInfoClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filamentColor = spool.filament.color_hex?.let { parseHexColor(it) }
    val progress = spool.progress

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(272.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Top row: material badge (left) + location tag (right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val material = spool.filament.material
                if (material != null) {
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = material,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                val location = spool.location
                if (location != null) {
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = location,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 2. Spool disc
            SpoolDisc(
                colorHex = spool.filament.color_hex,
                size = 54.dp,
                dimmed = spool.archived
            )

            // 3. Progress bar (only when progress is calculable)
            if (progress != null) {
                val trackColor = MaterialTheme.colorScheme.surfaceVariant
                val tintColor = filamentColor?.let { color ->
                    // Luminance contrast guard: if the color is too dark against the dark surface,
                    // fall back to the primary accent.
                    val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
                    if (luminance < 0.15f) MaterialTheme.colorScheme.primary else color
                } ?: MaterialTheme.colorScheme.primary

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = tintColor,
                    trackColor = trackColor,
                )
            }

            // 4. Name
            Text(
                text = spool.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (spool.archived) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            // 5. Used/remaining percentages (prominent) + grams breakdown
            if (progress != null) {
                val remainingPct = (progress * 100).roundToInt()
                // Prefer the real used_weight/initial_weight ratio; fall back to 100 − remaining.
                val usedPct = run {
                    val used = spool.used_weight
                    val init = spool.initial_weight
                    if (used != null && init != null && init > 0f) {
                        (used / init * 100f).roundToInt().coerceIn(0, 100)
                    } else {
                        100 - remainingPct
                    }
                }
                Text(
                    text = "$remainingPct% left · $usedPct% used",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Grams: remaining and used, whichever are known.
            val rem = spool.remaining_weight?.let { "${it.roundToInt()} g left" }
            val used = spool.used_weight?.let { "${it.roundToInt()} g used" }
            val gramsText = listOfNotNull(rem, used).joinToString(" · ")
            if (gramsText.isNotEmpty()) {
                Text(
                    text = gramsText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Push the action row to the bottom so cards stay a uniform height
            // regardless of name length or which optional rows are present.
            Spacer(Modifier.weight(1f))

            // 6. Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onInfoClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Spool details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEditClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Edit spool",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
