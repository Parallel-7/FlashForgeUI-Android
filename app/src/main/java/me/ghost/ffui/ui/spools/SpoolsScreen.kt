package me.ghost.ffui.ui.spools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ghost.ffui.api.SpoolmanSpool
import me.ghost.ffui.data.SpoolmanLoadState
import me.ghost.ffui.ui.MainViewModel

/** Sort options available in the Spools screen dropdown. */
enum class SpoolSortOption(val label: String, val sortKey: String?) {
    Remaining("Remaining", "remaining_weight:asc"),
    Material("Material", "filament.material:asc"),
    Vendor("Vendor", "filament.vendor.name:asc"),
    RecentlyUsed("Recently used", "last_used:desc")
}

/**
 * The Spools tab — a 2-column grid of [SpoolCard]s backed by the user's Spoolman server.
 * Handles search, sort, show-archived, and the NotConfigured/Error/Empty states.
 *
 * @param viewModel The app's [MainViewModel].
 * @param onNavigateToEdit Navigate to the spool edit screen with the given spool ID.
 * @param onNavigateToSettings Navigate to the Settings screen (from the NotConfigured empty state).
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    var query by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SpoolSortOption.Remaining) }
    var showArchived by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var infoSpool by remember { mutableStateOf<SpoolmanSpool?>(null) }

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

    // Client-side search filter
    val filteredSpools = remember(spools, query) {
        if (query.isBlank()) spools
        else spools.filter { spool ->
            val q = query.lowercase()
            spool.displayName.lowercase().contains(q) ||
                spool.filament.material?.lowercase()?.contains(q) == true ||
                spool.filament.vendor?.name?.lowercase()?.contains(q) == true ||
                spool.location?.lowercase()?.contains(q) == true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spools") },
                actions = {
                    // Refresh
                    IconButton(onClick = {
                        scope.launch { repo.refresh(allowArchived = showArchived, sort = sortOption.sortKey) }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
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

                // Sort + archive row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
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
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(filteredSpools, key = { it.id }) { spool ->
                                SpoolCard(
                                    spool = spool,
                                    onInfoClick = { infoSpool = spool },
                                    onEditClick = { onNavigateToEdit(spool.id) }
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
            onEditClick = { onNavigateToEdit(spool.id) }
        )
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
