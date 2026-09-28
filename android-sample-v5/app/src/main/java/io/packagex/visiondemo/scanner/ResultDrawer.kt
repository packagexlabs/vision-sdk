package io.packagex.visiondemo.scanner

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import io.packagex.visiondemo.data.ItemLabelFeedback
import io.packagex.visiondemo.data.OcrParser
import io.packagex.visiondemo.data.PriceTag
import io.packagex.visiondemo.designsystem.Badge
import io.packagex.visiondemo.designsystem.BadgeTone
import io.packagex.visiondemo.designsystem.CloseButton
import io.packagex.visiondemo.designsystem.LinkLabel
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.PXButton
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.mono
import io.packagex.visiondemo.designsystem.montserrat
import io.packagex.visiondemo.model.DetectedCode
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.OcrField
import io.packagex.visiondemo.model.OcrResult
import io.packagex.visiondemo.model.OcrTable
import io.packagex.visiondemo.model.ScanResult
import io.packagex.visiondemo.model.SheetKind

/**
 * Bottom drawer with the result of the last capture; drag handle (or a drag gesture) toggles
 * half / expanded. Fills whatever slot the caller gives it (a full-screen `Box`) and anchors
 * itself to the bottom, so the controller can drop it straight into `ScannerScreen`.
 * Ported from iOS `UI/ResultDrawer.swift`.
 *
 * [tags] defaults to empty so existing call sites that only pass `result`/`expanded`/`onAction`
 * still compile; real callers should pass `ScannerUiState.tags` (the Price drawer reads it live).
 */
@Composable
fun ResultDrawer(
    result: ScanResult,
    tags: List<PriceTag> = emptyList(),
    expanded: Boolean,
    onAction: (ScannerAction) -> Unit,
) {
    var reportOpen by remember { mutableStateOf(false) }
    var zoomedImage by remember { mutableStateOf<Bitmap?>(null) }
    // Item-label field feedback (the original's extended view): per-field thumbs and corrections,
    // overall comment. Reset whenever a new result is shown.
    var ilFeedback by remember(result) { mutableStateOf(mapOf<String, ItemLabelFeedback.Entry>()) }
    var ilComment by remember(result) { mutableStateOf("") }
    var editingField by remember(result) { mutableStateOf<String?>(null) }
    var selectedField by remember(result) { mutableStateOf<String?>(null) }
    var dragTotal by remember { mutableStateOf(0f) }

    val heightFraction by animateFloatAsState(
        targetValue = if (expanded) 0.89f else 0.46f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "resultDrawerHeight",
    )

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(heightFraction)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color.White)
                .pointerInput(expanded) {
                    detectVerticalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragEnd = {
                            when {
                                dragTotal < -40f && !expanded -> onAction(ScannerAction.ToggleExpanded)
                                dragTotal > 60f && expanded -> onAction(ScannerAction.ToggleExpanded)
                                dragTotal > 60f && !expanded -> onAction(ScannerAction.CloseResult)
                            }
                        },
                    ) { change, amount -> dragTotal += amount; change.consume() }
                },
        ) {
            // Drag handle.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 22.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        onAction(ScannerAction.ToggleExpanded)
                    }
                    .semantics { contentDescription = if (expanded) "Collapse" else "Expand" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(40.dp).height(5.dp).clip(RoundedCornerShape(50)).background(PX.SwitchOff))
            }

            DrawerHeader(result = result, tags = tags, onReport = { reportOpen = true }, onClose = { onAction(ScannerAction.CloseResult) })

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (result) {
                    is ScanResult.Codes -> CodesContent(result.codes, onAction)
                    is ScanResult.Ocr -> OcrContent(
                        scan = result,
                        onAction = onAction,
                        ilFeedback = ilFeedback,
                        onFeedbackChange = { id, e -> ilFeedback = ilFeedback + (id to e) },
                        ilComment = ilComment,
                        onCommentChange = { ilComment = it },
                        onSubmitFeedback = { onAction(ScannerAction.SendFeedback(ilFeedback, ilComment)); ilComment = "" },
                        editingField = editingField,
                        onEditingChange = { editingField = it },
                        selectedField = selectedField,
                        onSelectedChange = { selectedField = it },
                        onZoom = { zoomedImage = it },
                    )
                    ScanResult.Price -> PriceContent(tags, onAction)
                    is ScanResult.Retrieval -> RetrievalContent(result.codes, onAction)
                    is ScanResult.Ar -> ArContent(result)
                    is ScanResult.Document -> DocumentContent(result)
                }
            }

            DrawerFooter(result = result, tags = tags, onAction = onAction)
        }

        if (reportOpen && result is ScanResult.Ocr) {
            ReportCard(result = result.result, onAction = onAction, onClose = { reportOpen = false })
        }
        zoomedImage?.let { img -> ImageViewer(image = img, onClose = { zoomedImage = null }) }
    }
}

