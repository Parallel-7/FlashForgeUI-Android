package me.ghost.ffui.ui.spools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.ghost.ffui.api.SpoolPatchBody
import me.ghost.ffui.data.SpoolBox
import me.ghost.ffui.data.SpoolmanRepository
import me.ghost.ffui.data.SettingsDataStore
import me.ghost.ffui.ui.components.BoxGlyph
import me.ghost.ffui.ui.components.SpoolDisc
import me.ghost.ffui.data.SpoolStatStyle
import kotlin.math.roundToInt

/**
 * Full-detail dialog for a box. Shows a header with the [BoxGlyph], location name, and roll count,
 * followed by a tappable list of member spools. Each row opens that spool's info dialog.
 *
 * Footer actions: **Rename box** (bulk-patches `location` on all member spools) and **Close**.
 *
 * ### Rename flow
 * 1. Prompt for the new name.
 * 2. If a box with the new name already exists → confirm merge.
 * 3. Bulk-patch `location` on all member spools via [SpoolmanRepository.patchSpool].
 * 4. Migrate local tagged-state: `unmarkBoxTagged(old)` + `markBoxTagged(new, ts)`.
 * 5. Auto-open the write dialog for the new name (via [onRequestWrite]).
 * 6. Partial-failure handling: surface "Renamed X of N rolls — retry?".
 *
 * @param box The box to display.
 * @param allBoxes All currently-derived boxes (used for the merge-exists check during rename).
 * @param repo The Spoolman repository for bulk-patching spool locations.
 * @param settings Settings store for migrating tagged-box state.
 * @param onDismiss Closes the dialog.
 * @param onSpoolInfo Opens the spool info dialog for the given spool ID.
 * @param onRequestWrite Requests the NFC write flow for a box at the given location.
 * @param statStyle Which usage metric to show for each spool row.
 */
@Composable
fun BoxDetailDialog(
    box: SpoolBox,
    allBoxes: List<SpoolBox>,
    repo: SpoolmanRepository,
    settings: SettingsDataStore,
    onDismiss: () -> Unit,
    onSpoolInfo: (spoolId: Int) -> Unit,
    onRequestWrite: (location: String) -> Unit,
    statStyle: SpoolStatStyle = SpoolStatStyle.PERCENT
) {
    val scope = rememberCoroutineScope()
    val colorHexes = box.spools.map { it.filament.color_hex }

    // Rename state
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInProgress by remember { mutableStateOf(false) }

    // Partial-failure result
    var renameResult by remember { mutableStateOf<RenameResult?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header: glyph + name + count
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BoxGlyph(colorHexes = colorHexes, size = 56.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = box.location,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${box.spools.size} rolls",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Roll list
                box.spools.forEach { spool ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SpoolDisc(
                            colorHex = spool.filament.color_hex,
                            size = 32.dp,
                            dimmed = spool.archived
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = spool.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            val statText = when (statStyle) {
                                SpoolStatStyle.PERCENT -> spool.progress?.let { p ->
                                    "${(p * 100).roundToInt()}% left"
                                }
                                SpoolStatStyle.WEIGHT -> spool.remaining_weight?.let {
                                    "${it.roundToInt()} g left"
                                }
                            }
                            if (statText != null) {
                                Text(
                                    text = statText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(onClick = { onSpoolInfo(spool.id) }) {
                            Text("View")
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Footer: Rename + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showRenameDialog = true }) {
                        Text("Rename")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }

    // Rename dialog
    if (showRenameDialog) {
        RenameBoxDialog(
            currentName = box.location,
            allBoxes = allBoxes,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                renameInProgress = true
                showRenameDialog = false
                scope.launch {
                    val result = performRename(box, newName, repo, settings)
                    renameResult = result
                    renameInProgress = false
                }
            }
        )
    }

    // Progress indicator
    if (renameInProgress) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Renaming…") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        )
    }

    // Partial-failure result
    renameResult?.let { result ->
        AlertDialog(
            onDismissRequest = {
                renameResult = null
                val successName = (result as? RenameResult.FullSuccess)?.newName
                    ?: (result as? RenameResult.RetrySuccess)?.newName
                if (successName != null) {
                    onRequestWrite(successName)
                    onDismiss()
                }
            },
            confirmButton = {
                when (result) {
                    is RenameResult.FullSuccess, is RenameResult.RetrySuccess -> {
                        val successName = (result as? RenameResult.FullSuccess)?.newName
                            ?: (result as? RenameResult.RetrySuccess)?.newName
                            ?: ""
                        TextButton(onClick = {
                            renameResult = null
                            onRequestWrite(successName)
                            onDismiss()
                        }) { Text("OK") }
                    }
                    is RenameResult.PartialFailure -> {
                        TextButton(onClick = {
                            renameResult = null
                            // Retry the failed ones
                            scope.launch {
                                renameInProgress = true
                                val retryResult = retryFailed(result.failedIds, result.newName, repo)
                                renameInProgress = false
                                renameResult = retryResult
                            }
                        }) { Text("Retry") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            renameResult = null
                            onRequestWrite(result.newName)
                            onDismiss()
                        }) { Text("Skip") }
                    }
                }
            },
            title = {
                Text(
                    when (result) {
                        is RenameResult.FullSuccess -> "Box renamed"
                        is RenameResult.RetrySuccess -> "All rolls renamed"
                        is RenameResult.PartialFailure -> "Partial rename"
                    }
                )
            },
            text = {
                Text(
                    when (result) {
                        is RenameResult.FullSuccess -> "\"${result.oldName}\" is now \"${result.newName}\""
                        is RenameResult.RetrySuccess -> "All rolls renamed to \"${result.newName}\""
                        is RenameResult.PartialFailure -> {
                            val success = result.successCount
                            val total = result.successCount + result.failedIds.size
                            "Renamed $success of $total rolls — some couldn't be updated."
                        }
                    }
                )
            }
        )
    }
}

/** Result of a rename operation. */
private sealed class RenameResult {
    /** All spools were patched successfully on initial rename. */
    data class FullSuccess(val oldName: String, val newName: String) : RenameResult()

    /** All spools were patched successfully on retry. */
    data class RetrySuccess(val newName: String) : RenameResult()

    /** Some spools failed to patch. [failedIds] lists the ones that need retrying. */
    data class PartialFailure(
        val newName: String,
        val successCount: Int,
        val failedIds: List<Int>
    ) : RenameResult()
}

/**
 * The rename dialog: prompts for a new name, confirms on merge if needed.
 */
@Composable
private fun RenameBoxDialog(
    currentName: String,
    allBoxes: List<SpoolBox>,
    onDismiss: () -> Unit,
    onConfirm: (newName: String) -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    var showMergeConfirm by remember { mutableStateOf(false) }
    var pendingName by remember { mutableStateOf("") }

    if (showMergeConfirm) {
        AlertDialog(
            onDismissRequest = { showMergeConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    showMergeConfirm = false
                    onConfirm(pendingName)
                }) { Text("Merge") }
            },
            dismissButton = {
                TextButton(onClick = { showMergeConfirm = false }) { Text("Cancel") }
            },
            title = { Text("Merge boxes?") },
            text = { Text("\"$pendingName\" already exists — spools will be merged into it.") }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newName.trim()
                        if (trimmed.isBlank() || trimmed == currentName) return@TextButton
                        // Check if a box with this name already exists (case-insensitive, trimmed)
                        val exists = allBoxes.any {
                            it.location.equals(trimmed, ignoreCase = true)
                        }
                        if (exists) {
                            pendingName = trimmed
                            showMergeConfirm = true
                        } else {
                            onConfirm(trimmed)
                        }
                    },
                    enabled = newName.trim().isNotBlank() && newName.trim() != currentName
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
            title = { Text("Rename box") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New name") },
                    singleLine = true
                )
            }
        )
    }
}

