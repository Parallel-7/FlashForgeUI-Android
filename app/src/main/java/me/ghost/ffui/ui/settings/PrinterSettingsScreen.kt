package me.ghost.ffui.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.ghost.ffui.api.PrinterModel
import me.ghost.ffui.data.PrinterEntity
import me.ghost.ffui.data.maskSerial
import me.ghost.ffui.ui.MainViewModel
import kotlinx.coroutines.launch

/**
 * Per-printer settings screen. Loads the [PrinterEntity] from Room, exposes toggles for
 * custom LED, camera, force-legacy, and auto-match, plus a danger-zone delete action.
 * All changes are persisted immediately via [MainViewModel.repository].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSettingsScreen(
    serialNumber: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    var printer by remember { mutableStateOf<PrinterEntity?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val sessions by viewModel.sessions.collectAsState()
    val hideSerials by viewModel.settingsDataStore.hideSerials.collectAsState(initial = false)

    LaunchedEffect(serialNumber) {
        printer = viewModel.repository.getPrinter(serialNumber)
    }

    /** Persist the updated entity (Room + live session) and refresh local state. */
    fun updatePrinter(updated: PrinterEntity) {
        printer = updated
        viewModel.updatePrinterSettings(updated)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Printer Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val currentPrinter = printer

        if (currentPrinter == null) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val isConnected = sessions.containsKey(serialNumber)
        var showDeleteDialog by remember { mutableStateOf(false) }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Read-Only Info Card ─────────────────────────────────────────
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        InfoRow(
                            label = "MODEL",
                            value = when (currentPrinter.modelPid) {
                                PrinterModel.PID_5M -> "Adventurer 5M"
                                PrinterModel.PID_5M_PRO -> "Adventurer 5M Pro"
                                PrinterModel.PID_AD5X -> "AD5X"
                                else -> "Unknown"
                            }
                        )
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        InfoRow(
                            label = "FIRMWARE",
                            value = currentPrinter.firmwareVersion ?: "Not detected"
                        )
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        InfoRow(label = "IP ADDRESS", value = currentPrinter.ipAddress)
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        InfoRow(label = "SERIAL", value = maskSerial(currentPrinter.serialNumber, hideSerials))

                        if (!currentPrinter.cameraStreamUrl.isNullOrBlank()) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            InfoRow(
                                label = "CAMERA URL",
                                value = currentPrinter.cameraStreamUrl
                            )
                        }
                    }
                }
            }

            // ── LED Control ─────────────────────────────────────────────────
            item {
                Text(
                    "LED Control",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                SettingToggle(
                    label = "Custom LED control",
                    subtitle = "Enable TCP LED commands for printers without native HTTP LED support",
                    checked = currentPrinter.customLedEnabled,
                    onCheckedChange = { enabled ->
                        updatePrinter(currentPrinter.copy(customLedEnabled = enabled))
                        scope.launch {
                            snackbarHostState.showSnackbar("Reconnect for changes to take effect")
                        }
                        if (isConnected) viewModel.reconnectSession(serialNumber)
                    }
                )
            }

            // ── Camera ──────────────────────────────────────────────────────
            item {
                Text(
                    "Camera",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                SettingToggle(
                    label = "Custom camera URL",
                    subtitle = "Use a user-provided RTSP, HTTP, or MJPEG camera stream",
                    checked = currentPrinter.customCameraEnabled,
                    onCheckedChange = { enabled ->
                        val updated = if (enabled) {
                            currentPrinter.copy(customCameraEnabled = true)
                        } else {
                            currentPrinter.copy(
                                customCameraEnabled = false,
                                customCameraUrl = ""
                            )
                        }
                        updatePrinter(updated)
                    }
                )
            }
            item {
                SettingToggle(
                    label = "Auto-play camera",
                    subtitle = "Automatically start playing the camera stream when viewing the dashboard",
                    checked = currentPrinter.cameraAutoPlayEnabled,
                    onCheckedChange = { enabled ->
                        updatePrinter(currentPrinter.copy(cameraAutoPlayEnabled = enabled))
                    }
                )
            }
            item {
                SettingToggle(
                    label = "Show FPS counter",
                    subtitle = "Overlay the live frame rate on the camera feed",
                    checked = currentPrinter.cameraFpsCounterEnabled,
                    onCheckedChange = { enabled ->
                        updatePrinter(currentPrinter.copy(cameraFpsCounterEnabled = enabled))
                    }
                )
            }
            if (currentPrinter.customCameraEnabled) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        OutlinedTextField(
                            value = currentPrinter.customCameraUrl,
                            onValueChange = { url ->
                                updatePrinter(currentPrinter.copy(customCameraUrl = url))
                            },
                            label = { Text("Camera URL") },
                            placeholder = { Text("rtsp://192.168.1.x:554/stream") },
                            singleLine = true,
                            supportingText = {
                                Text("Blank falls back to the printer's built-in stream.")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                }
            }

            // ── API & Behavior ──────────────────────────────────────────────
            item {
                Text(
                    "API & Behavior",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                SettingToggle(
                    label = "Force legacy API",
                    subtitle = "Use TCP-only API even for modern printers. Requires reconnection.",
                    checked = currentPrinter.forceLegacy,
                    onCheckedChange = { enabled ->
                        updatePrinter(currentPrinter.copy(forceLegacy = enabled))
                        scope.launch {
                            snackbarHostState.showSnackbar("Reconnect for changes to take effect")
                        }
                        if (isConnected) viewModel.reconnectSession(serialNumber)
                    }
                )
            }
            item {
                SettingToggle(
                    label = "Auto-match materials",
                    subtitle = "Automatically map tool slots when starting prints (AD5X)",
                    checked = currentPrinter.autoMatchMaterials,
                    onCheckedChange = { enabled ->
                        updatePrinter(currentPrinter.copy(autoMatchMaterials = enabled))
                    }
                )
            }

            // ── Danger Zone ─────────────────────────────────────────────────
            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                Text(
                    "Danger Zone",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Delete Printer")
                        }
                    }
                }
            }
        }

        // ── Delete Confirmation Dialog ──────────────────────────────────
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete Printer") },
                text = {
                    Text(
                        "Are you sure you want to remove \"${currentPrinter.name}\" " +
                            "(${maskSerial(currentPrinter.serialNumber, hideSerials)})? This cannot be undone."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteDialog = false
                            scope.launch {
                                viewModel.disconnect(serialNumber)
                                viewModel.repository.deletePrinter(serialNumber)
                            }
                            onBack()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

// ── Private helpers ─────────────────────────────────────────────────────────

/** A single label + value row used inside the read-only info card. */
@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * A reusable settings-toggle row: label + subtitle on the left, [Switch] on the right,
 * wrapped in a bordered card.
 */
@Composable
private fun SettingToggle(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
