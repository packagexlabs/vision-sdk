package io.packagex.visiondemo.model

import io.packagex.visionsdk.core.DetectionMode

data class ScannerConfig(
    /** The SDK's own box detector to run; null when the mode doesn't use it at all -- Price tag and Item
     *  retrieval read their own SDK callbacks (price/retrieval events, not box detection), and AR Count
     *  and Document Acquisition run entirely their own camera pipeline (see `CameraOwner`). */
    val detection: DetectionMode?,
    val multiple: Boolean,
    val nthFrame: Int,
    val restrictToFrame: Boolean,
    val showBoxes: Boolean,
    val needsEntitlement: Boolean
) {
    /** Barcode/QR multiple scan with boxes on: the SDK draws them with its barcode engine's overlay (decoded codes
     *  only, with their text), where the app drew them before; the app then draws none, so no code is outlined
     *  twice. Vision Scanner's boxes stay the app's own. */
    val sdkDrawsBoxes: Boolean get() = showBoxes && (detection == DetectionMode.Barcode || detection == DetectionMode.QRCode)
}

fun scannerConfig(mode: ScanMode, multi: Boolean, showBoxesPref: Boolean): ScannerConfig = when (mode) {
    ScanMode.Barcode, ScanMode.QR -> ScannerConfig(
        detection = if (mode == ScanMode.Barcode) DetectionMode.Barcode else DetectionMode.QRCode,
        multiple = multi,
        nthFrame = 7,
        restrictToFrame = !multi,
        showBoxes = showBoxesPref && multi,
        needsEntitlement = false
    )
    ScanMode.Ocr -> ScannerConfig(
        detection = DetectionMode.OCR,
        multiple = false,
        nthFrame = 7,
        restrictToFrame = false,
        showBoxes = showBoxesPref,
        needsEntitlement = false
    )
    ScanMode.Price -> ScannerConfig(
        detection = null,
        multiple = false,
        nthFrame = 7,
        restrictToFrame = false,
        showBoxes = false,
        needsEntitlement = true
    )
    ScanMode.Retrieval -> ScannerConfig(
        detection = null,
        multiple = true,
        nthFrame = 2,
        restrictToFrame = false,
        showBoxes = false,
        needsEntitlement = true
    )
    ScanMode.Ar, ScanMode.DocAcq -> ScannerConfig(
        detection = null,
        multiple = false,
        nthFrame = 7,
        restrictToFrame = false,
        showBoxes = false,
        needsEntitlement = false
    )
}
