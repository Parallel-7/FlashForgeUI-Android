package com.example.ui.discovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.api.DiscoveredPrinter
import com.example.data.PrinterEntity
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    viewModel: MainViewModel,
    onNavigateToDashboard: () -> Unit
) {
    val discovered by viewModel.discoveredPrinters.collectAsState()
    val savedPrinters by viewModel.savedPrinters.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedToConnect by remember { mutableStateOf<DiscoveredPrinter?>(null) }

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
                        PrinterCard(
                            name = printer.name,
                            ip = printer.ipAddress,
                            isSaved = true,
                            onClick = {
                                viewModel.connectToPrinter(printer)
                                onNavigateToDashboard()
                            }
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
fun PrinterCard(name: String, ip: String, isSaved: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Text(ip, style = MaterialTheme.typography.bodyMedium)
            }
            if (isSaved) {
                Badge { Text("Saved") }
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
