package me.ghost.ffui.ui.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ghost.ffui.R
import me.ghost.ffui.data.ActivePrinterSession
import me.ghost.ffui.data.ConnectionState
import me.ghost.ffui.ui.PrinterModelNames
import me.ghost.ffui.ui.theme.GeometricPrimary
import me.ghost.ffui.ui.theme.GeometricSurface
import me.ghost.ffui.ui.theme.StatusConnected
import me.ghost.ffui.ui.theme.StatusConnecting
import me.ghost.ffui.ui.theme.StatusError
import me.ghost.ffui.ui.theme.StatusOffline

/** Horizontal, scrollable strip of printer tabs with a trailing add button. */
@Composable
internal fun PrinterTabBar(
    sessions: Map<String, ActivePrinterSession>,
    activeSerial: String?,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onAddClick: () -> Unit
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = GeometricSurface,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for ((serial, session) in sessions) {
                val connState by session.connectionState.collectAsStateWithLifecycle()
                PrinterTab(
                    name = session.printer.name,
                    subtitle = buildString {
                        append(session.printer.ipAddress)
                        val model = PrinterModelNames.shortName(session.printer.modelPid)
                        if (model.isNotBlank()) {
                            append(" · ")
                            append(model)
                        }
                    },
                    connectionState = connState,
                    isActive = serial == activeSerial,
                    onClick = { onTabClick(serial) },
                    onClose = { onTabClose(serial) }
                )
            }

            // Trailing "+" button — default touch-target size; only the icon is small.
            IconButton(
                onClick = onAddClick
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.dashboard_add_printer_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PrinterTab(
    name: String,
    subtitle: String,
    connectionState: ConnectionState,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val primaryColor = GeometricPrimary
    val activeBg = primaryColor.copy(alpha = 0.15f)
    val activeBottomBorder = primaryColor
    val surfaceBg = MaterialTheme.colorScheme.surface

    Surface(
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        color = if (isActive) activeBg else surfaceBg,
        shadowElevation = if (isActive) 2.dp else 0.dp,
        modifier = Modifier
            .widthIn(min = 160.dp, max = 240.dp)
            .fillMaxHeight()
            .then(
                if (isActive) {
                    Modifier.drawBehind {
                        drawLine(
                            color = activeBottomBorder,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(start = 10.dp, end = 4.dp)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusDot(connectionState)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Close (disconnect) — keep the default 48dp touch target even though it slightly
            // overflows the 44dp tab; an 18dp target on a destructive action misses too easily.
            IconButton(
                onClick = onClose
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.dashboard_close_tab_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** Connection status dot; pulses while connecting. */
@Composable
private fun StatusDot(connectionState: ConnectionState) {
    val isConnecting = connectionState is ConnectionState.Connecting

    val dotColor = when (connectionState) {
        is ConnectionState.Connected -> StatusConnected
        is ConnectionState.Connecting -> StatusConnecting
        is ConnectionState.Offline -> StatusOffline
        is ConnectionState.AuthFailed -> StatusError
    }

    if (isConnecting) {
        val infiniteTransition = rememberInfiniteTransition(label = "connecting-pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse-scale"
        )
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse-alpha"
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(scale)
                .background(dotColor.copy(alpha = alpha), CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
    }
}
