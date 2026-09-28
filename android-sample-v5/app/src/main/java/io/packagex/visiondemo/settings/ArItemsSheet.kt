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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.ar.PayloadCount
import io.packagex.visiondemo.designsystem.Badge
import io.packagex.visiondemo.designsystem.BadgeTone
import io.packagex.visiondemo.designsystem.LinkLabel
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.PXButton
import io.packagex.visiondemo.designsystem.SectionLabel
import io.packagex.visiondemo.designsystem.SheetScaffold
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.mono
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visiondemo.scanner.ScannerAction
import io.packagex.visiondemo.scanner.ScannerUiState

/**
 * Names for the barcodes AR Barcode finds. Ported from iOS `UI/Sheets.swift` `ARItemsSheet`. AR keeps running
 * under this half-height sheet, so codes it marks can be named in place and nothing it has counted is lost.
 */
@Composable
fun ArItemsSheet(state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    val drafts = remember { mutableStateMapOf<String, String>() }
    var newSku by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    fun save(sku: String) {
        val name = drafts[sku].orEmpty().trim()
        if (name.isEmpty()) return
        onAction(ScannerAction.NameArItem(sku, name))
        drafts.remove(sku)
    }

    // Android markers are drawn without text, so names show in the results (iOS also labels new markers).
    Text("Name the barcodes AR Barcode finds. Names show in the scan results.", style = inter(13.sp), color = PX.Muted)

    val unnamed = state.arCounts.filter { it.payload !in state.itemNames }
    if (unnamed.isNotEmpty()) {
        Column {
            SectionLabel("Codes in view (${unnamed.size})")
            unnamed.forEach { code ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(code.payload, style = mono(13.sp), color = PX.Ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        OutlinedTextField(
                            value = drafts[code.payload].orEmpty(),
                            onValueChange = { drafts[code.payload] = it },
                            placeholder = { Text("Item name", style = inter(14.sp), color = PX.Muted) },
                            textStyle = inter(14.sp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save(code.payload) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    LinkLabel("Add") { save(code.payload) }
                }
                HorizontalDivider(color = PX.Hairline)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Add by SKU")
        OutlinedTextField(
            value = newSku,
            onValueChange = { newSku = it },
            placeholder = { Text("SKU / barcode", style = mono(14.sp), color = PX.Muted) },
            textStyle = mono(14.sp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            placeholder = { Text("Item name", style = inter(14.sp), color = PX.Muted) },
            textStyle = inter(14.sp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val ready = newSku.isNotBlank() && newName.isNotBlank()
        Box(Modifier.alpha(if (ready) 1f else 0.4f)) {
            PXButton(title = "Add Item", height = 44.dp) {
                onAction(ScannerAction.NameArItem(newSku, newName))
                newSku = ""
                newName = ""
            }
        }
    }

    Column {
        SectionLabel("Items (${state.itemNames.size})")
        if (state.itemNames.isEmpty()) {
            Text("No items yet. Point AR Barcode at a code, or add a SKU above.", style = inter(13.sp), color = PX.Muted, modifier = Modifier.padding(vertical = 12.dp))
        }
        state.itemNames.forEach { (sku, name) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = inter(15.sp, FontWeight.Medium), color = PX.Ink)
                    Text(sku, style = mono(12.sp), color = PX.Muted)
                }
                state.arCounts.firstOrNull { it.payload == sku }?.let { Badge(text = "× ${it.count}", tone = BadgeTone.Success, dot = true) }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { onAction(ScannerAction.RemoveArItem(sku)) }
                        .semantics { contentDescription = "Remove $name" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = PX.Muted, modifier = Modifier.size(14.dp))
                }
            }
            HorizontalDivider(color = PX.Hairline)
        }
    }

    PXButton(title = "Done") { onAction(ScannerAction.DismissSheet) }
}

@Preview(showBackground = true)
@Composable
private fun ArItemsSheetPreview() {
    VisionTheme {
        SheetScaffold(title = "Items", onClose = {}) {
            ArItemsSheet(
                state = ScannerUiState(
                    sheet = SheetKind.ArItems,
                    arCounts = listOf(PayloadCount("012345678905", "EAN-13", 1), PayloadCount("4006381333931", "EAN-13", 1)),
                    itemNames = mapOf("012345678905" to "Oat milk"),
                ),
                onAction = {},
            )
        }
    }
}
