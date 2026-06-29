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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import me.ghost.ffui.api.SpoolmanSpool
import me.ghost.ffui.data.SpoolmanLoadState
import me.ghost.ffui.data.SpoolmanRepository
import me.ghost.ffui.data.SettingsDataStore
import me.ghost.ffui.data.SpoolStatStyle
import me.ghost.ffui.data.boxesFrom
import me.ghost.ffui.nfc.NfcManager
import me.ghost.ffui.nfc.NfcWriteResult

/**
 * The Boxes view — an adaptive grid of [BoxCard]s grouped by Spoolman `location`.
 *
 * Contains:
 * - Search field (filters by location name)
 * - Tags: All/Tagged/Untagged filter (mirrors the spool view)
 * - A pending-open consumer [LaunchedEffect] that opens the [BoxDetailDialog] when a scanned
 *   box tag resolves to a known box location
 *
 * @param spools The full spool list (boxes are derived client-side via [boxesFrom]).
 * @param loadState Current Spoolman load state.
 * @param repo The Spoolman repository (for the rename flow).
 * @param settings Settings store for NFC tagged-box state.
 * @param nfc NFC manager for box write flow.
 * @param nfcEnabled Whether NFC is enabled (gates write actions).
 * @param pendingOpenBox A box location to auto-open the detail dialog for, set by the
 *   smart scan routing. Consumed once the dialog opens.
 * @param onPendingOpenBoxConsumed Clears the pending-open value after the dialog opens.
 * @param onSnackbar Callback to show a snackbar message.
 * @param onNavigateToEdit Navigates to the spool edit screen for the given spool ID.
 * @param statStyle Which usage metric to show in spool rows inside the box detail.
 */
