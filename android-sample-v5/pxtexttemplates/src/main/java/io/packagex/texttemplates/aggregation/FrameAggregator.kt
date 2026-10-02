package io.packagex.texttemplates.aggregation

import io.packagex.texttemplates.aggregation.models.AggregatedExtraction
import io.packagex.texttemplates.extraction.models.FrameExtractionResult

/**
 * Accumulates accepted frames from the capture pipeline. Only the
 * running-best frame retains its `yBytes`; every other accepted frame's
 * byte buffer is dropped immediately. Caps peak image memory at one frame
 * (~8 MB at 1080p) regardless of streak length.
 *
 * "Best" = `avg OCR confidence × Laplacian blur variance × word count`.
 * Each component captures a different quality dimension:
 *  - confidence: how sure the OCR engine is about its character calls
 *  - blur variance: how visually sharp the frame is (high-freq edges)
 *  - word count: how complete the OCR was (more words = more signal for
 *    the matcher to work with)
 *
 * Their product naturally prefers frames that are simultaneously confident,
 * sharp, AND text-rich — vs. the prior "highest confidence" rule which
 * sometimes picked a fuzzy frame that happened to OCR confidently on the
 * few words it could see. The components live in very different numeric
 * ranges (0-1, 0-thousands, 0-50+) but we only use the score for relative
 * ordering between frames, so direct multiplication is fine.
 */
internal class FrameAggregator constructor(
    private val ocrConsensus: OcrConsensus,
    private val barcodeAggregator: BarcodeAggregator
) {
    /** Metadata per accepted frame, with `yBytes` stripped so the aggregator
     *  carries only one image at a time. */
    private val buffer = mutableListOf<FrameExtractionResult>()

    /** Running-best frame — the only entry holding live `yBytes`. */
    private var bestFrame: FrameExtractionResult? = null

    data class BestFrameScoreBreakdown(
        val confidence: Double,
        val blur: Double,
        val wordCount: Int,
        val score: Double,
    )

    val frameCount: Int get() = buffer.size

    fun addFrame(result: FrameExtractionResult) {
        val current = bestFrame
        if (current == null || qualityScore(result) > qualityScore(current)) {
            bestFrame = result
        }
        buffer.add(stripImage(result))
    }

    fun isReady(targetFrames: Int): Boolean = buffer.size >= targetFrames

    /**
     * Components of the running-best frame's quality score, snapshotted for
     * telemetry. Null when nothing has been collected yet. Used by the
     * ViewModel's [io.packagex.texttemplates.session.CaptureStats] log to surface
     * what the composite picked for diagnosis.
     */
    fun bestFrameScoreBreakdown(): BestFrameScoreBreakdown? {
        val best = bestFrame ?: return null
        val conf = best.ocrResult.averageConfidence.toDouble().coerceAtLeast(0.0)
        val blur = best.blurScore ?: 1.0
        val words = countWords(best).coerceAtLeast(1)
        return BestFrameScoreBreakdown(
            confidence = conf,
            blur = blur,
            wordCount = words,
            score = conf * blur.coerceAtLeast(1.0) * words.toDouble(),
        )
    }

    fun aggregate(): AggregatedExtraction {
        val ocrResults = buffer.map { it.ocrResult }
        val barcodeResults = buffer.map { it.barcodeResult }

        val textRegions = ocrConsensus.buildConsensus(ocrResults)
        val barcodes = barcodeAggregator.aggregate(barcodeResults)
        val fullText = textRegions.joinToString("\n") { it.text }

        val best = bestFrame
        // Multi-frame text consensus, built on the best frame's geometry. Only
        // meaningful with ≥2 buffered frames; fuseFrames returns the best frame
        // unchanged otherwise, so this is null-safe to prefer downstream.
        val fusedOcr = best?.let { ocrConsensus.fuseFrames(ocrResults, it.ocrResult) }
        return AggregatedExtraction(
            fullText = fullText,
            textRegions = textRegions,
            barcodes = barcodes,
            bestOcrFrame = best?.ocrResult,
            fusedOcrFrame = fusedOcr,
            bestBarcodeResult = best?.barcodeResult,
            bestFrameYBytes = best?.yBytes,
            bestFrameWidth = best?.frameWidth ?: 0,
            bestFrameHeight = best?.frameHeight ?: 0,
            bestFrameRotation = best?.rotationDegrees ?: 0,
        )
    }

    fun reset() {
        buffer.clear()
        bestFrame = null
    }

    /**
     * Composite quality score: `avg confidence × blur variance × word count`.
     * Defensive on each component so a missing blur score (legacy path) or
     * zero word count doesn't crater the score below something a sharper
     * frame could beat.
     */
    private fun qualityScore(result: FrameExtractionResult): Double {
        val conf = result.ocrResult.averageConfidence.toDouble().coerceAtLeast(0.0)
        val blur = result.blurScore ?: 1.0
        val words = countWords(result).coerceAtLeast(1)
        return conf * blur.coerceAtLeast(1.0) * words.toDouble()
    }

    private fun countWords(result: FrameExtractionResult): Int {
        var count = 0
        for (block in result.ocrResult.blocks) {
            for (line in block.lines) {
                count += line.words.size
            }
        }
        return count
    }

    private fun stripImage(result: FrameExtractionResult): FrameExtractionResult =
        result.copy(yBytes = null)
}
