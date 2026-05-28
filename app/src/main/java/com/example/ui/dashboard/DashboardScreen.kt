package com.example.ui.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.api.MatlSlotInfo
import com.example.api.MatlStationInfo
import com.example.backend.FiltrationMode
import com.example.data.ActivePrinterSession
import com.example.data.ConnectionState
import com.example.ui.MainViewModel
import com.example.ui.theme.GeometricPrimary
import com.example.ui.theme.GeometricSurface
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusConnecting
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusOffline
import kotlinx.coroutines.launch

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Parses a `#RRGGBB` (or bare `RRGGBB`) material color string; null when unparseable. */
private fun parseHexColor(hex: String): Color? {
    val s = if (hex.startsWith("#")) hex else "#$hex"
    return try { Color(android.graphics.Color.parseColor(s)) } catch (e: Exception) { null }
}

private fun modelDisplayName(pid: Int?): String = when (pid) {
    35 -> "5M"
    36 -> "5M Pro"
    38 -> "AD5X"
    else -> ""
}

// ── DashboardScreen (top-level entry point) ─────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: (String) -> Unit = {},
    onNavigateToFiles: (String) -> Unit = {},
    onNavigateToPrinters: () -> Unit = {}
) {
    val sessionsMap by viewModel.sessions.collectAsState()
    val activeSerial by viewModel.activeSerial.collectAsState()

    // Stable ordered list from the sessions map so that pager indices remain consistent.
    val sessionEntries = remember(sessionsMap) { sessionsMap.entries.toList() }

    // ── Empty state ─────────────────────────────────────────────────────────
    if (sessionEntries.isEmpty()) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Dashboard") }) }
        ) { padding ->
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No printer connected. Go to Printers tab.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        return
    }

    // ── Pager state ─────────────────────────────────────────────────────────
    val pagerState = rememberPagerState(
        initialPage = sessionEntries.indexOfFirst { it.key == activeSerial }.coerceAtLeast(0),
        pageCount = { sessionEntries.size }
    )
    val scope = rememberCoroutineScope()

    // Two-way sync: activeSerial → pager
    LaunchedEffect(activeSerial, sessionEntries) {
        val targetIdx = sessionEntries.indexOfFirst { it.key == activeSerial }
        if (targetIdx >= 0 && targetIdx != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetIdx)
        }
    }

    // Two-way sync: pager → activeSerial
    LaunchedEffect(pagerState.settledPage) {
        val serial = sessionEntries.getOrNull(pagerState.settledPage)?.key
        if (serial != null && serial != activeSerial) {
            viewModel.setActive(serial)
        }
    }

    // Derive title from whatever session is currently active in the pager.
    val currentEntry = sessionEntries.getOrNull(pagerState.settledPage)
    val titleText = currentEntry?.value?.printer?.name ?: "Dashboard"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleText) },
                actions = {
                    // Files / print picker
                    currentEntry?.key?.let { serial ->
                        IconButton(onClick = { onNavigateToFiles(serial) }) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = "Files",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Settings gear → per-printer settings
                    currentEntry?.key?.let { serial ->
                        IconButton(onClick = { onNavigateToSettings(serial) }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Printer settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // LED toggle (uses active session's capability / state)
                    currentEntry?.value?.let { session ->
                        val capabilities by session.capabilities.collectAsState()
                        val status by session.status.collectAsState()
                        val isLightOn = status?.lightStatus == "open" || status?.lightStatus == "1"
                        if (capabilities.ledControl) {
                            IconButton(onClick = {
                                scope.launch { session.setLight(!isLightOn) }
                            }) {
                                Icon(
                                    imageVector = if (isLightOn) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                                    contentDescription = if (isLightOn) "Turn light off" else "Turn light on",
                                    tint = if (isLightOn) com.example.ui.theme.GeometricYellowPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // Disconnect current session
                    IconButton(onClick = { viewModel.disconnect() }) {
                        Icon(Icons.Default.LinkOff, contentDescription = "Disconnect")
                    }
                }
            )
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .padding(scaffoldPadding)
                .fillMaxSize()
        ) {
            // ── Tab bar ─────────────────────────────────────────────────────
            PrinterTabBar(
                sessions = sessionsMap,
                activeSerial = activeSerial,
                onTabClick = { serial ->
                    viewModel.setActive(serial)
                    val idx = sessionEntries.indexOfFirst { it.key == serial }
                    if (idx >= 0) scope.launch { pagerState.animateScrollToPage(idx) }
                },
                onTabClose = { serial -> viewModel.disconnect(serial) },
                onAddClick = onNavigateToPrinters
            )

            // ── Pager (one page per session) ────────────────────────────────
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { sessionEntries[it].key }
            ) { page ->
                val entry = sessionEntries[page]
                DashboardContent(
                    session = entry.value,
                    viewModel = viewModel
                )
            }
        }
    }
}

// ── PrinterTabBar ───────────────────────────────────────────────────────────

@Composable
private fun PrinterTabBar(
    sessions: Map<String, ActivePrinterSession>,
    activeSerial: String?,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onAddClick: () -> Unit
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = GeometricSurface,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for ((serial, session) in sessions) {
                val connState by session.connectionState.collectAsState()
                PrinterTab(
                    name = session.printer.name,
                    subtitle = buildString {
                        append(session.printer.ipAddress)
                        val model = modelDisplayName(session.printer.modelPid)
                        if (model.isNotBlank()) {
                            append(" · ")
                            append(model)
                        }
                    },
                    connectionState = connState,
                    isActive = serial == activeSerial,
                    onClick = { onTabClick(serial) },
                    onClose = { onTabClose(serial) }
                )
            }

            // Trailing "+" button
            IconButton(
                onClick = onAddClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add printer",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── PrinterTab (individual tab chip) ────────────────────────────────────────

@Composable
private fun PrinterTab(
    name: String,
    subtitle: String,
    connectionState: ConnectionState,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val primaryColor = GeometricPrimary
    val activeBg = primaryColor.copy(alpha = 0.15f)
    val activeBottomBorder = primaryColor
    val surfaceBg = MaterialTheme.colorScheme.surface

    Surface(
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        color = if (isActive) activeBg else surfaceBg,
        shadowElevation = if (isActive) 2.dp else 0.dp,
        modifier = Modifier
            .widthIn(min = 160.dp, max = 240.dp)
            .fillMaxHeight()
            .then(
                if (isActive) {
                    Modifier.drawBehind {
                        drawLine(
                            color = activeBottomBorder,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(start = 10.dp, end = 4.dp)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status dot
            StatusDot(connectionState)

            // Name + subtitle
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Close button (always visible on mobile — no hover state)
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(18.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close tab",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ── Status dot with optional pulse animation for Connecting ─────────────────

@Composable
private fun StatusDot(connectionState: ConnectionState) {
    val isConnecting = connectionState is ConnectionState.Connecting

    val dotColor = when (connectionState) {
        is ConnectionState.Connected -> StatusConnected
        is ConnectionState.Connecting -> StatusConnecting
        is ConnectionState.Offline -> StatusOffline
        is ConnectionState.AuthFailed -> StatusError
    }

    if (isConnecting) {
        val infiniteTransition = rememberInfiniteTransition(label = "connecting-pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse-scale"
        )
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse-alpha"
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(scale)
                .background(dotColor.copy(alpha = alpha), CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
    }
}

// ── DashboardContent (per-session page contents) ────────────────────────────

@Composable
private fun DashboardContent(
    session: ActivePrinterSession,
    viewModel: MainViewModel
) {
    val status by session.status.collectAsState()
    val connectionState by session.connectionState.collectAsState()
    val capabilities by session.capabilities.collectAsState()
    val matlStation by session.matlStation.collectAsState()

    val scope = rememberCoroutineScope()

    val printerState = status?.status ?: "—"
    val nozzleCurrent = status?.rightTemp ?: 0f
    val nozzleTarget = status?.rightTargetTemp ?: 0f
    val bedCurrent = status?.platTemp ?: 0f
    val bedTarget = status?.platTargetTemp ?: 0f
    val progress = status?.printProgress?.let { (it * 100).toInt() } ?: 0
    val isLightOn = status?.lightStatus == "open" || status?.lightStatus == "1"

    // Filtration fan state ("open"/"close") drives the filtration card's current mode.
    val internalFanOn = status?.internalFanStatus == "open"
    val externalFanOn = status?.externalFanStatus == "open"

    // Normalized job state → which controls show/enable (see BASE_BLUEPRINT state machine).
    val state = printerState.lowercase()
    val isPrinting = state in listOf("printing", "building_from_sd", "busy")
    val isPrepping = state in listOf("heating", "calibrate_doing") // pre-print warm-up
    val isPaused = state == "paused"
    val isPausing = state == "pausing"
    val isCompleted = state in listOf("completed", "building_completed")
    val isActiveJob = isPrinting || isPrepping || isPaused || isPausing

    var showTempDialog by remember { mutableStateOf<String?>(null) } // heater: "Nozzle" / "Bed"

    Column(
        modifier = Modifier
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
                trackColor = Color.Transparent,
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
                                modifier = Modifier.clickable { showTempDialog = "Nozzle" }
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
                                modifier = Modifier.clickable { showTempDialog = "Bed" }
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
                // Layer progress
                Card(
                    modifier = Modifier.weight(1f).aspectRatio(1.2f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("LAYER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            val cur = status?.printLayer?.toInt() ?: 0
                            val tgt = status?.targetPrintLayer?.toInt() ?: 0
                            Text(if (tgt > 0) "$cur/$tgt" else "—", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }

        // Air filtration (5M Pro) — interactive, capability-gated.
        if (capabilities.filtrationControl) {
            FiltrationCard(
                internalFanOn = internalFanOn,
                externalFanOn = externalFanOn,
                tvoc = status?.tvoc,
                controlsEnabled = !(isPrinting || isPrepping),
                onSelect = { mode -> scope.launch { session.setFiltration(mode) } }
            )
        }

        // Camera feed
        CameraCard(
            streamUrl = session.printer.customCameraUrl.takeIf { session.printer.customCameraEnabled && it.isNotBlank() }
                ?: session.printer.cameraStreamUrl,
            autoPlay = session.printer.cameraAutoPlayEnabled,
            showFps = session.printer.cameraFpsCounterEnabled
        )

        // Material station (AD5X IFS) — full spool card.
        if (capabilities.hasMaterialStation) {
            matlStation?.let { IfsStationCard(it) }
        }

        // Controls — gated against the live job state. Pause/resume are mutually exclusive and
        // disabled mid-transition (pausing); stop is available for any active job.
        if (isActiveJob) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isPaused) {
                    Button(
                        onClick = { scope.launch { session.resume() } },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                        Spacer(Modifier.width(8.dp))
                        Text("RESUME")
                    }
                } else {
                    Button(
                        onClick = { scope.launch { session.pause() } },
                        enabled = isPrinting,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause")
                        Spacer(Modifier.width(8.dp))
                        Text(if (isPausing) "PAUSING…" else "PAUSE")
                    }
                }

                Button(
                    onClick = { scope.launch { session.cancel() } },
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            }
        }

        if (isCompleted) {
            Button(
                onClick = { scope.launch { session.clearPlatform() } },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("CLEAR PLATFORM")
            }
        }
    }

    // ── Temperature dialog (scoped per page) ────────────────────────────────
    showTempDialog?.let { heaterName ->
        var tempStr by remember { mutableStateOf("") }
        val isNozzle = heaterName == "Nozzle"
        AlertDialog(
            onDismissRequest = { showTempDialog = null },
            title = { Text("Set $heaterName Temperature") },
            text = {
                Column {
                    OutlinedTextField(
                        value = tempStr,
                        onValueChange = { tempStr = it.filter(Char::isDigit) },
                        label = { Text("Temperature °C") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Set 0 to turn the heater off.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val t = tempStr.toIntOrNull()
                    if (t != null) {
                        scope.launch {
                            if (isNozzle) session.setNozzleTemp(t) else session.setBedTemp(t)
                        }
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

// ── Connection Banner ───────────────────────────────────────────────────────

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

// ── Filtration card (5M Pro) ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltrationCard(
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
                    tvoc <= 100f -> com.example.ui.theme.GeometricGreenPrimary
                    tvoc <= 300f -> com.example.ui.theme.GeometricOrangePrimary
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

// ── IFS material-station card (AD5X) ─────────────────────────────────────────

@Composable
private fun IfsStationCard(station: MatlStationInfo) {
    val activeSlot = station.currentSlot.takeIf { it > 0 }
        ?: station.currentLoadSlot.takeIf { it > 0 }
        ?: 0
    val slotCount = if (station.slotCnt > 0) station.slotCnt else station.slotInfos.size.coerceAtLeast(1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MATERIAL STATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (activeSlot > 0) {
                    Text(
                        "Active: Slot $activeSlot",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (slotId in 1..slotCount) {
                    SpoolSlot(
                        slot = station.slotInfos.find { it.slotId == slotId },
                        slotId = slotId,
                        isActive = slotId == activeSlot,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ── Single spool slot ─────────────────────────────────────────────────────────

@Composable
private fun SpoolSlot(
    slot: MatlSlotInfo?,
    slotId: Int,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val hasFilament = slot?.hasFilament == true
    val spoolColor = slot?.materialColor?.let { parseHexColor(it) }
    val primary = MaterialTheme.colorScheme.primary

    // Pulse the active slot's ring for a subtle glow.
    val glowAlpha = if (isActive) {
        val transition = rememberInfiniteTransition(label = "spool-glow")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "spool-glow-alpha"
        ).value
    } else 0f

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
            // Active ring / glow.
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .border(2.dp, primary.copy(alpha = glowAlpha), CircleShape)
                )
            }
            // Spool body.
            val bodyColor = when {
                hasFilament && spoolColor != null -> spoolColor
                hasFilament -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .alpha(if (hasFilament) 1f else 0.6f)
                    .background(bodyColor, CircleShape)
                    .border(
                        width = 3.dp,
                        color = if (isActive) primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Center hub.
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                        .border(1.dp, Color.Black.copy(alpha = 0.15f), CircleShape)
                )
            }
        }

        // Material tag chip or "Empty" label.
        if (hasFilament) {
            val tag = slot?.materialName?.takeIf { it.isNotBlank() && it != "?" } ?: "—"
            Box(
                modifier = Modifier
                    .background(primary.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Text(
                "Empty",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        Text(
            "Slot $slotId",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
