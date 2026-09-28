package io.packagex.visiondemo.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.PXButton
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.designsystem.SectionLabel
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.montserrat
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.OcrResult

/**
 * Report chips: the report models' coding keys `ReportRepository` expects (not `OcrParser`'s field
 * keys). Ported from iOS `Model/Types.swift`'s `DocType.reportKeys`.
 */
val DocType.reportKeys: List<String>
    get() = when (this) {
        DocType.SL -> listOf("tracking_no", "courier_name", "weight", "dimensions", "receiver_name", "receiver_address", "sender_name", "sender_address")
        DocType.BOL -> listOf(
            "referenceNo", "loadNumber", "purchaseOrderNumber", "invoiceNumber", "customerPurchaseOrderNumber",
            "orderNumber", "billOfLading", "masterBillOfLading", "lineBillOfLading", "houseBillOfLading", "shippingId", "shippingDate", "date",
        )
        DocType.IL -> listOf("supplier_name", "item_name", "item_sku", "weight", "quantity", "dimensions", "production_date", "supplier_address")
        DocType.DC -> listOf("document_class")
        else -> emptyList()
    }

/** True for the doc types `ReportRepository` can build a report model for. */
val DocType.reportSupported: Boolean get() = reportKeys.isNotEmpty()

/**
 * "Report an error" overlay: pick the erroneous fields (report-model keys) and write a message.
 * Ported from iOS `UI/Overlays.swift`'s `ReportCard`.
 */
@Composable
fun ReportCard(result: OcrResult, onAction: (ScannerAction) -> Unit, onClose: () -> Unit) {
    var picked by remember { mutableStateOf(setOf<String>()) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val keys = result.docType.reportKeys

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PX.Ink.copy(alpha = 0.55f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { /* swallow taps under the card */ }
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Report an error", style = montserrat(18.sp), color = PX.Ink)
            SectionLabel(text = "Select erroneous data fields")
            FlowChips(items = keys, selected = picked) { k ->
                picked = if (k in picked) picked - k else picked + k
            }
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                placeholder = { Text("Enter error message here...", style = inter(14.sp)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PX.Purple, unfocusedBorderColor = Color(0xFFD9D0E3)),
            )
            error?.let { Text(it, style = inter(12.sp), color = PX.RedText) }
            PXButton(title = "Submit") {
                if (picked.isEmpty()) {
                    error = "Select at least one field"
                } else {
                    error = null
                    onAction(ScannerAction.Report(picked, message))
                    onClose()
                }
            }
            PXButton(title = "Cancel", kind = PXButtonKind.Tertiary, onClick = onClose)
        }
    }
}

/** Wrapping row of outlined chips. Ported from iOS `UI/ResultDrawer.swift`'s `FlowChips` / `FlowLayout`. */
@Composable
private fun FlowChips(items: List<String>, selected: Set<String>, onTap: (String) -> Unit) {
    FlowRow(spacing = 6.dp, modifier = Modifier.fillMaxWidth()) {
        items.forEach { k ->
            val on = k in selected
            Text(
                text = k,
                style = inter(13.sp),
                color = PX.Ink,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Color(0xFFF4D4D4) else Color.White)
                    .border(if (on) 2.dp else 1.dp, if (on) PX.Red else PX.Hairline, RoundedCornerShape(10.dp))
                    .clickable { onTap(k) }
                    .padding(horizontal = 12.dp),
            )
        }
    }
}

/** Minimal wrap layout (no weight/justify): places children left to right, wrapping to a new row when full. */
@Composable
private fun FlowRow(modifier: Modifier = Modifier, spacing: Dp = 6.dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val spacingPx = spacing.roundToPx()
        val itemConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(itemConstraints) }
        var x = 0
        var y = 0
        var rowHeight = 0
        val positions = ArrayList<Pair<Int, Int>>(placeables.size)
        for (p in placeables) {
            if (x > 0 && x + p.width > constraints.maxWidth) {
                x = 0
                y += rowHeight + spacingPx
                rowHeight = 0
            }
            positions.add(x to y)
            x += p.width + spacingPx
            rowHeight = maxOf(rowHeight, p.height)
        }
        val totalHeight = y + rowHeight
        layout(constraints.maxWidth, totalHeight) {
            placeables.forEachIndexed { i, p -> p.placeRelative(positions[i].first, positions[i].second) }
        }
    }
}
