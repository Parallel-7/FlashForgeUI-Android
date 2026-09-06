package me.ghost.ffui.ui.spools

import android.nfc.NfcAdapter
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ghost.ffui.R
import me.ghost.ffui.api.SpoolmanSpool
import me.ghost.ffui.data.SpoolStatStyle
import me.ghost.ffui.data.SpoolmanLoadState
import me.ghost.ffui.nfc.NfcMode
import me.ghost.ffui.nfc.NfcReadResult
import me.ghost.ffui.nfc.NfcWriteResult
import me.ghost.ffui.ui.MainViewModel

/** Sort options available in the Spools screen dropdown. */
enum class SpoolSortOption(@StringRes val labelRes: Int, val sortKey: String?) {
    Remaining(R.string.spools_sort_remaining, "remaining_weight:asc"),
    Material(R.string.spools_sort_material, "filament.material:asc"),
    Vendor(R.string.spools_sort_vendor, "filament.vendor.name:asc"),
    RecentlyUsed(R.string.spools_sort_recently_used, "last_used:desc")
}

/** Filter spools by their local NFC-tagged state. Only shown when NFC is enabled. */
enum class NfcFilter(@StringRes val labelRes: Int) {
    All(R.string.nfc_filter_all),
    Tagged(R.string.nfc_filter_tagged),
    Untagged(R.string.nfc_filter_untagged);

    fun next(): NfcFilter = entries[(ordinal + 1) % entries.size]
}

/** Toggle between the Spools grid and the Boxes grid. */
enum class SpoolsViewMode { Spools, Boxes }

