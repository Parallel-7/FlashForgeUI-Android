package me.ghost.ffui.ui.spools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ghost.ffui.R
import me.ghost.ffui.api.SpoolmanSpool
import me.ghost.ffui.data.SpoolStatStyle
import me.ghost.ffui.nfc.NfcManager

/**
 * The extracted spool grid body — the inner content of the "Spools" view tab.
 * Contains the [LazyVerticalGrid] of [SpoolCard]s and the pending-scroll consumer
 * for smart NFC scan routing.
 *
 * @param filteredSpools The already-filtered spool list to render.
 * @param spools The unfiltered spool list (for NfcWriteDialog name lookup).
 * @param gridState Lazy grid state for scroll-to-item.
 * @param statStyle Which usage metric to show on cards.
 * @param nfcEnabled Whether NFC is enabled.
 * @param taggedSpools Map of spool IDs to tagged timestamps.
 * @param pendingScrollSpoolId A spool ID to scroll to + flash, set by smart scan routing.
 *   Consumed once the card is scrolled into view.
 * @param onPendingScrollConsumed Clears the pending scroll value.
 * @param onSnackbar Callback to show a snackbar message.
 * @param nfc NFC manager for write flow.
 * @param onInfoClick Opens the spool info dialog.
 * @param onEditClick Navigates to the spool edit screen.
 * @param writeDialogSpoolId Currently-open write dialog spool ID (nullable).
 * @param onWriteDialogSpoolIdChanged Updates the write dialog spool ID state.
 */
@Composable
fun SpoolListView(
    filteredSpools: List<SpoolmanSpool>,
    spools: List<SpoolmanSpool>,
    gridState: LazyGridState,
    statStyle: SpoolStatStyle,
    nfcEnabled: Boolean,
    taggedSpools: Map<Int, String>,
    pendingScrollSpoolId: Int?,
    onPendingScrollConsumed: () -> Unit,
    onSnackbar: (String) -> Unit,
    nfc: NfcManager,
    onInfoClick: (SpoolmanSpool) -> Unit,
    onEditClick: (spoolId: Int) -> Unit,
    writeDialogSpoolId: Int?,
    onWriteDialogSpoolIdChanged: (Int?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var highlightedSpoolId by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    // Consume the pending scroll target from smart scan routing
    LaunchedEffect(pendingScrollSpoolId, filteredSpools) {
        val target = pendingScrollSpoolId ?: return@LaunchedEffect
        val index = filteredSpools.indexOfFirst { it.id == target }
        if (index >= 0) {
            highlightedSpoolId = target
            scope.launch { gridState.animateScrollToItem(index) }
            onPendingScrollConsumed()
        } else if (filteredSpools.isNotEmpty()) {
            // The spool isn't in the current filtered list
            onPendingScrollConsumed()
            onSnackbar(context.getString(R.string.spools_scan_not_shown, target))
        }
    }

    // Clear the card highlight a moment after a successful scan
    LaunchedEffect(highlightedSpoolId) {
        if (highlightedSpoolId != null) {
            delay(2500)
            highlightedSpoolId = null
        }
    }

    if (filteredSpools.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (spools.isEmpty()) stringResource(R.string.spools_empty) else stringResource(R.string.spools_no_match),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
    } else {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredSpools, key = { it.id }) { spool ->
                SpoolCard(
                    spool = spool,
                    onInfoClick = { onInfoClick(spool) },
                    onEditClick = { onEditClick(spool.id) },
                    statStyle = statStyle,
                    nfcEnabled = nfcEnabled,
                    tagged = taggedSpools.containsKey(spool.id),
                    highlighted = highlightedSpoolId == spool.id,
                    onWriteClick = {
                        nfc.beginWrite(spool.id)
                        onWriteDialogSpoolIdChanged(spool.id)
                    }
                )
            }
        }
    }
}
