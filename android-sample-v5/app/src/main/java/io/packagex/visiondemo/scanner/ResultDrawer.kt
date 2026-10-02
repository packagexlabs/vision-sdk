package io.packagex.visiondemo.scanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.packagex.texttemplates.sdk.PXTemplateInfo
import io.packagex.visiondemo.data.DocumentFields
import io.packagex.visiondemo.data.ItemLabelFeedback
import io.packagex.visiondemo.data.JsonFields
import io.packagex.visiondemo.data.OcrParser
import io.packagex.visiondemo.data.PriceTag
import io.packagex.visiondemo.data.VlmPrompts
import io.packagex.visiondemo.designsystem.Badge
import io.packagex.visiondemo.designsystem.BadgeTone
import io.packagex.visiondemo.designsystem.CloseButton
import io.packagex.visiondemo.designsystem.LinkLabel
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.PXButton
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.designsystem.RecomposeLog
import io.packagex.visiondemo.designsystem.ShimmerRows
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.mono
import io.packagex.visiondemo.designsystem.montserrat
import io.packagex.visiondemo.document.DocumentReview
import io.packagex.visiondemo.model.DetectedCode
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.OcrField
import io.packagex.visiondemo.model.OcrResult
import io.packagex.visiondemo.model.OcrTable
import io.packagex.visiondemo.model.RetrievalRow
import io.packagex.visiondemo.model.ScanResult
import androidx.compose.foundation.layout.union
import kotlin.math.roundToInt

/**
 * The result of the last capture, full screen (v6: no half-height drawer). Fills whatever slot the caller
 * gives it, so the controller can drop it straight into `ScannerScreen`. Ported from iOS `UI/ResultDrawer.swift`.
 *
 * [tags] defaults to empty so existing call sites that only pass `result`/`onAction`
 * still compile; real callers should pass `ScannerUiState.tags` (the Price drawer reads it live).
 */
