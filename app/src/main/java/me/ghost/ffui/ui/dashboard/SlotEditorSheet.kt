package me.ghost.ffui.ui.dashboard

import android.content.Context
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
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.ghost.ffapi.models.SlotInfo as MatlSlotInfo
import me.ghost.ffui.R
import me.ghost.ffui.data.ActivePrinterSession
import me.ghost.ffui.data.SpoolmanRepository
import me.ghost.ffui.nfc.NfcManager
import me.ghost.ffui.nfc.NfcReadResult
import me.ghost.ffui.ui.components.IfsPalette
import me.ghost.ffui.ui.components.luminanceIsDark

/** Parses a `#RRGGBB` (or bare `RRGGBB`) string to a Compose [Color]; null when unparseable. */
private fun parseHex(hex: String): Color? {
    val s = if (hex.startsWith("#")) hex else "#$hex"
    return runCatching { Color(android.graphics.Color.parseColor(s)) }.getOrNull()
}

/** UI state of the optional NFC "scan roll" sub-flow inside the slot editor. */
private sealed interface ScanUi {
    /** No scan in progress — the editor shows normally. */
    data object Idle : ScanUi
    /** Waiting for a tag to be tapped. */
    data object Scanning : ScanUi
    /** A tag was read; fetching its spool from Spoolman. */
    data object Resolving : ScanUi
    /** The slot was set from the scanned spool; [message] names what matched. */
    data class Success(val message: String) : ScanUi
    /** Something went wrong; [message] explains and the user can retry or close. */
    data class Error(val message: String) : ScanUi
}

/**
 * Bottom-sheet editor for one material-station slot (AD5X or Creator 5). Lets the user set the
 * slot's material + color (`msConfig_cmd`). Material and color are restricted to the
 * printer-recognized palette for the current model — [IfsPalette.materialsFor] / [IfsPalette.colorsFor]
 * via [isCreator5] — so we never push a value the printer UI can't render (the Creator 5 firmware
 * needs an exact palette match, so it gets its own swatches).
 * Submitting fires the command and dismisses; the next `/detail` poll reflects the change.
 *
 * When [nfcEnabled] and [spoolmanEnabled] are both on, a **Scan roll** button appears: tapping it
 * scans an NFC-tagged spool, pulls its material + color from Spoolman, snaps them to the nearest
 * recognized palette values, and **immediately applies** them to the slot (auto-apply) before
 * auto-dismissing. This is the first feature to combine the NFC + Spoolman integrations.
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
    onDismiss: () -> Unit,
    nfc: NfcManager? = null,
    spoolmanRepository: SpoolmanRepository? = null,
    nfcEnabled: Boolean = false,
    spoolmanEnabled: Boolean = false,
    isCreator5: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val noSpoolDataMsg = stringResource(R.string.nfc_scan_error_no_spool_data)
    val boxTagMsg = stringResource(R.string.nfc_scan_error_box_tag)

    // Seed material from the slot if it's a recognized type; otherwise default to PLA.
    val initialName = slot?.materialName?.takeIf { it.isNotBlank() && it != "?" }
    var selectedMaterial by remember {
        mutableStateOf(initialName?.takeIf { it in IfsPalette.materialsFor(isCreator5) } ?: "PLA")
    }
    var materialMenuOpen by remember { mutableStateOf(false) }

    // Seed color: normalize to a `#RRGGBB` hex string.
    var hex by remember {
        mutableStateOf(slot?.materialColor?.takeIf { parseHex(it) != null }?.let {
            "#" + it.removePrefix("#").uppercase()
        } ?: "#FFFFFF")
    }

    val canSave = parseHex(hex) != null

    // Whether the optional scan-roll affordance is available.
    val scanAvailable = nfcEnabled && spoolmanEnabled && nfc != null && spoolmanRepository != null
    var scanUi by remember { mutableStateOf<ScanUi>(ScanUi.Idle) }

    // While in read mode, a tapped tag surfaces here; we react only when we're the ones scanning.
    val readResultFlow = remember(nfc) { nfc?.readResult ?: MutableStateFlow<NfcReadResult?>(null) }
    val readResult by readResultFlow.collectAsStateWithLifecycle()

    // Detect a tapped tag (keyed only on readResult so neither consumeReadResult() nor scanUi writes
    // can cancel the work below). The resolve/apply pipeline runs on the stable `scope` so it
    // survives this effect being torn down when readResult flips back to null.
    LaunchedEffect(readResult) {
        val result = readResult ?: return@LaunchedEffect
        if (scanUi != ScanUi.Scanning || nfc == null || spoolmanRepository == null) return@LaunchedEffect
        nfc.consumeReadResult()
        when (result) {
            is NfcReadResult.Found -> {
                scanUi = ScanUi.Resolving
                scope.launch {
                    applyScannedSpool(
                        spoolId = result.spoolId,
                        repo = spoolmanRepository,
                        session = session,
                        slotId = slotId,
                        isCreator5 = isCreator5,
                        currentMaterial = selectedMaterial,
                        context = context,
                        onMatched = { matchedMaterial, matchedColor ->
                            selectedMaterial = matchedMaterial
                            hex = matchedColor.hex
                        },
                        onResult = { scanUi = it }
                    )
                }
            }
            is NfcReadResult.Unknown -> scanUi = ScanUi.Error(noSpoolDataMsg)
            is NfcReadResult.Error -> scanUi = ScanUi.Error(result.message)
            is NfcReadResult.BoxFound -> scanUi = ScanUi.Error(boxTagMsg)
        }
    }

    // If the sheet leaves the screen mid-scan, drop out of NFC read mode.
    DisposableEffect(Unit) {
        onDispose { if (scanUi == ScanUi.Scanning) nfc?.cancel() }
    }

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
                    Text(stringResource(R.string.dashboard_ifs_slot, slotId), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val current = if (slot?.hasFilament == true) {
                        val n = slot.materialName.takeIf { it.isNotBlank() && it != "?" } ?: stringResource(R.string.common_unknown)
                        stringResource(R.string.dashboard_slot_loaded, n)
                    } else stringResource(R.string.dashboard_ifs_empty)
                    Text(current, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Material dropdown.
            ExposedDropdownMenuBox(expanded = materialMenuOpen, onExpandedChange = { materialMenuOpen = it }) {
                OutlinedTextField(
                    value = selectedMaterial,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.dashboard_slot_material_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = materialMenuOpen) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = materialMenuOpen, onDismissRequest = { materialMenuOpen = false }) {
                    IfsPalette.materialsFor(isCreator5).forEach { mat ->
                        DropdownMenuItem(
                            text = { Text(mat) },
                            onClick = { selectedMaterial = mat; materialMenuOpen = false }
                        )
                    }
                }
            }

            // Color swatch grid.
            Text(stringResource(R.string.dashboard_slot_color_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IfsPalette.colorsFor(isCreator5).forEach { pc ->
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
                            .clickable { hex = pc.hex }
                            // TalkBack: each swatch is a named, selectable color option.
                            .semantics {
                                contentDescription = pc.name
                                this.selected = selected
                            },
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

            // Optional: scan a tagged spool to set this slot from Spoolman.
            if (scanAvailable) {
                OutlinedButton(
                    onClick = {
                        scanUi = ScanUi.Scanning
                        nfc?.beginRead()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Contactless, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.nfc_scan_roll))
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
            ) { Text(stringResource(R.string.dashboard_slot_save)) }
        }
    }

    // Scan sub-flow dialogs.
    when (val state = scanUi) {
        is ScanUi.Idle -> Unit
        is ScanUi.Scanning -> ScanStatusDialog(
            icon = { Icon(Icons.Default.Contactless, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) },
            title = stringResource(R.string.nfc_scan_title),
            message = stringResource(R.string.nfc_scan_prompt),
            onDismiss = { scanUi = ScanUi.Idle; nfc?.cancel() }
        )
        is ScanUi.Resolving -> ScanStatusDialog(
            icon = { CircularProgressIndicator(modifier = Modifier.size(40.dp)) },
            title = stringResource(R.string.nfc_scan_reading_title),
            message = stringResource(R.string.nfc_scan_reading_message),
            onDismiss = null
        )
        is ScanUi.Success -> {
            LaunchedEffect(Unit) {
                delay(1500)
                onDismiss()
            }
            ScanStatusDialog(
                icon = { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) },
                title = stringResource(R.string.nfc_scan_updated_title),
                message = state.message,
                onDismiss = null
            )
        }
        is ScanUi.Error -> ScanErrorDialog(
            message = state.message,
            onRetry = { scanUi = ScanUi.Scanning; nfc?.beginRead() },
            onClose = { scanUi = ScanUi.Idle; nfc?.cancel() }
        )
    }
}

/**
 * Fetches the scanned spool, snaps its material + color to the nearest recognized palette values,
 * pushes them to the printer ([ActivePrinterSession.setSlotMaterial]), and reports the resulting
 * [ScanUi] state. [onMatched] lets the editor reflect the matched values in its own fields.
 */
