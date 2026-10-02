package io.packagex.texttemplates.extraction.models

internal data class OcrFrameResult(
    val blocks: List<OcrBlock>,
    val fullText: String,
    val averageConfidence: Float,
    /** True when coordinates are already in display (upright) space rather
     *  than raw sensor space. OcrExtractor normalises ML Kit output to
     *  upright and sets this; kept on the model so any future extractor that
     *  emits sensor coords can opt out. */
    val coordsPreRotated: Boolean = false,
)

internal data class OcrBlock(
    val text: String,
    val lines: List<OcrLine>,
    val boundingBox: BoundingBox?,
    val confidence: Float
)

internal data class OcrLine(
    val text: String,
    val words: List<OcrWord>,
    val boundingBox: BoundingBox?
)

internal data class OcrWord(
    val text: String,
    val boundingBox: BoundingBox?,
    val confidence: Float,
    val cornerPoints: List<Pair<Int, Int>>? = null,
    /** Characters between this word's end and the next word's start within the
     *  same line (e.g. " ", "-", "/"), captured from the line text. Null for
     *  the last word of a line. Drives the multi-occurrence expansion
     *  adjacency walk so Android matches iOS (which populates this from
     *  Vision's per-word char ranges). */
    val trailingSeparator: String? = null,
)

internal data class BoundingBox(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
    val area: Int get() = width * height

    fun iou(other: BoundingBox): Float {
        val xOverlap = maxOf(0, minOf(right, other.right) - maxOf(left, other.left))
        val yOverlap = maxOf(0, minOf(bottom, other.bottom) - maxOf(top, other.top))
        val intersection = xOverlap * yOverlap
        val union = area + other.area - intersection
        return if (union > 0) intersection.toFloat() / union else 0f
    }
}