@Composable
fun ResultDrawer(
    result: ScanResult,
    tags: List<PriceTag> = emptyList(),
    /** `ScannerUiState.items.size`, for the retrieval subtitle (iOS `model.items.count`). */
    itemCount: Int = 0,
    /** Text Templates: the templates loaded into the pool, for "Re-predict as…" (iOS `model.tt`). */
    ttLoaded: List<PXTemplateInfo> = emptyList(),
    /** [ScanResult.Pending]: its extraction is still running (shimmer), not failed. */
    loading: Boolean = false,
    onAction: (ScannerAction) -> Unit,
) {
    RecomposeLog("ResultDrawer")
    // Report/zoom overlays and item-label feedback are keyed on `result` so a new capture (a new
    // ScanResult instance) always starts from a clean drawer, even if the drawer composable itself
    // survives the swap (e.g. a rapid ReopenLast).
    var reportOpen by remember(result) { mutableStateOf(false) }
    var zoomedImage by remember(result) { mutableStateOf<Bitmap?>(null) }
    // Item-label field feedback (the original's extended view): per-field thumbs and corrections,
    // overall comment.
    var ilFeedback by remember(result) { mutableStateOf(mapOf<String, ItemLabelFeedback.Entry>()) }
    var ilComment by remember(result) { mutableStateOf("") }
    var editingField by remember(result) { mutableStateOf<String?>(null) }
    var selectedField by remember(result) { mutableStateOf<String?>(null) }
    // Document: Enhanced / Original (kept across captures, as iOS @State) and the page shown (the newest, iOS onAppear).
    var docEnhanced by remember { mutableStateOf(true) }
    var docPage by remember(result) { mutableIntStateOf((result as? ScanResult.Document)?.pages?.lastIndex ?: 0) }
    // Text Templates: values picked from a field's suggestions (iOS `ttEdits`); a re-predict is a new result.
    var ttEdits by remember(result) { mutableStateOf(mapOf<String, String>()) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                // Clear of the status bar / cutout on top, and of the keyboard (or navigation bar) below.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
        ) {
            DrawerHeader(result = result, tags = tags, itemCount = itemCount, onReport = { reportOpen = true }, onClose = { onAction(ScannerAction.CloseResult) })

            // v6: the captured image sits in its own fixed-height, scrollable frame above the details.
            (result as? ScanResult.Ocr)?.let { boxedOcr(it) }?.let { (img, fields) ->
                ResultImage { BoxedOcrImage(image = img, fields = fields, selected = selectedField, onSelect = { selectedField = it }) }
            }
            (result as? ScanResult.TextTemplate)?.image?.let { img ->
                ResultImage {
                    Image(bitmap = img.asImageBitmap(), contentDescription = "Captured label", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                }
            }
            (result as? ScanResult.Pending)?.image?.let { img ->
                ResultImage {
                    Image(bitmap = img.asImageBitmap(), contentDescription = "Captured photo", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (result) {
                    is ScanResult.Pending -> if (loading) ShimmerRows() else EmptyNote("Nothing was read from this photo.")
                    is ScanResult.Codes -> CodesContent(result.codes, onAction)
                    is ScanResult.Ocr -> OcrContent(
                        scan = result,
                        onAction = onAction,
                        ilFeedback = ilFeedback,
                        onFeedbackChange = { id, e -> ilFeedback = ilFeedback + (id to e) },
                        ilComment = ilComment,
                        onCommentChange = { ilComment = it },
                        onSubmitFeedback = {
                            // iOS clears the comment only when the async result starts with "Feedback
                            // sent" (:369-378). The VM's SendFeedback surface only emits a Toast effect —
                            // it doesn't hand the submit message back to the UI — so we can't gate on
                            // that here; keep the comment and just reset the editing state, as iOS does
                            // unconditionally before the async call.
                            editingField = null
                            onAction(ScannerAction.SendFeedback(ilFeedback, ilComment))
                        },
                        editingField = editingField,
                        onEditingChange = { editingField = it },
                        selectedField = selectedField,
                        onSelectedChange = { selectedField = it },
                    )
                    ScanResult.Price -> PriceContent(tags, onAction)
                    is ScanResult.Retrieval -> RetrievalContent(result.rows, onAction)
                    is ScanResult.TextTemplate -> TtContent(result, ttLoaded, ttEdits, onEdit = { k, v -> ttEdits = ttEdits + (k to v) }, onAction)
                    is ScanResult.Document -> DocumentReview(
                        pages = result.pages,
                        index = docPage,
                        onIndexChange = { docPage = it },
                        enhanced = docEnhanced,
                        onEnhancedChange = { docEnhanced = it },
                        onZoom = { zoomedImage = it },
                        onAddPage = { onAction(ScannerAction.RescanDocument(dropLast = false)) },
                    )
                }
            }

            if (result is ScanResult.Pending) PendingFooter(onAction) else DrawerFooter(result = result, tags = tags, docEnhanced = docEnhanced, edits = ttEdits, onAction = onAction)
        }

        if (reportOpen && result is ScanResult.Ocr) {
            ReportCard(result = result.result, onAction = onAction, onClose = { reportOpen = false })
        }
        if (reportOpen && result is ScanResult.TextTemplate) {
            ReportCard(keys = result.fields.keys.sorted(), onAction = onAction, onClose = { reportOpen = false })
        }
        zoomedImage?.let { img -> ImageViewer(image = img, onClose = { zoomedImage = null }) }
    }
}

// region Header / footer

@Composable
private fun DrawerHeader(result: ScanResult, tags: List<PriceTag>, itemCount: Int, onReport: () -> Unit, onClose: () -> Unit) {
    val (title, subtitle, ok) = titlesFor(result, tags, itemCount)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ok) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(PX.Green), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = PX.Ink, modifier = Modifier.size(10.dp))
                        }
                    }
                    Text(title, style = montserrat(18.sp), color = PX.Ink, maxLines = 1)
                }
                Text(subtitle, style = mono(11.sp), color = PX.Muted)
            }
            if (canReport(result)) {
                Text(
                    "Report", style = montserrat(13.sp), color = PX.Purple,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clickable(onClick = onReport)
                        .padding(horizontal = 8.dp)
                        .padding(top = 12.dp),
                )
            }
            CloseButton(onClick = onClose)
        }
        HorizontalDivider(color = PX.Hairline)
    }
}

