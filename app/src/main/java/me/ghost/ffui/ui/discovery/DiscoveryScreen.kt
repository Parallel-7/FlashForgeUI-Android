package me.ghost.ffui.ui.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import me.ghost.ffui.api.DiscoveredPrinter
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
            
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (savedPrinters.isNotEmpty()) {
                    item { Text("Saved Printers", style = MaterialTheme.typography.titleMedium) }
                    items(savedPrinters) { printer ->
                        val isConnected = sessions.containsKey(printer.serialNumber)
                        PrinterCard(
                            name = printer.name,
                            ip = printer.ipAddress,
                            isSaved = true,
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
                        item { Text("Discovered on Network", style = MaterialTheme.typography.titleMedium) }
                        items(notSaved) { printer ->
                            PrinterCard(
                                name = printer.name,
                                ip = printer.ipAddress,
                                isSaved = false,
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

@Composable
fun PrinterCard(
    name: String,
    ip: String,
    isSaved: Boolean,
    isConnected: Boolean,
    onClick: () -> Unit,
    onInfoClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Connection status dot for saved printers
            if (isSaved) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (isConnected) StatusConnected
                            else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Text(ip, style = MaterialTheme.typography.bodyMedium)
            }
            if (isConnected) {
                Badge(
                    containerColor = StatusConnected.copy(alpha = 0.15f),
                    contentColor = StatusConnected
                ) { Text("Connected") }
                Spacer(Modifier.width(8.dp))
            } else if (isSaved) {
                Badge { Text("Saved") }
                Spacer(Modifier.width(8.dp))
            }
            if (onInfoClick != null) {
                IconButton(onClick = onInfoClick) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Printer Info",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onSettingsClick != null) {
                IconButton(onClick = onSettingsClick) {
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
