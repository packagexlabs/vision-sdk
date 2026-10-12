package io.packagex.visiondemo.scanner

import io.packagex.arcount.CountView
import io.packagex.arcount.Gtin
import io.packagex.arcount.ItemCount
import io.packagex.visiondemo.ar.promptText
import io.packagex.texttemplates.sdk.PXGuidance
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.data.VlmPrompts
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.model.Box
import io.packagex.visiondemo.model.DetectedCode
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.Processing
import io.packagex.visiondemo.model.RetrievalRow
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visionsdk.dto.ScannedCodeResult
import io.packagex.visionsdk.exceptions.VisionSDKException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Pure rules behind [ScannerViewModel], ported from iOS `DemoModel`. */

/** [ttStream]: Text Templates in Stream, where `PXScannerView` runs its own CameraX pipeline (iOS usesScanner).
 *  [countStep]: AR Item Count runs the AR session only for counting; its step 1 reads barcodes with the SDK (design v6). */
internal fun ownerFor(mode: ScanMode, ttStream: Boolean = false, countStep: Boolean = true) = when (mode) {
    ScanMode.Retrieval -> if (countStep) CameraOwner.Ar else CameraOwner.Scanner
    ScanMode.DocAcq -> CameraOwner.Document
    ScanMode.TextTemplates -> if (ttStream) CameraOwner.TextTemplates else CameraOwner.Scanner
    else -> CameraOwner.Scanner
}

/** Doc types with an on-device model (the VLM prompts are cloud-only). */
internal val DocType.onDevice get() = this in setOf(DocType.SL, DocType.BOL, DocType.IL, DocType.DC)

/** A cloud VLM type (the default prompt or a custom one, [VlmPrompts]) is picked: it reads any photo, so capture
 *  needs no text or document in view (wild card picks its own type, so it isn't one). */
internal val Prefs.vlm get() = !wildCard && (docType == DocType.VLM || VlmPrompts.spec(docType) != null)

/** Vision Scanner Auto with a VLM type: nothing to detect, so it captures once the live view has held [VLM_AUTO_MS]. */
internal fun vlmAutoArmed(s: ScannerUiState) = !s.home && s.mode == ScanMode.Ocr && s.prefs.autoCapture && s.prefs.vlm &&
    s.result == null && s.sheet == null && s.alert == null && s.phase == Phase.Idle && !s.paused && !s.gated &&
    !s.permissionDenied && s.detectionEnabled

/** The SDK's own wait before a document auto capture (`secondsToWaitBeforeDocumentCapture`). */
internal const val VLM_AUTO_MS = 3_000L

/** iOS `cloudSelected`: wild card picks its own routes; otherwise cloud unless on-device is chosen and exists. */
internal fun cloudSelected(p: Prefs) = !p.wildCard && (!p.docType.onDevice || p.processing == Processing.Cloud)

/** iOS `activeModel`: document classification is always micro; Android ships BOL and IL in large only. */
internal fun activeModel(p: Prefs): Pair<DocType, ModelSize>? = when (p.docType) {
    DocType.DC -> DocType.DC to ModelSize.Micro
    DocType.BOL, DocType.IL -> p.docType to ModelSize.Large
    DocType.SL -> DocType.SL to p.modelSize
    else -> null
}

/** Settings "Reset to defaults" (iOS Sheets.swift:161-166; `detectionEnabled` is reset by the ViewModel). */
internal val resetPrefs: (Prefs) -> Prefs = {
    it.copy(multi = false, showBoxes = true, showHints = true, wildCard = false, parseRecipient = true, parseSender = true, modelSize = ModelSize.Micro)
}

/** Runs [block]; returns the user-facing error (null on success, and for a cancelled download, which toasts itself). */
internal suspend fun runCatchingModel(block: suspend () -> Unit): String? = try {
    block()
    null
} catch (e: CancellationException) {
    throw e
} catch (e: VisionSDKException.ModelDownloadCancelledException) {
    null
} catch (e: IOException) {
    "Download failed. Check the connection."
} catch (e: Exception) {
    (e as? VisionSDKException)?.errorMessage ?: e.message ?: "Something went wrong"
}

internal val useCloud: (Prefs) -> Prefs = { it.copy(processing = Processing.Cloud) }
internal val useDevice: (Prefs) -> Prefs = { it.copy(processing = Processing.Device) }