@Composable
private fun DrawerFooter(result: ScanResult, tags: List<PriceTag>, docEnhanced: Boolean, edits: Map<String, String>, onAction: (ScannerAction) -> Unit) {
    val context = LocalContext.current
    Column {
        HorizontalDivider(color = PX.Hairline)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 20.dp),
        ) {
            // iOS `ShareLink` (:74-78): the Android share sheet, with the same summary text as Copy.
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, PX.Purple, RoundedCornerShape(12.dp))
                    .clickable {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, summaryFor(result, tags, edits))
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                    .semantics { contentDescription = "Share" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = PX.Purple, modifier = Modifier.size(20.dp))
            }
            if (result is ScanResult.Document) {   // iOS :79-81
                Box(Modifier.weight(1f)) {
                    PXButton(title = "Retake", kind = PXButtonKind.Secondary) { onAction(ScannerAction.RescanDocument(dropLast = true)) }
                }
                Box(Modifier.weight(1f)) { PXButton(title = "Export PDF") { onAction(ScannerAction.ExportPdf(docEnhanced)) } }
            } else {
                Box(Modifier.weight(1f)) {
                    // Label "to clipboard" so the VM's "Copied <label>" toast reads "Copied to clipboard",
                    // matching iOS's fixed string (:83).
                    PXButton(title = "Copy", kind = PXButtonKind.Secondary) {
                        onAction(ScannerAction.Copy("to clipboard", summaryFor(result, tags, edits)))
                    }
                }
                Box(Modifier.weight(1f)) {
                    val label = if (result is ScanResult.Retrieval) "New Scan" else "Scan next"
                    PXButton(title = label) { onAction(ScannerAction.ScanNext) }
                }
            }
        }
    }
}

/** While the photo is being read: Cancel stops the extraction and closes (as Close does). */
@Composable
private fun PendingFooter(onAction: (ScannerAction) -> Unit) {
    Column {
        HorizontalDivider(color = PX.Hairline)
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 20.dp)) {
            PXButton(title = "Cancel", kind = PXButtonKind.Secondary) { onAction(ScannerAction.CloseResult) }
        }
    }
}

private fun canReport(result: ScanResult): Boolean =
    (result is ScanResult.Ocr && result.result.docType.reportSupported) || (result is ScanResult.TextTemplate && result.prediction.scanId != null)

private fun titlesFor(result: ScanResult, tags: List<PriceTag>, itemCount: Int): Triple<String, String, Boolean> = when (result) {
    is ScanResult.Codes -> {
        val n = result.codes.size
        if (n <= 1) {
            // iOS picks the title from `model.mode == .qr` (:99), which isn't available here (the
            // signature has no mode param) — approximate from the code's own symbology instead.
            val symbology = result.codes.firstOrNull()?.symbology.orEmpty()
            val title = if (symbology.contains("QR", ignoreCase = true)) "QR code detected" else "Barcode detected"
            Triple(title, symbology, true)
        } else {
            Triple("$n codes detected", "Multiple scan", true)
        }
    }
    is ScanResult.Ocr -> Triple(result.title, result.subtitle, true)
    is ScanResult.Pending -> Triple(result.title, result.subtitle, false)
    ScanResult.Price -> Triple("Found ${tags.size} Items", "${tags.count { !it.valid }} invalid", true)
    is ScanResult.Retrieval -> {
        val n = result.rows.count { it.inList }
        Triple(
            if (n == 0) "No listed items found" else "$n listed item${if (n == 1) "" else "s"} found",
            "$itemCount codes in list",
            true,
        )
    }
    is ScanResult.Document -> Triple("Document captured", "${result.pages.size} ${if (result.pages.size == 1) "page" else "pages"} · on-device", true)
    is ScanResult.TextTemplate -> result.templateName.let { name ->
        Triple(name ?: "No confident match", "Template match · ${result.path.label} · ${result.fields.size} fields", name != null)
    }
}

