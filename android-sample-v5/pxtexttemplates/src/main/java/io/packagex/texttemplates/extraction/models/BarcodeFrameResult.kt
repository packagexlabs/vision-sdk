package io.packagex.texttemplates.extraction.models

internal data class BarcodeFrameResult(
    val barcodes: List<DetectedBarcode>
)

internal data class DetectedBarcode(
    val data: String,
    val format: String,
    val bounds: BoundingBox?  = null,
    /**
     * Oriented quad corners in [TL, TR, BR, BL] order, normalised to the same
     * upright space as [bounds] (see BarcodeExtractor). Null when ML Kit
     * returns no corner points or an unexpected count — callers fall back to
     * deskewing the axis-aligned [bounds]. Mirrors the iOS `DetectedBarcode`
     * corners, so both platforms deskew barcodes from the real quad instead of
     * inflating the AABB.
     */
    val corners: List<Pair<Int, Int>>? = null,
)
