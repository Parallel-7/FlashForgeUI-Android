package me.ghost.ffui.ui.spools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ghost.ffui.R
import me.ghost.ffui.api.SpoolPatchBody
import me.ghost.ffui.ui.MainViewModel
import me.ghost.ffui.ui.components.SpoolDisc
import me.ghost.ffui.ui.components.SectionHeader
import kotlin.math.roundToInt

/**
 * Edit screen for a single spool. Sections:
 * - Adjust usage (deduct weight or set remaining)
 * - Edit details (location, lot, comment)
 * - Archive / unarchive
 *
 * @param spoolId The ID of the spool to edit.
 * @param viewModel The app's [MainViewModel].
 * @param onBack Navigate back (called after save or archive).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpoolEditScreen(
    spoolId: Int,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val repo = viewModel.spoolmanRepository
    val spools by repo.spools.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val spool = spools.find { it.id == spoolId }
    var saving by remember { mutableStateOf(false) }

    // Usage fields
    var useGrams by remember { mutableStateOf("") }
    var setRemainingGrams by remember { mutableStateOf("") }

    // Detail fields — keyed on spool id so they (re)seed once the spools list resolves. Without
    // the key, a screen composed before the list loads (process-death restore, cold repo) stays
    // blank forever even after `spool` resolves.
    var location by remember(spool?.id) { mutableStateOf(spool?.location ?: "") }
    var lotNr by remember(spool?.id) { mutableStateOf(spool?.lot_nr ?: "") }
    var comment by remember(spool?.id) { mutableStateOf(spool?.comment ?: "") }

    // Track if detail fields were modified
    val detailsChanged = spool != null && (
        location != (spool.location ?: "") ||
            lotNr != (spool.lot_nr ?: "") ||
            comment != (spool.comment ?: "")
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(spool?.displayName ?: stringResource(R.string.spools_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (spool == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.spools_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBack) { Text(stringResource(R.string.spools_go_back)) }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Spool header
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpoolDisc(
                        colorHex = spool.filament.color_hex,
                        size = 56.dp
                    )
                    Column {
                        Text(
                            spool.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.spools_edit_remaining, spool.remaining_weight?.roundToInt() ?: "—"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.spools_edit_used, spool.used_weight?.roundToInt() ?: "—"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Adjust usage section ──
            SectionHeader(stringResource(R.string.spools_edit_section_usage), emphasized = true)

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = useGrams,
                        onValueChange = { useGrams = it },
                        label = { Text(stringResource(R.string.spools_edit_deduct_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            val grams = useGrams.toFloatOrNull()
                            if (grams != null && grams > 0f) {
                                saving = true
                                scope.launch {
                                    val result = repo.useWeight(spoolId, grams)
                                    saving = false
                                    if (result.isSuccess) {
                                        useGrams = ""
                                        snackbarHostState.showSnackbar(context.getString(R.string.spools_edit_usage_saved))
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.common_failed, result.exceptionOrNull()?.message ?: ""))
                                    }
                                }
                            }
                        },
                        enabled = !saving && useGrams.toFloatOrNull()?.let { it > 0f } == true,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.spools_edit_deduct_button))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    OutlinedTextField(
                        value = setRemainingGrams,
                        onValueChange = { setRemainingGrams = it },
                        label = { Text(stringResource(R.string.spools_edit_set_remaining_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            val grams = setRemainingGrams.toFloatOrNull()
                            if (grams != null && grams >= 0f) {
                                saving = true
                                scope.launch {
                                    val result = repo.patchSpool(spoolId, SpoolPatchBody(remaining_weight = grams))
                                    saving = false
                                    if (result.isSuccess) {
                                        setRemainingGrams = ""
                                        snackbarHostState.showSnackbar(context.getString(R.string.spools_edit_remaining_saved))
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.common_failed, result.exceptionOrNull()?.message ?: ""))
                                    }
                                }
                            }
                        },
                        enabled = !saving && setRemainingGrams.toFloatOrNull()?.let { it >= 0f } == true,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.spools_edit_set_remaining_button))
                    }
                }
            }

            // ── Details section ──
            SectionHeader(stringResource(R.string.spools_details), emphasized = true)

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text(stringResource(R.string.spools_edit_location)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = lotNr,
                        onValueChange = { lotNr = it },
                        label = { Text(stringResource(R.string.spools_edit_lot)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text(stringResource(R.string.spools_edit_comment)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            saving = true
                            scope.launch {
                                val result = repo.patchSpool(
                                    spoolId,
                                    SpoolPatchBody(
                                        location = location.ifBlank { null },
                                        lot_nr = lotNr.ifBlank { null },
                                        comment = comment.ifBlank { null }
                                    )
                                )
                                saving = false
                                if (result.isSuccess) {
                                    snackbarHostState.showSnackbar(context.getString(R.string.spools_edit_details_saved))
                                } else {
                                    snackbarHostState.showSnackbar(context.getString(R.string.common_failed, result.exceptionOrNull()?.message ?: ""))
                                }
                            }
                        },
                        enabled = !saving && detailsChanged,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.spools_edit_save_details))
                    }
                }
            }

            // ── Archive section ──
            SectionHeader(stringResource(R.string.spools_edit_section_archive), emphasized = true)

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (spool.archived) {
                        Text(
                            stringResource(R.string.spools_edit_archived_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val result = repo.setArchived(spoolId, false)
                                    if (result.isSuccess) {
                                        snackbarHostState.showSnackbar(context.getString(R.string.spools_edit_unarchived_saved))
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.common_failed, result.exceptionOrNull()?.message ?: ""))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.spools_edit_unarchive))
                        }
                    } else {
                        Text(
                            stringResource(R.string.spools_edit_archive_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        var confirmArchive by remember { mutableStateOf(false) }
                        if (!confirmArchive) {
                            OutlinedButton(
                                onClick = { confirmArchive = true },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.spools_edit_archive_spool))
                            }
                        } else {
                            Text(
                                stringResource(R.string.spools_edit_archive_confirm),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { confirmArchive = false },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val result = repo.setArchived(spoolId, true)
                                            if (result.isSuccess) {
                                                onBack()
                                            } else {
                                                confirmArchive = false
                                                snackbarHostState.showSnackbar(context.getString(R.string.common_failed, result.exceptionOrNull()?.message ?: ""))
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.spools_edit_archive_action))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

