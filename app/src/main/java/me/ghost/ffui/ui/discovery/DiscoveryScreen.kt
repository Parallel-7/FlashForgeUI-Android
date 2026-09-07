package me.ghost.ffui.ui.discovery

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ghost.ffui.R
import me.ghost.ffui.api.DiscoveredPrinter
import me.ghost.ffapi.PrinterModel
import me.ghost.ffui.data.PrinterEntity
import me.ghost.ffui.data.maskSerial
import me.ghost.ffui.ui.MainViewModel
import me.ghost.ffui.ui.info.PrinterInfoDialog
import me.ghost.ffui.ui.theme.StatusConnected
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: MainViewModel,
    onNavigateToDashboard: () -> Unit,
    onNavigateToSettings: (String) -> Unit = {}
) {
    val discovered by viewModel.discoveredPrinters.collectAsStateWithLifecycle()
    val savedPrinters by viewModel.savedPrinters.collectAsStateWithLifecycle()
    val isDiscovering by viewModel.isDiscovering.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val needsAddress by viewModel.needsAddressSerials.collectAsStateWithLifecycle()
    
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedToConnect by remember { mutableStateOf<DiscoveredPrinter?>(null) }
    var infoFor by remember { mutableStateOf<PrinterEntity?>(null) }
    val hideSerials by viewModel.settingsDataStore.hideSerials.collectAsStateWithLifecycle(initialValue = false)

    // Auto-scan on tab entry so saved tiles get fresh Ready dots; discoverPrinters()'s own
    // isDiscovering guard makes this a no-op while a scan is already running.
    LaunchedEffect(Unit) { viewModel.discoverPrinters() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.printers_title)) },
                actions = {
                    IconButton(onClick = { viewModel.discoverPrinters() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.discovery_refresh_cd))
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.discovery_add_printer_cd))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isDiscovering) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (savedPrinters.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.discovery_saved_section), style = MaterialTheme.typography.titleMedium)
                    }
                    items(savedPrinters, key = { it.serialNumber }) { printer ->
                        val isConnected = sessions.containsKey(printer.serialNumber)
                        // "Ready" = seen in the latest scan with no live session; a live session
                        // (Connected) always wins.
                        val isReady = !isConnected &&
                            discovered.any { it.serialNumber == printer.serialNumber }
                        PrinterTile(
                            name = printer.name,
                            ip = printer.ipAddress,
                            imageRes = printerImageRes(printer.modelPid, printer.name),
                            isConnected = isConnected,
                            isReady = isReady,
                            onClick = {
                                viewModel.connectToPrinter(printer)
                                onNavigateToDashboard()
                            },
                            onInfoClick = { infoFor = printer },
                            onSettingsClick = { onNavigateToSettings(printer.serialNumber) }
                        )
                    }
                }

                if (discovered.isNotEmpty()) {
                    val notSaved = discovered.filter { d -> savedPrinters.none { it.serialNumber == d.serialNumber } }
                    if (notSaved.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.discovery_network_section), style = MaterialTheme.typography.titleMedium)
                        }
                        items(notSaved, key = { it.serialNumber }) { printer ->
                            PrinterTile(
                                name = printer.name,
                                ip = printer.ipAddress,
                                imageRes = printerImageRes(null, printer.name),
                                isConnected = false,
                                onClick = { selectedToConnect = printer }
                            )
                        }
                    }
                }
            }
        }
    }
    
    if (showAddDialog) {
        AddPrinterDialog(
            onDismiss = { showAddDialog = false },
            onConnect = { entity ->
                viewModel.saveAndConnect(entity)
                showAddDialog = false
                onNavigateToDashboard()
            }
        )
    }
    
    infoFor?.let { printer ->
        PrinterInfoDialog(
            printer = printer,
            session = sessions[printer.serialNumber],
            hideSerials = hideSerials,
            onDismiss = { infoFor = null }
        )
    }

    selectedToConnect?.let { ptr ->
        AddPrinterDialog(
            initialIp = ptr.ipAddress,
            initialName = ptr.name,
            initialSerial = ptr.serialNumber,
            onDismiss = { selectedToConnect = null },
            onConnect = { entity ->
                viewModel.saveAndConnect(entity)
                selectedToConnect = null
                onNavigateToDashboard()
            }
        )
    }

    // Address re-entry: a user-tapped connect whose discovery + saved-address attempts both
    // failed. One dialog at a time (first prompted serial); Save re-resolves, Cancel consumes.
    needsAddress.firstNotNullOfOrNull { serial -> savedPrinters.find { it.serialNumber == serial } }?.let { printer ->
        EditAddressDialog(
            printerName = printer.name,
            serialDisplay = maskSerial(printer.serialNumber, hideSerials),
            onDismiss = { viewModel.dismissAddressPrompt(printer.serialNumber) },
            onSave = { ip -> viewModel.updatePrinterAddress(printer.serialNumber, ip) }
        )
    }
}

/**
 * Picks the printer render for a tile. Prefers the firmware-stable [PrinterEntity.modelPid]
 * (35=5M, 36=5M Pro, 38=AD5X, 40=Creator 5, 41=Creator 5 Pro); legacy printers (Adventurer 3/4) have
 * no pid, and discovered-but-unsaved printers don't know it yet, so it falls back to name heuristics.
 * Unknown models default to the 5M render.
 */
