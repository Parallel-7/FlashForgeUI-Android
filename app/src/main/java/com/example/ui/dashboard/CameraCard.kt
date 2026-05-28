package com.example.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.components.MpvController
import com.example.ui.components.MpvVideoSurface
import com.example.ui.components.rememberMpvController
import kotlinx.coroutines.delay

/**
 * Dashboard camera card with a tap-to-expand fullscreen view.
 *
 * The card and the fullscreen overlay share **one** [MpvController] (one mpv instance, one
 * network connection): expanding doesn't open a second stream, it hands the single stream's
 * output to the fullscreen surface. To stay within mpv's "one surface at a time" constraint (see
 * [MpvVideoSurface]), only one [MpvVideoSurface] is composed at once — the card's is removed
 * while fullscreen is open and restored on close. The controller keeps the stream loaded across
 * that handoff, so there's no reconnect.
 */
@Composable
fun CameraCard(streamUrl: String?, autoPlay: Boolean, showFps: Boolean) {
    var isPlaying by remember(streamUrl) { mutableStateOf(autoPlay) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    val controller = rememberMpvController()
    LaunchedEffect(controller, streamUrl, isPlaying) {
        controller.setMedia(streamUrl, isPlaying)
    }

    // Poll the controller's measured frame rate only while the overlay is enabled and playing.
    var fps by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(controller, showFps, isPlaying) {
        if (showFps && isPlaying) {
            while (true) {
                fps = controller.currentFps()
                delay(500)
            }
        } else {
            fps = 0.0
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "CAMERA",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!streamUrl.isNullOrBlank()) {
                    IconButton(
                        onClick = { fullscreen = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.OpenInFull,
                            contentDescription = "Expand to fullscreen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black, RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .clickable(enabled = !streamUrl.isNullOrBlank()) {
                        isPlaying = !isPlaying
                    },
                contentAlignment = Alignment.Center
            ) {
                if (streamUrl.isNullOrBlank()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.VideocamOff,
                            contentDescription = "No Camera",
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Camera Not Available",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    // While fullscreen owns the surface, the card's surface is removed so mpv only
                    // ever has one surface attached. Restored (and reattached) when fullscreen closes.
                    if (!fullscreen) {
                        MpvVideoSurface(
                            controller = controller,
                            modifier = Modifier.fillMaxSize(),
                            cropToFill = true
                        )
                        if (!isPlaying) PlayBadge()
                        if (showFps && isPlaying) FpsBadge(fps)
                    }
                }
            }
        }
    }

    if (fullscreen && !streamUrl.isNullOrBlank()) {
        FullscreenCamera(
            controller = controller,
            isPlaying = isPlaying,
            onTogglePlay = { isPlaying = !isPlaying },
            showFps = showFps,
            fps = fps,
            onClose = { fullscreen = false }
        )
    }
}

/**
 * Edge-to-edge fullscreen overlay shown as a borderless [Dialog]. Renders the shared [controller]
 * (no new stream), fits the whole frame (no crop), and follows the device's current orientation —
 * with `configChanges` on `MainActivity`, rotating resizes this in place rather than recreating
 * it. Tapping toggles play/pause, shared with the card.
 */
@Composable
private fun FullscreenCamera(
    controller: MpvController,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    showFps: Boolean,
    fps: Double,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center
        ) {
            MpvVideoSurface(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
                cropToFill = false
            )
            if (!isPlaying) PlayBadge()
            if (showFps && isPlaying) FpsBadge(fps)
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(
                    Icons.Default.CloseFullscreen,
                    contentDescription = "Exit fullscreen",
                    tint = Color.White
                )
            }
        }
    }
}

/** Centered translucent play button shown when the feed is paused. */
@Composable
private fun BoxScope.PlayBadge() {
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            .padding(12.dp)
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "Play",
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

/** Top-left live FPS overlay. */
@Composable
private fun BoxScope.FpsBadge(fps: Double) {
    Text(
        text = "${fps.toInt()} FPS",
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