private suspend fun applyScannedSpool(
    spoolId: Int,
    repo: SpoolmanRepository,
    session: ActivePrinterSession,
    slotId: Int,
    isCreator5: Boolean,
    currentMaterial: String,
    context: Context,
    onMatched: (material: String, color: IfsPalette.PaletteColor) -> Unit,
    onResult: (ScanUi) -> Unit
) {
    val spool = repo.getSpool(spoolId).getOrElse {
        onResult(ScanUi.Error(context.getString(R.string.nfc_scan_error_load_failed, spoolId)))
        return
    }

    // Prefer the single color; fall back to the first of a multi-color filament.
    val rawColor = spool.filament.color_hex?.takeIf { it.isNotBlank() }
        ?: spool.filament.multi_color_hexes?.split(",")?.firstOrNull()?.trim()
    val matchedColor = IfsPalette.nearestColorFor(isCreator5, rawColor)
    if (matchedColor == null) {
        onResult(ScanUi.Error(context.getString(R.string.nfc_scan_error_no_color, spool.displayName)))
        return
    }
    val matchedMaterial = IfsPalette.nearestMaterial(spool.filament.material) ?: currentMaterial
    onMatched(matchedMaterial, matchedColor)

    val applied = session.setSlotMaterial(slotId, matchedMaterial, matchedColor.hex)
    if (applied.isSuccess) {
        onResult(ScanUi.Success(context.getString(R.string.nfc_scan_success_applied, slotId, matchedMaterial, matchedColor.name, spool.displayName)))
    } else {
        onResult(ScanUi.Error(context.getString(R.string.nfc_scan_error_update_failed, slotId)))
    }
}

/** A centered status dialog (icon + title + message), with an optional Cancel action. */
@Composable
private fun ScanStatusDialog(
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    onDismiss: (() -> Unit)?
) {
    Dialog(onDismissRequest = { onDismiss?.invoke() }) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                icon()
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        }
    }
}

/** Error state of the scan flow: explains what went wrong and offers Retry / Close. */
@Composable
private fun ScanErrorDialog(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Text(stringResource(R.string.nfc_scan_error_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                }
            }
        }
    }
}

