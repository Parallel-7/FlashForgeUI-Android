package me.ghost.ffui.ui.info

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ghost.ffui.R
import me.ghost.ffui.ui.PrinterModelNames
import me.ghost.ffapi.models.FFPrinterDetail as PrinterDetailResponse
import me.ghost.ffui.data.ActivePrinterSession
import me.ghost.ffui.data.PrinterEntity
import me.ghost.ffui.data.maskSerial
import me.ghost.ffui.ui.MainViewModel
import me.ghost.ffui.ui.components.InfoRow
import kotlinx.coroutines.launch

/**
 * Full-screen route version of the printer-info view (reachable by tapping the dashboard title).
 * The discoverable entry point is [PrinterInfoDialog] from the Printers list; both share
 * [PrinterInfoBody].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterInfoScreen(
    serialNumber: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val session = sessions[serialNumber]

    if (session == null) {
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.printers_info_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
        }) }) { p ->
            Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.printers_not_connected)) }
        }
        return
    }

    val status by session.status.collectAsStateWithLifecycle()
    val livePrinter by session.printerFlow.collectAsStateWithLifecycle()
    val hideSerials by viewModel.settingsDataStore.hideSerials.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val displayName = status?.name?.takeIf { it.isNotBlank() } ?: livePrinter.name

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                }
            )
        }
    ) { padding ->
        PrinterInfoBody(
            printer = livePrinter,
            status = status,
            connected = true,
            hideSerials = hideSerials,
            onRename = { scope.launch { session.rename(it) } },
            onAutoShutdown = { enabled, minutes -> scope.launch { session.setAutoShutdown(enabled, minutes) } },
            modifier = Modifier.padding(padding)
        )
    }
}

/**
 * Borderless full-screen dialog showing "what the printer reports". Launched from the (i) icon on a
 * saved printer in the Printers list. Live stats and the rename / auto-shutdown actions require a
 * live [session]; when the printer isn't connected it shows the saved identity only.
 */
@Composable
fun PrinterInfoDialog(
    printer: PrinterEntity,
    session: ActivePrinterSession?,
    hideSerials: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                if (session != null) {
                    val status by session.status.collectAsStateWithLifecycle()
                    val livePrinter by session.printerFlow.collectAsStateWithLifecycle()
                    val scope = rememberCoroutineScope()
                    DialogHeader(name = status?.name?.takeIf { it.isNotBlank() } ?: livePrinter.name, onClose = onDismiss)
                    PrinterInfoBody(
                        printer = livePrinter,
                        status = status,
                        connected = true,
                        hideSerials = hideSerials,
                        onRename = { scope.launch { session.rename(it) } },
                        onAutoShutdown = { enabled, minutes -> scope.launch { session.setAutoShutdown(enabled, minutes) } }
                    )
                } else {
                    DialogHeader(name = printer.name, onClose = onDismiss)
                    PrinterInfoBody(
                        printer = printer,
                        status = null,
                        connected = false,
                        hideSerials = hideSerials,
                        onRename = {},
                        onAutoShutdown = { _, _ -> }
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogHeader(name: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close)) }
    }
}

