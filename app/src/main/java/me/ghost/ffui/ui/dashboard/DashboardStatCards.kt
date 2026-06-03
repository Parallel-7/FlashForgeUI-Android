package me.ghost.ffui.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.ghost.ffapi.models.FFPrinterDetail as PrinterDetailResponse
import me.ghost.ffui.ui.theme.GeometricBlueContainer
import me.ghost.ffui.ui.theme.GeometricBluePrimary
import me.ghost.ffui.ui.theme.GeometricOrangeContainer
import me.ghost.ffui.ui.theme.GeometricOrangePrimary

/** Full-width colored banner for connection states (connecting / offline / auth). */
@Composable
internal fun ConnectionBanner(text: String, container: Color, content: Color) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/** Job file name + percent + progress bar + raw state label. */
@Composable
internal fun JobProgressHeader(fileName: String?, progress: Int, stateLabel: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text("Printing: ${fileName ?: "None"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Text("$progress%", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth().height(12.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
            strokeCap = StrokeCap.Round
        )
        Text("State: $stateLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Row of nozzle / bed (tap-to-set) heater cards. Split out from the old combined grid so the
 * dashboard can place job stats (remaining / layer) above the temperatures, and the Controls tab
 * can reuse just the heaters. See [JobStatsRow].
 */
@Composable
internal fun HeaterGrid(status: PrinterDetailResponse?, onHeaterClick: (String) -> Unit) {
    val nozzleCurrent = status?.rightTemp?.toFloat() ?: 0f
    val nozzleTarget = status?.rightTargetTemp?.toFloat() ?: 0f
    val bedCurrent = status?.platTemp?.toFloat() ?: 0f
    val bedTarget = status?.platTargetTemp?.toFloat() ?: 0f

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HeaterCard(
            label = "NOZZLE",
            value = "%.0f/%.0f°".format(nozzleCurrent, nozzleTarget),
            progress = if (nozzleTarget > 0) (nozzleCurrent / nozzleTarget).coerceIn(0f, 1f) else 0f,
            barColor = GeometricOrangePrimary,
            trackColor = GeometricOrangeContainer,
            onClick = { onHeaterClick("Nozzle") },
            modifier = Modifier.weight(1f)
        )
        HeaterCard(
            label = "BED",
            value = "%.0f/%.0f°".format(bedCurrent, bedTarget),
            progress = if (bedTarget > 0) (bedCurrent / bedTarget).coerceIn(0f, 1f) else 0f,
            barColor = GeometricBluePrimary,
            trackColor = GeometricBlueContainer,
            onClick = { onHeaterClick("Bed") },
            modifier = Modifier.weight(1f)
        )
    }
}

/** Row of remaining-time / current-layer stat cards. */
@Composable
internal fun JobStatsRow(status: PrinterDetailResponse?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val remMins = status?.estimatedTime?.let { (it / 60f).toInt() } ?: 0
        MetricCard(
            label = "REMAINING",
            value = "${remMins / 60}:${(remMins % 60).toString().padStart(2, '0')} hr",
            modifier = Modifier.weight(1f)
        )
        val cur = status?.printLayer?.toInt() ?: 0
        val tgt = status?.targetPrintLayer?.toInt() ?: 0
        MetricCard(
            label = "LAYER",
            value = if (tgt > 0) "$cur/$tgt" else "—",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HeaterCard(
    label: String,
    value: String,
    progress: Float,
    barColor: Color,
    trackColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.aspectRatio(1.2f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(text = value, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable(onClick = onClick))
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = barColor,
                trackColor = trackColor,
                strokeCap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.aspectRatio(1.2f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(value, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

/** Numeric temperature-set dialog for a heater ("Nozzle"/"Bed"); includes an Off button. */
@Composable
internal fun TemperatureDialog(heaterName: String, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    var tempStr by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set $heaterName Temperature") },
        text = {
            OutlinedTextField(
                value = tempStr,
                onValueChange = { tempStr = it.filter(Char::isDigit) },
                label = { Text("Temperature °C") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSet(0) }) { Text("Off") }
                Button(onClick = { tempStr.toIntOrNull()?.let(onSet) }) { Text("Set") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
