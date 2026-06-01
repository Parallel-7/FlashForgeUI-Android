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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import me.ghost.ffui.BuildConfig
import me.ghost.ffui.data.SettingsDataStore
import me.ghost.ffui.data.StartupReconnect
import me.ghost.ffui.service.BatteryOptimization
import me.ghost.ffui.ui.MainViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reconnectMode by viewModel.settingsDataStore.startupReconnect.collectAsState(initial = StartupReconnect.OFF)
    val hideSerials by viewModel.settingsDataStore.hideSerials.collectAsState(initial = false)
    val backgroundEnabled by viewModel.settingsDataStore.backgroundMonitoringEnabled.collectAsState(initial = false)
    val throttleEnabled by viewModel.settingsDataStore.backgroundThrottleEnabled.collectAsState(initial = false)
    val throttleSeconds by viewModel.settingsDataStore.backgroundThrottleSeconds.collectAsState(
        initial = SettingsDataStore.THROTTLE_DEFAULT_SECONDS
    )

    // Battery-optimization exemption status, refreshed each time the screen resumes (the system
    // grant dialog is a separate activity, so we re-check on return rather than reacting to a flow).
    var batteryIgnored by remember { mutableStateOf(BatteryOptimization.isIgnored(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryIgnored = BatteryOptimization.isIgnored(context) }

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
                            "Auto Reconnect",
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
                            label = "Off",
                            selected = reconnectMode == StartupReconnect.OFF,
                            onClick = { scope.launch { viewModel.settingsDataStore.setStartupReconnect(StartupReconnect.OFF) } }
                        )
                    }
                }
            }

            // ── Background section ──
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Background",
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
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        // Master toggle: keep monitoring (and alerts) running after the app closes.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Keep monitoring in background", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Stay connected for alerts even when the app is closed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = backgroundEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch { viewModel.settingsDataStore.setBackgroundMonitoringEnabled(enabled) }
                                    // Opting into background is the moment to ask for the exemption.
                                    if (enabled && !batteryIgnored) {
                                        context.startActivity(BatteryOptimization.requestIntent(context))
                                    }
                                }
                            )
                        }

                        // Nested controls — only relevant while background monitoring is on.
                        if (backgroundEnabled) {
                            // Persistent nudge if the OS can still freeze us (user declined / revoked).
                            if (!batteryIgnored) {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Allow background activity", style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "Recommended — without it the system may pause monitoring",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    TextButton(onClick = { context.startActivity(BatteryOptimization.requestIntent(context)) }) {
                                        Text("Allow")
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Slow updates in background", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Check less often to save battery",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = throttleEnabled,
                                    onCheckedChange = {
                                        scope.launch { viewModel.settingsDataStore.setBackgroundThrottleEnabled(it) }
                                    }
                                )
                            }

                            if (throttleEnabled) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Update interval",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text("${throttleSeconds}s", style = MaterialTheme.typography.bodyMedium)
                                }
                                // Live-drag local value; persist on release so DataStore isn't spammed.
                                var sliderValue by remember(throttleSeconds) { mutableFloatStateOf(throttleSeconds.toFloat()) }
                                Slider(
                                    value = sliderValue,
                                    onValueChange = { sliderValue = it },
                                    onValueChangeFinished = {
                                        scope.launch {
                                            viewModel.settingsDataStore.setBackgroundThrottleSeconds(sliderValue.roundToInt())
                                        }
                                    },
                                    valueRange = SettingsDataStore.THROTTLE_MIN_SECONDS.toFloat()..
                                        SettingsDataStore.THROTTLE_MAX_SECONDS.toFloat(),
                                    steps = SettingsDataStore.THROTTLE_MAX_SECONDS - SettingsDataStore.THROTTLE_MIN_SECONDS - 1
                                )
                            }
                        }
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
                                "Mask printer serial numbers",
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

            // ── Spoolman section ──
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Spoolman",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                val spoolmanEnabled by viewModel.settingsDataStore.spoolmanEnabled.collectAsState(initial = false)
                val spoolmanBaseUrl by viewModel.settingsDataStore.spoolmanBaseUrl.collectAsState(initial = "")

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        // Master toggle
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Enable Spoolman", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Track filament spools from your Spoolman server",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = spoolmanEnabled,
                                onCheckedChange = { scope.launch { viewModel.settingsDataStore.setSpoolmanEnabled(it) } }
                            )
                        }

                        // Nested controls — only visible when enabled
                        if (spoolmanEnabled) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(8.dp))

                            var serverUrl by remember(spoolmanBaseUrl) { mutableStateOf(spoolmanBaseUrl) }
                            var testResult by remember { mutableStateOf<Result<Unit>?>(null) }
                            var isTesting by remember { mutableStateOf(false) }

                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it },
                                label = { Text("Server address") },
                                placeholder = { Text("http://192.168.1.50:7912") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(12.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isTesting = true
                                            testResult = null
                                            viewModel.settingsDataStore.setSpoolmanBaseUrl(serverUrl.trim())
                                            testResult = viewModel.spoolmanRepository.testConnection(serverUrl.trim())
                                            isTesting = false
                                        }
                                    },
                                    enabled = !isTesting && serverUrl.isNotBlank()
                                ) {
                                    if (isTesting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text("Test connection")
                                }

                                testResult?.let { result ->
                                    if (result.isSuccess) {
                                        Text(
                                            "✓ Connected",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    } else {
                                        Text(
                                            "✗ Couldn't reach server",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
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
