package me.ghost.ffui.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import me.ghost.ffapi.models.AD5XMaterialMapping
import me.ghost.ffapi.models.FFGcodeFileEntry
import me.ghost.ffui.data.ActivePrinterSession
import me.ghost.ffui.data.ThumbnailCache
import me.ghost.ffui.ui.MainViewModel
import kotlinx.coroutines.launch

/** Formats a print duration (seconds) as `Hh Mm` / `Mm`, or empty when unknown. */
private fun formatPrintTime(seconds: Double?): String {
    val s = seconds?.toInt() ?: return ""
    if (s <= 0) return ""
    val mins = s / 60
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
}

/**
 * Browse files on the printer and start a print. Recent files come from HTTP `/gcodeList` (with
 * thumbnails + per-tool material data on AD5X); 5M / 5M Pro additionally list on-disk files over
 * TCP `~M661`. Starting an AD5X multi-color file routes through material matching (auto, then a
 * manual dialog fallback).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    serialNumber: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val session = sessions[serialNumber]

    if (session == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("Files") }, navigationIcon = { BackButton(onBack) }) }) { p ->
            Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Printer not connected.")
            }
        }
        return
    }

    val capabilities by session.capabilities.collectAsState()
    val matlStation by session.matlStation.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var recent by remember { mutableStateOf<List<FFGcodeFileEntry>>(emptyList()) }
    var local by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var selected by remember { mutableStateOf<SelectedFile?>(null) }
    var matchingFor by remember { mutableStateOf<FFGcodeFileEntry?>(null) }

    LaunchedEffect(serialNumber, refreshKey) {
        loading = true
        error = null
        session.listRecentFiles()
            .onSuccess { recent = it }
            .onFailure { error = it.message }
        // Local TCP list only for non-station printers (5M / 5M Pro).
        if (!capabilities.hasMaterialStation) {
            session.listLocalFiles().onSuccess { local = it }
        }
        loading = false
    }

    fun startPrint(fileName: String, leveling: Boolean, mappings: List<AD5XMaterialMapping> = emptyList()) {
        scope.launch {
            session.startPrint(fileName, leveling, mappings)
                .onSuccess { snackbar.showSnackbar("Print started: $fileName") }
                .onFailure { snackbar.showSnackbar("Failed: ${it.message}") }
        }
    }

    /** Decide print path: AD5X multi-color → matching; everything else → print directly. */
    fun requestPrint(file: SelectedFile, leveling: Boolean) {
        val entry = file.entry
        selected = null
        // `entry?.isMultiColor == true` smart-casts entry to non-null inside the block.
        if (capabilities.hasMaterialStation && entry?.isMultiColor == true) {
            val tools = entry.gcodeToolDatas.orEmpty()
            val slots = matlStation?.slotInfos.orEmpty()
            val auto = if (session.printer.autoMatchMaterials) autoMatchMappings(tools, slots) else null
            if (auto != null) {
                startPrint(entry.gcodeFileName, leveling, auto)
            } else {
                matchingFor = entry // open manual dialog
            }
        } else {
            startPrint(file.name, leveling)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(session.printer.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null && recent.isEmpty() && local.isEmpty() ->
                    Text(
                        "Couldn't load files: $error",
                        Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                recent.isEmpty() && local.isEmpty() ->
                    Text("No files on this printer.", Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (recent.isNotEmpty()) {
                        item { SectionHeader("RECENT FILES") }
                        items(recent, key = { "r:" + it.gcodeFileName }) { entry ->
                            RecentFileCard(
                                session = session,
                                entry = entry,
                                onClick = { selected = SelectedFile(entry.gcodeFileName, entry) }
                            )
                        }
                    }
                    if (local.isNotEmpty()) {
                        item { SectionHeader("ON PRINTER") }
                        items(local, key = { "l:$it" }) { name ->
                            LocalFileRow(name = name, onClick = { selected = SelectedFile(name, null) })
                        }
                    }
                }
            }
        }
    }

    // Print detail / confirm sheet.
    selected?.let { file ->
        PrintSheet(
            session = session,
            file = file,
            onDismiss = { selected = null },
            onPrint = { leveling -> requestPrint(file, leveling) }
        )
    }

    // Manual material matching (AD5X multi-color fallback).
    matchingFor?.let { entry ->
        MaterialMatchingDialog(
            fileName = entry.gcodeFileName,
            tools = entry.gcodeToolDatas.orEmpty(),
            slots = matlStation?.slotInfos.orEmpty().sortedBy { it.slotId },
            onConfirm = { mappings ->
                matchingFor = null
                startPrint(entry.gcodeFileName, leveling = false, mappings = mappings)
            },
            onDismiss = { matchingFor = null }
        )
    }
}

/** A file the user tapped; [entry] is present for HTTP recent files, null for TCP local names. */
private data class SelectedFile(val name: String, val entry: FFGcodeFileEntry?)

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun RecentFileCard(
    session: ActivePrinterSession,
    entry: FFGcodeFileEntry,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Thumbnail(session, entry.gcodeFileName, size = 56)
            Column(Modifier.weight(1f)) {
                Text(entry.gcodeFileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = buildList {
                    formatPrintTime(entry.printingTime).takeIf { it.isNotEmpty() }?.let { add(it) }
                    entry.totalFilamentWeight?.takeIf { it > 0 }?.let { add("%.0f g".format(it)) }
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entry.isMultiColor) {
                MultiColorBadge(entry.gcodeToolDatas.orEmpty().size)
            }
        }
    }
}

@Composable
private fun LocalFileRow(name: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun MultiColorBadge(toolCount: Int) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Text("$toolCount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Lazily fetches and renders a file's thumbnail through [ThumbnailCache] (memory + disk), so a row
 * that scrolls off-screen and back redraws instantly without re-hitting the network. Shows a
 * placeholder while loading / when the file has no thumbnail.
 */
@Composable
private fun Thumbnail(session: ActivePrinterSession, fileName: String, size: Int) {
    val context = LocalContext.current
    val cacheKey = "${session.printer.serialNumber}:$fileName"
    // Synchronous memory peek as the initial value avoids a one-frame placeholder flicker.
    val initial = remember(cacheKey) { ThumbnailCache.peek(cacheKey)?.asImageBitmap() }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = initial, cacheKey) {
        if (value == null) {
            value = ThumbnailCache.get(context, cacheKey) {
                session.getThumbnail(fileName).getOrNull()
            }?.asImageBitmap()
        }
    }
    Box(
        Modifier
            .size(size.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp))
        } ?: Icon(
            Icons.Default.Layers,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size((size / 2).dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrintSheet(
    session: ActivePrinterSession,
    file: SelectedFile,
    onDismiss: () -> Unit,
    onPrint: (leveling: Boolean) -> Unit
) {
    var leveling by remember { mutableStateOf(false) }
    val entry = file.entry
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Thumbnail(session, file.name, size = 72)
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val meta = buildList {
                        entry?.printingTime?.let { formatPrintTime(it) }?.takeIf { it.isNotEmpty() }?.let { add(it) }
                        entry?.totalFilamentWeight?.takeIf { it > 0 }?.let { add("%.0f g".format(it)) }
                    }.joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (entry?.isMultiColor == true) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
                    Text(
                        "Multi-color: ${entry.gcodeToolDatas.orEmpty().size} materials — you'll match them to station slots next.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = leveling, onCheckedChange = { leveling = it })
                Text("Level bed before printing", style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = { onPrint(leveling) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (entry?.isMultiColor == true) "Match & Print" else "Start Print")
            }
        }
    }
}
