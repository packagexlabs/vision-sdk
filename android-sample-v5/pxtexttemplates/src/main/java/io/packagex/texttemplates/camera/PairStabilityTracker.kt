package io.packagex.texttemplates.camera

import io.packagex.texttemplates.extraction.models.BoundingBox
import io.packagex.texttemplates.session.UnpairedKind
import kotlin.math.sqrt

/**
 * Rolling-reference pair-stability tracker. Owns the cache of the previous
 * frame that passed per-frame quality gates and decides whether a new frame
 * agrees with it well enough to count toward the capture streak.
 *
 * Design property: no single frame is authoritative. The reference always
 * rolls forward, agreeing or not, so a bad bootstrap frame can corrupt at
 * most one subsequent comparison. The streak counter is only advanced on
 * agreement; the reference is updated unconditionally.
 *
 * The bootstrap frame (the first to pass per-frame quality) is never itself
 * accepted — it only seeds the reference. The second agreeing frame is the
 * first one that enters the FrameAggregator.
 */
internal class PairStabilityTracker {
    sealed class Outcome {
        /** First frame to pass per-frame quality. Reference seeded. Caller
         *  should NOT add this frame to the aggregator. */
        data object BootstrapSeeded : Outcome()

        /** Frame agreed with the reference. Caller should add this frame to
         *  the aggregator and increment the streak. */
        data object Agreed : Outcome()

        /** Frame disagreed with the reference. Caller should reset the streak
         *  to 0 and NOT add this frame to the aggregator. Reference still
         *  rolled forward. */
        data class Disagreed(val kind: UnpairedKind) : Outcome()
    }

    private data class Reference(val words: Set<String>, val bbox: BoundingBox)

    private var reference: Reference? = null

    /** Resets the tracker. Call on collection start, streak reset, error,
     *  cancel, app background, and after every accepted prediction. */
    fun reset() {
        reference = null
    }

    /** Whether a reference frame has been seeded. */
    val hasReference: Boolean get() = reference != null

    /** Cached text-area bbox for the previous accepted frame. Null when no
     *  reference has been seeded yet. */
    val referenceBbox: BoundingBox? get() = reference?.bbox

    /** Evaluate a frame against the rolling reference. */
    fun evaluate(words: List<String>, textUnionBbox: BoundingBox): Outcome {
        val normalised = normaliseWords(words)
        val prev = reference
        // Reference rolls forward regardless of outcome.
        reference = Reference(normalised, textUnionBbox)

        if (prev == null) return Outcome.BootstrapSeeded

        val jaccard = jaccard(normalised, prev.words)
        if (jaccard < JACCARD_THRESHOLD) {
            return Outcome.Disagreed(UnpairedKind.WordDisagreement)
        }

        val dx = textUnionBbox.centerX.toDouble() - prev.bbox.centerX.toDouble()
        val dy = textUnionBbox.centerY.toDouble() - prev.bbox.centerY.toDouble()
        val delta = sqrt(dx * dx + dy * dy)

        // Threshold expressed in fraction of the reference frame's bbox height,
        // with a small absolute floor so pathologically tiny bboxes don't fail
        // on sub-pixel jitter.
        val scaled = prev.bbox.height.toDouble() * CENTROID_DELTA_FRACTION
        val threshold = kotlin.math.max(scaled, CENTROID_DELTA_PIXEL_FLOOR)
        if (delta > threshold) {
            return Outcome.Disagreed(UnpairedKind.SpatialDrift)
        }

        return Outcome.Agreed
    }

    private fun normaliseWords(words: List<String>): Set<String> {
        val out = HashSet<String>(words.size)
        for (w in words) {
            val trimmed = w.trim().trim { it.isPunctuation() }.lowercase()
            if (trimmed.isNotEmpty()) out.add(trimmed)
        }
        return out
    }

    private fun Char.isPunctuation(): Boolean = when (category) {
        CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION,
        CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION -> true
        else -> false
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val intersect = a.intersect(b).size
        val union = (a + b).size
        if (union == 0) return 0.0
        return intersect.toDouble() / union.toDouble()
    }

    companion object {
        // Initial calibration was 0.8/0.25; relaxed to 0.5/0.5 after handheld
        // testing — half the words agreeing across consecutive reads is plenty
        // of evidence the content is the same, and ~half a bbox-height of
        // drift is still well within "same document" territory.
        const val JACCARD_THRESHOLD: Double = 0.5
        const val CENTROID_DELTA_FRACTION: Double = 0.5
        const val CENTROID_DELTA_PIXEL_FLOOR: Double = 3.0
    }
}
