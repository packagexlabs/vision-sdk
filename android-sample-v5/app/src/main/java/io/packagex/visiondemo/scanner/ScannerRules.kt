package io.packagex.visiondemo.scanner

import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.model.Box
import io.packagex.visiondemo.model.DetectedCode
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.Processing
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visionsdk.dto.ScannedCodeResult

/** Pure rules behind [ScannerViewModel], ported from iOS `DemoModel`. */

internal fun ownerFor(mode: ScanMode) = when (mode) {
    ScanMode.Ar -> CameraOwner.Ar
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
