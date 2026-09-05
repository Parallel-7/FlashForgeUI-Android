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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ghost.ffui.ui.PrinterModelNames
import me.ghost.ffapi.models.FFPrinterDetail as PrinterDetailResponse
import me.ghost.ffui.data.ActivePrinterSession
import me.ghost.ffui.data.PrinterEntity
import me.ghost.ffui.data.maskSerial
import me.ghost.ffui.ui.MainViewModel
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
        Scaffold(topBar = { TopAppBar(title = { Text("Printer Info") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }) }) { p ->
            Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Printer not connected.") }
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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
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
        InfoCard("PRINTER") {
            InfoRow("Name", displayName)
            InfoRow("Model", model)
            InfoRow("Firmware", firmware ?: "—")
            InfoRow("Serial", maskSerial(printer.serialNumber, hideSerials))
            InfoRow("MAC", status?.macAddr ?: "—")
            InfoRow("IP", printer.ipAddress)
            InfoRow("Camera", cameraUrl ?: "—")
            InfoRow("LED", lightLabel(status?.lightStatus))
        }

        InfoCard("LIFETIME STATS") {
            if (connected) {
                InfoRow("Filament used", formatFilament(status?.cumulativeFilament))
                InfoRow("Print time", formatPrintMinutes(status?.cumulativePrintTime))
                InfoRow("Free disk", formatDiskGb(status?.remainingDiskSpace))
            } else {
                Text(
                    "Connect to this printer to see lifetime stats.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(onClick = { showRename = true }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
            Text("Edit name")
        }
        OutlinedButton(onClick = { showShutdown = true }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
            val on = status?.autoShutdown == "open"
            val mins = status?.autoShutdownTime?.toInt() ?: 0
            Text(if (on) "Auto-shutdown: on ($mins min)" else "Auto-shutdown: off")
        }
        if (!connected) {
            Text(
                "Connect to this printer to rename it or change auto-shutdown.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(displayName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename printer") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                Button(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty()) onRename(n)
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }

    if (showShutdown) {
        var enabled by remember { mutableStateOf(status?.autoShutdown == "open") }
        var minutesStr by remember { mutableStateOf((status?.autoShutdownTime?.toInt() ?: 30).toString()) }
        AlertDialog(
            onDismissRequest = { showShutdown = false },
            title = { Text("Auto-shutdown") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                        Spacer(Modifier.width(12.dp))
                        Text(if (enabled) "Shut down after a completed print" else "Disabled")
                    }
                    OutlinedTextField(
                        value = minutesStr,
                        onValueChange = { minutesStr = it.filter(Char::isDigit) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text("Minutes after completion") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onAutoShutdown(enabled, minutesStr.toIntOrNull() ?: 0)
                    showShutdown = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showShutdown = false }) { Text("Cancel") } }
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
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

private fun lightLabel(status: String?): String = when (status) {
    "open", "1" -> "On"
    "close", "0" -> "Off"
    else -> "—"
}

/** Cumulative filament is in meters; show km past 1 km. */
private fun formatFilament(meters: Float?): String {
    val m = meters ?: return "—"
    return if (m >= 1000f) "%.2f km".format(m / 1000f) else "%.1f m".format(m)
}

/** Cumulative print time is in minutes. */
private fun formatPrintMinutes(minutes: Float?): String {
    val total = minutes?.toInt() ?: return "—"
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** remainingDiskSpace is GB free (a fractional value on the wire); show MB under 1 GB. */
private fun formatDiskGb(gb: Float?): String {
    val g = gb ?: return "—"
    return if (g >= 1f) "%.2f GB".format(g) else "%.0f MB".format(g * 1024f)
}
