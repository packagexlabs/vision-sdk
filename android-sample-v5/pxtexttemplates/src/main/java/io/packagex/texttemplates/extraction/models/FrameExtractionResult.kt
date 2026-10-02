package io.packagex.texttemplates.extraction.models

internal data class FrameExtractionResult(
    val ocrResult: OcrFrameResult,
    val barcodeResult: BarcodeFrameResult,
    val timestamp: Long,
    val yBytes: ByteArray? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val rotationDegrees: Int = 0,
    /**
     * Laplacian variance from the per-frame blur gate — higher = sharper.
     * Carried through so FrameAggregator's best-frame selection can use a
     * composite (`confidence × blur × wordCount`) instead of OCR confidence
     * alone. Null for any legacy code path that didn't populate it.
     */
    val blurScore: Double? = null,
)