private fun summaryFor(result: ScanResult, tags: List<PriceTag>, edits: Map<String, String> = emptyMap()): String = when (result) {
    // iOS :415-416: `.code` is the value only, `.multi` is every value, one per line.
    is ScanResult.Codes -> result.codes.joinToString("\n") { it.value }
    is ScanResult.Ocr -> {
        val documentClass = OcrParser.documentClass(result.result.rawJson)
        if (documentClass != null) {
            DocumentFields.documentClass(documentClass)
        } else {
            val fields = result.result.fields.joinToString("\n") { f -> "${f.section?.let { "$it · " }.orEmpty()}${f.label}: ${f.value}" }
            val tables = result.result.tables.joinToString("\n") { t ->
                (listOf(t.title, t.headers.joinToString("\t")) + t.rows.map { it.joinToString("\t") }).joinToString("\n")
            }
            listOf(fields, tables).filter { it.isNotBlank() }.joinToString("\n")
        }
    }
    ScanResult.Price -> tags.joinToString("\n") { "${it.sku}\t${it.price}\t${if (it.valid) "Valid" else "Invalid"}" }
    is ScanResult.Retrieval -> result.rows.joinToString("\n") { r ->
        listOfNotNull(r.code, if (r.inList) "In list" else "Not in list", r.countText()).joinToString("\t")
    }
    is ScanResult.Pending -> ""
    is ScanResult.Document -> "Scanned document · ${result.pages.size} ${if (result.pages.size == 1) "page" else "pages"}"
    is ScanResult.TextTemplate -> result.fields.toSortedMap().entries.joinToString("\n") { (k, f) -> "$k: ${edits[k] ?: f.text}" }
}

// endregion

// region Content per result type

@Composable
private fun CodesContent(codes: List<DetectedCode>, onAction: (ScannerAction) -> Unit) {
    codes.forEach { c ->
        DarkCard(label = c.symbology, value = c.value, size = if (codes.size == 1) 24.sp else 16.sp) {
            onAction(ScannerAction.Copy(c.symbology, c.value))
        }
        KVList(rows = listOf("Bounding box" to "${c.box.left}, ${c.box.top} · ${c.box.right - c.box.left} × ${c.box.bottom - c.box.top} px"))
    }
}

