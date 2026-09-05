package me.ghost.ffui.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import me.ghost.ffapi.api.controls.TempControl
import me.ghost.ffapi.models.FFPrinterDetail
import me.ghost.ffui.ui.theme.GeometricBlueContainer
import me.ghost.ffui.ui.theme.GeometricBluePrimary
import me.ghost.ffui.ui.theme.GeometricGreenPrimary
import me.ghost.ffui.ui.theme.GeometricOrangeContainer
import me.ghost.ffui.ui.theme.GeometricOrangePrimary
import me.ghost.ffui.ui.theme.GeometricYellowPrimary

/** Firmware ceiling for the heated chamber; the dialog clamps entered values to this. */
private const val CHAMBER_MAX_TEMP = 80

/** Tolerance (°C) below target within which a heater is considered "at target". */
private const val AT_TARGET_TOLERANCE = 2f

/**
 * Which heater the shared [TemperatureDialog] is currently editing. Held as a single nullable state
 * so only one dialog is ever open at a time. Tools carry their 0-based wire index.
 */
private sealed interface Creator5HeaterTarget {
    /** A tool head; [index] is the 0-based wire index into `nozzleTemps` (display label is T{index+1}). */
    data class Tool(val index: Int) : Creator5HeaterTarget
    data object Bed : Creator5HeaterTarget
    data object Chamber : Creator5HeaterTarget
}

/**
 * Unified, interactive temperature card for the Creator 5 / 5 Pro tool-changer: the four tool heads
 * (T1–T4), the heated bed, and the heated chamber — each with current/target readings, a heating
 * progress bar, and Set/Off controls. Mirrors the desktop Electron `creator5-temperature` card.
 *
 * Reads [FFPrinterDetail] directly (no derived `FFMachineInfo` flow): tool temps are read from
 * [FFPrinterDetail.nozzleTemps] / [FFPrinterDetail.nozzleTargetTemps] and padded/truncated to
 * [TempControl.NOZZLE_COUNT] (4); the bed from [FFPrinterDetail.platTemp] /
 * [FFPrinterDetail.platTargetTemp]; the chamber from [FFPrinterDetail.chamberTemp] /
 * [FFPrinterDetail.chamberTargetTemp].
 *
 * Visual states mirror [HeaterGrid]: a LinearProgressIndicator fills with current/target, colored by
 * heater type while heating and switching to [GeometricGreenPrimary] once at target (within
 * [AT_TARGET_TOLERANCE]). Tools use [GeometricOrangePrimary], the bed [GeometricBluePrimary], and the
 * chamber [GeometricYellowPrimary] (a warm, distinct token — bed is already blue).
 *
 * The Off affordance routes to the heater's dedicated **cancel** lambda ([onCreate5CancelTool] /
 * [onCreate5CancelBed] / [onCreate5CancelChamber]) — it never calls a `set(0)` path, which is wrong
 * for Creator 5 tools. Set opens the shared [TemperatureDialog], client-clamped to each heater's
 * firmware ceiling (tools [NOZZLE_MAX_TEMP], bed [BED_MAX_TEMP], chamber [CHAMBER_MAX_TEMP]).
 *
 * Rendered behind a Creator 5 capability gate in both [DashboardScreen] and the Controls tab
 * (gated on `PrinterCapabilities.model.isCreator5`).
 *
 * @param detail Latest `/detail` snapshot, or null while connecting (cells render 0/0°).
 * @param onCreate5SetTool Set tool [toolIndex] (0-based wire index) to [celsius].
 * @param onCreate5CancelTool Turn tool [toolIndex] off (dedicated cancel — not set(0)).
 * @param onCreate5SetBed Set the bed to [celsius].
 * @param onCreate5CancelBed Turn the bed off.
 * @param onCreate5SetChamber Set the chamber to [celsius] (firmware ceiling [CHAMBER_MAX_TEMP]).
 * @param onCreate5CancelChamber Turn the chamber off.
 * @param modifier Outer modifier (the card fills available width).
 */
