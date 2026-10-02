package io.packagex.visiondemo.scanner

import io.packagex.arcount.CountView
import io.packagex.arcount.Gtin
import io.packagex.arcount.ItemCount
import io.packagex.visiondemo.ar.promptText
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.model.Box
import io.packagex.visiondemo.model.DetectedCode
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.Processing
import io.packagex.visiondemo.model.RetrievalRow
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visionsdk.dto.ScannedCodeResult
import io.packagex.visionsdk.exceptions.VisionSDKException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Pure rules behind [ScannerViewModel], ported from iOS `DemoModel`. */

internal fun ownerFor(mode: ScanMode) = when (mode) {
    ScanMode.Retrieval -> CameraOwner.Ar
    ScanMode.DocAcq -> CameraOwner.Document
    else -> CameraOwner.Scanner
}

/** Doc types with an on-device model (the VLM prompts are cloud-only). */
internal val DocType.onDevice get() = this in setOf(DocType.SL, DocType.BOL, DocType.IL, DocType.DC)

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

/** iOS `noCodeFound`'s title, message and extra action (torch, unless it is already on). */
internal fun noCodeCopy(mode: ScanMode, torchOn: Boolean): Triple<String, String, List<AlertAction>> {
    val text = mode == ScanMode.Ocr
    return Triple(
        if (text) "No Text Found" else if (mode == ScanMode.QR) "No QR Code Found" else "No Barcode Found",
        if (text) "Fill the frame with the label and hold still, then capture again." else "Move closer so the code fills the frame, then try again.",
        if (torchOn) emptyList() else listOf(AlertAction("Turn on torch and retry", PXButtonKind.Secondary, ScannerAction.TorchRetry)),
    )
}

/**
 * AR Item Count's hint line (spec 5.10): with an empty list, what to do about it; else the counter's prompt while it
 * shows one; else what is in view of the list, and the units counted so far ([CountView.items]' lower bounds).
 */
internal fun retrievalHint(items: List<String>, codesInView: List<String>, view: CountView): String {
    if (items.isEmpty()) {
        val n = codesInView.size
        return when (n) {
            0 -> "Add item codes to find"
            1 -> "1 code in view · add it from Item list"
            else -> "$n codes in view · add them from Item list"
        }
    }
    view.prompt?.let { return promptText(it, view.bracket) }
    if (codesInView.isEmpty()) return "Pan across the shelf"
    val n = codesInView.count { items.lists(it) }
    val counted = view.items.sumOf { it.countLow }
    return "${if (n == 0) "No" else "$n"} listed item${if (n == 1) "" else "s"} in view · $counted counted"
}

/**
 * AR Item Count's shutter (spec 5.10): a row per code in view, then one per listed code counted so far that is not in
 * view; a listed code carries its count ([counts], 0 while the counter has none for it), an unlisted one none.
 */
internal fun retrievalRows(codesInView: List<String>, items: List<String>, counts: List<ItemCount>): List<RetrievalRow> {
    val byCode = counts.associateBy { codeKey(it.code) }
    fun row(code: String): RetrievalRow {
        val listed = items.lists(code)
        val c = byCode[codeKey(code)]
        return RetrievalRow(code, listed, if (listed) c?.countLow ?: 0 else null, if (listed) c?.countHigh ?: 0 else null)
    }
    val inView = codesInView.map(::codeKey).toSet()
    val counted = counts.filter { it.countHigh > 0 && items.lists(it.code) && codeKey(it.code) !in inView }.map { it.code }
    return (codesInView + counted).map(::row)
}

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
