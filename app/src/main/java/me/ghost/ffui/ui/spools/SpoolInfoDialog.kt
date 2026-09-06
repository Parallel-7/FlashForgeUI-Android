package me.ghost.ffui.ui.spools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.ghost.ffui.R
import me.ghost.ffui.api.SpoolmanSpool
import me.ghost.ffui.ui.components.parseHexColor
import me.ghost.ffui.ui.components.InfoRow
import kotlin.math.roundToInt

/**
 * Read-only dialog showing the full spool read-out: vendor, material, color swatch + hex,
 * weights, diameter, density, temperatures, location, lot, comment, and price.
 *
 * @param spool The spool to display.
 * @param onDismiss Closes the dialog.
 * @param onEditClick Opens the edit screen for this spool.
 * @param taggedAt ISO-8601 timestamp of when this spool was last written to an NFC tag from this
 *   device, or null if it hasn't been tagged. Shown as a "Tagged" row when present.
 */
@Composable
fun SpoolInfoDialog(
    spool: SpoolmanSpool,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit,
    taggedAt: String? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title row: spool name + color swatch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val color = spool.filament.color_hex?.let { parseHexColor(it) }
                    if (color != null) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                    }
                    Text(
                        text = spool.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Info rows
                InfoRow(stringResource(R.string.spools_info_vendor), spool.filament.vendor?.name)
                InfoRow(stringResource(R.string.spools_info_material), spool.filament.material)
                InfoRow(stringResource(R.string.spools_info_color), spool.filament.color_hex?.let { "#${it.removePrefix("#")}" })
                InfoRow(stringResource(R.string.spools_info_remaining), spool.remaining_weight?.let { stringResource(R.string.spools_info_grams, it.roundToInt()) })
                InfoRow(stringResource(R.string.spools_info_used), spool.used_weight?.let { stringResource(R.string.spools_info_grams, it.roundToInt()) })
                InfoRow(stringResource(R.string.spools_info_initial_weight), spool.initial_weight?.let { stringResource(R.string.spools_info_grams, it.roundToInt()) })
                InfoRow(stringResource(R.string.spools_info_diameter), spool.filament.diameter?.let { stringResource(R.string.spools_info_mm, it) })
                InfoRow(stringResource(R.string.spools_info_density), spool.filament.density?.let { stringResource(R.string.spools_info_density_value, it) })
                InfoRow(stringResource(R.string.spools_info_extruder_temp), spool.filament.settings_extruder_temp?.let { stringResource(R.string.spools_info_temp_value, it) })
                InfoRow(stringResource(R.string.spools_info_bed_temp), spool.filament.settings_bed_temp?.let { stringResource(R.string.spools_info_temp_value, it) })
                InfoRow(stringResource(R.string.spools_info_location), spool.location)
                InfoRow(stringResource(R.string.spools_info_lot), spool.lot_nr)
                InfoRow(stringResource(R.string.spools_info_comment), spool.comment)
                InfoRow(stringResource(R.string.spools_info_price), spool.price?.let { "$it" })
                InfoRow(stringResource(R.string.spools_info_tagged), taggedAt?.let { formatTaggedAt(it) })

                if (spool.archived) {
                    Text(
                        stringResource(R.string.spools_info_archived),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_close))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        onDismiss()
                        onEditClick()
                    }) {
                        Text(stringResource(R.string.spools_edit))
                    }
                }
            }
        }
    }
}

/** Render an ISO-8601 instant as a short local date/time, falling back to the raw string. */
@Composable
private fun formatTaggedAt(iso: String): String {
    val pattern = stringResource(R.string.spools_info_tagged_format)
    return try {
        java.time.Instant.parse(iso)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern(pattern))
    } catch (_: Exception) {
        iso
    }
}

