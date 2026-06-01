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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.ghost.ffui.api.SpoolmanSpool
import me.ghost.ffui.ui.components.parseHexColor
import kotlin.math.roundToInt

/**
 * Read-only dialog showing the full spool read-out: vendor, material, color swatch + hex,
 * weights, diameter, density, temperatures, location, lot, comment, and price.
 *
 * @param spool The spool to display.
 * @param onDismiss Closes the dialog.
 * @param onEditClick Opens the edit screen for this spool.
 */
@Composable
fun SpoolInfoDialog(
    spool: SpoolmanSpool,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit
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
                InfoRow("Vendor", spool.filament.vendor?.name)
                InfoRow("Material", spool.filament.material)
                InfoRow("Color", spool.filament.color_hex?.let { "#${it.removePrefix("#")}" })
                InfoRow("Remaining", spool.remaining_weight?.let { "${it.roundToInt()} g" })
                InfoRow("Used", spool.used_weight?.let { "${it.roundToInt()} g" })
                InfoRow("Initial weight", spool.initial_weight?.let { "${it.roundToInt()} g" })
                InfoRow("Diameter", spool.filament.diameter?.let { "$it mm" })
                InfoRow("Density", spool.filament.density?.let { "$it g/cm³" })
                InfoRow("Extruder temp", spool.filament.settings_extruder_temp?.let { "$it °C" })
                InfoRow("Bed temp", spool.filament.settings_bed_temp?.let { "$it °C" })
                InfoRow("Location", spool.location)
                InfoRow("Lot", spool.lot_nr)
                InfoRow("Comment", spool.comment)
                InfoRow("Price", spool.price?.let { "$it" })

                if (spool.archived) {
                    Text(
                        "Archived",
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
                        Text("Close")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        onDismiss()
                        onEditClick()
                    }) {
                        Text("Edit")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value == null) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}