/**
 * The Spools tab — hosts both the "Spools" grid view and the "Boxes" grid view behind a
 * segmented toggle ([SpoolsViewMode]). Handles search, sort, show-archived, NFC scan/write,
 * and the NotConfigured/Error/Empty states.
 *
 * The scan handler lives at this level for smart routing: a scanned spool tag flips to the
 * Spools view, a scanned box tag flips to the Boxes view. Pending targets are passed down
 * to each view for consumption.
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
    val context = LocalContext.current
    // NFC affordances require BOTH the user setting AND real NFC hardware. Mirror the Settings
    // screen's effective state (nfcEnabled && nfcAvailable) so we never show scan/write buttons on a
    // device (e.g. the emulator) that can't do NFC — even if the stored flag persisted from another
    // device where it was turned on.
    val nfcAvailable = remember { NfcAdapter.getDefaultAdapter(context) != null }
    val nfcEnabledSetting by viewModel.settingsDataStore.nfcEnabled.collectAsStateWithLifecycle(initialValue = false)
    val nfcEnabled = nfcEnabledSetting && nfcAvailable
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
    var writeDialogSpoolId by remember { mutableStateOf<Int?>(null) }

    // View mode + pending scan targets
    var viewMode by remember { mutableStateOf(SpoolsViewMode.Spools) }
    var pendingScrollSpoolId by remember { mutableStateOf<Int?>(null) }
    var pendingOpenBox by remember { mutableStateOf<String?>(null) }

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

    // Smart scan routing: handle readResult at this level, flip view mode, set pending targets.
    LaunchedEffect(readResult) {
        val result = readResult ?: return@LaunchedEffect
        nfc.consumeReadResult()
        when (result) {
            is NfcReadResult.Found -> {
                viewMode = SpoolsViewMode.Spools
                pendingScrollSpoolId = result.spoolId
            }
            is NfcReadResult.BoxFound -> {
                viewMode = SpoolsViewMode.Boxes
                pendingOpenBox = result.location
            }
            is NfcReadResult.Unknown -> scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.nfc_no_spool_data))
            }
            is NfcReadResult.Error -> scope.launch {
                snackbarHostState.showSnackbar(result.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewMode == SpoolsViewMode.Boxes) stringResource(R.string.spools_boxes_title) else stringResource(R.string.spools_title)) },
                actions = {
                    // Scan a tag → smart-routes to spool or box
                    if (nfcEnabled) {
                        IconButton(onClick = { nfc.beginRead() }) {
                            Icon(Icons.Default.Contactless, contentDescription = stringResource(R.string.nfc_scan_tag_cd))
                        }
                    }
                    // Refresh
                    IconButton(onClick = {
                        scope.launch { repo.refresh(allowArchived = showArchived, sort = sortOption.sortKey) }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.files_refresh_cd))
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
            // Segmented toggle: Spools | Boxes
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    onClick = { viewMode = SpoolsViewMode.Spools },
                    selected = viewMode == SpoolsViewMode.Spools
                ) {
                    Text(stringResource(R.string.spools_title))
                }
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    onClick = { viewMode = SpoolsViewMode.Boxes },
                    selected = viewMode == SpoolsViewMode.Boxes
                ) {
                    Text(stringResource(R.string.spools_boxes_title))
                }
            }

            // Search + sort + archive toggle row (shared context for spools view)
            if (viewMode == SpoolsViewMode.Spools) {
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
                        placeholder = { Text(stringResource(R.string.spools_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_clear_search))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { /* client-side, no action needed */ })
                    )

                    // Sort + archive + tag filter row
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Sort dropdown trigger
                        Box {
                            TextButton(onClick = { sortExpanded = true }) {
                                Text(
                                    stringResource(R.string.spools_sort_label, stringResource(sortOption.labelRes)),
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
                                        text = { Text(stringResource(option.labelRes)) },
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
                                contentDescription = if (showArchived) stringResource(R.string.spools_hide_archived) else stringResource(R.string.spools_show_archived),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                if (showArchived) stringResource(R.string.spools_hide_archived) else stringResource(R.string.spools_show_archived),
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
                                    stringResource(R.string.spools_tags_label, stringResource(nfcFilter.labelRes)),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            // Body
            when (val state = loadState) {
                is SpoolmanLoadState.NotConfigured -> {
                    EmptyState(
                        message = stringResource(R.string.spools_not_configured),
                        onAction = onNavigateToSettings,
                        actionLabel = stringResource(R.string.spools_open_settings)
                    )
                }
                is SpoolmanLoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.spools_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is SpoolmanLoadState.Error -> {
                    EmptyState(
                        message = state.message,
                        onAction = {
                            scope.launch { repo.refresh(allowArchived = showArchived, sort = sortOption.sortKey) }
                        },
                        actionLabel = stringResource(R.string.common_retry)
                    )
                }
                is SpoolmanLoadState.Loaded -> {
                    when (viewMode) {
                        SpoolsViewMode.Spools -> {
                            SpoolListView(
                                filteredSpools = filteredSpools,
                                spools = spools,
                                gridState = gridState,
                                statStyle = statStyle,
                                nfcEnabled = nfcEnabled,
                                taggedSpools = taggedSpools,
                                pendingScrollSpoolId = pendingScrollSpoolId,
                                onPendingScrollConsumed = { pendingScrollSpoolId = null },
                                onSnackbar = { scope.launch { snackbarHostState.showSnackbar(it) } },
                                nfc = nfc,
                                onInfoClick = { infoSpool = it },
                                onEditClick = onNavigateToEdit,
                                writeDialogSpoolId = writeDialogSpoolId,
                                onWriteDialogSpoolIdChanged = { writeDialogSpoolId = it }
                            )
                        }
                        SpoolsViewMode.Boxes -> {
                            BoxGridView(
                                spools = spools,
                                loadState = loadState,
                                repo = repo,
                                settings = viewModel.settingsDataStore,
                                nfc = nfc,
                                nfcEnabled = nfcEnabled,
                                pendingOpenBox = pendingOpenBox,
                                onPendingOpenBoxConsumed = { pendingOpenBox = null },
                                onSnackbar = { scope.launch { snackbarHostState.showSnackbar(it) } },
                                onNavigateToEdit = onNavigateToEdit,
                                statStyle = statStyle
                            )
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
            title = stringResource(R.string.nfc_prompt_scan_title),
            message = stringResource(R.string.nfc_prompt_scan_message),
            onDismiss = { nfc.cancel() }
        )
    }

    // NFC write dialog for spools — shows the prompt, then the write result.
    writeDialogSpoolId?.let { spoolId ->
        val spoolName = spools.firstOrNull { it.id == spoolId }?.displayName ?: stringResource(R.string.spools_fallback_name, spoolId)
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
 *
 * Package-visible so [BoxGridView] can reuse it for box write dialogs.
 */
@Composable
internal fun NfcRippleIndicator(expanding: Boolean, modifier: Modifier = Modifier) {
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
 *
 * Package-visible so [BoxGridView] can reuse it for box write dialogs.
 */
@Composable
internal fun NfcPromptDialog(
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
                    Text(stringResource(R.string.common_cancel))
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
            title = stringResource(R.string.nfc_prompt_write_title),
            message = stringResource(R.string.nfc_prompt_write_message, spoolName),
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
                            stringResource(R.string.nfc_write_success, spoolName),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        is NfcWriteResult.BoxSuccess -> {
            // A box success arriving while writing a spool — dismiss it
            LaunchedEffect(Unit) { onDismiss() }
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
                        Text(stringResource(R.string.nfc_write_error_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                            Spacer(Modifier.size(8.dp))
                            Button(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
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