@Composable
internal fun Creator5TemperatureCard(
    detail: FFPrinterDetail?,
    onCreate5SetTool: (toolIndex: Int, celsius: Int) -> Unit,
    onCreate5CancelTool: (toolIndex: Int) -> Unit,
    onCreate5SetBed: (celsius: Int) -> Unit,
    onCreate5CancelBed: () -> Unit,
    onCreate5SetChamber: (celsius: Int) -> Unit,
    onCreate5CancelChamber: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nozzleCount = TempControl.NOZZLE_COUNT
    val toolCurrents = (0 until nozzleCount).map { i -> detail?.nozzleTemps?.getOrNull(i) ?: 0f }
    val toolTargets = (0 until nozzleCount).map { i -> detail?.nozzleTargetTemps?.getOrNull(i) ?: 0f }
    val bedCurrent = detail?.platTemp ?: 0f
    val bedTarget = detail?.platTargetTemp ?: 0f
    val chamberCurrent = detail?.chamberTemp ?: 0f
    val chamberTarget = detail?.chamberTargetTemp ?: 0f

    var dialogTarget by remember { mutableStateOf<Creator5HeaterTarget?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Temperature",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            // Tool grid: NOZZLE_COUNT (4) cells laid out two-per-row. Assumes an even count.
            for (rowStart in 0 until nozzleCount step 2) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Creator5HeaterCell(
                        label = "T${rowStart + 1}",
                        current = toolCurrents[rowStart],
                        target = toolTargets[rowStart],
                        heatingColor = GeometricOrangePrimary,
                        trackColor = GeometricOrangeContainer,
                        onSet = { dialogTarget = Creator5HeaterTarget.Tool(rowStart) },
                        onOff = { onCreate5CancelTool(rowStart) },
                        modifier = Modifier.weight(1f)
                    )
                    Creator5HeaterCell(
                        label = "T${rowStart + 2}",
                        current = toolCurrents[rowStart + 1],
                        target = toolTargets[rowStart + 1],
                        heatingColor = GeometricOrangePrimary,
                        trackColor = GeometricOrangeContainer,
                        onSet = { dialogTarget = Creator5HeaterTarget.Tool(rowStart + 1) },
                        onOff = { onCreate5CancelTool(rowStart + 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // Base row: bed + chamber.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Creator5HeaterCell(
                    label = "BED",
                    current = bedCurrent,
                    target = bedTarget,
                    heatingColor = GeometricBluePrimary,
                    trackColor = GeometricBlueContainer,
                    onSet = { dialogTarget = Creator5HeaterTarget.Bed },
                    onOff = onCreate5CancelBed,
                    modifier = Modifier.weight(1f)
                )
                Creator5HeaterCell(
                    label = "CHAMBER",
                    current = chamberCurrent,
                    target = chamberTarget,
                    heatingColor = GeometricYellowPrimary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    onSet = { dialogTarget = Creator5HeaterTarget.Chamber },
                    onOff = onCreate5CancelChamber,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // Single shared dialog; rendered as a popup so its position in the tree is layout-neutral.
    dialogTarget?.let { target ->
        when (target) {
            is Creator5HeaterTarget.Tool -> TemperatureDialog(
                heaterName = "T${target.index + 1}",
                onSet = { celsius -> onCreate5SetTool(target.index, celsius); dialogTarget = null },
                onOff = { onCreate5CancelTool(target.index); dialogTarget = null },
                onDismiss = { dialogTarget = null },
                maxTemp = NOZZLE_MAX_TEMP   // same clamp family as the nozzle heaters
            )

            Creator5HeaterTarget.Bed -> TemperatureDialog(
                heaterName = "Bed",
                onSet = { celsius -> onCreate5SetBed(celsius); dialogTarget = null },
                onOff = { onCreate5CancelBed(); dialogTarget = null },
                onDismiss = { dialogTarget = null },
                maxTemp = BED_MAX_TEMP
            )

            Creator5HeaterTarget.Chamber -> TemperatureDialog(
                heaterName = "Chamber",
                onSet = { celsius -> onCreate5SetChamber(celsius); dialogTarget = null },
                onOff = { onCreate5CancelChamber(); dialogTarget = null },
                onDismiss = { dialogTarget = null },
                maxTemp = CHAMBER_MAX_TEMP
            )
        }
    }
}

/**
 * One heater cell inside [Creator5TemperatureCard]: label, current/target reading, a heating
 * progress bar, and Set/Off buttons. The bar fills with [heatingColor] while heating and switches to
 * [GeometricGreenPrimary] once within [AT_TARGET_TOLERANCE] of target (the card border also gains a
 * green tint). Matches the card/shape/border styling of the single-toolhead [HeaterCard].
 *
 * @param current Current temperature (°C).
 * @param target Target temperature (°C); `<= 0` means off (empty bar).
 * @param heatingColor Heater-type color used while heating.
 * @param trackColor Unfilled track color of the progress bar.
 * @param onSet Open the set dialog (caller opens the shared [TemperatureDialog]).
 * @param onOff Turn this heater off via its dedicated cancel.
 */
@Composable
private fun Creator5HeaterCell(
    label: String,
    current: Float,
    target: Float,
    heatingColor: Color,
    trackColor: Color,
    onSet: () -> Unit,
    onOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOn = target > 0f
    val atTarget = isOn && current >= target - AT_TARGET_TOLERANCE
    val progress = if (isOn) (current / target).coerceIn(0f, 1f) else 0f
    val barColor = if (atTarget) GeometricGreenPrimary else heatingColor
    val borderColor = if (atTarget) GeometricGreenPrimary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "%.0f/%.0f°".format(current, target),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = barColor,
                trackColor = trackColor,
                strokeCap = StrokeCap.Round
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSet, modifier = Modifier.weight(1f)) { Text("Set") }
                OutlinedButton(onClick = onOff, modifier = Modifier.weight(1f)) { Text("Off") }
            }
        }
    }
}
