package me.ghost.ffui.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import me.ghost.ffui.BuildConfig
import me.ghost.ffui.data.StartupReconnect
import me.ghost.ffui.ui.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val reconnectMode by viewModel.settingsDataStore.startupReconnect.collectAsState(initial = StartupReconnect.OFF)
    val hideSerials by viewModel.settingsDataStore.hideSerials.collectAsState(initial = false)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Startup section ──
            item {
                Text(
                    "Startup",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .selectableGroup()
                    ) {
                        Text(
                            "Reconnect on startup",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Choose which printers to reconnect when the app opens",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))

                        ReconnectOption(
                            label = "All previously connected printers",
                            selected = reconnectMode == StartupReconnect.ALL,
                            onClick = { scope.launch { viewModel.settingsDataStore.setStartupReconnect(StartupReconnect.ALL) } }
                        )
                        ReconnectOption(
                            label = "Last active printer only",
                            selected = reconnectMode == StartupReconnect.LAST_ACTIVE,
                            onClick = { scope.launch { viewModel.settingsDataStore.setStartupReconnect(StartupReconnect.LAST_ACTIVE) } }
                        )
                        ReconnectOption(
                            label = "Off (manual connect)",
                            selected = reconnectMode == StartupReconnect.OFF,
                            onClick = { scope.launch { viewModel.settingsDataStore.setStartupReconnect(StartupReconnect.OFF) } }
                        )
                    }
                }
            }

            // ── Privacy section ──
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Privacy",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hide serial numbers", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Mask printer serials throughout the app (for screenshots / recordings)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = hideSerials,
                            onCheckedChange = { scope.launch { viewModel.settingsDataStore.setHideSerials(it) } }
                        )
                    }
                }
            }

            // ── About section ──
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "About",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        InfoRow("FlashForgeUI", "v${BuildConfig.VERSION_NAME}")

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Text(
                            "Developer",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GithubProfileRow(
                            handle = "GhostTypes",
                            subtitle = "Developer",
                            avatarUrl = "https://github.com/GhostTypes.png",
                            profileUrl = "https://github.com/GhostTypes"
                        )
                        GithubProfileRow(
                            handle = "Parallel-7",
                            subtitle = "Organization",
                            avatarUrl = "https://github.com/Parallel-7.png",
                            profileUrl = "https://github.com/Parallel-7"
                        )
                    }
                }
            }
        }
    }
}

/**
 * A tappable GitHub identity row (avatar + handle + subtitle) that opens [profileUrl] in the
 * browser. Avatars load straight from `github.com/<handle>.png`, so no images are bundled.
 */
@Composable
private fun GithubProfileRow(
    handle: String,
    subtitle: String,
    avatarUrl: String,
    profileUrl: String
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, profileUrl.toUri()))
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(avatarUrl).crossfade(true).build(),
            contentDescription = "$handle avatar",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                handle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "Open $handle on GitHub",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ReconnectOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