@Composable
fun BoxGridView(
    spools: List<SpoolmanSpool>,
    loadState: SpoolmanLoadState,
    repo: SpoolmanRepository,
    settings: SettingsDataStore,
    nfc: NfcManager,
    nfcEnabled: Boolean,
    pendingOpenBox: String?,
    onPendingOpenBoxConsumed: () -> Unit,
    onSnackbar: (String) -> Unit,
    onNavigateToEdit: (spoolId: Int) -> Unit,
    statStyle: SpoolStatStyle
) {
    val taggedBoxes by settings.nfcTaggedBoxes.collectAsStateWithLifecycle(initialValue = emptyMap())
    val writeResult by nfc.writeResult.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var nfcFilter by remember { mutableStateOf(NfcFilter.All) }
    var detailBoxLocation by remember { mutableStateOf<String?>(null) }
    var writeDialogLocation by remember { mutableStateOf<String?>(null) }
    var infoSpool by remember { mutableStateOf<SpoolmanSpool?>(null) }

    // Derive boxes from the spool list
    val boxes = remember(spools) { boxesFrom(spools) }

    // The currently-open box detail, derived live from [boxes] (keyed by location) so it stays fresh
    // as spools change — e.g. after editing a roll or a background refresh — and auto-closes if the
    // box disappears (all its spools moved elsewhere).
    val detailBox = remember(boxes, detailBoxLocation) {
        detailBoxLocation?.let { loc -> boxes.firstOrNull { it.location == loc } }
    }

    // Client-side search + NFC-tagged filter
    val filteredBoxes = remember(boxes, query, nfcFilter, taggedBoxes) {
        boxes.filter { box ->
            val matchesQuery = query.isBlank() || box.location.lowercase().contains(query.lowercase())
            val matchesNfc = when (nfcFilter) {
                NfcFilter.All -> true
                NfcFilter.Tagged -> isBoxTagged(taggedBoxes, box.location)
                NfcFilter.Untagged -> !isBoxTagged(taggedBoxes, box.location)
            }
            matchesQuery && matchesNfc
        }
    }

    // Consume the pending-open target from smart scan routing
    LaunchedEffect(pendingOpenBox, boxes) {
        val target = pendingOpenBox ?: return@LaunchedEffect
        val match = boxes.firstOrNull {
            it.location.equals(target.trim(), ignoreCase = true)
        }
        if (match != null) {
            detailBoxLocation = match.location
            onPendingOpenBoxConsumed()
        } else if (boxes.isNotEmpty()) {
            // Boxes are loaded but no match
            onPendingOpenBoxConsumed()
            onSnackbar("No box named \"$target\"")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search + tag filter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search boxes…") },
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
                keyboardActions = KeyboardActions(onSearch = { /* client-side */ })
            )

            // NFC-tagged filter
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

        // Body
        when (loadState) {
            is SpoolmanLoadState.Loaded -> {
                if (filteredBoxes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (boxes.isEmpty()) "No boxes found. Assign locations to spools."
                            else "No boxes match your search.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredBoxes, key = { it.location }) { box ->
                            BoxCard(
                                box = box,
                                nfcEnabled = nfcEnabled,
                                tagged = isBoxTagged(taggedBoxes, box.location),
                                onDetailsClick = { detailBoxLocation = box.location },
                                onWriteClick = {
                                    nfc.beginWriteBox(box.location)
                                    writeDialogLocation = box.location
                                }
                            )
                        }
                    }
                }
            }
            is SpoolmanLoadState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading boxes…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                // Handled by SpoolsScreen shell
            }
        }
    }

    // Box detail dialog
    detailBox?.let { box ->
        BoxDetailDialog(
            box = box,
            allBoxes = boxes,
            repo = repo,
            settings = settings,
            onDismiss = { detailBoxLocation = null },
            onSpoolInfo = { spoolId ->
                val spool = spools.firstOrNull { it.id == spoolId }
                if (spool != null) infoSpool = spool
            },
            onRequestWrite = { location ->
                nfc.beginWriteBox(location)
                writeDialogLocation = location
            },
            statStyle = statStyle
        )
    }

    // Spool info dialog (opened from a box detail roll row). Editing navigates to the spool edit
    // screen; SpoolInfoDialog dismisses itself first.
    infoSpool?.let { spool ->
        SpoolInfoDialog(
            spool = spool,
            onDismiss = { infoSpool = null },
            onEditClick = { onNavigateToEdit(spool.id) },
            taggedAt = null
        )
    }

    // NFC write dialog for box
    writeDialogLocation?.let { location ->
        NfcBoxWriteDialog(
            location = location,
            result = writeResult,
            onRetry = { nfc.beginWriteBox(location) },
            onDismiss = {
                nfc.cancel()
                nfc.consumeWriteResult()
                writeDialogLocation = null
            }
        )
    }
}

/**
 * Whether [location] is recorded as tagged in [taggedBoxes]. Matches trimmed + case-insensitively
 * so the card badge and the Tags filter agree even if the stored key differs in case/whitespace
 * from the derived box location.
 */
internal fun isBoxTagged(taggedBoxes: Map<String, String>, location: String): Boolean =
    taggedBoxes.keys.any { it.trim().equals(location.trim(), ignoreCase = true) }

/**
 * The NFC write flow dialog for a box. While [result] is null it shows the "approach a tag" prompt;
 * on [NfcWriteResult.BoxSuccess] it shows a confirmation and auto-dismisses; on
 * [NfcWriteResult.Error] it shows the error with Retry/Close.
 */
@Composable
internal fun NfcBoxWriteDialog(
    location: String,
    result: NfcWriteResult?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    when (result) {
        null -> NfcPromptDialog(
            expanding = false,
            title = "Write to a tag",
            message = "Hold your phone to a tag to program it for \"$location\".",
            onDismiss = onDismiss
        )
        is NfcWriteResult.BoxSuccess -> {
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
                            "Tag written for \"$location\"",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        is NfcWriteResult.Success -> {
            // A spool success arriving while writing a box — dismiss it
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
                        Text("Couldn't write tag", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            result.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
