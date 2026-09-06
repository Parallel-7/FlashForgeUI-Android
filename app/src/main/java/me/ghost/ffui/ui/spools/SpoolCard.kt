package me.ghost.ffui.ui.spools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ghost.ffui.R
import me.ghost.ffui.api.SpoolmanSpool
import me.ghost.ffui.data.SpoolStatStyle
import me.ghost.ffui.ui.components.luminanceIsDark
import me.ghost.ffui.ui.components.SpoolDisc
import me.ghost.ffui.ui.components.parseHexColor
import kotlin.math.roundToInt

/**
 * A compact card representing one spool in the Spools grid. Follows the same rounded-card
 * pattern as the printer and IFS cards.
 *
 * @param spool The spool to display.
 * @param onInfoClick Callback for the "Details" action.
 * @param onEditClick Callback for the "Edit" action.
 * @param modifier Outer modifier.
 * @param statStyle Which usage metric to show on the card's stat line.
 * @param nfcEnabled When true, show the "Write to tag" action and the "tagged" badge.
 * @param tagged Whether this spool has been written to a tag from this device.
 * @param highlighted Briefly true after a scan resolves to this spool — flashes the card border.
 * @param onWriteClick Callback for the "Write to tag" action.
 */
@Composable
fun SpoolCard(
    spool: SpoolmanSpool,
    onInfoClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
    statStyle: SpoolStatStyle = SpoolStatStyle.PERCENT,
    nfcEnabled: Boolean = false,
    tagged: Boolean = false,
    highlighted: Boolean = false,
    onWriteClick: () -> Unit = {}
) {
    val filamentColor = spool.filament.color_hex?.let { parseHexColor(it) }
    val progress = spool.progress

    // Flash the border to the primary accent when a scan resolves to this card.
    val borderColor by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        label = "spoolCardBorder"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(272.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(if (highlighted) 2.dp else 1.dp, borderColor),
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

                // "Tagged" indicator — kept up here so the action row below stays free for the
                // (i)/cog/write buttons.
                if (nfcEnabled && tagged) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Contactless,
                        contentDescription = stringResource(R.string.nfc_tagged_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
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
                    if (color.luminanceIsDark()) MaterialTheme.colorScheme.primary else color
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

            // 5. A single prominent stat line — percentage or weight, per the Spoolman setting.
            val statText = when (statStyle) {
                SpoolStatStyle.PERCENT -> progress?.let { p ->
                    val remainingPct = (p * 100).roundToInt()
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
                    stringResource(R.string.spools_stat_percent, remainingPct, usedPct)
                }
                SpoolStatStyle.WEIGHT -> {
                    val rem = spool.remaining_weight?.let { stringResource(R.string.spools_stat_grams_left, it.roundToInt()) }
                    val used = spool.used_weight?.let { stringResource(R.string.spools_stat_grams_used, it.roundToInt()) }
                    listOfNotNull(rem, used).joinToString(" · ").ifEmpty { null }
                }
            }
            if (statText != null) {
                Text(
                    text = statText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (spool.archived) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            // Push the action row to the bottom so cards stay a uniform height
            // regardless of name length or which optional rows are present.
            Spacer(Modifier.weight(1f))

            // 6. A single full-width action button. Tapping it opens a menu of the card's actions
            // (details / edit / write tag) — far more tappable than cramming icons onto a narrow card.
            var menuOpen by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Default.MoreHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.spools_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.spools_details)) },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onInfoClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.spools_edit)) },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEditClick()
                        }
                    )
                    if (nfcEnabled) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nfc_write_to_tag)) },
                            leadingIcon = { Icon(Icons.Default.Contactless, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onWriteClick()
                            }
                        )
                    }
                }
            }
        }
    }
}
