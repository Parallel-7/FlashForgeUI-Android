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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import me.ghost.ffapi.models.FFPrinterDetail as PrinterDetailResponse
import me.ghost.ffui.R
import me.ghost.ffui.ui.jobStateOf
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
            Text(stringResource(R.string.dashboard_printing_file, fileName ?: stringResource(R.string.common_none)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(stringResource(R.string.dashboard_progress_percent, progress), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth().height(12.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
            strokeCap = StrokeCap.Round
        )
        Text(stringResource(R.string.dashboard_state_label, stateLabel), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Which single-toolhead heater a [HeaterGrid] card (or the shared [TemperatureDialog]) refers to.
 * A stable identity for dialog state — the display label is resolved via stringResource at render
 * time and is never compared against (a localized string is a fragile sentinel). Mirrors the
 * private sealed [Creator5HeaterTarget] pattern the Creator 5 card uses.
 */
internal enum class HeaterTarget { Nozzle, Bed }

/**
 * Row of nozzle / bed (tap-to-set) heater cards. Split out from the old combined grid so the
 * dashboard can place job stats (remaining / layer) above the temperatures, and the Controls tab
 * can reuse just the heaters. See [JobStatsRow].
 */
@Composable
internal fun HeaterGrid(status: PrinterDetailResponse?, onHeaterClick: (HeaterTarget) -> Unit) {
    val nozzleCurrent = status?.rightTemp ?: 0f
    val nozzleTarget = status?.rightTargetTemp ?: 0f
    val bedCurrent = status?.platTemp ?: 0f
    val bedTarget = status?.platTargetTemp ?: 0f

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HeaterCard(
            label = stringResource(R.string.dashboard_heater_nozzle),
            value = stringResource(R.string.dashboard_temp_current_target, nozzleCurrent, nozzleTarget),
            progress = if (nozzleTarget > 0) (nozzleCurrent / nozzleTarget).coerceIn(0f, 1f) else 0f,
            barColor = GeometricOrangePrimary,
            trackColor = GeometricOrangeContainer,
            onClick = { onHeaterClick(HeaterTarget.Nozzle) },
            modifier = Modifier.weight(1f)
        )
        HeaterCard(
            label = stringResource(R.string.dashboard_heater_bed),
            value = stringResource(R.string.dashboard_temp_current_target, bedCurrent, bedTarget),
            progress = if (bedTarget > 0) (bedCurrent / bedTarget).coerceIn(0f, 1f) else 0f,
            barColor = GeometricBluePrimary,
            trackColor = GeometricBlueContainer,
            onClick = { onHeaterClick(HeaterTarget.Bed) },
            modifier = Modifier.weight(1f)
        )
    }
}

/** Row of remaining-time / current-layer stat cards. */
@Composable
internal fun JobStatsRow(status: PrinterDetailResponse?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // `estimatedTime` is remaining seconds and reads 0 when no job is running — don't render
        // that as a bogus "0:00 hr" estimate; blank the card like LAYER does when idle.
        val jobActive = jobStateOf(status).isActiveJob
        val remMins = status?.estimatedTime?.takeIf { jobActive }?.let { (it / 60f).toInt() } ?: 0
        MetricCard(
            label = stringResource(R.string.dashboard_stat_remaining),
            value = if (jobActive) stringResource(R.string.dashboard_remaining_value, remMins / 60, remMins % 60) else "—",
            modifier = Modifier.weight(1f)
        )
        val cur = status?.printLayer?.toInt() ?: 0
        val tgt = status?.targetPrintLayer?.toInt() ?: 0
        MetricCard(
            label = stringResource(R.string.dashboard_stat_layer),
            value = if (tgt > 0) stringResource(R.string.dashboard_layer_value, cur, tgt) else "—",
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

/** Firmware ceiling for nozzle-style heaters, °C (docs: `rightNozzle` 0-265). */
internal const val NOZZLE_MAX_TEMP = 265

/** Firmware ceiling for the heated bed, °C (docs: `platform` 0-100). */
internal const val BED_MAX_TEMP = 100

/**
 * Sanitizes raw temperature-dialog input for display: keeps digits plus one decimal separator
 * (`.` or `,`, shown as `.`) with at most one fractional digit, drops leading zeros, and clamps to
 * [maxTemp] (null = no clamp). Keeping the separator matters: stripping it turned a typed `21.5`
 * into `215`. Empty / garbage input stays empty; a digit run like `"26x5"` reads as `265`.
 * Extracted so the clamp family is unit-testable headlessly.
 */
internal fun sanitizeTempInput(raw: String, maxTemp: Int?): String {
    val whole = StringBuilder()
    var fraction: String? = null
    for (c in raw) {
        when {
            c == '.' || c == ',' -> if (fraction == null) fraction = ""
            !c.isDigit() -> Unit
            fraction == null -> whole.append(c)
            fraction.isEmpty() -> fraction = c.toString()
        }
    }
    val wholeText = whole.trimStart('0').ifEmpty { if (whole.isNotEmpty()) "0" else "" }.toString()
    val text = if (fraction == null) wholeText else "$wholeText.$fraction"
    val value = text.toFloatOrNull() ?: return text
    return if (maxTemp != null && value > maxTemp) maxTemp.toString() else text
}

/**
 * Resolves the (already sanitized) display string to the whole-degree value the Set button sends
 * (the firmware only takes integers, so `21.5` rounds to `22`), clamped to [maxTemp]
 * (null = no clamp); null when there is nothing parseable to send.
 */
internal fun clampTempValue(displayed: String, maxTemp: Int?): Int? =
    displayed.toFloatOrNull()?.roundToInt()?.let { v -> if (maxTemp != null) v.coerceAtMost(maxTemp) else v }

/**
 * Numeric temperature-set dialog for a heater ("Nozzle"/"Bed") whose **Off** means `set(0)` — the
 * single-toolhead 5M / 5M Pro / AD5X path. Delegates to the generalized overload with
 * `onOff = onSet(0)`; pass [maxTemp] to clamp the entry client-side to the firmware ceiling
 * ([NOZZLE_MAX_TEMP] / [BED_MAX_TEMP]).
 */
@Composable
internal fun TemperatureDialog(
    heaterName: String,
    onSet: (Int) -> Unit,
    onDismiss: () -> Unit,
    maxTemp: Int? = null,
) = TemperatureDialog(
    heaterName = heaterName,
    onSet = onSet,
    onOff = { onSet(0) },
    onDismiss = onDismiss,
    maxTemp = maxTemp,
)

/**
 * Generalized [TemperatureDialog] for heaters whose **Off** action must route to a dedicated cancel
 * (rather than `onSet(0)`), and/or which need a client-side temperature clamp.
 *
 * The original 3-arg [TemperatureDialog] routes Off through `onSet(0)` — correct for the
 * single-toolhead 5M / 5M Pro / AD5X bed & nozzle (cancelled by setting 0), but **wrong** for a
 * Creator 5 tool, whose firmware only treats a literal 0 inside the `nozzles` array as off and
 * ignores -100. Passing a distinct [onOff] lambda keeps each call site safe: tool Off routes to the
 * backend tool-cancel, bed/chamber Off route to their own cancels. The original 3-arg overload is
 * left unchanged for the single-toolhead path.
 *
 * @param heaterName Label shown in the dialog title.
 * @param onSet Invoked with the entered (and clamped) temperature when the user taps Set.
 * @param onOff Invoked when the user taps Off — routes to the heater's dedicated cancel.
 * @param onDismiss Invoked when the user dismisses the dialog (Cancel / back / outside tap).
 * @param maxTemp Optional firmware ceiling; the entered value is clamped to this client-side
 *  (e.g. 80 for the heated chamber).
 */
@Composable
internal fun TemperatureDialog(
    heaterName: String,
    onSet: (Int) -> Unit,
    onOff: () -> Unit,
    onDismiss: () -> Unit,
    maxTemp: Int? = null,
) {
    var tempStr by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dashboard_set_temp_title, heaterName)) },
        text = {
            OutlinedTextField(
                value = tempStr,
                onValueChange = { raw ->
                    tempStr = sanitizeTempInput(raw, maxTemp)
                },
                label = { Text(stringResource(R.string.dashboard_temp_field_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOff) { Text(stringResource(R.string.common_off)) }
                Button(onClick = {
                    clampTempValue(tempStr, maxTemp)?.let { v -> onSet(v) }
                }) { Text(stringResource(R.string.common_set)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
