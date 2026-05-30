package me.ghost.ffui.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.ghost.ffui.data.ActivePrinterSession
import me.ghost.ffui.ui.MainViewModel
import me.ghost.ffui.ui.dashboard.FiltrationCard
import me.ghost.ffui.ui.dashboard.HeaterGrid
import me.ghost.ffui.ui.dashboard.TemperatureDialog

/**
 * The Controls tab: a single always-available surface for driving the active printer — job
 * control, temperature, motion (homing), lighting, and air filtration. Binds to the same active
 * session the dashboard pager tracks ([MainViewModel.activeSerial]); only one printer is driven at
 * a time, consistent with the StateFlow seam.
 *
 * Motion (`home`) and temperature set both ride the TCP G-code path (`~G28` / `~M104` / `~M140`).
 * Per CLAUDE.md that path is control-only and only hardware-verified on the AD5X HTTP side — homing
 * in particular is the first real exercise of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsScreen(viewModel: MainViewModel) {
    val sessionsMap by viewModel.sessions.collectAsState()
    val activeSerial by viewModel.activeSerial.collectAsState()
    val session = activeSerial?.let { sessionsMap[it] }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(session?.printer?.name?.let { "Controls — $it" } ?: "Controls")
            })
        }
    ) { padding ->
        if (session == null) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No printer connected. Go to the Printers tab.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            ControlsContent(session = session, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun ControlsContent(session: ActivePrinterSession, modifier: Modifier = Modifier) {
    val status by session.status.collectAsState()
    val capabilities by session.capabilities.collectAsState()
    val scope = rememberCoroutineScope()

    // Same job-state machine the dashboard uses (see DashboardScreen.DashboardContent).
    val state = (status?.status ?: "").lowercase()
    val isPrinting = state in listOf("printing", "building_from_sd", "busy")
    val isPrepping = state in listOf("heating", "calibrate_doing")
    val isPaused = state == "paused"
    val isPausing = state == "pausing"
    val isActiveJob = isPrinting || isPrepping || isPaused || isPausing

    var showTempDialog by remember { mutableStateOf<String?>(null) } // "Nozzle" / "Bed"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Job ─────────────────────────────────────────────────────────────
        ControlSection("Job") {
            if (isActiveJob) {
                JobControlRow(
                    isPrinting = isPrinting,
                    isPaused = isPaused,
                    isPausing = isPausing,
                    onPause = { scope.launch { session.pause() } },
                    onResume = { scope.launch { session.resume() } },
                    onCancel = { scope.launch { session.cancel() } }
                )
            } else {
                Text(
                    "No active job.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = { scope.launch { session.clearPlatform() } },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("CLEAR PLATFORM")
            }
        }

        // ── Temperature ─────────────────────────────────────────────────────
        ControlSection("Temperature") {
            HeaterGrid(status = status, onHeaterClick = { showTempDialog = it })
            Text(
                "Tap a heater to set its target. Set 0 to turn it off.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── Motion ──────────────────────────────────────────────────────────
        ControlSection("Motion") {
            Button(
                onClick = { scope.launch { session.home() } },
                enabled = !isActiveJob,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Home, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("HOME ALL AXES")
            }
        }

        // ── Lighting ────────────────────────────────────────────────────────
        if (capabilities.ledControl) {
            val isLightOn = status?.lightStatus == "open" || status?.lightStatus == "1"
            ControlSection("Lighting") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isLightOn) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                            contentDescription = null,
                            tint = if (isLightOn) me.ghost.ffui.ui.theme.GeometricYellowPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(if (isLightOn) "Light on" else "Light off")
                    }
                    Switch(
                        checked = isLightOn,
                        onCheckedChange = { on -> scope.launch { session.setLight(on) } }
                    )
                }
            }
        }

        // ── Filtration (5M Pro) ─────────────────────────────────────────────
        if (capabilities.filtrationControl) {
            ControlSection("Filtration") {
                FiltrationCard(
                    internalFanOn = status?.internalFanStatus == "open",
                    externalFanOn = status?.externalFanStatus == "open",
                    tvoc = status?.tvoc,
                    controlsEnabled = !(isPrinting || isPrepping),
                    onSelect = { mode -> scope.launch { session.setFiltration(mode) } }
                )
            }
        }
    }

    showTempDialog?.let { heaterName ->
        val isNozzle = heaterName == "Nozzle"
        TemperatureDialog(
            heaterName = heaterName,
            onSet = { t ->
                scope.launch { if (isNozzle) session.setNozzleTemp(t) else session.setBedTemp(t) }
                showTempDialog = null
            },
            onDismiss = { showTempDialog = null }
        )
    }
}

/** A titled card grouping related controls. */
@Composable
private fun ControlSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
            }
        }
    }
}