@DrawableRes
private fun printerImageRes(modelPid: Int?, name: String): Int = when {
    modelPid == PrinterModel.PID_AD5X -> R.drawable.printer_ad5x
    modelPid == PrinterModel.PID_5M_PRO -> R.drawable.printer_5m_pro
    modelPid == PrinterModel.PID_5M -> R.drawable.printer_5m
    modelPid == PrinterModel.PID_CREATOR_5_PRO -> R.drawable.printer_creator5_pro
    modelPid == PrinterModel.PID_CREATOR_5 -> R.drawable.printer_creator5
    // Creator 5 arms precede the generic Pro/5M name checks: "Creator 5 Pro" contains "Pro", so it
    // must be matched first (mirrors how 5X/Pro are ordered against 5M below).
    name.contains("Creator 5 Pro", ignoreCase = true) -> R.drawable.printer_creator5_pro
    name.contains("Creator 5", ignoreCase = true) -> R.drawable.printer_creator5
    name.contains("5X", ignoreCase = true) -> R.drawable.printer_ad5x
    name.contains("Pro", ignoreCase = true) -> R.drawable.printer_5m_pro
    name.contains("5M", ignoreCase = true) -> R.drawable.printer_5m
    // Legacy machines, matched on the model name the printer reports (no pid over TCP).
    name.contains("Adventurer 4", ignoreCase = true) || name.contains("Adventurer4", ignoreCase = true) ||
        name.contains("Adventurer IV", ignoreCase = true) -> R.drawable.printer_adventurer4
    name.contains("Adventurer 3", ignoreCase = true) || name.contains("Adventurer3", ignoreCase = true) ||
        name.contains("Adventurer III", ignoreCase = true) -> R.drawable.printer_adventurer3
    else -> R.drawable.printer_5m
}

/**
 * Poster-style grid tile: the printer render is the hero, with the name and (for saved printers)
 * info/settings actions below. A live session shows a small status dot over the image.
 */
@Composable
fun PrinterTile(
    name: String,
    ip: String,
    @DrawableRes imageRes: Int,
    isConnected: Boolean,
    onClick: () -> Unit,
    isReady: Boolean = false,
    onInfoClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(12.dp)
            )
            // Live session wins; otherwise a serial seen in the latest scan shows Ready.
            val badgeLabel = when {
                isConnected -> stringResource(R.string.discovery_connected_badge)
                isReady -> stringResource(R.string.discovery_ready_badge)
                else -> null
            }
            if (badgeLabel != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            CircleShape
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).background(StatusConnected, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusConnected
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    ip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onInfoClick != null) {
                IconButton(onClick = onInfoClick, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(R.string.discovery_info_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onSettingsClick != null) {
                IconButton(onClick = onSettingsClick, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.printers_settings_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun AddPrinterDialog(
    initialIp: String = "",
    initialName: String = "",
    initialSerial: String = "",
    onDismiss: () -> Unit,
    onConnect: (PrinterEntity) -> Unit
) {
    var ip by remember { mutableStateOf(initialIp) }
    var name by remember { mutableStateOf(initialName) }
    var serial by remember { mutableStateOf(initialSerial) }
    var pin by remember { mutableStateOf("") }

    // A discovered legacy printer reports no serial over UDP — the user must read it off the
    // printer and type it in. Say so instead of showing a blank required field.
    val needsManualSerial = initialIp.isNotBlank() && initialSerial.isBlank()

    // Connect is disabled until every field is filled — previously a blank field made the tap a
    // silent no-op (the blank-check lived inside onClick), which read as "nothing happened".
    val formComplete = ip.isNotBlank() && name.isNotBlank() && serial.isNotBlank() && pin.isNotBlank()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialSerial.isEmpty()) stringResource(R.string.discovery_add_printer_cd) else stringResource(R.string.discovery_connect_to, name)) },
        text = {
            Column {
                if (needsManualSerial) {
                    Text(
                        stringResource(R.string.discovery_manual_serial_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.discovery_field_name)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it },
                    label = { Text(stringResource(R.string.discovery_field_ip)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = serial, onValueChange = { serial = it },
                    label = { Text(stringResource(R.string.discovery_field_serial)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it },
                    label = { Text(stringResource(R.string.discovery_field_pin)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = formComplete,
                onClick = { onConnect(PrinterEntity(serial, ip, name, pin)) }
            ) {
                Text(stringResource(R.string.discovery_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * Address re-entry dialog for a saved printer whose discovery + saved-address connects both
 * failed: the name and serial are read-only context, only the address is editable.
 */
@Composable
fun EditAddressDialog(
    printerName: String,
    serialDisplay: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var ip by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discovery_address_title)) },
        text = {
            Column {
                Text(
                    printerName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Text(
                    serialDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text(stringResource(R.string.discovery_field_ip)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = isValidIpv4(ip),
                onClick = { onSave(ip.trim()) }
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * Light IPv4 validation for the address re-entry dialog: exactly four dotted decimal octets,
 * each 0–255, no leading zeros. Not a full IP parser — just enough to block obvious typos.
 */
internal fun isValidIpv4(input: String): Boolean {
    val parts = input.trim().split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
            (part == "0" || !part.startsWith("0")) &&
            part.toInt() < 256
    }
}