@Composable
private fun PriceContent(tags: List<PriceTag>, onAction: (ScannerAction) -> Unit) {
    if (tags.isEmpty()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        ) {
            Icon(Icons.Filled.LocalOffer, contentDescription = null, tint = PX.Purple, modifier = Modifier.size(36.dp))
            Text(
                "Point the phone's camera to a barcode to validate its price tag",
                style = inter(15.sp), color = PX.Muted, textAlign = TextAlign.Center,
            )
        }
    } else {
        Column {
            tags.forEach { t ->
                RowLine(label = if (t.name == t.sku) "SKU" else "${t.name} · SKU", value = t.sku) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(t.price, style = mono(16.sp), color = PX.Ink)
                        Text("Expected ${t.expected}", style = inter(11.sp), color = PX.Muted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Badge(text = if (t.valid) "Valid" else "Invalid", tone = if (t.valid) BadgeTone.Success else BadgeTone.Danger, dot = true)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            LinkLabel(text = "Clear tags", color = PX.RedText) { onAction(ScannerAction.ClearTags) }
        }
    }
}

@Composable
private fun RetrievalContent(rows: List<RetrievalRow>, onAction: (ScannerAction) -> Unit) {
    if (rows.isEmpty()) {
        EmptyNote(text = "No codes in view. Pan across the shelf, then capture.")
    } else {
        Column {
            rows.forEach { r ->
                RowLine(label = "Code", value = r.code) {
                    r.countText()?.let { Text(it, style = mono(14.sp), color = PX.Text2) }
                    Badge(text = if (r.inList) "In list" else "Not in list", tone = if (r.inList) BadgeTone.Success else BadgeTone.Neutral, dot = r.inList)
                }
            }
        }
    }
    LinkLabel(text = "Open item list") { onAction(ScannerAction.OpenItemList) }
}

/** Text Templates: one row per predicted field with its confidence and suggestions, the frame's barcodes, and
 *  "Re-predict as…" any other loaded template (iOS `ttContent`). */
@Composable
private fun TtContent(
    result: ScanResult.TextTemplate,
    loaded: List<PXTemplateInfo>,
    edits: Map<String, String>,
    onEdit: (String, String) -> Unit,
    onAction: (ScannerAction) -> Unit,
) {
    Column {
        result.fields.toSortedMap().forEach { (k, f) ->
            val pct = (f.confidence * (if (f.confidence <= 1f) 100f else 1f)).roundToInt()
            // The value plus the SDK's alternatives, deduplicated (the original review screen's "Change" menu).
            val options = (listOf(f.text) + f.suggestions.orEmpty().map { it.text }).distinct()
            val value = edits[k] ?: f.text
            RowLine(label = JsonFields.humanize(k), value = value.ifEmpty { "—" }) {
                if (options.size > 1) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .heightIn(min = 28.dp)
                                .clip(CircleShape)
                                .background(PX.Purple.copy(alpha = 0.12f))
                                .clickable { open = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .semantics { contentDescription = "Change ${JsonFields.humanize(k)}" },
                        ) {
                            Text("Change", style = montserrat(11.sp), color = PX.Purple)
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = PX.Purple, modifier = Modifier.size(12.dp))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Color.White) {
                            options.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.ifEmpty { "—" }, style = inter(14.sp), color = PX.Ink) },
                                    leadingIcon = if (opt == value) { { Icon(Icons.Filled.Check, contentDescription = null, tint = PX.Purple) } } else null,
                                    onClick = { open = false; onEdit(k, opt) },
                                )
                            }
                        }
                    }
                }
                Badge(text = "$pct%", tone = if (pct >= 90) BadgeTone.Success else if (pct >= 75) BadgeTone.Brand else BadgeTone.Danger)
            }
        }
        if (result.fields.isEmpty()) EmptyNote("No fields predicted. Re-predict as another template, or scan again.")
    }
    val barcodes = result.repredicted?.barcodes ?: result.prediction.barcodes
    if (barcodes.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Barcodes (${barcodes.size})", style = montserrat(11.sp), color = PX.Muted)
            barcodes.forEach { b -> Text("${b.format} · ${b.data}", style = mono(14.sp), color = PX.Ink) }
        }
    }
    // Any loaded template, as the original's template menu (not just the detector's candidates).
    val others = loaded.filter { it.name != result.templateName }
    if (result.prediction.scanId != null && others.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Re-predict as…", style = montserrat(13.sp), color = PX.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                others.forEach { t ->
                    Text(
                        t.name,
                        style = inter(13.sp),
                        color = PX.Ink,
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, PX.Hairline, RoundedCornerShape(10.dp))
                            .clickable { onAction(ScannerAction.TtRepredict(t.id)) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OcrContent(
    scan: ScanResult.Ocr,
    onAction: (ScannerAction) -> Unit,
    ilFeedback: Map<String, ItemLabelFeedback.Entry>,
    onFeedbackChange: (String, ItemLabelFeedback.Entry) -> Unit,
    ilComment: String,
    onCommentChange: (String) -> Unit,
    onSubmitFeedback: () -> Unit,
    editingField: String?,
    onEditingChange: (String?) -> Unit,
    selectedField: String?,
    onSelectedChange: (String?) -> Unit,
) {
    val o = scan.result
    val documentClass = remember(o.rawJson) { OcrParser.documentClass(o.rawJson) }
    if (documentClass != null) {
        DocumentClassCard(DocumentFields.documentClass(documentClass))
        return
    }

    val image = scan.image
    val feedbackOn = o.docType == DocType.IL
    val hasFieldBoxes = o.fields.any { it.vertices != null }
    val boxed = image != null && hasFieldBoxes

    // The boxed image itself is drawn above the details ([ResultImage]); its legend stays here.
    if (hasFieldBoxes && image != null && o.fields.any { it.validatedBy.isNotEmpty() }) ValidationLegend()

    o.primary?.let { primary ->
        DarkCard(label = primary.label, value = primary.value, size = 22.sp) {
            onAction(ScannerAction.Copy(primary.label, primary.value))
        }
    }

    // iOS :182 (`o.cloud, o.docType.vlmPrompt == nil`), not gated on `boxed`.
    if (o.cloud && VlmPrompts.spec(o.docType) == null) {
        Text(
            "Cloud results do not include field locations. Switch to On-device to see linked boxes.",
            style = inter(13.sp), color = PX.Text2,
            modifier = Modifier.fillMaxWidth().background(PX.Surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }

    Text(
        if (boxed) (o.fields.firstOrNull { it.id == selectedField }?.label ?: "Tap a box or a field to link them") else "${o.fields.size} fields",
        style = inter(12.sp, FontWeight.Medium), color = PX.Muted,
    )

    Column {
        o.fields.forEachIndexed { i, f ->
            val sec = f.section
            if (sec != null && (i == 0 || o.fields[i - 1].section != sec)) {
                Text(
                    sec.uppercase(), style = montserrat(11.sp), color = PX.Muted,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                )
            }
            // iOS :183-238 shows the primary field again here (large card above + full row below);
            // ported as-is, ambiguous-test risk handled on the test side (assertCountEquals).
            OcrFieldRow(
                field = f,
                index = i,
                selected = selectedField == f.id,
                feedbackOn = feedbackOn,
                editing = editingField == f.id,
                entry = ilFeedback[f.id],
                onSelect = { if (f.vertices != null) onSelectedChange(if (selectedField == f.id) null else f.id) },
                onEditToggle = { onEditingChange(if (editingField == f.id) null else f.id) },
                onEditChange = { onFeedbackChange(f.id, ItemLabelFeedback.Entry(it, ilFeedback[f.id]?.thumbs)) },
                onThumbs = { onFeedbackChange(f.id, ItemLabelFeedback.Entry(ilFeedback[f.id]?.edited ?: f.value, it)) },
                // iOS :222 always copies the original `f.value`, even mid-edit.
                onCopy = { onAction(ScannerAction.Copy(f.label, f.value)) },
            )
        }
    }

    o.tables.forEach { t -> TableCard(t) }

    if (feedbackOn) {
        ItemLabelFeedbackSection(result = o, entries = ilFeedback, comment = ilComment, onCommentChange = onCommentChange, onSubmit = onSubmitFeedback)
    }
}

// endregion

// region OCR field list / boxed image

@Composable
private fun OcrFieldRow(
    field: OcrField,
    index: Int,
    selected: Boolean,
    feedbackOn: Boolean,
    editing: Boolean,
    entry: ItemLabelFeedback.Entry?,
    onSelect: () -> Unit,
    onEditToggle: () -> Unit,
    onEditChange: (String) -> Unit,
    onThumbs: (Boolean?) -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFFDAFBF9) else Color.Transparent)
            .clickable(enabled = field.vertices != null, onClick = onSelect)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    ) {
        if (field.vertices != null) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) PX.Neon else PX.Purple),
                contentAlignment = Alignment.Center,
            ) { Text("${index + 1}", style = montserrat(11.sp), color = if (selected) PX.Ink else Color.White) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(field.label, style = montserrat(11.sp), color = PX.Muted)
            if (feedbackOn && editing) {
                OutlinedTextField(
                    value = entry?.edited ?: field.value,
                    onValueChange = onEditChange,
                    singleLine = true,
                    textStyle = inter(14.sp, FontWeight.Medium),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val edited = entry?.edited
                val shown = edited ?: field.value
                // iOS :209: `f.mono ? .mono(13) : .inter(14, .medium)`, from the original value.
                Text(shown, style = if (field.mono) mono(13.sp) else inter(14.sp, FontWeight.Medium), color = PX.Ink)
                if (edited != null && edited != field.value) {
                    Text(field.value, style = inter(12.sp), color = PX.Muted, textDecoration = TextDecoration.LineThrough)
                }
            }
        }
        if (feedbackOn) {
            FieldFeedbackButtons(entry = entry, editing = editing, onThumbs = onThumbs, onEdit = onEditToggle)
        }
        Box(
            Modifier.size(44.dp).clickable(onClick = onCopy).semantics { contentDescription = "Copy" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = PX.Muted, modifier = Modifier.size(15.dp))
        }
    }
}

