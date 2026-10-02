package io.packagex.texttemplates.aggregation.models

import io.packagex.texttemplates.extraction.models.BarcodeFrameResult
import io.packagex.texttemplates.extraction.models.BoundingBox
import io.packagex.texttemplates.extraction.models.OcrFrameResult

internal data class AggregatedExtraction(
    val fullText: String,
    val textRegions: List<AggregatedTextRegion>,
    val barcodes: List<AggregatedBarcode>,
    val bestOcrFrame: OcrFrameResult? = null,
    /**
     * Multi-frame text-consensus fusion of the burst's per-frame OCR, built on
     * the best frame's geometry/structure (see [OcrConsensus.fuseFrames]).
     * Drop-in for [bestOcrFrame] on the prediction path — prefer it when
     * present; falls back to [bestOcrFrame] (single frame / nothing to fuse).
     */
    val fusedOcrFrame: OcrFrameResult? = null,
    val bestBarcodeResult: BarcodeFrameResult? = null,
    val bestFrameYBytes: ByteArray? = null,
    val bestFrameWidth: Int = 0,
    val bestFrameHeight: Int = 0,
    val bestFrameRotation: Int = 0,
)

internal data class AggregatedTextRegion(
    val text: String,
    val confidence: Float,
    val boundingBox: BoundingBox?,
    val wordConfidences: List<Float>
)

internal data class AggregatedBarcode(
    val data: String,
    val format: String,
    val frameCount: Int
)