/** iOS `ensureModelReady`'s prompt for an on-device model that isn't loaded yet. */
internal fun modelPrompt(t: DocType, s: ModelSize, state: ModelState): Alert {
    val name = "${t.label} · ${if (s == ModelSize.Micro) "micro" else "large"}"
    val acts = buildList {
        when (state) {
            ModelState.Downloaded -> add(AlertAction("Load model", action = ScannerAction.LoadModel(t, s)))
            is ModelState.Downloading -> {}
            else -> add(AlertAction("Download and load", action = ScannerAction.DownloadModel(t, s, thenLoad = true)))
        }
        add(AlertAction("Use Cloud instead", PXButtonKind.Secondary, ScannerAction.UpdatePrefs(useCloud)))
        add(AlertAction("Cancel", PXButtonKind.Tertiary, ScannerAction.DismissAlert))
    }
    val why = when (state) {
        is ModelState.Downloading -> "still downloading"
        ModelState.Downloaded -> "downloaded but not loaded"
        else -> "not downloaded"
    }
    return Alert("On-device model not loaded", "$name is $why. Load it to extract on this device.", acts)
}

internal fun ScannedCodeResult.toDetected() = DetectedCode(
    value = scannedCode,
    symbology = symbology.stringValue,
    box = Box(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom),
)

/** Wholly inside [frame] (iOS `CGRect.contains(CGRect)`). */
internal fun Box.inside(frame: Box) =
    left >= frame.left && top >= frame.top && right <= frame.right && bottom <= frame.bottom

/** iOS shutter in Item retrieval with an empty list. */
internal val noItemsAlert = Alert(
    "No items to find",
    "Add item codes to the list first. The scanner then reports which of them are in view.",
    listOf(
        AlertAction("Open item list", action = ScannerAction.OpenSheet(SheetKind.Items)),
        AlertAction("Cancel", PXButtonKind.Tertiary, ScannerAction.DismissAlert),
    ),
)

/** iOS `ttNotLoaded`: shutter in Text Templates with an empty pool. */
internal fun ttNotLoadedAlert(hasEmail: Boolean) = Alert(
    "No templates loaded",
    "No templates loaded — load them before scanning a label.",
    listOf(
        if (hasEmail) AlertAction("Sync and load templates", action = ScannerAction.TtLoad)
        else AlertAction("Enter your email", action = ScannerAction.OpenSheet(SheetKind.TtSetup)),
        AlertAction("Cancel", PXButtonKind.Tertiary, ScannerAction.DismissAlert),
    ),
)

/** Text Templates Stream's hint for the session's guidance (iOS shows the guidance code). */
internal fun PXGuidance.hint(): String = when (this) {
    PXGuidance.Searching -> "Point camera at a label"
    PXGuidance.HoldStill -> "Hold still"
    PXGuidance.Focusing -> "Focusing…"
    PXGuidance.TooBlurry -> "Too blurry · hold steady"
    PXGuidance.ImproveLighting -> "Improve the lighting"
    PXGuidance.MoveCloser -> "Move closer"
    PXGuidance.MoveBack -> "Move back"
    is PXGuidance.Stabilizing -> "Hold still · $captured/$of"
}

/** iOS `noCodeFound`'s title, message and extra action (torch, unless it is already on). */
internal fun noCodeCopy(mode: ScanMode, torchOn: Boolean): Triple<String, String, List<AlertAction>> {
    val text = mode == ScanMode.Ocr || mode == ScanMode.TextTemplates
    return Triple(
        if (text) "No Text Found" else if (mode == ScanMode.QR) "No QR Code Found" else "No Barcode Found",
        if (text) "Fill the frame with the label and hold still, then capture again." else "Move closer so the code fills the frame, then try again.",
        if (torchOn) emptyList() else listOf(AlertAction("Turn on torch and retry", PXButtonKind.Secondary, ScannerAction.TorchRetry)),
    )
}

/**
 * AR Item Count's hint line (spec 5.10): with an empty list, what to do about it; else the counter's prompt while it
 * shows one; else, while the pins wait for a surface ([findingSurface]), to move slowly so ARCore finds it; else what is
 * in view of the list, and the units counted so far ([CountView.items]' lower bounds).
 */
internal fun retrievalHint(
    items: List<String>,
    codesInView: List<String>,
    view: CountView,
    findingSurface: Boolean = false,
    pinCounts: Map<String, Int> = emptyMap(),
): String {
    if (items.isEmpty()) return "Add SKUs to count"
    view.prompt?.let { return promptText(it, view.bracket) }
    if (findingSurface) return "Move slowly to find the surface"
    if (codesInView.isEmpty()) return "Pan across the shelf"
    val n = codesInView.count { items.lists(it) }
    val counted = pinCounts.values.sum()   // codes not on the list are pinned and counted too
    return "${if (n == 0) "No" else "$n"} listed item${if (n == 1) "" else "s"} in view · $counted counted"
}