/**
 * Captured image with numbered, tappable field boxes (on-device results only). Not a zoom target —
 * iOS only opens `ImageViewer` from document-acquisition page thumbnails (Task 12).
 */
/** The OCR result's image and fields when its fields have boxes to draw (not for a document class). */
private fun boxedOcr(scan: ScanResult.Ocr): Pair<Bitmap, List<OcrField>>? {
    val image = scan.image ?: return null
    if (scan.result.fields.none { it.vertices != null } || OcrParser.documentClass(scan.result.rawJson) != null) return null
    return image to scan.result.fields
}

/** Design "Result image": a 320 dp frame the image scrolls inside, with a hint while there is more to see. */
@Composable
private fun ResultImage(content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Box(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PX.Ink)
            .border(1.dp, PX.Hairline, RoundedCornerShape(12.dp)),
    ) {
        Box(Modifier.fillMaxWidth().verticalScroll(scroll)) { content() }
        if (scroll.maxValue > 0) {
            Text(
                "Scroll to see the full image",
                style = montserrat(11.sp),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .background(PX.Ink.copy(alpha = 0.72f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun BoxedOcrImage(image: Bitmap, fields: List<OcrField>, selected: String?, onSelect: (String?) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(image.width.toFloat() / image.height.toFloat())
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Image(bitmap = image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        fields.forEachIndexed { i, f ->
            val rect = fieldBox(f, image.width, image.height) ?: return@forEachIndexed
            val on = selected == f.id
            val tint = if (on) PX.Neon else colorFor(f.validatedBy)
            val boxX = maxWidth * rect.left
            val boxY = maxHeight * rect.top
            Box(
                modifier = Modifier
                    .offset(x = boxX, y = boxY)
                    .size(width = maxWidth * (rect.right - rect.left), height = maxHeight * (rect.bottom - rect.top))
                    .zIndex(if (on) 1f else 0f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tint.copy(alpha = if (on) 0.3f else 0.16f))
                    .border(2.dp, tint, RoundedCornerShape(4.dp))
                    .clickable { onSelect(if (on) null else f.id) },
            )
            // iOS :449-453: the number chip is a separate overlay above-left of the box (offset
            // -2/-14), always neon/purple — not the box's own validated_by tint — and must sit
            // outside the box's clip so it isn't cropped.
            Box(
                modifier = Modifier
                    .offset(x = boxX - 2.dp, y = boxY - 14.dp)
                    .zIndex(if (on) 1f else 0f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (on) PX.Neon else PX.Purple),
            ) {
                Text(
                    "${i + 1}", style = montserrat(9.sp), color = if (on) PX.Ink else Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** iOS `sourceColor`/legend colours (Types.swift :141-145): system green/red/blue. */
@Composable
private fun ValidationLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Barcode" to iosGreen, "Rule" to iosRed, "ML" to iosBlue).forEach { (label, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(width = 12.dp, height = 10.dp).clip(RoundedCornerShape(2.dp)).border(2.dp, c, RoundedCornerShape(2.dp)))
                Text(label, style = inter(12.sp), color = PX.Muted)
            }
        }
    }
}

private val iosGreen = Color(0xFF34C759)
private val iosRed = Color(0xFFFF3B30)
private val iosBlue = Color(0xFF007AFF)

/** iOS `OCRField.sourceColor` (Types.swift :141-145): BARCODE -> green, APRIORI -> red, any other
 *  non-empty validated_by -> blue, none -> the default purple box tint. */
private fun colorFor(validatedBy: List<String>): Color = when {
    "BARCODE" in validatedBy -> iosGreen
    "APRIORI" in validatedBy -> iosRed
    validatedBy.isNotEmpty() -> iosBlue
    else -> PX.Purple
}

/**
 * On-device responses carry raw vertices (TL, TR, BL, BR) in the *captured image's own pixel
 * space*, not normalised 0..1 like iOS's precomputed `box: CGRect?`. We detect which we got (any
 * coordinate > ~1 means raw pixels) and, if raw, divide by the bitmap's width/height so the box
 * lines up with the image drawn at any display size.
 */
private fun fieldBox(field: OcrField, bitmapW: Int, bitmapH: Int): RectF? {
    val v = field.vertices ?: return null
    if (v.size < 4 || bitmapW <= 0 || bitmapH <= 0) return null
    val xs = v.map { it.getOrElse(0) { 0.0 } }
    val ys = v.map { it.getOrElse(1) { 0.0 } }
    val maxX = xs.max()
    val maxY = ys.max()
    val normalized = maxX <= 1.01 && maxY <= 1.01
    val nx = if (normalized) xs else xs.map { it / bitmapW }
    val ny = if (normalized) ys else ys.map { it / bitmapH }
    val left = nx.min().toFloat().coerceIn(0f, 1f)
    val top = ny.min().toFloat().coerceIn(0f, 1f)
    val right = nx.max().toFloat().coerceIn(0f, 1f)
    val bottom = ny.max().toFloat().coerceIn(0f, 1f)
    if (right <= left || bottom <= top) return null
    return RectF(left, top, right, bottom)
}

// endregion

// region Small shared views (ported from iOS `UI/ResultDrawer.swift`)

@Composable
private fun DarkCard(label: String, value: String, size: TextUnit, onCopy: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PX.Ink)
            .padding(top = 14.dp, bottom = 14.dp, start = 16.dp, end = if (onCopy == null) 16.dp else 8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = montserrat(11.sp), color = PX.Neon)
            Text(value, style = mono(size), color = Color.White)
        }
        if (onCopy != null) {
            Box(
                Modifier.size(44.dp).clickable(onClick = onCopy).semantics { contentDescription = "Copy" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DocumentClassCard(cls: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PX.Ink).padding(vertical = 18.dp, horizontal = 16.dp),
    ) {
        Icon(Icons.Filled.Description, contentDescription = null, tint = PX.Neon, modifier = Modifier.size(30.dp))
        Column {
            Text("Document class", style = montserrat(11.sp), color = PX.Neon)
            Text(cls, style = mono(20.sp), color = Color.White)
        }
    }
}

@Composable
private fun KVList(rows: List<Pair<String, String>>) {
    Column {
        rows.forEach { (k, v) ->
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            ) {
                Text(k, style = inter(14.sp), color = PX.Ink)
                Text(v, style = mono(13.sp), color = PX.Text2, textAlign = TextAlign.End)
            }
            HorizontalDivider(color = PX.Hairline)
        }
    }
}

@Composable
private fun RowLine(label: String, value: String, trailing: @Composable RowScope.() -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = montserrat(11.sp), color = PX.Muted)
                Text(value, style = mono(14.sp), color = PX.Ink)
            }
            trailing()
        }
        HorizontalDivider(color = PX.Hairline)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text, style = inter(14.sp), color = PX.Muted, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
    )
}

/** Header + rows grid for tables in extraction responses (BOL tables, invoice/receipt line items). */
@Composable
private fun TableCard(table: OcrTable) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (table.title.isNotEmpty()) Text(table.title.uppercase(), style = montserrat(11.sp), color = PX.Muted)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(PX.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, PX.Hairline, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                table.headers.forEach { h -> Text(h, style = montserrat(11.sp), color = PX.Muted, modifier = Modifier.width(140.dp)) }
            }
            HorizontalDivider(color = PX.Hairline)
            table.rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { cell -> Text(cell, style = inter(13.sp), color = PX.Ink, modifier = Modifier.width(140.dp)) }
                }
            }
        }
    }
}

// endregion
