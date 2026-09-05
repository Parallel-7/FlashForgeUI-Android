package me.ghost.ffui.ui.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ghost.ffapi.models.SlotInfo as MatlSlotInfo
import me.ghost.ffapi.models.MatlStationInfo
import me.ghost.ffui.R
import me.ghost.ffui.data.ActivePrinterSession
import me.ghost.ffui.ui.MainViewModel
import me.ghost.ffui.ui.components.SpoolDisc

// parseHexColor moved to ui/components/SpoolDisc.kt

/**
 * AD5X IFS card: a row of spool slots; tapping a slot opens the [SlotEditorSheet]. When NFC and
 * Spoolman are both enabled, the editor gains a "Scan roll" affordance ([viewModel] supplies the
 * NFC + Spoolman dependencies for that flow).
 */
@Composable
internal fun IfsStationCard(station: MatlStationInfo, session: ActivePrinterSession, viewModel: MainViewModel) {
    val nfcEnabled by viewModel.settingsDataStore.nfcEnabled.collectAsStateWithLifecycle(initialValue = false)
    val spoolmanEnabled by viewModel.settingsDataStore.spoolmanEnabled.collectAsStateWithLifecycle(initialValue = false)
    val capabilities by session.capabilities.collectAsStateWithLifecycle()
    val isCreator5 = capabilities.model.isCreator5
    val activeSlot = station.currentSlot.takeIf { it > 0 }
        ?: station.currentLoadSlot.takeIf { it > 0 }
        ?: 0
    val slotCount = if (station.slotCnt > 0) station.slotCnt else station.slotInfos.size.coerceAtLeast(1)
    var editingSlot by remember(session.printer.serialNumber) { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.dashboard_ifs_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (activeSlot > 0) {
                    Text(
                        stringResource(R.string.dashboard_ifs_active_slot, activeSlot),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (slotId in 1..slotCount) {
                    SpoolSlot(
                        slot = station.slotInfos.find { it.slotId == slotId },
                        slotId = slotId,
                        isActive = slotId == activeSlot,
                        onClick = { editingSlot = slotId },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    editingSlot?.let { slotId ->
        SlotEditorSheet(
            slotId = slotId,
            slot = station.slotInfos.find { it.slotId == slotId },
            session = session,
            onDismiss = { editingSlot = null },
            nfc = viewModel.nfcManager,
            spoolmanRepository = viewModel.spoolmanRepository,
            nfcEnabled = nfcEnabled,
            spoolmanEnabled = spoolmanEnabled,
            isCreator5 = isCreator5
        )
    }
}

@Composable
private fun SpoolSlot(
    slot: MatlSlotInfo?,
    slotId: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasFilament = slot?.hasFilament == true
    val primary = MaterialTheme.colorScheme.primary

    // Pulse the active slot's ring for a subtle glow.
    val glowAlpha = if (isActive) {
        val transition = rememberInfiniteTransition(label = "spool-glow")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "spool-glow-alpha"
        ).value
    } else 0f

    val ringColor = if (isActive) primary.copy(alpha = glowAlpha) else null

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
            // Active ring / glow.
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .border(2.dp, primary.copy(alpha = glowAlpha), CircleShape)
                )
            }
            // Spool disc — shared component.
            SpoolDisc(
                colorHex = slot?.materialColor,
                size = 44.dp,
                ringColor = ringColor,
                dimmed = !hasFilament
            )
        }

        // Material tag chip or "Empty" label.
        if (hasFilament) {
            val tag = slot?.materialName?.takeIf { it.isNotBlank() && it != "?" } ?: "—"
            Box(
                modifier = Modifier
                    .background(primary.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Text(
                stringResource(R.string.dashboard_ifs_empty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        Text(
            stringResource(R.string.dashboard_ifs_slot, slotId),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
