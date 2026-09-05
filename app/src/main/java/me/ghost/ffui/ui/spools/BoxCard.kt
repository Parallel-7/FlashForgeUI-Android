package me.ghost.ffui.ui.spools

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ghost.ffui.data.SpoolBox
import me.ghost.ffui.ui.components.BoxGlyph

/**
 * A compact card representing one box (a grouped location) in the Boxes grid.
 *
 * Anatomy:
 * - Top row: roll-count badge + tagged icon (when NFC is enabled and the box is tagged)
 * - [BoxGlyph] with up to 6 color swatches from the box's member spools
 * - Location name
 * - Stat line: "N rolls"
 * - Bottom-pinned "Options" dropdown: Details + Write to tag (when NFC enabled)
 *
 * @param box The box to display.
 * @param nfcEnabled When true, show the "Write to tag" action and the "tagged" badge.
 * @param tagged Whether this box has had a tag written from this device.
 * @param highlighted Briefly true after a scan resolves to this box — flashes the border.
 * @param onDetailsClick Callback for the "Details" action.
 * @param onWriteClick Callback for the "Write to tag" action.
 */
@Composable
fun BoxCard(
    box: SpoolBox,
    modifier: Modifier = Modifier,
    nfcEnabled: Boolean = false,
    tagged: Boolean = false,
    highlighted: Boolean = false,
    onDetailsClick: () -> Unit = {},
    onWriteClick: () -> Unit = {}
) {
    val colorHexes = box.spools.map { it.filament.color_hex }

    val borderColor by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        label = "boxCardBorder"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(272.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(if (highlighted) 2.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Top row: roll-count badge + tagged icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Roll count badge
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${box.spools.size} rolls",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }

                Spacer(Modifier.weight(1f))

                // Tagged indicator
                if (nfcEnabled && tagged) {
                    Icon(
                        Icons.Default.Contactless,
                        contentDescription = "Tagged",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 2. Box glyph
            BoxGlyph(colorHexes = colorHexes, size = 54.dp)

            // 3. Location name
            Text(
                text = box.location,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Push the action row to the bottom
            Spacer(Modifier.weight(1f))

            // 5. Options button + dropdown
            var menuOpen by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Default.MoreHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Details") },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDetailsClick()
                        }
                    )
                    if (nfcEnabled) {
                        DropdownMenuItem(
                            text = { Text("Write to tag") },
                            leadingIcon = { Icon(Icons.Default.Contactless, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onWriteClick()
                            }
                        )
                    }
                }
            }
        }
    }
}