/**
 * Performs the rename: bulk-patches `location` on all member spools, migrates tagged state.
 * Returns a [RenameResult] indicating full success or partial failure.
 */
private suspend fun performRename(
    box: SpoolBox,
    newName: String,
    repo: SpoolmanRepository,
    settings: SettingsDataStore
): RenameResult {
    val oldName = box.location
    val taggedTimestamp = run {
        // Preserve the existing NFC tagged timestamp for the old location
        runCatching {
            settings.nfcTaggedBoxes.first()[oldName.trim()]
        }.getOrNull() ?: java.time.Instant.now().toString()
    }

    var successCount = 0
    val failedIds = mutableListOf<Int>()

    for (spool in box.spools) {
        val result = repo.patchSpool(spool.id, SpoolPatchBody(location = newName))
        if (result.isSuccess) {
            successCount++
        } else {
            failedIds.add(spool.id)
        }
    }

    // Migrate tagged state if at least one succeeded
    if (successCount > 0) {
        settings.unmarkBoxTagged(oldName)
        settings.markBoxTagged(newName, taggedTimestamp)
    }

    // Refresh to pick up the new locations
    repo.refresh()

    return if (failedIds.isEmpty()) {
        RenameResult.FullSuccess(oldName, newName)
    } else {
        RenameResult.PartialFailure(newName, successCount, failedIds)
    }
}

/**
 * Retries patching the location on only the failed spool IDs.
 */
private suspend fun retryFailed(
    failedIds: List<Int>,
    newName: String,
    repo: SpoolmanRepository
): RenameResult {
    var successCount = 0
    val stillFailing = mutableListOf<Int>()

    for (id in failedIds) {
        val result = repo.patchSpool(id, SpoolPatchBody(location = newName))
        if (result.isSuccess) {
            successCount++
        } else {
            stillFailing.add(id)
        }
    }

    repo.refresh()

    return if (stillFailing.isEmpty()) {
        RenameResult.RetrySuccess(newName)
    } else {
        RenameResult.PartialFailure(newName, successCount, stillFailing)
    }
}
