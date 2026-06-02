package me.ghost.ffui.ui.spools

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ghost.ffui.api.SpoolmanSpool
import me.ghost.ffui.data.SpoolStatStyle
import me.ghost.ffui.data.SpoolmanLoadState
import me.ghost.ffui.nfc.NfcMode
import me.ghost.ffui.nfc.NfcReadResult
import me.ghost.ffui.nfc.NfcWriteResult
import me.ghost.ffui.ui.MainViewModel

/** Sort options available in the Spools screen dropdown. */
enum class SpoolSortOption(val label: String, val sortKey: String?) {
    Remaining("Remaining", "remaining_weight:asc"),
    Material("Material", "filament.material:asc"),
    Vendor("Vendor", "filament.vendor.name:asc"),
    RecentlyUsed("Recently used", "last_used:desc")
}

/** Filter spools by their local NFC-tagged state. Only shown when NFC is enabled. */
enum class NfcFilter(val label: String) {
    All("All"),
    Tagged("Tagged"),
    Untagged("Untagged");

    fun next(): NfcFilter = entries[(ordinal + 1) % entries.size]
}

/**
 * The Spools tab — a 2-column grid of [SpoolCard]s backed by the user's Spoolman server.
 * Handles search, sort, show-archived, and the NotConfigured/Error/Empty states.
 *
 * @param viewModel The app's [MainViewModel].
 * @param onNavigateToEdit Navigate to the spool edit screen with the given spool ID.
 * @param onNavigateToSettings Navigate to the Settings screen (from the NotConfigured empty state).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpoolsScreen(
    viewModel: MainViewModel,
    onNavigateToEdit: (spoolId: Int) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val repo = viewModel.spoolmanRepository
    val spools by repo.spools.collectAsStateWithLifecycle()
    val loadState by repo.loadState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // NFC: scan/write state + local tagged map. The icons & filter only appear when NFC is enabled.
    val nfc = viewModel.nfcManager
    val nfcEnabled by viewModel.settingsDataStore.nfcEnabled.collectAsStateWithLifecycle(initialValue = false)
    val taggedSpools by viewModel.settingsDataStore.nfcTaggedSpools.collectAsStateWithLifecycle(initialValue = emptyMap())
    val statStyle by viewModel.settingsDataStore.spoolStatStyle.collectAsStateWithLifecycle(initialValue = SpoolStatStyle.PERCENT)
    val nfcMode by nfc.mode.collectAsStateWithLifecycle()
    val readResult by nfc.readResult.collectAsStateWithLifecycle()
    val writeResult by nfc.writeResult.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }

    var query by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SpoolSortOption.Remaining) }
    var showArchived by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var infoSpool by remember { mutableStateOf<SpoolmanSpool?>(null) }
    var nfcFilter by remember { mutableStateOf(NfcFilter.All) }
    var highlightedSpoolId by remember { mutableStateOf<Int?>(null) }
    var writeDialogSpoolId by remember { mutableStateOf<Int?>(null) }

    // Initial load when the screen is first composed and the repo is configured
    LaunchedEffect(loadState) {
        if (loadState is SpoolmanLoadState.Idle) {
            scope.launch { repo.refresh(allowArchived = showArchived, sort = sortOption.sortKey) }
        }
    }

    // Reload when sort or archived toggle changes
    LaunchedEffect(sortOption, showArchived) {
        if (loadState is SpoolmanLoadState.Loaded || loadState is SpoolmanLoadState.Error) {
            scope.launch { repo.refresh(allowArchived = showArchived, sort = sortOption.sortKey) }
        }
    }

    // Client-side search + NFC-tagged filter
    val filteredSpools = remember(spools, query, nfcFilter, taggedSpools) {
        spools.filter { spool ->
            val matchesQuery = query.isBlank() || run {
                val q = query.lowercase()
                spool.displayName.lowercase().contains(q) ||
                    spool.filament.material?.lowercase()?.contains(q) == true ||
                    spool.filament.vendor?.name?.lowercase()?.contains(q) == true ||
                    spool.location?.lowercase()?.contains(q) == true
            }
            val matchesNfc = when (nfcFilter) {
                NfcFilter.All -> true
                NfcFilter.Tagged -> taggedSpools.containsKey(spool.id)
                NfcFilter.Untagged -> !taggedSpools.containsKey(spool.id)
            }
            matchesQuery && matchesNfc
        }
    }

    // Resolve a completed scan: scroll to + flash the matching card, or report why we can't.
    LaunchedEffect(readResult) {
        val result = readResult ?: return@LaunchedEffect
        nfc.consumeReadResult()
        when (result) {
            is NfcReadResult.Found -> {
                val index = filteredSpools.indexOfFirst { it.id == result.spoolId }
                if (index >= 0) {
                    highlightedSpoolId = result.spoolId
                    scope.launch { gridState.animateScrollToItem(index) }
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar("Spool #${result.spoolId} isn't shown — check search/filters")
                    }
                }
            }
            is NfcReadResult.Unknown -> scope.launch {
                snackbarHostState.showSnackbar("Tag has no spool data")
            }
            is NfcReadResult.Error -> scope.launch {
                snackbarHostState.showSnackbar(result.message)
            }
        }
    }

    // Clear the card highlight a moment after a successful scan.
    LaunchedEffect(highlightedSpoolId) {
        if (highlightedSpoolId != null) {
            delay(2500)
            highlightedSpoolId = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spools") },
                actions = {
                    // Scan a tag → jump to its spool (NFC only)
                    if (nfcEnabled) {
                        IconButton(onClick = { nfc.beginRead() }) {
                            Icon(Icons.Default.Contactless, contentDescription = "Scan tag")
                        }
                    }
                    // Refresh
                    IconButton(onClick = {
                        scope.launch { repo.refresh(allowArchived = showArchived, sort = sortOption.sortKey) }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search + sort + archive toggle row
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search field
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search spools…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { /* client-side, no action needed */ })
                )

                // Sort + archive + tag filter row. FlowRow lets the chips wrap to a second line on
                // narrow screens instead of overflowing and inflating the row height, and keeps them
                // on a single line where there's room — no hardcoded widths.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Sort dropdown trigger
                    Box {
                        TextButton(onClick = { sortExpanded = true }) {
                            Text(
                                "Sort: ${sortOption.label}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                            SpoolSortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        sortOption = option
                                        sortExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Show archived toggle
                    TextButton(onClick = { showArchived = !showArchived }) {
                        Icon(
                            if (showArchived) Icons.Default.Inventory2 else Icons.Outlined.Inventory2,
                            contentDescription = if (showArchived) "Hide archived" else "Show archived",
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            if (showArchived) "Hide archived" else "Show archived",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    // NFC-tagged filter — cycles All → Tagged → Untagged
                    if (nfcEnabled) {
                        TextButton(onClick = { nfcFilter = nfcFilter.next() }) {
                            Icon(
                                Icons.Default.Contactless,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Tags: ${nfcFilter.label}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            // Body
            when (val state = loadState) {
                is SpoolmanLoadState.NotConfigured -> {
                    EmptyState(
                        message = "Set your Spoolman server in Settings",
                        onAction = onNavigateToSettings,
                        actionLabel = "Open Settings"
                    )
                }
                is SpoolmanLoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading spools…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is SpoolmanLoadState.Error -> {
                    EmptyState(
                        message = state.message,
                        onAction = {
                            scope.launch { repo.refresh(allowArchived = showArchived, sort = sortOption.sortKey) }
                        },
                        actionLabel = "Retry"
                    )
                }
                is SpoolmanLoadState.Loaded -> {
                    if (filteredSpools.isEmpty()) {
                        EmptyState(
                            message = if (spools.isEmpty()) "No spools found." else "No spools match your search.",
                            onAction = null,
                            actionLabel = null
                        )
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            // Adaptive instead of a fixed count: 2 columns on a phone, more on wider
                            // screens, derived from a min card width rather than a hardcoded number.
                            columns = GridCells.Adaptive(minSize = 150.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(filteredSpools, key = { it.id }) { spool ->
                                SpoolCard(
                                    spool = spool,
                                    onInfoClick = { infoSpool = spool },
                                    onEditClick = { onNavigateToEdit(spool.id) },
                                    statStyle = statStyle,
                                    nfcEnabled = nfcEnabled,
                                    tagged = taggedSpools.containsKey(spool.id),
                                    highlighted = highlightedSpoolId == spool.id,
                                    onWriteClick = {
                                        nfc.beginWrite(spool.id)
                                        writeDialogSpoolId = spool.id
                                    }
                                )
                            }
                        }
                    }
                }
                is SpoolmanLoadState.Idle -> {
                    // Will trigger LaunchedEffect above
                }
            }
        }
    }

    // Info dialog
    infoSpool?.let { spool ->
        SpoolInfoDialog(
            spool = spool,
            onDismiss = { infoSpool = null },
            onEditClick = { onNavigateToEdit(spool.id) },
            taggedAt = taggedSpools[spool.id]
        )
    }

    // NFC scan ("approach a tag") dialog — visible while in read mode.
    if (nfcMode is NfcMode.Reading) {
        NfcPromptDialog(
            expanding = true,
            title = "Scan a tag",
            message = "Hold your phone to the spool's NFC tag.",
            onDismiss = { nfc.cancel() }
        )
    }

    // NFC write dialog — shows the prompt, then the write result.
    writeDialogSpoolId?.let { spoolId ->
        val spoolName = spools.firstOrNull { it.id == spoolId }?.displayName ?: "spool #$spoolId"
        NfcWriteDialog(
            spoolName = spoolName,
            result = writeResult,
            onRetry = { nfc.beginWrite(spoolId) },
            onDismiss = {
                nfc.cancel()
                nfc.consumeWriteResult()
                writeDialogSpoolId = null
            }
        )
    }
}

/**
 * The round [Icons.Default.Contactless] glyph wrapped in animated concentric rings. The rings
 * ripple **outward** when [expanding] is true (the read/scan flow — sensing an approaching tag) or
 * contract **inward** when false (the write flow — pushing data into the tag), so the two flows are
 * distinguishable at a glance while sharing the same icon. Sized in dp so it scales with density.
 */
@Composable
private fun NfcRippleIndicator(expanding: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "nfcRipple")
    val ringCount = 3
    val periodMs = 1800
    // Stagger the rings so one is always mid-flight.
    val phases = (0 until ringCount).map { i ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(periodMs, easing = LinearEasing),
                initialStartOffset = StartOffset(periodMs / ringCount * i)
            ),
            label = "nfcRing$i"
        )
    }
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(96.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxR = size.minDimension / 2f
            val minR = maxR * 0.30f
            val strokePx = 2.dp.toPx()
            phases.forEach { phase ->
                val t = phase.value
                val radius = if (expanding) minR + (maxR - minR) * t else maxR - (maxR - minR) * t
                drawCircle(
                    color = color,
                    radius = radius,
                    alpha = (1f - t) * 0.6f,
                    style = Stroke(width = strokePx)
                )
            }
        }
        Icon(
            Icons.Default.Contactless,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(44.dp)
        )
    }
}

/**
 * Generic "hold your phone to a tag" prompt. Shows the animated NFC indicator — rings ripple
 * outward when [expanding] (reading) or contract inward when writing — above the title/message.
 * Used for the read flow and as the waiting state of the write flow.
 */
@Composable
private fun NfcPromptDialog(
    expanding: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                NfcRippleIndicator(expanding = expanding)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * The NFC write flow dialog. While [result] is null it shows the "approach a tag" prompt; on
 * [NfcWriteResult.Success] it shows a confirmation and auto-dismisses; on [NfcWriteResult.Error]
 * it shows the error with Retry/Close.
 */
@Composable
private fun NfcWriteDialog(
    spoolName: String,
    result: NfcWriteResult?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    when (result) {
        null -> NfcPromptDialog(
            expanding = false,
            title = "Write to a tag",
            message = "Hold your phone to a tag to program it for $spoolName.",
            onDismiss = onDismiss
        )
        is NfcWriteResult.Success -> {
            // Auto-dismiss shortly after a successful write.
            LaunchedEffect(Unit) {
                delay(1400)
                onDismiss()
            }
            Dialog(onDismissRequest = onDismiss) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            "Tag written for $spoolName",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        is NfcWriteResult.Error -> {
            Dialog(onDismissRequest = onDismiss) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp)
                        )
                        Text("Couldn't write tag", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            result.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) { Text("Close") }
                            Spacer(Modifier.size(8.dp))
                            Button(onClick = onRetry) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    message: String,
    onAction: (() -> Unit)?,
    actionLabel: String?
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            if (onAction != null && actionLabel != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
