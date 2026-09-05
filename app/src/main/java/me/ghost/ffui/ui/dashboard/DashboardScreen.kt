package me.ghost.ffui.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.ghost.ffui.data.ActivePrinterSession
import me.ghost.ffui.data.ConnectionState
import me.ghost.ffui.ui.MainViewModel
import me.ghost.ffui.ui.controls.JobControlRow
import me.ghost.ffui.ui.jobStateOf
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

// ── DashboardScreen (top-level entry point) ─────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: (String) -> Unit = {},
    onNavigateToFiles: (String) -> Unit = {},
    onNavigateToInfo: (String) -> Unit = {},
    onNavigateToPrinters: () -> Unit = {}
) {
    val sessionsMap by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSerial by viewModel.activeSerial.collectAsStateWithLifecycle()

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
                title = {
                    val serial = currentEntry?.key
                    Text(
                        titleText,
                        modifier = if (serial != null) Modifier.clickable { onNavigateToInfo(serial) } else Modifier
                    )
                },
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
                        val capabilities by session.capabilities.collectAsStateWithLifecycle()
                        val status by session.status.collectAsStateWithLifecycle()
                        val isLightOn = status?.lightStatus == "open" || status?.lightStatus == "1"
                        if (capabilities.ledControl) {
                            IconButton(onClick = {
                                scope.launch { session.setLight(!isLightOn) }
                            }) {
                                Icon(
                                    imageVector = if (isLightOn) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                                    contentDescription = if (isLightOn) "Turn light off" else "Turn light on",
                                    tint = if (isLightOn) me.ghost.ffui.ui.theme.GeometricYellowPrimary else MaterialTheme.colorScheme.onSurfaceVariant
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

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { sessionEntries[it].key }
            ) { page ->
                DashboardContent(session = sessionEntries[page].value, viewModel = viewModel)
            }
        }
    }
}

// ── DashboardContent (per-session page contents) ────────────────────────────

@Composable
private fun DashboardContent(session: ActivePrinterSession, viewModel: MainViewModel) {
    val status by session.status.collectAsStateWithLifecycle()
    val connectionState by session.connectionState.collectAsStateWithLifecycle()
    val capabilities by session.capabilities.collectAsStateWithLifecycle()
    val matlStation by session.matlStation.collectAsStateWithLifecycle()
    // Live per-printer settings so camera prefs (autoplay / FPS / custom URL) apply without a reconnect.
    val livePrinter by session.printerFlow.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()

    val printerState = status?.status ?: "—"
    val progress = status?.printProgress?.let { (it * 100).toInt() } ?: 0

    // Normalized job state → which controls show/enable. Shared with the Controls tab via jobStateOf.
    val job = jobStateOf(status)

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

        // Camera feed first — at-a-glance "what is it doing right now". Job thumbnail overlay only
        // while there's an active job.
        val jobFileName = status?.printFileName
        val showJobThumb = job.isActiveJob && !jobFileName.isNullOrBlank()
        CameraCard(
            streamUrl = livePrinter.customCameraUrl.takeIf { livePrinter.customCameraEnabled && it.isNotBlank() }
                ?: livePrinter.cameraStreamUrl,
            autoPlay = livePrinter.cameraAutoPlayEnabled,
            showFps = livePrinter.cameraFpsCounterEnabled,
            jobThumbnail = if (showJobThumb) {
                JobThumbnailRef(session, jobFileName!!, status?.printFileThumbUrl)
            } else null
        )

        JobProgressHeader(fileName = status?.printFileName, progress = progress, stateLabel = printerState)

        // At-a-glance job control — directly under the progress bar, above remaining/layer. Gated
        // against the live job state; shares JobControlRow with the Controls tab (ui/controls).
        if (job.isActiveJob) {
            JobControlRow(
                isPrinting = job.isPrinting,
                isPaused = job.isPaused,
                isPausing = job.isPausing,
                onPause = { scope.launch { session.pause() } },
                onResume = { scope.launch { session.resume() } },
                onCancel = { scope.launch { session.cancel() } }
            )
        }

        JobStatsRow(status = status)

        if (capabilities.model.isCreator5) {
            // Creator 5 / 5 Pro tool-changer: 4 tool heads + heated bed + heated chamber, each with
            // its own Set/Off. The card owns its own dialog; these lambdas mirror the single-toolhead
            // dispatch below (same scope, same session pass-throughs).
            Creator5TemperatureCard(
                detail = status,
                onCreate5SetTool = { idx, t -> scope.launch { session.setToolTemp(idx, t) } },
                onCreate5CancelTool = { idx -> scope.launch { session.cancelToolTemp(idx) } },
                onCreate5SetBed = { t -> scope.launch { session.setBedTemp(t) } },
                onCreate5CancelBed = { scope.launch { session.cancelBedTemp() } },
                onCreate5SetChamber = { t -> scope.launch { session.setChamberTemp(t) } },
                onCreate5CancelChamber = { scope.launch { session.cancelChamberTemp() } }
            )
        } else {
            HeaterGrid(status = status, onHeaterClick = { showTempDialog = it })
        }

        // Air filtration (5M Pro) — interactive, capability-gated.
        if (capabilities.filtrationControl) {
            FiltrationCard(
                internalFanOn = status?.internalFanStatus == "open",
                externalFanOn = status?.externalFanStatus == "open",
                tvoc = status?.tvoc,
                controlsEnabled = !(job.isPrinting || job.isPrepping),
                onSelect = { mode -> scope.launch { session.setFiltration(mode) } }
            )
        }

        // Material station (AD5X IFS) — full spool card; tap a slot to edit material / load-unload.
        if (capabilities.hasMaterialStation) {
            matlStation?.let { IfsStationCard(it, session, viewModel) }
        }

        if (job.isCompleted) {
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
        val isNozzle = heaterName == "Nozzle"
        TemperatureDialog(
            heaterName = heaterName,
            maxTemp = if (isNozzle) NOZZLE_MAX_TEMP else BED_MAX_TEMP,
            onSet = { t ->
                scope.launch { if (isNozzle) session.setNozzleTemp(t) else session.setBedTemp(t) }
                showTempDialog = null
            },
            onDismiss = { showTempDialog = null }
        )
    }
}
