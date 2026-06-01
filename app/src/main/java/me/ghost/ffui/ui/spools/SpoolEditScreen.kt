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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ghost.ffui.api.SpoolPatchBody
import me.ghost.ffui.ui.MainViewModel
import me.ghost.ffui.ui.components.SpoolDisc
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

    val spool = spools.find { it.id == spoolId }
    var saving by remember { mutableStateOf(false) }

    // Usage fields
    var useGrams by remember { mutableStateOf("") }
    var setRemainingGrams by remember { mutableStateOf("") }

    // Detail fields
    var location by remember { mutableStateOf(spool?.location ?: "") }
    var lotNr by remember { mutableStateOf(spool?.lot_nr ?: "") }
    var comment by remember { mutableStateOf(spool?.comment ?: "") }

    // Track if detail fields were modified
    val detailsChanged = spool != null && (
        location != (spool.location ?: "") ||
            lotNr != (spool.lot_nr ?: "") ||
            comment != (spool.comment ?: "")
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(spool?.displayName ?: "Edit Spool") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                Text("Spool not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBack) { Text("Go back") }
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
                            "Remaining: ${spool.remaining_weight?.roundToInt() ?: "—"} g",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Used: ${spool.used_weight?.roundToInt() ?: "—"} g",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Adjust usage section ──
            SectionHeader("Adjust usage")

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
                        label = { Text("Deduct used (g)") },
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
                                        snackbarHostState.showSnackbar("Usage updated")
                                    } else {
                                        snackbarHostState.showSnackbar("Failed: ${result.exceptionOrNull()?.message}")
                                    }
                                }
                            }
                        },
                        enabled = !saving && useGrams.toFloatOrNull()?.let { it > 0f } == true,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Deduct usage")
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    OutlinedTextField(
                        value = setRemainingGrams,
                        onValueChange = { setRemainingGrams = it },
                        label = { Text("Set remaining (g)") },
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
                                        snackbarHostState.showSnackbar("Remaining updated")
                                    } else {
                                        snackbarHostState.showSnackbar("Failed: ${result.exceptionOrNull()?.message}")
                                    }
                                }
                            }
                        },
                        enabled = !saving && setRemainingGrams.toFloatOrNull()?.let { it >= 0f } == true,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Set remaining")
                    }
                }
            }

            // ── Details section ──
            SectionHeader("Details")

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
                        label = { Text("Location") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = lotNr,
                        onValueChange = { lotNr = it },
                        label = { Text("Lot number") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("Comment") },
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
                                    snackbarHostState.showSnackbar("Details saved")
                                } else {
                                    snackbarHostState.showSnackbar("Failed: ${result.exceptionOrNull()?.message}")
                                }
                            }
                        },
                        enabled = !saving && detailsChanged,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save details")
                    }
                }
            }

            // ── Archive section ──
            SectionHeader("Archive")

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
                            "This spool is archived.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val result = repo.setArchived(spoolId, false)
                                    if (result.isSuccess) {
                                        snackbarHostState.showSnackbar("Spool unarchived")
                                    } else {
                                        snackbarHostState.showSnackbar("Failed: ${result.exceptionOrNull()?.message}")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Unarchive")
                        }
                    } else {
                        Text(
                            "Archived spools are hidden from the default view.",
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
                                Text("Archive spool")
                            }
                        } else {
                            Text(
                                "Are you sure?",
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
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val result = repo.setArchived(spoolId, true)
                                            if (result.isSuccess) {
                                                onBack()
                                            } else {
                                                confirmArchive = false
                                                snackbarHostState.showSnackbar("Failed: ${result.exceptionOrNull()?.message}")
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Archive")
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

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
}
