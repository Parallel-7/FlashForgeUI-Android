package me.ghost.ffui.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.ghost.ffapi.backend.FiltrationMode
import me.ghost.ffui.ui.theme.GeometricGreenPrimary
import me.ghost.ffui.ui.theme.GeometricOrangePrimary

/** Air-filtration control (5M Pro): external/internal/off segmented row + a color-coded TVOC readout. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FiltrationCard(
    internalFanOn: Boolean,
    externalFanOn: Boolean,
    tvoc: Float?,
    controlsEnabled: Boolean,
    onSelect: (FiltrationMode) -> Unit
) {
    val selected = when {
        externalFanOn -> FiltrationMode.EXTERNAL
        internalFanOn -> FiltrationMode.INTERNAL
        else -> FiltrationMode.OFF
    }
    val options = listOf(
        FiltrationMode.EXTERNAL to "External",
        FiltrationMode.INTERNAL to "Internal",
        FiltrationMode.OFF to "Off"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AIR FILTRATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                        enabled = controlsEnabled,
                        shape = SegmentedButtonDefaults.itemShape(index, options.size)
                    ) {
                        Text(label)
                    }
                }
            }

            // TVOC readout, color-coded: ≤100 green, ≤300 orange, >300 red.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tvocColor = when {
                    tvoc == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    tvoc <= 100f -> GeometricGreenPrimary
                    tvoc <= 300f -> GeometricOrangePrimary
                    else -> MaterialTheme.colorScheme.error
                }
                Box(modifier = Modifier.size(8.dp).background(tvocColor, CircleShape))
                Text(
                    text = if (tvoc != null) "Air quality (TVOC): ${tvoc.toInt()}" else "Air quality (TVOC): —",
                    style = MaterialTheme.typography.labelMedium,
                    color = tvocColor
                )
            }

            if (!controlsEnabled) {
                Text(
                    "Filtration is locked while the printer is heating or printing.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}
