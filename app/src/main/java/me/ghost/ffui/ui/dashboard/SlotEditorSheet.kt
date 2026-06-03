package me.ghost.ffui.ui.dashboard

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.ghost.ffapi.models.SlotInfo as MatlSlotInfo
import me.ghost.ffui.data.ActivePrinterSession
import me.ghost.ffui.ui.components.IfsPalette
import kotlinx.coroutines.launch

/** Parses a `#RRGGBB` (or bare `RRGGBB`) string to a Compose [Color]; null when unparseable. */
private fun parseHex(hex: String): Color? {
    val s = if (hex.startsWith("#")) hex else "#$hex"
    return runCatching { Color(android.graphics.Color.parseColor(s)) }.getOrNull()
}

/**
 * Bottom-sheet editor for one AD5X IFS slot. Lets the user set the slot's material + color
 * (`msConfig_cmd`). Material and color are restricted to the printer-recognized
 * [IfsPalette.MATERIALS] / [IfsPalette.COLORS] so we never push a value the printer UI can't render.
 * Submitting fires the command and dismisses; the next `/detail` poll reflects the change.
 *
 * Load / unload / cancel (`ms_cmd`) actions are intentionally omitted for now — held back until we
 * verify exactly what each does on real hardware (the backend `slotAction` plumbing still exists).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SlotEditorSheet(
    slotId: Int,
    slot: MatlSlotInfo?,
    session: ActivePrinterSession,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // Seed material from the slot if it's a recognized type; otherwise default to PLA.
    val initialName = slot?.materialName?.takeIf { it.isNotBlank() && it != "?" }
    var selectedMaterial by remember {
        mutableStateOf(initialName?.takeIf { it in IfsPalette.MATERIALS } ?: "PLA")
    }
    var materialMenuOpen by remember { mutableStateOf(false) }

    // Seed color: normalize to a `#RRGGBB` hex string.
    var hex by remember {
        mutableStateOf(slot?.materialColor?.takeIf { parseHex(it) != null }?.let {
            "#" + it.removePrefix("#").uppercase()
        } ?: "#FFFFFF")
    }

    val canSave = parseHex(hex) != null

    // Open fully expanded so the whole editor is visible without a drag.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header + current state.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(parseHex(hex) ?: Color.Gray, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                Column(Modifier.weight(1f)) {
                    Text("Slot $slotId", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val current = if (slot?.hasFilament == true) {
                        val n = slot.materialName.takeIf { it.isNotBlank() && it != "?" } ?: "Unknown"
                        "Loaded: $n"
                    } else "Empty"
                    Text(current, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Material dropdown.
            ExposedDropdownMenuBox(expanded = materialMenuOpen, onExpandedChange = { materialMenuOpen = it }) {
                OutlinedTextField(
                    value = selectedMaterial,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Material") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = materialMenuOpen) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = materialMenuOpen, onDismissRequest = { materialMenuOpen = false }) {
                    IfsPalette.MATERIALS.forEach { mat ->
                        DropdownMenuItem(
                            text = { Text(mat) },
                            onClick = { selectedMaterial = mat; materialMenuOpen = false }
                        )
                    }
                }
            }

            // Color swatch grid.
            Text("Color", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IfsPalette.COLORS.forEach { pc ->
                    val selected = pc.hex.equals("#" + hex.removePrefix("#"), ignoreCase = true)
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(pc.color, CircleShape)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                            .clickable { hex = pc.hex },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = pc.name,
                                tint = if (pc.color.luminanceIsDark()) Color.White else Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            // Save material metadata.
            Button(
                onClick = {
                    scope.launch { session.setSlotMaterial(slotId, selectedMaterial, hex) }
                    onDismiss()
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Save material") }
        }
    }
}

/** Rough perceptual-luminance check so the check mark stays legible on any swatch. */
private fun Color.luminanceIsDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.6f
