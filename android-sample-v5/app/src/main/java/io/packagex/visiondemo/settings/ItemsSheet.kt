package io.packagex.visiondemo.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.designsystem.Badge
import io.packagex.visiondemo.designsystem.BadgeTone
import io.packagex.visiondemo.designsystem.LinkLabel
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.PXButton
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.designsystem.SectionLabel
import io.packagex.visiondemo.designsystem.SheetScaffold
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.mono
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visiondemo.scanner.ScannerAction
import io.packagex.visiondemo.scanner.ScannerUiState
import io.packagex.visiondemo.scanner.SkuNameDialog
import io.packagex.visiondemo.scanner.foundSkus
import io.packagex.visiondemo.scanner.seenRows

/** Ported 1:1 from iOS `UI/Sheets.swift` `ItemsSheet`, plus a manual "Add by code" field for [ScannerAction.AddItem]
 *  (typed entry) -- iOS's plain Item list sheet has none; only its (Task 11) AR items sheet does. */
@Composable
fun ItemsSheet(state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    /** The Seen row whose Add was tapped: its code goes into the naming dialog */
    var naming by remember { mutableStateOf<String?>(null) }
    /** The listed SKU whose name was tapped, to rename */
    var renaming by remember { mutableStateOf<String?>(null) }
    val found = foundSkus(state.items, state.seen, state.pinCounts)

    Text("AR Item Count highlights these SKUs on the shelf and counts what it finds. ${state.items.size} in list.", style = inter(13.sp), color = PX.Muted)

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) { PXButton(title = "Scan SKUs", height = 44.dp) { onAction(ScannerAction.ScanSkus) } }
        Box(Modifier.weight(1f)) {
            PXButton(title = "Delete All", kind = PXButtonKind.Secondary, height = 44.dp) { confirmDeleteAll = true }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("SKU / barcode", style = mono(13.sp), color = PX.Muted) },
            textStyle = mono(14.sp),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        LinkLabel("Add") {
            if (draft.isNotBlank()) {
                onAction(ScannerAction.AddItem(draft))
                draft = ""
            }
        }
    }

    if (state.items.isEmpty()) {
        Text("No SKUs yet. Scan them one at a time with the camera, or type one above.", style = inter(13.sp), color = PX.Muted)
    }

    Column {
        state.items.forEach { code ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
            ) {
                Column(Modifier.weight(1f).clickable { renaming = code }.semantics { contentDescription = "Rename $code" }) {
                    state.itemNames[code]?.let { Text(it, style = inter(14.sp), color = PX.Ink) }
                    Text(code, style = mono(if (code in state.itemNames) 12.sp else 14.sp), color = if (code in state.itemNames) PX.Muted else PX.Ink)
                }
                // Design v6: Not counted before Start count, then Found or Missing
                when {
                    !state.countStep -> Badge(text = "Not counted", tone = BadgeTone.Neutral)
                    code in found -> Badge(text = "Found", tone = BadgeTone.Success, dot = true)
                    else -> Badge(text = "Missing", tone = BadgeTone.Danger, dot = true)
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { onAction(ScannerAction.RemoveItem(code)) }
                        .semantics { contentDescription = "Delete $code" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = PX.Muted, modifier = Modifier.size(14.dp))
                }
            }
            HorizontalDivider(color = PX.Hairline)
        }
    }

    if (state.items.isNotEmpty()) {
        PXButton(title = if (state.countStep) "Continue counting" else "Start count (${state.items.size})") { onAction(ScannerAction.StartCount) }
    }

    // Every code the AR session read (spec 5.10), so the worker sees what the camera reads and can list it
    SectionLabel("Seen")
    if (state.seen.isEmpty()) {
        Text("No codes read yet. Pan across the shelf.", style = inter(13.sp), color = PX.Muted)
    }
    Column {
        seenRows(state.seen, state.items).forEach { (code, listed) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
            ) {
                Text(code, style = mono(14.sp), color = PX.Ink, modifier = Modifier.weight(1f))
                if (listed) {
                    Badge(text = "In list", tone = BadgeTone.Success, dot = true)
                } else {
                    LinkLabel("Add") { naming = code }
                }
            }
            HorizontalDivider(color = PX.Hairline)
        }
    }

    naming?.let { code -> AddSeenDialog(code, onSave = { sku, name -> onAction(ScannerAction.AddItem(sku, name)); naming = null }, onDismiss = { naming = null }) }
    renaming?.let { code ->
        SkuNameDialog(code, state.itemNames[code].orEmpty(), onSave = { onAction(ScannerAction.NameItem(code, it)); renaming = null }, onDismiss = { renaming = null })
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all codes?") },
            text = { Text("This action cannot be undone") },
            confirmButton = {
                TextButton(onClick = { onAction(ScannerAction.ClearItems); confirmDeleteAll = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

/** Add from the Seen list: the barcode (editable) and the item's name, which has the focus; a blank name adds it unnamed. */
@Composable
private fun AddSeenDialog(code: String, onSave: (sku: String, name: String) -> Unit, onDismiss: () -> Unit) {
    var sku by remember { mutableStateOf(code) }
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = sku, onValueChange = { sku = it }, label = { Text("Barcode") }, textStyle = mono(14.sp), singleLine = true)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item name") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(enabled = sku.isNotBlank(), onClick = { onSave(sku, name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(showBackground = true)
@Composable
private fun ItemsSheetPreview() {
    VisionTheme {
        SheetScaffold(title = "Item list", onClose = {}) {
            ItemsSheet(state = ScannerUiState(sheet = SheetKind.Items, items = listOf("012345678905")), onAction = {})
        }
    }
}
