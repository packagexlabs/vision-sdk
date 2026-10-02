package io.packagex.texttemplates.aggregation

import io.packagex.texttemplates.aggregation.models.AggregatedTextRegion
import io.packagex.texttemplates.extraction.models.BoundingBox
import io.packagex.texttemplates.extraction.models.OcrFrameResult
import io.packagex.texttemplates.extraction.models.OcrWord

internal class OcrConsensus constructor() {

    /**
     * Multi-frame OCR consensus using spatial alignment and word-level voting.
     * Full implementation in Step 8.
     * For now, uses the first frame's text as-is.
     */
    fun buildConsensus(frames: List<OcrFrameResult>): List<AggregatedTextRegion> {
        if (frames.isEmpty()) return emptyList()

        // Simple passthrough for scaffolding — full consensus in Step 8
        val anchor = frames.first()
        return anchor.blocks.map { block ->
            AggregatedTextRegion(
                text = block.text,
                confidence = block.confidence,
                boundingBox = block.boundingBox,
                wordConfidences = block.lines.flatMap { line ->
                    line.words.map { it.confidence }
                }
            )
        }
    }

    /**
     * Fuse the per-frame OCR results of one capture burst into a single,
     * text-corrected [OcrFrameResult], using [best] as the structural skeleton
     * and voting each word's text across all [frames].
     *
     * Pair-stability already guarantees the buffered frames are the same,
     * spatially-aligned document (word-set Jaccard ≥ 0.5, centroid drift
     * < ½ bbox-height), so a token's detections across frames can be associated
     * by bbox overlap. For each word in [best] we collect the best-overlapping
     * detection from every frame and take the confidence-weighted majority text.
     * This corrects frame-specific misreads that otherwise break downstream key
     * matching — e.g. a best frame that read the key as "Shglo"/"Oty" while
     * another frame in the same burst read "ShpNo"/"Qty".
     *
     * Deliberately conservative for V1:
     *  - Geometry (bbox / corner points), block-line structure, and
     *    `coordsPreRotated` are inherited from [best] verbatim, so the result is
     *    a drop-in for the single-best-frame prediction path.
     *  - [OcrFrameResult.fullText] is preserved verbatim from [best]. Value
     *    expansion does substring matching on the raw OCR text, so we keep the
     *    exact OCR output there; only the per-word `text` inside `blocks` carries
     *    consensus corrections, which is what feeds the key/geometry matchers.
     *  - A token is only substituted when another reading STRICTLY outweighs the
     *    best frame's own reading (ties keep [best] — stability). Abstaining
     *    frames (token dropped there) simply don't vote.
     *
     * Not yet handled (follow-ons): injecting tokens the best frame dropped
     * entirely (coverage gaps), median bbox fusion, and character-level voting
     * across divergent tokenizations. Returns [best] unchanged when fewer than
     * two frames are available or when no word changed.
     */
    fun fuseFrames(frames: List<OcrFrameResult>, best: OcrFrameResult): OcrFrameResult {
        if (frames.size < 2) return best

        data class FrameWord(val text: String, val bbox: BoundingBox, val confidence: Float)

        // Flatten every frame to its (text, bbox, confidence) tokens once.
        val perFrame: List<List<FrameWord>> = frames.map { fr ->
            fr.blocks.flatMap { b ->
                b.lines.flatMap { l ->
                    l.words.mapNotNull { w ->
                        val bb = w.boundingBox ?: return@mapNotNull null
                        FrameWord(w.text, bb, w.confidence)
                    }
                }
            }
        }

        // The single highest-IoU token in [words] for [target], or null when
        // nothing overlaps enough to be confidently the same token.
        fun bestOverlap(target: BoundingBox, words: List<FrameWord>): FrameWord? {
            var hit: FrameWord? = null
            var hitIou = 0f
            for (fw in words) {
                val iou = target.iou(fw.bbox)
                if (iou > hitIou) { hitIou = iou; hit = fw }
            }
            return if (hitIou >= MIN_ASSOC_IOU) hit else null
        }

        // Confidence-weighted majority text for one best-frame word. The best
        // frame is itself in [frames], so its own reading is among the votes;
        // we only override it when another reading strictly outweighs it.
        fun consensusText(word: OcrWord): String {
            val bb = word.boundingBox ?: return word.text
            val votes = HashMap<String, Double>()
            for (fwList in perFrame) {
                val m = bestOverlap(bb, fwList) ?: continue
                if (m.text.isEmpty()) continue
                votes[m.text] = (votes[m.text] ?: 0.0) + m.confidence.toDouble().coerceAtLeast(MIN_VOTE_WEIGHT)
            }
            if (votes.isEmpty()) return word.text
            val ownWeight = votes[word.text] ?: 0.0
            val winner = votes.entries.maxByOrNull { it.value } ?: return word.text
            // winner is the argmax, so winner.value >= ownWeight always; a
            // strictly-greater weight therefore cannot belong to word.text's own
            // entry. The single `>` check is the whole decision — equality (incl.
            // ties with the own reading) keeps the original text.
            return if (winner.value > ownWeight) winner.key else word.text
        }

        var changed = false
        val fusedBlocks = best.blocks.map { block ->
            val newLines = block.lines.map { line ->
                val newWords = line.words.map { w ->
                    val ct = consensusText(w)
                    if (ct != w.text) { changed = true; w.copy(text = ct) } else w
                }
                // Keep line.text internally consistent with its (corrected)
                // words via each token's captured separator (space fallback).
                line.copy(words = newWords, text = rebuildLineText(newWords))
            }
            block.copy(lines = newLines, text = newLines.joinToString(" ") { it.text })
        }
        if (!changed) return best

        val allConf = fusedBlocks.flatMap { b -> b.lines.flatMap { it.words } }.map { it.confidence }
        val avgConf = if (allConf.isEmpty()) best.averageConfidence else allConf.average().toFloat()

        return OcrFrameResult(
            blocks = fusedBlocks,
            // Preserved verbatim — see the kdoc: expansion matches on raw text.
            fullText = best.fullText,
            averageConfidence = avgConf,
            coordsPreRotated = best.coordsPreRotated,
        )
    }

    private fun rebuildLineText(words: List<OcrWord>): String {
        val sb = StringBuilder()
        for ((i, w) in words.withIndex()) {
            sb.append(w.text)
            if (i < words.size - 1) sb.append(w.trailingSeparator ?: " ")
        }
        return sb.toString()
    }

    companion object {
        /** Minimum bbox IoU for two detections across frames to be treated as
         *  the same physical token. Conservative: a miss just means fewer votes
         *  (no correction), never a wrong-token vote. */
        private const val MIN_ASSOC_IOU = 0.3f

        /** Vote-weight floor so a zero-confidence read still counts a little. */
        private const val MIN_VOTE_WEIGHT = 0.05
    }
}