// region Header / footer

@Composable
private fun DrawerHeader(result: ScanResult, tags: List<PriceTag>, onReport: () -> Unit, onClose: () -> Unit) {
    val (title, subtitle, ok) = titlesFor(result, tags)
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
private fun DrawerFooter(result: ScanResult, tags: List<PriceTag>, onAction: (ScannerAction) -> Unit) {
    Column {
        HorizontalDivider(color = PX.Hairline)
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 20.dp),
        ) {
            Box(Modifier.weight(1f)) {
                PXButton(title = "Copy", kind = PXButtonKind.Secondary) {
                    onAction(ScannerAction.Copy("Result", summaryFor(result, tags)))
                }
            }
            Box(Modifier.weight(1f)) {
                val label = if (result is ScanResult.Ar) "New Scan" else "Scan next"
                PXButton(title = label) { onAction(ScannerAction.ScanNext) }
            }
        }
    }
}

private fun canReport(result: ScanResult): Boolean = result is ScanResult.Ocr && result.result.docType.reportSupported

private fun titlesFor(result: ScanResult, tags: List<PriceTag>): Triple<String, String, Boolean> = when (result) {
    is ScanResult.Codes -> {
        val n = result.codes.size
        // The symbology/value already appear in the dark card below, so the subtitle stays generic
        // (Android's `DetectedCode` carries no capture-timing field to show here, unlike iOS).
        if (n <= 1) Triple("Code detected", "Barcode", true)
        else Triple("$n codes detected", "Multiple scan", true)
    }
    is ScanResult.Ocr -> Triple(result.title, result.subtitle, true)
    ScanResult.Price -> Triple("Found ${tags.size} Items", "${tags.count { !it.valid }} invalid", true)
    is ScanResult.Retrieval -> {
        val n = result.codes.count { it.second }
        Triple(
            if (n == 0) "No listed items found" else "$n listed item${if (n == 1) "" else "s"} found",
            "${result.codes.size} code${if (result.codes.size == 1) "" else "s"} in view",
            true,
        )
    }
    is ScanResult.Ar -> Triple("Scan Results", "${result.rows.sumOf { it.third }} barcodes · ${result.rows.size} unique", true)
    is ScanResult.Document -> Triple("Document captured", "${result.pageCount} ${if (result.pageCount == 1) "page" else "pages"}", true)
}

