package com.example.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.ConnectionState
import com.example.ui.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun IFSItem(slot: com.example.api.MatlSlotInfo?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(4.dp).fillMaxSize(), contentAlignment = Alignment.Center) {
        if (slot?.hasFilament == true) {
            val colorStr = slot.materialColor.takeIf { it.isNotBlank() } ?: "#FFFFFF"
            val color = try { Color(android.graphics.Color.parseColor(if (colorStr.startsWith("#")) colorStr else "#$colorStr")) } catch (e: Exception) { Color.Gray }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(modifier = Modifier.size(24.dp).background(color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                Spacer(Modifier.height(2.dp))
                Text(slot.materialName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        } else {
            Box(modifier = Modifier.size(24.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
                Text(slot?.slotId?.toString() ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel
) {
    val session by viewModel.activeSession.collectAsState()
    val currentSession = session

    if (currentSession == null) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Dashboard") }) }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No printer connected. Go to Printers tab.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val status by currentSession.status.collectAsState()
    val connectionState by currentSession.connectionState.collectAsState()
    val capabilities by currentSession.capabilities.collectAsState()
    val matlStation by currentSession.matlStation.collectAsState()

    val scope = rememberCoroutineScope()

    val printerState = status?.status ?: "—"
    val nozzleCurrent = status?.rightTemp ?: 0f
    val nozzleTarget = status?.rightTargetTemp ?: 0f
    val bedCurrent = status?.platTemp ?: 0f
    val bedTarget = status?.platTargetTemp ?: 0f
    val progress = status?.printProgress?.let { (it * 100).toInt() } ?: 0
    val isLightOn = status?.lightStatus == "open" || status?.lightStatus == "1"

    // Filtration state derived from real fan status ("open"/"close"), not hardcoded.
    val internalFanOn = status?.internalFanStatus == "open"
    val externalFanOn = status?.externalFanStatus == "open"
    val filtrationMode = when {
        internalFanOn -> "Internal"
        externalFanOn -> "External"
        else -> "Off"
    }

    var showTempDialog by remember { mutableStateOf<Pair<String, Int>?>(null) } // Heater Name, Heater Index

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentSession.printer.name) },
                actions = {
                    if (capabilities.ledControl) {
                        IconButton(onClick = {
                            scope.launch { currentSession.setLight(!isLightOn) }
                        }) {
                            Icon(
                                if (isLightOn) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                                contentDescription = "Toggle Light"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Connection banner (offline / auth issues)
            when (val cs = connectionState) {
                is ConnectionState.Connecting -> ConnectionBanner("Connecting…", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                is ConnectionState.Offline -> ConnectionBanner("Offline — reconnecting… ${cs.reason ?: ""}".trim(), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                is ConnectionState.AuthFailed -> ConnectionBanner("Authentication failed — check serial / check code", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                is ConnectionState.Connected -> {}
            }

            // Status Area
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text("Printing: ${status?.printFileName ?: "None"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text("$progress%", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(12.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = androidx.compose.ui.graphics.Color.Transparent,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                Text("State: $printerState", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Temps & Info Grid
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Nozzle
                    Card(
                        modifier = Modifier.weight(1f).aspectRatio(1.2f),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("NOZZLE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "%.0f/%.0f°".format(nozzleCurrent, nozzleTarget),
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.clickable { showTempDialog = "Nozzle" to 0 }
                                )
                            }
                            LinearProgressIndicator(
                                progress = { if (nozzleTarget > 0) (nozzleCurrent / nozzleTarget).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = com.example.ui.theme.GeometricOrangePrimary,
                                trackColor = com.example.ui.theme.GeometricOrangeContainer,
                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        }
                    }
                    // Bed
                    Card(
                        modifier = Modifier.weight(1f).aspectRatio(1.2f),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("BED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "%.0f/%.0f°".format(bedCurrent, bedTarget),
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.clickable { showTempDialog = "Bed" to 1 }
                                )
                            }
                            LinearProgressIndicator(
                                progress = { if (bedTarget > 0) (bedCurrent / bedTarget).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = com.example.ui.theme.GeometricBluePrimary,
                                trackColor = com.example.ui.theme.GeometricBlueContainer,
                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Remaining
                    Card(
                        modifier = Modifier.weight(1f).aspectRatio(1.2f),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("REMAINING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                val remMins = status?.estimatedTime?.let { (it / 60f).toInt() } ?: 0
                                Text("${remMins / 60}:${(remMins % 60).toString().padStart(2, '0')} hr", style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                    // Filtration (5M Pro) or Material Station (AD5X) — capability-gated.
                    if (capabilities.filtrationControl) {
                        Card(
                            modifier = Modifier.weight(1f).aspectRatio(1.2f),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("FILTRATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(4.dp))
                                    Text(filtrationMode, style = MaterialTheme.typography.headlineSmall)
                                }
                                val active = internalFanOn || externalFanOn
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(modifier = Modifier.size(8.dp).background(
                                        if (active) com.example.ui.theme.GeometricGreenPrimary else MaterialTheme.colorScheme.outlineVariant,
                                        CircleShape
                                    ))
                                    val chamber = status?.chamberTemp
                                    Text(if (chamber != null) "Chamber ${chamber.toInt()}°C" else "Chamber --", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else if (capabilities.hasMaterialStation && matlStation != null) {
                        Card(
                            modifier = Modifier.weight(1f).aspectRatio(1.2f),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(8.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                Text("MATERIAL (IFS)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp))

                                val slots = matlStation!!.slotInfos.sortedBy { it.slotId }
                                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    Row(modifier = Modifier.weight(1f)) {
                                        IFSItem(slots.find { it.slotId == 1 }, Modifier.weight(1f))
                                        IFSItem(slots.find { it.slotId == 2 }, Modifier.weight(1f))
                                    }
                                    Row(modifier = Modifier.weight(1f)) {
                                        IFSItem(slots.find { it.slotId == 3 }, Modifier.weight(1f))
                                        IFSItem(slots.find { it.slotId == 4 }, Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // Controls
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (printerState.lowercase() in listOf("printing", "building_from_sd")) {
                    Button(
                        onClick = { scope.launch { currentSession.pause() } },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause")
                        Spacer(Modifier.width(8.dp))
                        Text("PAUSE")
                    }
                } else if (printerState.lowercase() == "paused") {
                    Button(
                        onClick = { scope.launch { currentSession.resume() } },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                        Spacer(Modifier.width(8.dp))
                        Text("RESUME")
                    }
                }

                if (printerState.lowercase() in listOf("printing", "building_from_sd", "paused", "pausing")) {
                    Button(
                        onClick = { scope.launch { currentSession.cancel() } },
                        modifier = Modifier.size(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                }
            }

            if (printerState.lowercase() in listOf("completed", "building_completed")) {
                Button(
                    onClick = { scope.launch { currentSession.clearPlatform() } },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("CLEAR PLATFORM")
                }
            }
        }
    }

    showTempDialog?.let { (heaterName, _) ->
        var tempStr by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTempDialog = null },
            title = { Text("Set $heaterName Temperature") },
            text = {
                OutlinedTextField(
                    value = tempStr,
                    onValueChange = { tempStr = it },
                    label = { Text("Temperature °C") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val t = tempStr.toIntOrNull()
                    if (t != null) {
                        scope.launch { currentSession.httpApi.controlTemp(currentSession.printer.serialNumber, currentSession.printer.checkCode, heaterName, t) }
                        showTempDialog = null
                    }
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showTempDialog = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ConnectionBanner(text: String, container: Color, content: Color) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}
