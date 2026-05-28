package me.ghost.ffui.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ghost.ffui.api.AD5XMaterialMapping
import me.ghost.ffui.api.FFGcodeToolData
import me.ghost.ffui.api.MatlSlotInfo

// ── Matching helpers (ported from FlashForgeUI-Electron material-matching) ────

private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")

private fun normalizeMaterial(value: String?): String = value.orEmpty().trim().lowercase()

/** Two materials match when their normalized names are equal (and non-blank). */
internal fun materialsMatch(tool: String?, slot: String?): Boolean {
    val t = normalizeMaterial(tool)
    return t.isNotEmpty() && t == normalizeMaterial(slot)
}

/** Coerces a color to a valid `#RRGGBB` string the printer will accept; falls back to white. */
internal fun normalizeHexColor(color: String?): String {
    val raw = color.orEmpty().trim()
    val withHash = if (raw.startsWith("#")) raw else "#$raw"
    return if (HEX_COLOR.matches(withHash)) withHash else "#FFFFFF"
}

/** True when both colors are valid and differ (a cosmetic, allowed mismatch). */
internal fun colorsDiffer(toolColor: String?, slotColor: String?): Boolean {
    val t = toolColor.orEmpty().trim().lowercase()
    if (t.isEmpty()) return false
    return t != slotColor.orEmpty().trim().lowercase()
}

/**
 * Attempts to auto-assign every tool to a compatible, unused, loaded slot (first match wins).
 * Returns the complete mapping list, or `null` if any tool can't be satisfied (caller should then
 * fall back to the manual matching dialog).
 */
internal fun autoMatchMappings(
    tools: List<FFGcodeToolData>,
    slots: List<MatlSlotInfo>
): List<AD5XMaterialMapping>? {
    val used = mutableSetOf<Int>()
    val result = mutableListOf<AD5XMaterialMapping>()
    for (tool in tools) {
        val slot = slots.firstOrNull {
            it.slotId !in used && it.hasFilament && materialsMatch(tool.materialName, it.materialName)
        } ?: return null
        used += slot.slotId
        result += AD5XMaterialMapping(
            toolId = tool.toolId,
            slotId = slot.slotId,
            materialName = tool.materialName,
            toolMaterialColor = normalizeHexColor(tool.materialColor),
            slotMaterialColor = normalizeHexColor(slot.materialColor)
        )
    }
    return result
}

// ── Manual matching dialog ────────────────────────────────────────────────────

/**
 * Dual-panel tool→slot matcher for AD5X multi-color jobs. Tap a tool, then a compatible slot, to
 * create a mapping. Confirm is enabled only once every tool is mapped. Material type must match;
 * color differences are allowed but warned. Mirrors the Electron material-matching dialog.
 */
@Composable
internal fun MaterialMatchingDialog(
    fileName: String,
    tools: List<FFGcodeToolData>,
    slots: List<MatlSlotInfo>,
    onConfirm: (List<AD5XMaterialMapping>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTool by remember { mutableStateOf<Int?>(null) }
    val mappings = remember { mutableStateMapOf<Int, AD5XMaterialMapping>() }
    var error by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }

    fun slotAssigned(slotId: Int) = mappings.values.any { it.slotId == slotId }

    fun assign(slot: MatlSlotInfo) {
        val toolId = selectedTool
        if (toolId == null) { error = "Select a tool first, then a slot."; return }
        if (!slot.hasFilament) { error = "Slot ${slot.slotId} is empty. Load filament first."; return }
        val tool = tools.first { it.toolId == toolId }
        if (!materialsMatch(tool.materialName, slot.materialName)) {
            error = "Tool ${toolId + 1} needs ${tool.materialName}, but Slot ${slot.slotId} has ${slot.materialName.ifBlank { "no material" }}."
            return
        }
        if (slotAssigned(slot.slotId)) { error = "Slot ${slot.slotId} is already assigned."; return }
        mappings[toolId] = AD5XMaterialMapping(
            toolId = toolId,
            slotId = slot.slotId,
            materialName = tool.materialName,
            toolMaterialColor = normalizeHexColor(tool.materialColor),
            slotMaterialColor = normalizeHexColor(slot.materialColor)
        )
        selectedTool = null
        error = null
        warning = if (colorsDiffer(tool.materialColor, slot.materialColor))
            "Tool ${toolId + 1} and Slot ${slot.slotId} are different colors — print will succeed but look different."
        else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Match Materials", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(fileName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    // Tools (requirements)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("FILE TOOLS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        tools.forEach { tool ->
                            val mapped = mappings[tool.toolId]
                            ToolRow(
                                label = "Tool ${tool.toolId + 1}",
                                material = tool.materialName,
                                color = tool.materialColor,
                                selected = selectedTool == tool.toolId,
                                mappedSlot = mapped?.slotId,
                                onClick = {
                                    selectedTool = if (selectedTool == tool.toolId) null else tool.toolId
                                    error = null
                                }
                            )
                        }
                    }
                    // Slots
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("STATION SLOTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (slots.isEmpty()) {
                            Text("Station not connected.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        slots.sortedBy { it.slotId }.forEach { slot ->
                            SlotRow(
                                slot = slot,
                                assigned = slotAssigned(slot.slotId),
                                onClick = { assign(slot) }
                            )
                        }
                    }
                }

                error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
                warning?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = me.ghost.ffui.ui.theme.GeometricOrangePrimary) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(tools.mapNotNull { mappings[it.toolId] }) },
                enabled = mappings.size == tools.size
            ) { Text("Start Print") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ToolRow(
    label: String,
    material: String,
    color: String,
    selected: Boolean,
    mappedSlot: Int?,
    onClick: () -> Unit
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorDot(color)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    mappedSlot?.let { "→ Slot $it" } ?: material.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mappedSlot != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SlotRow(slot: MatlSlotInfo, assigned: Boolean, onClick: () -> Unit) {
    val disabled = !slot.hasFilament || assigned
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (assigned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (disabled) 0.5f else 1f)
            .then(if (disabled) Modifier else Modifier.clickable(onClick = onClick))
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorDot(slot.materialColor)
            Column(Modifier.weight(1f)) {
                Text("Slot ${slot.slotId}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    if (slot.hasFilament) slot.materialName.ifBlank { "Loaded" } else "Empty",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ColorDot(color: String) {
    val parsed = remember(color) {
        runCatching { Color(android.graphics.Color.parseColor(if (color.startsWith("#")) color else "#$color")) }.getOrNull()
    }
    Box(
        Modifier
            .size(18.dp)
            .background(parsed ?: MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    )
}
