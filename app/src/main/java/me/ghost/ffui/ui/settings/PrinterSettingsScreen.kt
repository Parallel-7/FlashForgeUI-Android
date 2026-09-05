package me.ghost.ffui.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ghost.ffapi.PrinterModel
import me.ghost.ffui.R
import me.ghost.ffui.ui.PrinterModelNames
import me.ghost.ffui.data.PrinterEntity
import me.ghost.ffui.data.maskSerial
import me.ghost.ffui.ui.MainViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val hideSerials by viewModel.settingsDataStore.hideSerials.collectAsStateWithLifecycle(initialValue = false)
    val reconnectHint = stringResource(R.string.printers_settings_reconnect_hint)

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
                title = { Text(stringResource(R.string.printers_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
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
            // ── Header ──────────────────────────────────────────────────────
            // Full identity (model/firmware/IP/serial) lives behind the (i) info screen; this page
            // just needs to say which printer is being configured.
            item {
                val modelName = PrinterModelNames.shortName(currentPrinter.modelPid).ifBlank { stringResource(R.string.common_printer) }
                Text(
                    text = stringResource(R.string.printers_settings_for, currentPrinter.name.ifBlank { modelName }),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // ── LED Control ─────────────────────────────────────────────────
            item {
                Text(
                    stringResource(R.string.printers_settings_section_led),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                SettingToggle(
                    label = stringResource(R.string.printers_settings_custom_led_title),
                    subtitle = stringResource(R.string.printers_settings_custom_led_subtitle),
                    checked = currentPrinter.customLedEnabled,
                    onCheckedChange = { enabled ->
                        updatePrinter(currentPrinter.copy(customLedEnabled = enabled))
                        if (isConnected) viewModel.reconnectSession(serialNumber)
                        else scope.launch { snackbarHostState.showSnackbar(reconnectHint) }
                    }
                )
            }

            // ── Camera ──────────────────────────────────────────────
            item {
                Text(
                    stringResource(R.string.printers_settings_section_camera),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                SettingToggle(
                    label = stringResource(R.string.printers_settings_custom_camera_title),
                    subtitle = stringResource(R.string.printers_settings_custom_camera_subtitle),
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
                    label = stringResource(R.string.printers_settings_autoplay_title),
                    subtitle = stringResource(R.string.printers_settings_autoplay_subtitle),
                    checked = currentPrinter.cameraAutoPlayEnabled,
                    onCheckedChange = { enabled ->
                        updatePrinter(currentPrinter.copy(cameraAutoPlayEnabled = enabled))
                    }
                )
            }
            item {
                SettingToggle(
                    label = stringResource(R.string.printers_settings_fps_title),
                    subtitle = stringResource(R.string.printers_settings_fps_subtitle),
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
                        // Local state + debounce: writing to Room on every keystroke churned the
                        // DAO and the live session entity ~20x per typed URL.
                        var cameraUrl by remember(currentPrinter.serialNumber) {
                            mutableStateOf(currentPrinter.customCameraUrl)
                        }
                        LaunchedEffect(cameraUrl) {
                            delay(600)
                            if (cameraUrl != currentPrinter.customCameraUrl) {
                                updatePrinter(currentPrinter.copy(customCameraUrl = cameraUrl))
                            }
                        }
                        OutlinedTextField(
                            value = cameraUrl,
                            onValueChange = { url -> cameraUrl = url },
                            label = { Text(stringResource(R.string.printers_settings_camera_url_label)) },
                            placeholder = { Text(stringResource(R.string.printers_settings_camera_url_placeholder)) },
                            singleLine = true,
                            supportingText = {
                                Text(stringResource(R.string.printers_settings_camera_url_hint))
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
                    stringResource(R.string.printers_settings_section_api),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                SettingToggle(
                    label = stringResource(R.string.printers_settings_force_legacy_title),
                    subtitle = stringResource(R.string.printers_settings_force_legacy_subtitle),
                    checked = currentPrinter.forceLegacy,
                    onCheckedChange = { enabled ->
                        updatePrinter(currentPrinter.copy(forceLegacy = enabled))
                        if (isConnected) viewModel.reconnectSession(serialNumber)
                        else scope.launch { snackbarHostState.showSnackbar(reconnectHint) }
                    }
                )
            }
            // Auto-match is an AD5X material-station feature; hide it for models without one.
            if (currentPrinter.modelPid == PrinterModel.PID_AD5X) {
                item {
                    SettingToggle(
                        label = stringResource(R.string.printers_settings_auto_match_title),
                        subtitle = stringResource(R.string.printers_settings_auto_match_subtitle),
                        checked = currentPrinter.autoMatchMaterials,
                        onCheckedChange = { enabled ->
                            updatePrinter(currentPrinter.copy(autoMatchMaterials = enabled))
                        }
                    )
                }
            }

            // ── Notifications ───────────────────────────────────────────────
            item {
                Text(
                    stringResource(R.string.printers_settings_section_notifications),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                SettingToggle(
                    label = stringResource(R.string.printers_settings_notify_complete_title),
                    subtitle = stringResource(R.string.printers_settings_notify_complete_subtitle),
                    checked = currentPrinter.notifyOnComplete,
                    onCheckedChange = { enabled ->
                        updatePrinter(currentPrinter.copy(notifyOnComplete = enabled))
                    }
                )
            }
            item {
                SettingToggle(
                    label = stringResource(R.string.printers_settings_notify_cooled_title),
                    subtitle = stringResource(R.string.printers_settings_notify_cooled_subtitle),
                    checked = currentPrinter.notifyOnCooled,
                    onCheckedChange = { enabled ->
                        updatePrinter(currentPrinter.copy(notifyOnCooled = enabled))
                    }
                )
            }
            item {
                SettingToggle(
                    label = stringResource(R.string.printers_settings_notify_errors_title),
                    subtitle = stringResource(R.string.printers_settings_notify_errors_subtitle),
                    checked = currentPrinter.notifyOnError,
                    onCheckedChange = { enabled ->
                        updatePrinter(currentPrinter.copy(notifyOnError = enabled))
                    }
                )
            }

            // ── Danger Zone ─────────────────────────────────────────────────
            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                Text(
                    stringResource(R.string.printers_settings_section_danger),
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
                            Text(stringResource(R.string.printers_settings_delete))
                        }
                    }
                }
            }
        }

        // ── Delete Confirmation Dialog ──────────────────────────────────
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(stringResource(R.string.printers_settings_delete)) },
                text = {
                    Text(
                        stringResource(
                            R.string.printers_settings_delete_confirm,
                            currentPrinter.name,
                            maskSerial(currentPrinter.serialNumber, hideSerials)
                        )
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
                        Text(stringResource(R.string.common_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}

// ── Private helpers ─────────────────────────────────────────────────────────

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