/** Shared info body: identity + lifetime stats cards, plus rename / auto-shutdown actions. */
@Composable
private fun PrinterInfoBody(
    printer: PrinterEntity,
    status: PrinterDetailResponse?,
    connected: Boolean,
    hideSerials: Boolean,
    onRename: (String) -> Unit,
    onAutoShutdown: (enabled: Boolean, minutes: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showRename by remember { mutableStateOf(false) }
    var showShutdown by remember { mutableStateOf(false) }

    val displayName = status?.name?.takeIf { it.isNotBlank() } ?: printer.name
    val firmware = status?.firmwareVersion ?: printer.firmwareVersion
    val model = PrinterModelNames.fullName(status?.pid ?: printer.modelPid)
    val cameraUrl = printer.customCameraUrl.takeIf { printer.customCameraEnabled && it.isNotBlank() }
        ?: status?.cameraStreamUrl ?: printer.cameraStreamUrl

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        InfoCard(stringResource(R.string.printers_info_section_printer)) {
            InfoRow(stringResource(R.string.printers_info_name), displayName)
            InfoRow(stringResource(R.string.printers_info_model), model)
            InfoRow(stringResource(R.string.printers_info_firmware), firmware ?: "—")
            InfoRow(stringResource(R.string.printers_info_serial), maskSerial(printer.serialNumber, hideSerials))
            InfoRow(stringResource(R.string.printers_info_mac), status?.macAddr ?: "—")
            InfoRow(stringResource(R.string.printers_info_ip), printer.ipAddress)
            InfoRow(stringResource(R.string.printers_info_camera), cameraUrl ?: "—")
            InfoRow(stringResource(R.string.printers_info_led), lightLabel(status?.lightStatus))
        }

        InfoCard(stringResource(R.string.printers_info_section_stats)) {
            if (connected) {
                InfoRow(stringResource(R.string.printers_info_stat_filament), formatFilament(status?.cumulativeFilament))
                InfoRow(stringResource(R.string.printers_info_stat_print_time), formatPrintMinutes(status?.cumulativePrintTime))
                InfoRow(stringResource(R.string.printers_info_stat_free_disk), formatDiskGb(status?.remainingDiskSpace))
            } else {
                Text(
                    stringResource(R.string.printers_info_stats_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(onClick = { showRename = true }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.printers_info_edit_name))
        }
        OutlinedButton(onClick = { showShutdown = true }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
            val on = status?.autoShutdown == "open"
            val mins = status?.autoShutdownTime?.toInt() ?: 0
            Text(if (on) stringResource(R.string.printers_info_shutdown_on, mins) else stringResource(R.string.printers_info_shutdown_off))
        }
        if (!connected) {
            Text(
                stringResource(R.string.printers_info_actions_offline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(displayName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.printers_info_rename_title)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.printers_info_name)) }) },
            confirmButton = {
                Button(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty()) onRename(n)
                    showRename = false
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (showShutdown) {
        var enabled by remember { mutableStateOf(status?.autoShutdown == "open") }
        var minutesStr by remember { mutableStateOf((status?.autoShutdownTime?.toInt() ?: 30).toString()) }
        AlertDialog(
            onDismissRequest = { showShutdown = false },
            title = { Text(stringResource(R.string.printers_info_shutdown_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                        Spacer(Modifier.width(12.dp))
                        Text(if (enabled) stringResource(R.string.printers_info_shutdown_enabled) else stringResource(R.string.printers_info_shutdown_disabled))
                    }
                    OutlinedTextField(
                        value = minutesStr,
                        onValueChange = { minutesStr = it.filter(Char::isDigit) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.printers_info_shutdown_minutes)) }
                    )
                }
            },
            confirmButton = {
                // Require a valid number while the feature is on — a blank field must not silently
                // send 0 (firmware behavior at 0 is unverified). Disabled-with-blank still saves.
                val minutes = minutesStr.toIntOrNull()
                Button(
                    onClick = {
                        onAutoShutdown(enabled, minutes?.coerceIn(1, 720) ?: 0)
                        showShutdown = false
                    },
                    enabled = !enabled || (minutes != null && minutes >= 1)
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { showShutdown = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun lightLabel(status: String?): String = when (status) {
    "open", "1" -> stringResource(R.string.common_on)
    "close", "0" -> stringResource(R.string.common_off)
    else -> "—"
}

/** Cumulative filament is in meters; show km past 1 km. */
@Composable
private fun formatFilament(meters: Float?): String {
    val m = meters ?: return "—"
    return if (m >= 1000f) stringResource(R.string.printers_info_km, m / 1000f) else stringResource(R.string.printers_info_m, m)
}

/** Cumulative print time is in minutes. */
@Composable
private fun formatPrintMinutes(minutes: Float?): String {
    val total = minutes?.toInt() ?: return "—"
    val h = total / 60
    val m = total % 60
    return if (h > 0) stringResource(R.string.printers_info_duration_hm, h, m) else stringResource(R.string.printers_info_duration_m, m)
}

/** remainingDiskSpace is GB free (a fractional value on the wire); show MB under 1 GB. */
@Composable
private fun formatDiskGb(gb: Float?): String {
    val g = gb ?: return "—"
    return if (g >= 1f) stringResource(R.string.printers_info_gb, g) else stringResource(R.string.printers_info_mb, g * 1024f)
}