/**
 * AR Item Count's shutter (spec 5.10): a row per code in view, then one per listed code counted so far that is not in
 * view; a listed code carries its count ([counts], 0 while the counter has none for it), an unlisted one none.
 */
internal fun retrievalRows(codesInView: List<String>, items: List<String>, counts: List<ItemCount>, names: Map<String, String> = emptyMap()): List<RetrievalRow> {
    val byCode = counts.associateBy { codeKey(it.code) }
    fun row(code: String): RetrievalRow {
        val listed = items.lists(code)
        val c = byCode[codeKey(code)]
        return RetrievalRow(code, listed, if (listed) c?.countLow ?: 0 else null, if (listed) c?.countHigh ?: 0 else null, nameOf(code, names))
    }
    val inView = codesInView.map(::codeKey).toSet()
    val counted = counts.filter { it.countHigh > 0 && items.lists(it.code) && codeKey(it.code) !in inView }.map { it.code }
    return (codesInView + counted).map(::row)
}

/** AR Item Count step 1's hint (design v6): one SKU at a time, added by the shutter. */
internal fun buildHint(codeInView: Boolean): String =
    if (codeInView) "Code in the frame · press the shutter" else "Align one barcode or QR code inside the frame"

/** [code]'s live AR pins, one per unit on the shelf (GTINs compared as 14 digits): AR Item Count's count of it. */
internal fun pinsOf(code: String, pinCounts: Map<String, Int>): Int =
    codeKey(code).let { k -> pinCounts.entries.sumOf { (c, n) -> if (codeKey(c) == k) n else 0 } }

/** AR Item Count step 2 (design v6): the listed SKUs read on the shelf since Start count, seen or pinned. */
internal fun foundSkus(items: List<String>, seen: List<String>, pinCounts: Map<String, Int>): Set<String> {
    val read = seen.mapTo(HashSet(), ::codeKey)
    return items.filterTo(mutableSetOf()) { codeKey(it) in read || pinsOf(it, pinCounts) > 0 }
}

/**
 * AR Item Count step 2's shutter (design v6): a row per listed SKU, Found (in [found]) or Missing, with its pins; then a
 * row per code not on the list that got pins ([unlistedCounts]), not in list.
 */
internal fun skuRows(
    items: List<String>,
    found: Set<String>,
    pinCounts: Map<String, Int>,
    names: Map<String, String> = emptyMap(),
    seen: List<String> = emptyList(),
): List<RetrievalRow> =
    items.map { code ->
        val n = pinsOf(code, pinCounts)
        RetrievalRow(code, true, n, n, nameOf(code, names), found = code in found)
    } + unlistedCounts(items, pinCounts, seen).map { (code, n) -> RetrievalRow(code, false, n, n, nameOf(code, names), found = true) }

/**
 * AR Item Count's codes not on the SKU list that have pins (every code read is pinned and counted): each by its value as
 * read ([seen], else its pins' key), with its live pins, most first.
 */
internal fun unlistedCounts(items: List<String>, pinCounts: Map<String, Int>, seen: List<String>): List<Pair<String, Int>> {
    val listed = items.mapTo(HashSet(), ::codeKey)
    return pinCounts.entries
        .filter { (code, n) -> n > 0 && codeKey(code) !in listed }
        .map { (code, n) -> (seen.firstOrNull { codeKey(it) == codeKey(code) } ?: code) to n }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
}

/** [code]'s item name: its own, else that of the listed SKU it matches (GTINs as 14 digits); null when unnamed */
internal fun nameOf(code: String, names: Map<String, String>): String? =
    names[code] ?: codeKey(code).let { k -> names.entries.firstOrNull { codeKey(it.key) == k }?.value }

/** The item list's "Seen" rows (spec 5.10): each seen code, and whether it is listed ("In list") or can be added. */
internal fun seenRows(seen: List<String>, items: List<String>): List<Pair<String, Boolean>> = seen.map { it to items.lists(it) }

/** A code as the counter compares it (spec 5.10): a GTIN as its 14 digits (UPC-A, EAN-13, EAN-8 alike), else its text */
internal fun codeKey(code: String): String = Gtin.normalize(code)

/** Whether this item list holds [code], compared as the counter compares codes */
internal fun List<String>.lists(code: String): Boolean = codeKey(code).let { k -> any { codeKey(it) == k } }

/** A listed code's count in the drawer: "× N", or "× N–M" while its range is open. */
internal fun RetrievalRow.countText(): String? = countLow?.let { low ->
    val high = countHigh ?: low
    if (high > low) "× $low–$high" else "× $low"
}
