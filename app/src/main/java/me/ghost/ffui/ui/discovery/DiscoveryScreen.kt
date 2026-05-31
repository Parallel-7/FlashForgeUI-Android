package me.ghost.ffui.ui.discovery

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ghost.ffui.R
import me.ghost.ffui.api.DiscoveredPrinter
import me.ghost.ffui.api.PrinterModel
import me.ghost.ffui.data.PrinterEntity
import me.ghost.ffui.data.maskSerial
import me.ghost.ffui.ui.MainViewModel
import me.ghost.ffui.ui.info.PrinterInfoDialog
import me.ghost.ffui.ui.theme.StatusConnected

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: MainViewModel,
    onNavigateToDashboard: () -> Unit,
    onNavigateToSettings: (String) -> Unit = {}
) {
    val discovered by viewModel.discoveredPrinters.collectAsState()
    val savedPrinters by viewModel.savedPrinters.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedToConnect by remember { mutableStateOf<DiscoveredPrinter?>(null) }
    var infoFor by remember { mutableStateOf<PrinterEntity?>(null) }
    val hideSerials by viewModel.settingsDataStore.hideSerials.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Printers") },
                actions = {
                    IconButton(onClick = { viewModel.discoverPrinters() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Discover")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Printer")
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
                        Text("Saved Printers", style = MaterialTheme.typography.titleMedium)
                    }
                    items(savedPrinters, key = { it.serialNumber }) { printer ->
                        val isConnected = sessions.containsKey(printer.serialNumber)
                        PrinterTile(
                            name = printer.name,
                            ip = printer.ipAddress,
                            imageRes = printerImageRes(printer.modelPid, printer.name),
                            isConnected = isConnected,
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
                            Text("Discovered on Network", style = MaterialTheme.typography.titleMedium)
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

    if (selectedToConnect != null) {
        val ptr = selectedToConnect!!
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
}

/**
 * Picks the printer render for a tile. Prefers the firmware-stable [PrinterEntity.modelPid]
 * (35=5M, 36=5M Pro, 38=AD5X); legacy printers (Adventurer 3/4) have no pid, and discovered-but-
 * unsaved printers don't know it yet, so it falls back to name heuristics. Unknown models default
 * to the 5M render.
 */
@DrawableRes
private fun printerImageRes(modelPid: Int?, name: String): Int = when {
    modelPid == PrinterModel.PID_AD5X -> R.drawable.printer_ad5x
    modelPid == PrinterModel.PID_5M_PRO -> R.drawable.printer_5m_pro
    modelPid == PrinterModel.PID_5M -> R.drawable.printer_5m
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
            if (isConnected) {
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
                        "Connected",
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
                        contentDescription = "Printer Info",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onSettingsClick != null) {
                IconButton(onClick = onSettingsClick, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Printer Settings",
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
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialSerial.isEmpty()) "Add Printer" else "Connect to $name") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Printer Name") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it },
                    label = { Text("IP Address") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = serial, onValueChange = { serial = it },
                    label = { Text("Serial Number") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it },
                    label = { Text("Check Code (PIN)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (ip.isNotBlank() && name.isNotBlank() && serial.isNotBlank() && pin.isNotBlank()) {
                        onConnect(PrinterEntity(serial, ip, name, pin))
                    }
                }
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
