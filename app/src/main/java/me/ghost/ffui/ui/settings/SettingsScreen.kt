package me.ghost.ffui.ui.settings

import android.content.Intent
import android.nfc.NfcAdapter
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ghost.ffui.BuildConfig
import me.ghost.ffui.R
import me.ghost.ffui.data.SettingsDataStore
import me.ghost.ffui.data.SpoolStatStyle
import me.ghost.ffui.data.StartupReconnect
import me.ghost.ffui.service.BatteryOptimization
import me.ghost.ffui.ui.MainViewModel
import me.ghost.ffui.ui.components.InfoRow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reconnectMode by viewModel.settingsDataStore.startupReconnect.collectAsStateWithLifecycle(initialValue = StartupReconnect.OFF)
    val hideSerials by viewModel.settingsDataStore.hideSerials.collectAsStateWithLifecycle(initialValue = false)
    val backgroundEnabled by viewModel.settingsDataStore.backgroundMonitoringEnabled.collectAsStateWithLifecycle(initialValue = false)
    val throttleEnabled by viewModel.settingsDataStore.backgroundThrottleEnabled.collectAsStateWithLifecycle(initialValue = false)
    val throttleSeconds by viewModel.settingsDataStore.backgroundThrottleSeconds.collectAsStateWithLifecycle(
        initialValue = SettingsDataStore.THROTTLE_DEFAULT_SECONDS
    )

    // Battery-optimization exemption status, refreshed each time the screen resumes (the system
    // grant dialog is a separate activity, so we re-check on return rather than reacting to a flow).
    var batteryIgnored by remember { mutableStateOf(BatteryOptimization.isIgnored(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryIgnored = BatteryOptimization.isIgnored(context) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Startup section ──
            item {
                Text(
                    stringResource(R.string.settings_section_startup),
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
                            stringResource(R.string.settings_auto_reconnect_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_auto_reconnect_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))

                        ReconnectOption(
                            label = stringResource(R.string.settings_reconnect_all),
                            selected = reconnectMode == StartupReconnect.ALL,
                            onClick = { scope.launch { viewModel.settingsDataStore.setStartupReconnect(StartupReconnect.ALL) } }
                        )
                        ReconnectOption(
                            label = stringResource(R.string.settings_reconnect_last_active),
                            selected = reconnectMode == StartupReconnect.LAST_ACTIVE,
                            onClick = { scope.launch { viewModel.settingsDataStore.setStartupReconnect(StartupReconnect.LAST_ACTIVE) } }
                        )
                        ReconnectOption(
                            label = stringResource(R.string.common_off),
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
                    stringResource(R.string.settings_section_background),
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
                                Text(stringResource(R.string.settings_background_monitoring_title), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.settings_background_monitoring_subtitle),
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
                                        Text(stringResource(R.string.settings_allow_background_title), style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            stringResource(R.string.settings_allow_background_subtitle),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    TextButton(onClick = { context.startActivity(BatteryOptimization.requestIntent(context)) }) {
                                        Text(stringResource(R.string.settings_allow))
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.settings_throttle_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.settings_throttle_subtitle),
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
                                        stringResource(R.string.settings_throttle_interval),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(stringResource(R.string.common_seconds_format, throttleSeconds), style = MaterialTheme.typography.bodyMedium)
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
                    stringResource(R.string.settings_section_privacy),
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
                            Text(stringResource(R.string.settings_hide_serials_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_hide_serials_subtitle),
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
                    stringResource(R.string.settings_section_spoolman),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                val spoolmanEnabled by viewModel.settingsDataStore.spoolmanEnabled.collectAsStateWithLifecycle(initialValue = false)
                val spoolmanBaseUrl by viewModel.settingsDataStore.spoolmanBaseUrl.collectAsStateWithLifecycle(initialValue = "")

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        // Master toggle
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_spoolman_enable_title), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.settings_spoolman_enable_subtitle),
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
                                label = { Text(stringResource(R.string.settings_spoolman_server_label)) },
                                placeholder = { Text(stringResource(R.string.settings_spoolman_server_placeholder)) },
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
                                            // Persist only on success — a failing URL must not
                                            // become the saved configuration.
                                            testResult = viewModel.spoolmanRepository.testConnection(serverUrl.trim())
                                                .onSuccess { viewModel.settingsDataStore.setSpoolmanBaseUrl(serverUrl.trim()) }
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
                                    Text(stringResource(R.string.settings_spoolman_test_connection))
                                }

                                testResult?.let { result ->
                                    if (result.isSuccess) {
                                        Text(
                                            stringResource(R.string.settings_spoolman_test_connected),
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    } else {
                                        Text(
                                            stringResource(R.string.settings_spoolman_test_failed),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(12.dp))

                            // Which stat the spool cards display.
                            val statStyle by viewModel.settingsDataStore.spoolStatStyle
                                .collectAsStateWithLifecycle(initialValue = SpoolStatStyle.PERCENT)
                            Text(stringResource(R.string.settings_spool_stat_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_spool_stat_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = statStyle == SpoolStatStyle.PERCENT,
                                    onClick = {
                                        scope.launch {
                                            viewModel.settingsDataStore.setSpoolStatStyle(SpoolStatStyle.PERCENT)
                                        }
                                    },
                                    label = { Text(stringResource(R.string.settings_spool_stat_percent)) }
                                )
                                FilterChip(
                                    selected = statStyle == SpoolStatStyle.WEIGHT,
                                    onClick = {
                                        scope.launch {
                                            viewModel.settingsDataStore.setSpoolStatStyle(SpoolStatStyle.WEIGHT)
                                        }
                                    },
                                    label = { Text(stringResource(R.string.settings_spool_stat_weight)) }
                                )
                            }
                        }
                    }
                }
            }

            // ── NFC section ──
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_section_nfc),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                // NFC hardware presence decides whether the toggle is interactive at all.
                val nfcAvailable = remember { NfcAdapter.getDefaultAdapter(context) != null }
                val nfcEnabled by viewModel.settingsDataStore.nfcEnabled.collectAsStateWithLifecycle(initialValue = false)
                val nfcWriteUrl by viewModel.settingsDataStore.nfcWriteUrl.collectAsStateWithLifecycle(initialValue = false)

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        // Master toggle — greyed out on devices with no NFC hardware.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.settings_nfc_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (nfcAvailable) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                                Text(
                                    if (nfcAvailable) stringResource(R.string.settings_nfc_subtitle)
                                    else stringResource(R.string.settings_nfc_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = nfcEnabled && nfcAvailable,
                                enabled = nfcAvailable,
                                onCheckedChange = { scope.launch { viewModel.settingsDataStore.setNfcEnabled(it) } }
                            )
                        }

                        // Sub-toggle — only relevant once NFC is on.
                        if (nfcAvailable && nfcEnabled) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.settings_nfc_write_url_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.settings_nfc_write_url_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = nfcWriteUrl,
                                    onCheckedChange = { scope.launch { viewModel.settingsDataStore.setNfcWriteUrl(it) } }
                                )
                            }
                        }
                    }
                }
            }

            // ── About section ──
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_section_about),
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
                        InfoRow(stringResource(R.string.app_name), stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME))

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Text(
                            stringResource(R.string.settings_about_developer),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GithubProfileRow(
                            handle = "GhostTypes",
                            subtitle = stringResource(R.string.settings_about_developer),
                            avatarUrl = "https://github.com/GhostTypes.png",
                            profileUrl = "https://github.com/GhostTypes"
                        )
                        GithubProfileRow(
                            handle = "Parallel-7",
                            subtitle = stringResource(R.string.settings_about_organization),
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
            contentDescription = stringResource(R.string.settings_github_avatar_cd, handle),
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
            contentDescription = stringResource(R.string.settings_github_open_cd, handle),
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