private fun summaryFor(result: ScanResult, tags: List<PriceTag>): String = when (result) {
    is ScanResult.Codes -> result.codes.joinToString("\n") { "${it.symbology}\t${it.value}" }
    is ScanResult.Ocr -> {
        val fields = result.result.fields.joinToString("\n") { f -> "${f.section?.let { "$it · " }.orEmpty()}${f.label}: ${f.value}" }
        val tables = result.result.tables.joinToString("\n") { t ->
            (listOf(t.title, t.headers.joinToString("\t")) + t.rows.map { it.joinToString("\t") }).joinToString("\n")
        }
        listOf(fields, tables).filter { it.isNotBlank() }.joinToString("\n")
    }
    ScanResult.Price -> tags.joinToString("\n") { "${it.sku}\t${it.price}\t${if (it.valid) "Valid" else "Invalid"}" }
    is ScanResult.Retrieval -> result.codes.joinToString("\n") { "${it.first}\t${if (it.second) "In list" else "Not in list"}" }
    is ScanResult.Ar -> result.rows.joinToString("\n") { "${it.first} × ${it.third}" }
    is ScanResult.Document -> "Scanned document · ${result.pageCount} ${if (result.pageCount == 1) "page" else "pages"}"
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
private fun RetrievalContent(codes: List<Pair<String, Boolean>>, onAction: (ScannerAction) -> Unit) {
    if (codes.isEmpty()) {
        EmptyNote(text = "No codes in view. Pan across the shelf, then capture.")
    } else {
        Column {
            codes.forEach { (code, inList) ->
                RowLine(label = "Code", value = code) {
                    Badge(text = if (inList) "In list" else "Not in list", tone = if (inList) BadgeTone.Success else BadgeTone.Neutral, dot = inList)
                }
            }
        }
    }
    LinkLabel(text = "Open item list") { onAction(ScannerAction.OpenSheet(SheetKind.Items)) }
}

@Composable
private fun ArContent(result: ScanResult.Ar) {
    if (result.rows.isEmpty()) {
        EmptyNote(text = "AR results are coming soon.")
    } else {
        Column {
            result.rows.forEach { (value, symbology, count) ->
                RowLine(label = symbology, value = value) {
                    Text("× $count", style = mono(14.sp), color = PX.Text2)
                }
            }
        }
    }
}

@Composable
private fun DocumentContent(result: ScanResult.Document) {
    EmptyNote(text = "${result.pageCount} ${if (result.pageCount == 1) "page" else "pages"} captured. Document review is coming soon.")
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
    onZoom: (Bitmap) -> Unit,
) {
    val o = scan.result
    val documentClass = remember(o.rawJson) { OcrParser.documentClass(o.rawJson) }
    if (documentClass != null) {
        DocumentClassCard(documentClass)
        return
    }

    val image = scan.image
    val feedbackOn = o.docType == DocType.IL
    val hasOnDeviceAlt = o.docType == DocType.SL || o.docType == DocType.BOL || o.docType == DocType.IL || o.docType == DocType.DC
    val hasFieldBoxes = o.fields.any { it.vertices != null }
    val boxed = image != null && hasFieldBoxes

    if (hasFieldBoxes) {
        image?.let { img ->
            BoxedOcrImage(image = img, fields = o.fields, selected = selectedField, onSelect = onSelectedChange, onZoom = { onZoom(img) })
            if (o.fields.any { it.validatedBy.isNotEmpty() }) ValidationLegend()
        }
    }

    o.primary?.let { primary ->
        DarkCard(label = primary.label, value = primary.value, size = 22.sp) {
            onAction(ScannerAction.Copy(primary.label, primary.value))
        }
    }

    if (!boxed && hasOnDeviceAlt && scan.subtitle.startsWith("Cloud")) {
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
            // Shown large above already; skip it here so its value isn't duplicated verbatim.
            if (o.primary != null && f.id == o.primary.id) return@forEachIndexed
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
                onCopy = { onAction(ScannerAction.Copy(f.label, ilFeedback[f.id]?.edited ?: f.value)) },
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
                Text(edited ?: field.value, style = inter(14.sp, FontWeight.Medium), color = PX.Ink)
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

/** Captured image with numbered, tappable field boxes (on-device results only). Tap to zoom full-screen. */
@Composable
private fun BoxedOcrImage(image: Bitmap, fields: List<OcrField>, selected: String?, onSelect: (String?) -> Unit, onZoom: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(image.width.toFloat() / image.height.toFloat())
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Image(
            bitmap = image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().clickable(onClick = onZoom),
        )
        fields.forEachIndexed { i, f ->
            val rect = fieldBox(f, image.width, image.height) ?: return@forEachIndexed
            val on = selected == f.id
            val tint = if (on) PX.Neon else colorFor(f.validatedBy)
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * rect.left, y = maxHeight * rect.top)
                    .size(width = maxWidth * (rect.right - rect.left), height = maxHeight * (rect.bottom - rect.top))
                    .clip(RoundedCornerShape(4.dp))
                    .background(tint.copy(alpha = if (on) 0.3f else 0.16f))
                    .border(2.dp, tint, RoundedCornerShape(4.dp))
                    .clickable { onSelect(if (on) null else f.id) },
            ) {
                Text(
                    "${i + 1}", style = montserrat(9.sp), color = if (on) PX.Ink else Color.White,
                    modifier = Modifier.background(tint, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ValidationLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Barcode" to Color(0xFF2E7D32), "Rule" to Color(0xFFC62828), "ML" to Color(0xFF1565C0)).forEach { (label, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(width = 12.dp, height = 10.dp).clip(RoundedCornerShape(2.dp)).border(2.dp, c, RoundedCornerShape(2.dp)))
                Text(label, style = inter(12.sp), color = PX.Muted)
            }
        }
    }
}

private fun colorFor(validatedBy: List<String>): Color = when {
    "BARCODE" in validatedBy -> Color(0xFF2E7D32)
    "APRIORI" in validatedBy -> Color(0xFFC62828)
    "ML" in validatedBy -> Color(0xFF1565C0)
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
