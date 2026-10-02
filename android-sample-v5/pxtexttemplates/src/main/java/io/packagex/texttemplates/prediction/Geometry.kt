package io.packagex.texttemplates.prediction

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

// --- WordBox ---

/**
 * (text, [x1, y1, x2, y2]).
 *
 * `cornerPoints` carries the 4 oriented corners returned by the OCR engine
 * when available. The matcher uses them in the deskew step to derive a tighter
 * AABB than `bbox` provides — see [PredictionEngine]'s deskew block. Null for
 * synthetic / merged WordBoxes where there's no single oriented quad.
 *
 * Implements [operator]s `component1`/`component2` so existing destructuring
 * `val (text, bbox) = wb` keeps working from the prior `Pair<String, DoubleArray>`
 * typealias era.
 */
internal data class WordBox(
    val text: String,
    val bbox: DoubleArray,
    val cornerPoints: List<Pair<Int, Int>>? = null,
    /**
     * The exact characters the OCR engine saw between this word's end and the
     * next word's start within the same observation/line, captured at
     * extraction time. Null for the last token of an observation (and when the
     * OCR backend doesn't expose inter-word spans). Used by the
     * multi-occurrence expansion path ([walkAdjacentTokens]) to reconstruct
     * joined forms like "SKHYNIX-SLS" directly from the word list, bypassing
     * fragile regex/rawOcrText pairing.
     */
    val trailingSeparator: String? = null,
) {
    val first: String get() = text
    val second: DoubleArray get() = bbox

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WordBox) return false
        if (text != other.text) return false
        if (!bbox.contentEquals(other.bbox)) return false
        return cornerPoints == other.cornerPoints
    }

    override fun hashCode(): Int {
        var h = text.hashCode()
        h = 31 * h + bbox.contentHashCode()
        h = 31 * h + (cornerPoints?.hashCode() ?: 0)
        return h
    }
}

// --- Bbox helpers ---

/**
 * Return the bbox's (left-edge x, vertical-center y) anchor point.
 *
 * NOTE: this is *not* the geometric center — the x-coordinate is the
 * bbox's left edge, not (x1+x2)/2. Every relation-vector / dx
 * computation in the matchers uses this anchor (so dx is effectively
 * left-to-left). Mirrors `get_left_midpoint` in api/geometry.py and
 * `getLeftMidpoint` in ios Geometry.swift — all three must use the
 * same anchor so stored and live vectors agree.
 *
 * Renamed from `getCenter` in 2026-05 — the old name implied a true
 * center and silently hid the left-edge convention.
 */
internal fun getLeftMidpoint(bbox: DoubleArray): Pair<Double, Double> {
    return Pair(bbox[0], (bbox[1] + bbox[3]) / 2.0)
}

/**
 * The bbox's geometric center ((x1+x2)/2, (y1+y2)/2). Used as the barcode
 * reference point in the HEIGHT-normalized barcode→field vector: a barcode's
 * left edge is an arbitrary point on the bars, so dx to a value is more
 * meaningful from the barcode center. The value bbox keeps `getLeftMidpoint`.
 * y is identical to `getLeftMidpoint`, so this only affects dx. Mirrors
 * `get_center` in api/geometry.py and `getCenter` in ios Geometry.swift.
 */
internal fun getCenter(bbox: DoubleArray): Pair<Double, Double> {
    return Pair((bbox[0] + bbox[2]) / 2.0, (bbox[1] + bbox[3]) / 2.0)
}

internal fun getHeight(bbox: DoubleArray): Double = abs(bbox[3] - bbox[1])

internal fun getDistanceAndAngle(p1: Pair<Double, Double>, p2: Pair<Double, Double>): Pair<Double, Double> {
    val dx = p2.first - p1.first
    val dy = p2.second - p1.second
    val distance = sqrt(dx * dx + dy * dy)
    val angle = Math.toDegrees(atan2(dy, dx))
    return Pair(distance, angle)
}

// --- 8D relation vectors ---

/** 8D dx/dy variant — used by KV and barcode matchers. */
internal fun computeRelationVector(
    bboxA: DoubleArray,
    bboxB: DoubleArray,
    medianHeight: Double,
    pageWidth: Double,
    pageHeight: Double,
    heightScale: Double = 1.0,
): DoubleArray {
    val centerA = getLeftMidpoint(bboxA)
    val centerB = getLeftMidpoint(bboxB)
    val dx = centerB.first - centerA.first
    val dy = centerB.second - centerA.second
    val hA = getHeight(bboxA)
    val hB = getHeight(bboxB)

    val mh = if (medianHeight != 0.0) medianHeight else 1.0
    val pw = if (pageWidth != 0.0) pageWidth else 1.0
    val ph = if (pageHeight != 0.0) pageHeight else 1.0
    val hs = if (heightScale != 0.0) heightScale else 1.0

    return doubleArrayOf(
        dx / mh,
        dy / mh,
        centerB.first / mh,
        (pw - centerB.first) / mh,
        centerB.second / mh,
        (ph - centerB.second) / mh,
        hA / mh * hs,
        hB / mh * hs,
    )
}

/** 8D distance/angle variant — used by pairwise and anchor matchers. */
internal fun computeRelationVectorPolar(
    bboxA: DoubleArray,
    bboxB: DoubleArray,
    medianHeight: Double,
    pageWidth: Double,
    pageHeight: Double,
    heightScale: Double = 1.0,
): DoubleArray {
    val centerA = getLeftMidpoint(bboxA)
    val centerB = getLeftMidpoint(bboxB)
    val dx = centerB.first - centerA.first
    val dy = centerB.second - centerA.second
    val dist = sqrt(dx * dx + dy * dy)
    val angle = Math.toDegrees(atan2(dy, dx))
    val hA = getHeight(bboxA)
    val hB = getHeight(bboxB)

    val mh = if (medianHeight != 0.0) medianHeight else 1.0
    val pw = if (pageWidth != 0.0) pageWidth else 1.0
    val ph = if (pageHeight != 0.0) pageHeight else 1.0
    val hs = if (heightScale != 0.0) heightScale else 1.0

    return doubleArrayOf(
        dist / mh,
        angle,
        centerB.first / mh,
        (pw - centerB.first) / mh,
        centerB.second / mh,
        (ph - centerB.second) / mh,
        hA / mh * hs,
        hB / mh * hs,
    )
}

// --- Average-height (h_ref = (h_a + h_b) / 2) variants ---

/** dx/dy variant normalized by `(h_a + h_b) / 2` — pair-intrinsic, no global mh. */
internal fun computeRelationVectorAvg(
    bboxA: DoubleArray,
    bboxB: DoubleArray,
    pageWidth: Double,
    pageHeight: Double,
    heightScale: Double = 1.0,
): DoubleArray {
    val centerA = getLeftMidpoint(bboxA)
    val centerB = getLeftMidpoint(bboxB)
    val dx = centerB.first - centerA.first
    val dy = centerB.second - centerA.second
    val hA = getHeight(bboxA)
    val hB = getHeight(bboxB)

    val sum = hA + hB
    val hRef = if (sum > 0.0) sum / 2.0 else 1.0
    val pw = if (pageWidth != 0.0) pageWidth else 1.0
    val ph = if (pageHeight != 0.0) pageHeight else 1.0
    val hs = if (heightScale != 0.0) heightScale else 1.0

    return doubleArrayOf(
        dx / hRef,
        dy / hRef,
        centerB.first / hRef,
        (pw - centerB.first) / hRef,
        centerB.second / hRef,
        (ph - centerB.second) / hRef,
        hA / hRef * hs,
        hB / hRef * hs,
    )
}

/**
 * 8D relation vector normalized by `bboxA`'s height (h_a) only. Used for
 * KV vectors: bboxA is the key word, bboxB is the value/candidate.
 *
 * Using h_a (the key's own height) keeps the reference constant across all
 * candidate value bboxes at runtime, so candidates compete on apples-to-apples
 * normalised coordinates rather than each getting a candidate-specific h_ref.
 */
internal fun computeRelationVectorKeyH(
    bboxA: DoubleArray,
    bboxB: DoubleArray,
    pageWidth: Double,
    pageHeight: Double,
    heightScale: Double = 1.0,
): DoubleArray {
    val centerA = getLeftMidpoint(bboxA)
    val centerB = getLeftMidpoint(bboxB)
    val dx = centerB.first - centerA.first
    val dy = centerB.second - centerA.second
    val hA = getHeight(bboxA)
    val hB = getHeight(bboxB)

    val hRef = if (hA > 0.0) hA else 1.0
    val pw = if (pageWidth != 0.0) pageWidth else 1.0
    val ph = if (pageHeight != 0.0) pageHeight else 1.0
    val hs = if (heightScale != 0.0) heightScale else 1.0

    return doubleArrayOf(
        dx / hRef,
        dy / hRef,
        centerB.first / hRef,
        (pw - centerB.first) / hRef,
        centerB.second / hRef,
        (ph - centerB.second) / hRef,
        hA / hRef * hs, // always = hs
        hB / hRef * hs,
    )
}

/**
 * 8D relation vector normalized by the *width* of bboxA. Used for barcode→field
 * vectors. Barcodes are predominantly horizontal, so width is a more stable
 * per-image scale reference than `(h_a + h_b) / 2`.
 */
internal fun computeRelationVectorBarcodeWidth(
    bboxA: DoubleArray,
    bboxB: DoubleArray,
    pageWidth: Double,
    pageHeight: Double,
    heightScale: Double = 1.0,
): DoubleArray {
    val centerA = getLeftMidpoint(bboxA)
    val centerB = getLeftMidpoint(bboxB)
    val dx = centerB.first - centerA.first
    val dy = centerB.second - centerA.second
    val hA = getHeight(bboxA)
    val hB = getHeight(bboxB)
    val rawWidth = bboxA[2] - bboxA[0]
    val wA = if (rawWidth > 0.0) rawWidth else 1.0

    val pw = if (pageWidth != 0.0) pageWidth else 1.0
    val ph = if (pageHeight != 0.0) pageHeight else 1.0
    val hs = if (heightScale != 0.0) heightScale else 1.0

    return doubleArrayOf(
        dx / wA,
        dy / wA,
        centerB.first / wA,
        (pw - centerB.first) / wA,
        centerB.second / wA,
        (ph - centerB.second) / wA,
        hA / wA * hs,
        hB / wA * hs,
    )
}

/**
 * 8D relation vector normalized by the *height* of bboxA (the barcode).
 * Replaces the width-normalized variant for barcode→field vectors. Android's
 * detected barcode height is unreliable (HRI inflation), so the caller passes a
 * box whose height has been reconstructed as `stored_aspect · detected_width`
 * (see reconstructBarcodeBox) — the normalizer is then the stable a·w, never
 * the flaky detected height. dim 6 is always `hs` (h_a normalized by itself).
 * Mirrors `compute_relation_vector_barcode_height` in api/geometry.py.
 */
internal fun computeRelationVectorBarcodeHeight(
    bboxA: DoubleArray,
    bboxB: DoubleArray,
    pageWidth: Double,
    pageHeight: Double,
    heightScale: Double = 1.0,
    useCenterOrigin: Boolean = true,
): DoubleArray {
    // Barcode (bboxA) uses its CENTER when useCenterOrigin; value (bboxB) keeps
    // its LEFT-midpoint. Only dx is affected (y is the box center either way).
    // The origin is GATED by the template's barcode_origin: a legacy height-
    // LEFT template must be matched left-to-left (useCenterOrigin=false) or dx
    // is off by w_a/2 and the rerank scrambles.
    val centerA = if (useCenterOrigin) getCenter(bboxA) else getLeftMidpoint(bboxA)
    val centerB = getLeftMidpoint(bboxB)
    val dx = centerB.first - centerA.first
    val dy = centerB.second - centerA.second
    val hA = getHeight(bboxA)
    val hB = getHeight(bboxB)
    val norm = if (hA > 0.0) hA else 1.0

    val pw = if (pageWidth != 0.0) pageWidth else 1.0
    val ph = if (pageHeight != 0.0) pageHeight else 1.0
    val hs = if (heightScale != 0.0) heightScale else 1.0

    return doubleArrayOf(
        dx / norm,
        dy / norm,
        centerB.first / norm,
        (pw - centerB.first) / norm,
        centerB.second / norm,
        (ph - centerB.second) / norm,
        hA / norm * hs,
        hB / norm * hs,
    )
}

/**
 * 8D polar relation vector normalized by the height of bboxA alone.
 * Used for anchor→field vectors. Normalizing by `h_a` only — instead of
 * `(h_a + h_b)/2` — keeps geometry in "anchor heights" even when the field
 * text height diverges from the anchor.
 */
internal fun computeRelationVectorPolarAnchorH(
    bboxA: DoubleArray,
    bboxB: DoubleArray,
    pageWidth: Double,
    pageHeight: Double,
    heightScale: Double = 1.0,
): DoubleArray {
    val centerA = getLeftMidpoint(bboxA)
    val centerB = getLeftMidpoint(bboxB)
    val dx = centerB.first - centerA.first
    val dy = centerB.second - centerA.second
    val dist = sqrt(dx * dx + dy * dy)
    val angle = Math.toDegrees(atan2(dy, dx))
    val hA = getHeight(bboxA)
    val hB = getHeight(bboxB)

    val hRef = if (hA > 0.0) hA else 1.0
    val pw = if (pageWidth != 0.0) pageWidth else 1.0
    val ph = if (pageHeight != 0.0) pageHeight else 1.0
    val hs = if (heightScale != 0.0) heightScale else 1.0

    return doubleArrayOf(
        dist / hRef,
        angle,
        centerB.first / hRef,
        (pw - centerB.first) / hRef,
        centerB.second / hRef,
        (ph - centerB.second) / hRef,
        hA / hRef * hs,
        hB / hRef * hs,
    )
}

/**
 * 8D polar relation vector normalized by `h_a + w_a` of bboxA. Used for
 * anchor→field vectors. Combining the anchor's height and width reduces
 * single-dimension OCR bbox noise — the anchor text is fixed for a given
 * template, so this reference is computed against the same physical artefact
 * across scans.
 */
internal fun computeRelationVectorPolarAnchorHW(
    bboxA: DoubleArray,
    bboxB: DoubleArray,
    pageWidth: Double,
    pageHeight: Double,
    heightScale: Double = 1.0,
): DoubleArray {
    val centerA = getLeftMidpoint(bboxA)
    val centerB = getLeftMidpoint(bboxB)
    val dx = centerB.first - centerA.first
    val dy = centerB.second - centerA.second
    val dist = sqrt(dx * dx + dy * dy)
    val angle = Math.toDegrees(atan2(dy, dx))
    val hA = getHeight(bboxA)
    val hB = getHeight(bboxB)
    val rawWidth = bboxA[2] - bboxA[0]
    val wA = if (rawWidth > 0.0) rawWidth else 0.0

    val denom = hA + wA
    val hRef = if (denom > 0.0) denom else 1.0
    val pw = if (pageWidth != 0.0) pageWidth else 1.0
    val ph = if (pageHeight != 0.0) pageHeight else 1.0
    val hs = if (heightScale != 0.0) heightScale else 1.0

    return doubleArrayOf(
        dist / hRef,
        angle,
        centerB.first / hRef,
        (pw - centerB.first) / hRef,
        centerB.second / hRef,
        (ph - centerB.second) / hRef,
        hA / hRef * hs,
        hB / hRef * hs,
    )
}

/** dist/angle variant normalized by `(h_a + h_b) / 2`. */
internal fun computeRelationVectorPolarAvg(
    bboxA: DoubleArray,
    bboxB: DoubleArray,
    pageWidth: Double,
    pageHeight: Double,
    heightScale: Double = 1.0,
): DoubleArray {
    val centerA = getLeftMidpoint(bboxA)
    val centerB = getLeftMidpoint(bboxB)
    val dx = centerB.first - centerA.first
    val dy = centerB.second - centerA.second
    val dist = sqrt(dx * dx + dy * dy)
    val angle = Math.toDegrees(atan2(dy, dx))
    val hA = getHeight(bboxA)
    val hB = getHeight(bboxB)

    val sum = hA + hB
    val hRef = if (sum > 0.0) sum / 2.0 else 1.0
    val pw = if (pageWidth != 0.0) pageWidth else 1.0
    val ph = if (pageHeight != 0.0) pageHeight else 1.0
    val hs = if (heightScale != 0.0) heightScale else 1.0

    return doubleArrayOf(
        dist / hRef,
        angle,
        centerB.first / hRef,
        (pw - centerB.first) / hRef,
        centerB.second / hRef,
        (ph - centerB.second) / hRef,
        hA / hRef * hs,
        hB / hRef * hs,
    )
}

// --- Median height (75th percentile) ---

internal fun medianHeight(wordBoxTuples: List<WordBox>): Double {
    if (wordBoxTuples.isEmpty()) return 1.0
    val heights = wordBoxTuples.map { it.bbox[3] - it.bbox[1] }.sorted()
    val idx = min((heights.size * 0.75).toInt(), heights.size - 1)
    return heights[idx]
}

// --- Focus rect (platform-independent) ---

/**
 * Axis-aligned rectangle in image-pixel space. Platform-independent stand-in
 * for iOS's `CGRect` so the prediction module stays free of `android.graphics`
 * dependencies. Used to carry the scanner's `previewRect` / `innerBox` focus
 * geometry into orientation detection and focus-area outlier removal.
 */
internal data class RectD(val minX: Double, val minY: Double, val width: Double, val height: Double) {
    val maxX: Double get() = minX + width
    val maxY: Double get() = minY + height
}

/**
 * Whether two axis-aligned bboxes `[x1, y1, x2, y2]` share at least one pixel.
 * Used by the per-occurrence expansion filter to keep only alternates whose
 * source bbox overlaps the matcher's predicted bbox.
 */
internal fun bboxesOverlap(a: DoubleArray, b: DoubleArray): Boolean {
    if (a.size < 4 || b.size < 4) return false
    return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1]
}

/**
 * Intersection-over-union of two axis-aligned bboxes `[x1, y1, x2, y2]`.
 * Returns 0.0 when they don't overlap or either is degenerate. Used to
 * cross-check a barcode's oriented-quad AABB against its (authoritative)
 * upright `bounds` box — a corner set that didn't normalise into upright
 * space lands rotated ~90°, producing a near-zero IoU.
 */
internal fun bboxIou(a: DoubleArray, b: DoubleArray): Double {
    if (a.size < 4 || b.size < 4) return 0.0
    val iw = minOf(a[2], b[2]) - maxOf(a[0], b[0])
    val ih = minOf(a[3], b[3]) - maxOf(a[1], b[1])
    if (iw <= 0.0 || ih <= 0.0) return 0.0
    val inter = iw * ih
    val areaA = maxOf(0.0, a[2] - a[0]) * maxOf(0.0, a[3] - a[1])
    val areaB = maxOf(0.0, b[2] - b[0]) * maxOf(0.0, b[3] - b[1])
    val union = areaA + areaB - inter
    return if (union > 0.0) inter / union else 0.0
}

/** Canonical 1-D (linear) symbologies. Their human-readable interpretation
 *  (HRI) text prints just outside the bars, so a correct box bounds the bars
 *  only. Mirrors Python's `_ONE_D_TYPES`. 2-D codes (QR / DataMatrix / PDF417 /
 *  Aztec) carry no separate HRI line and are left untouched. */
private val ONE_D_BARCODE_TYPES = setOf(
    "CODE128", "CODE39", "CODE93", "CODABAR",
    "EAN13", "EAN8", "UPCA", "UPCE", "I25", "DATABAR", "DATABAR_EXP",
)

/** Minimum fraction of a word's area that must sit inside the barcode box for
 *  it to count as "contained" (i.e. HRI / label text the detector swallowed). */
private const val BARCODE_TEXT_CONTAINMENT_FRAC = 0.5
/** A clipped bar band must retain at least this fraction of the original box
 *  height; below it we can't confidently isolate the bars, so keep the box. */
private const val BARCODE_BAR_BAND_MIN_FRAC = 0.33

/**
 * Clip an inflated 1-D barcode box down to its bar region.
 *
 * A prediction-time detector (notably ML Kit on some captures) can return a box
 * that swallows the HRI digit line and nearby label text — inflating the box
 * vertically, which shifts the left-midpoint the barcode matcher anchors on and
 * corrupts every barcode→field vector. Bars produce no OCR text, so the bar
 * region is the tallest vertical band inside the box containing NO
 * substantially-overlapping OCR word. Returns that band.
 *
 * Generic and conservative: applies to every field and layout, only ever
 * SHRINKS the box, and is a no-op when the box already excludes text (tight
 * detectors like Apple Vision) or when no confident bar band can be isolated.
 * Mirrors the spirit of the creation-time horizontal `tighten_1d_barcode_bbox`,
 * extended to the vertical axis and using OCR words instead of a gradient pass.
 */
internal fun clipBarcodeBoxToBarRegion(
    box: DoubleArray,
    wordBoxes: List<WordBox>,
    typeId: String,
): DoubleArray {
    val base = typeId.substringBefore('-').uppercase()
    if (base !in ONE_D_BARCODE_TYPES) return box
    val y1 = box[1]
    val y2 = box[3]
    if (y2 - y1 <= 0.0) return box

    // Vertical spans of words substantially inside the box (clamped to it).
    val occ = wordBoxes.mapNotNull { wb ->
        val b = wb.bbox
        val ix = minOf(box[2], b[2]) - maxOf(box[0], b[0])
        val iy = minOf(y2, b[3]) - maxOf(y1, b[1])
        if (ix <= 0.0 || iy <= 0.0) return@mapNotNull null
        val wArea = (b[2] - b[0]) * (b[3] - b[1])
        if (wArea > 0.0 && ix * iy / wArea >= BARCODE_TEXT_CONTAINMENT_FRAC) {
            Pair(maxOf(y1, b[1]), minOf(y2, b[3]))
        } else null
    }.sortedBy { it.first }
    if (occ.isEmpty()) return box  // already bars-only — common, correct case.

    // Tallest vertical gap free of any contained word = the bar band.
    var bestLo = y1
    var bestHi = y1
    var cur = y1
    for ((lo, hi) in occ) {
        if (lo - cur > bestHi - bestLo) { bestLo = cur; bestHi = lo }
        if (hi > cur) cur = hi
    }
    if (y2 - cur > bestHi - bestLo) { bestLo = cur; bestHi = y2 }

    if (bestHi - bestLo < BARCODE_BAR_BAND_MIN_FRAC * (y2 - y1)) return box
    return doubleArrayOf(box[0], bestLo, box[2], bestHi)
}

// --- Orientation detection (Zone-2 area-weighted circular mean) ---

/**
 * Area-weighted circular mean of each word's top-edge angle (`TL → TR` in the
 * corner-point convention `[TL, TR, BR, BL]`), restricted to words whose centre
 * falls inside [innerBox] (Zone 2). Returns degrees in `(-180, +180]` in image
 * coordinates (top-left origin):
 *   - `≈ 0°`   text reads left-to-right (label upright).
 *   - `≈ +90°` label rotated clockwise vs template axes.
 *   - `≈ -90°` label rotated counter-clockwise.
 *   - `≈ ±180°` label upside-down.
 *
 * Uses a circular mean (weighted vector sum, then atan2) rather than a sorted
 * median so the ±180° wrap-around doesn't average an upside-down cluster back
 * toward 0°. Each sample is weighted by bbox area so noise tokens can't outvote
 * document text. Returns null when fewer than 3 weighted samples exist or the
 * resultant magnitude is too small (angles too dispersed). `innerBox = null`
 * disables the Zone-2 gate and considers every word with a corner-point quad.
 */
internal fun areaWeightedTopEdgeAngle(
    wordBoxTuples: List<WordBox>,
    innerBox: RectD? = null,
): Double? {
    var sumCos = 0.0
    var sumSin = 0.0
    var totalWeight = 0.0
    var sampleCount = 0

    for (wb in wordBoxTuples) {
        val corners = wb.cornerPoints ?: continue
        if (corners.size < 2) continue
        if (wb.bbox.size < 4) continue
        val w = wb.bbox[2] - wb.bbox[0]
        val h = wb.bbox[3] - wb.bbox[1]
        if (w <= 0 || h <= 0) continue

        if (innerBox != null) {
            val cx = (wb.bbox[0] + wb.bbox[2]) / 2.0
            val cy = (wb.bbox[1] + wb.bbox[3]) / 2.0
            if (cx < innerBox.minX || cx > innerBox.maxX ||
                cy < innerBox.minY || cy > innerBox.maxY
            ) {
                continue
            }
        }

        val tl = corners[0]
        val tr = corners[1]
        val dx = (tr.first - tl.first).toDouble()
        val dy = (tr.second - tl.second).toDouble()
        if (dx == 0.0 && dy == 0.0) continue
        val radians = atan2(dy, dx)
        val area = w * h
        sumCos += area * cos(radians)
        sumSin += area * sin(radians)
        totalWeight += area
        sampleCount++
    }

    if (sampleCount < 3 || totalWeight == 0.0) return null

    val resultantMagnitude = sqrt(sumCos * sumCos + sumSin * sumSin) / totalWeight
    if (resultantMagnitude < 0.1) return null

    return Math.toDegrees(atan2(sumSin, sumCos))
}

/**
 * Split a measured orientation angle (degrees) into a coarse axis-snap
 * (multiple of 90° in `{-180, -90, 0, +90, +180}`) and a fine residual in
 * `[-45, +45]`. The coarse step is an exact integer axis-swap; the residual
 * feeds the existing fine-deskew step.
 *
 * Example: `decomposeOrientationAngle(92.3)` → `(coarse = 90, fine = 2.3)`.
 */
internal fun decomposeOrientationAngle(angle: Double): Pair<Double, Double> {
    val coarse = round(angle / 90.0) * 90.0
    val fine = angle - coarse
    return Pair(coarse, fine)
}

/**
 * Translate a detected coarse orientation angle (the direction the LABEL is
 * rotated relative to image axes) into the CW degrees to UNDO it. Always
 * returns 0/90/180/270.
 *   - coarse =   0° → 0
 *   - coarse = +90° → 270 (undo CW rotation with CCW 90°)
 *   - coarse = -90° → 90
 *   - coarse = ±180° → 180 (its own inverse)
 */
internal fun grossRotationToApply(coarse: Double): Int = when (round(coarse).toInt()) {
    90 -> 270
    -90 -> 90
    180, -180 -> 180
    else -> 0
}

// --- Axis-swap rotation (multiples of 90° around the top-left origin) ---
//
// After a ±90° rotation, callers must swap imageWidth ↔ imageHeight for
// downstream consumers. Math is exact integer arithmetic — no float drift:
//   +90° (CW):  (x, y) → (H − y, x)         new dims: H × W
//   180°:       (x, y) → (W − x, H − y)      new dims: W × H
//   +270° (CCW):(x, y) → (y, W − x)          new dims: H × W
// Degrees are normalised to {0, 90, 180, 270}; other values are no-ops.

/**
 * Rotate an axis-aligned bbox `[x1, y1, x2, y2]` by [degrees] CW around the
 * image's top-left origin. Returns a new AABB. Non-90°-multiple inputs are a
 * no-op (sub-90° rotation is the fine-deskew step's job).
 */
internal fun rotateBboxAxisAligned(
    bbox: DoubleArray,
    degrees: Int,
    imageWidth: Double,
    imageHeight: Double,
): DoubleArray {
    if (bbox.size < 4) return bbox
    val n = ((degrees % 360) + 360) % 360
    val x1 = bbox[0]; val y1 = bbox[1]; val x2 = bbox[2]; val y2 = bbox[3]
    return when (n) {
        90 -> doubleArrayOf(imageHeight - y2, x1, imageHeight - y1, x2)
        180 -> doubleArrayOf(imageWidth - x2, imageHeight - y2, imageWidth - x1, imageHeight - y1)
        270 -> doubleArrayOf(y1, imageWidth - x2, y2, imageWidth - x1)
        else -> bbox
    }
}

/**
 * Rotate corner points (image-pixel space, top-left origin) by [degrees] CW.
 * The point order `[TL, TR, BR, BL]` is preserved.
 *
 * NOTE: `OcrExtractor.rotatePointToUpright` duplicates this math per-point
 * (kept local there so the extraction layer doesn't import the prediction
 * package — prediction already depends on extraction.models, and the reverse
 * import would create a package cycle). Any change to the rotation convention
 * here must be mirrored there.
 */
internal fun rotateCornerPoints(
    points: List<Pair<Int, Int>>,
    degrees: Int,
    imageWidth: Int,
    imageHeight: Int,
): List<Pair<Int, Int>> {
    val n = ((degrees % 360) + 360) % 360
    if (n == 0) return points
    return points.map { (x, y) ->
        when (n) {
            90 -> Pair(imageHeight - y, x)
            180 -> Pair(imageWidth - x, imageHeight - y)
            270 -> Pair(y, imageWidth - x)
            else -> Pair(x, y)
        }
    }
}

/**
 * Rotate a [RectD] by [degrees] CW around the image's top-left origin. Width
 * and height swap for ±90° rotations.
 */
internal fun rotateRect(
    rect: RectD,
    degrees: Int,
    imageWidth: Double,
    imageHeight: Double,
): RectD {
    val n = ((degrees % 360) + 360) % 360
    return when (n) {
        90 -> RectD(imageHeight - rect.maxY, rect.minX, rect.height, rect.width)
        180 -> RectD(imageWidth - rect.maxX, imageHeight - rect.maxY, rect.width, rect.height)
        270 -> RectD(rect.minY, imageWidth - rect.maxX, rect.height, rect.width)
        else -> rect
    }
}

/** Trusted focus-zone geometry, shared by [computeFocusRects] (the prediction
 *  filter) and the on-screen `CaptureReticle` (the UI guide) so the two never
 *  drift apart: the bracket the user sees marks exactly the region the outlier
 *  filter trusts. Width is a fraction of the frame; height = width ×
 *  [FOCUS_BOX_ASPECT], clamped to the frame. */
internal const val FOCUS_BOX_WIDTH_FRACTION = 0.75
internal const val FOCUS_BOX_ASPECT = 4.0 / 3.0

/**
 * Bounding boxes of the value tokens adjacent to a key word: the contiguous run
 * of same-line tokens immediately to the key's right (walked outward while the
 * inter-token gap stays within [gapRatio]× the key height), plus any token
 * directly below it within [belowRatio]× the key height that overlaps it
 * horizontally. KV values sit next to their key by construction, so these are
 * protected from outlier removal alongside the key itself — without this, a
 * value sitting just outside the trusted focus zone (e.g. a right-column
 * "Quantity 1") is MAD-filtered away while its key, inside the zone, survives,
 * and no matcher can then recover the value.
 */
internal fun collectAdjacentValueBboxes(
    keyBox: WordBox,
    words: List<WordBox>,
    gapRatio: Double = 2.5,
    belowRatio: Double = 1.5,
): List<DoubleArray> {
    val kb = keyBox.bbox
    val keyH = kb[3] - kb[1]
    if (keyH <= 0) return emptyList()
    val keyMidY = (kb[1] + kb[3]) / 2.0
    val maxGap = keyH * gapRatio
    val out = mutableListOf<DoubleArray>()

    // Same-line tokens to the right, walked contiguously from the key edge.
    val sameLineRight = words
        .filter {
            it !== keyBox && it.bbox[0] >= kb[0] &&
                abs((it.bbox[1] + it.bbox[3]) / 2.0 - keyMidY) <= keyH * 0.6
        }
        .sortedBy { it.bbox[0] }
    var rightEdge = kb[2]
    for (w in sameLineRight) {
        if (w.bbox[2] <= rightEdge) continue          // fully behind the current edge
        if (w.bbox[0] - rightEdge > maxGap) break
        out.add(w.bbox)
        rightEdge = w.bbox[2]
    }

    // Directly-below tokens (stacked value layouts).
    for (w in words) {
        if (w === keyBox) continue
        val gap = w.bbox[1] - kb[3]
        if (gap < 0 || gap > keyH * belowRatio) continue
        val overlap = min(kb[2], w.bbox[2]) - kotlin.math.max(kb[0], w.bbox[0])
        if (overlap > 0) out.add(w.bbox)
    }
    return out
}

/**
 * Scanner focus rects in the upright-frame coordinate system. Port of iOS
 * `FrameAnalyzer.computeFocusRects`.
 *
 * `previewRect`: the part of the captured frame visible to the user. The
 * SDK-Camera preview is aspect-fill (`PreviewView.ScaleType.FILL_CENTER`, like
 * iOS `.resizeAspectFill`). When the host frames the preview at the analysis
 * aspect (the recommended aspect-locked container — see `PXRegionOfInterest`),
 * aspect-fill crops nothing and the whole frame is visible, so `previewRect` is
 * the full frame. (A host that stretches the preview to a different aspect gets
 * a centre-crop; the filter still conservatively treats the full frame as
 * visible rather than reconstructing the crop.)
 *
 * `innerBox`: centred portrait rectangle — [FOCUS_BOX_WIDTH_FRACTION] of frame
 * width wide, [FOCUS_BOX_ASPECT] (height:width) tall, clamped to fit inside the
 * frame. Represents the prediction-time "trusted area" (Zone 2 of
 * [removeOutliersWithFocusArea] and the [areaWeightedTopEdgeAngle] orientation
 * gate). The on-screen `CaptureReticle` is drawn from these same constants so
 * the bracket the user sees marks exactly this trusted region.
 *
 * Both rects are in upright (display-oriented) coordinates. ML Kit word
 * boxes live in raw sensor space, so callers on that path must map the rects
 * with `rotateRect(rect, (360 - rotationDegrees) % 360, uprightW, uprightH)`
 * before handing them to `predict`.
 */
internal fun computeFocusRects(
    uprightWidth: Double,
    uprightHeight: Double,
): Pair<RectD?, RectD?> {
    if (uprightWidth <= 0 || uprightHeight <= 0) return Pair(null, null)

    // --- innerBox: 75 % width × 4/3 portrait, clamped, centred ---
    val innerW = uprightWidth * FOCUS_BOX_WIDTH_FRACTION
    val innerH = minOf(innerW * FOCUS_BOX_ASPECT, uprightHeight)
    val inner = RectD(
        minX = (uprightWidth - innerW) / 2.0,
        minY = (uprightHeight - innerH) / 2.0,
        width = innerW,
        height = innerH,
    )

    // --- previewRect: whole frame (aspect-fill at the analysis aspect → no crop) ---
    val preview = RectD(0.0, 0.0, uprightWidth, uprightHeight)
    return Pair(preview, inner)
}

// --- Focus-area outlier removal (three-zone filter) ---

/**
 * Three-zone outlier filter that uses the scanner's focus rects as a strong
 * prior on which OCR detections belong to the document and which are
 * background / off-screen noise. Falls back to [removeOutlierBoxes] when the
 * rects aren't available.
 *
 * Zones:
 *  - [previewRect]: the part of the captured frame visible to the user.
 *    Centres outside are dropped unconditionally (off-screen capture under
 *    resize-aspect-fill).
 *  - [innerBox]: centred document-framed region. Words whose centres fall
 *    inside are kept unconditionally and bootstrap robust statistics.
 *  - border zone (inside preview, outside inner): MAD-filtered against the
 *    trusted-set statistics from [innerBox].
 *
 * Matched template keys ([protectedBboxes]) are re-included even when they'd
 * otherwise be filtered.
 */
internal fun removeOutliersWithFocusArea(
    wordBoxTuples: List<WordBox>,
    previewRect: RectD?,
    innerBox: RectD?,
    threshold: Double = 5.0,
    protectedBboxes: List<DoubleArray>? = null,
): List<WordBox> {
    if (previewRect == null && innerBox == null) {
        return removeOutlierBoxes(wordBoxTuples, threshold, protectedBboxes)
    }
    if (wordBoxTuples.size < 3) return wordBoxTuples

    val centers = wordBoxTuples.map { wb ->
        doubleArrayOf((wb.bbox[0] + wb.bbox[2]) / 2.0, (wb.bbox[1] + wb.bbox[3]) / 2.0)
    }

    fun centerInRect(c: DoubleArray, r: RectD): Boolean =
        c[0] >= r.minX && c[0] <= r.maxX && c[1] >= r.minY && c[1] <= r.maxY

    // Zone 1: drop centres outside the preview.
    val insidePreview = BooleanArray(wordBoxTuples.size) { true }
    if (previewRect != null) {
        for (i in wordBoxTuples.indices) insidePreview[i] = centerInRect(centers[i], previewRect)
    }

    // Zone 2: trusted set inside innerBox.
    val insideInner = BooleanArray(wordBoxTuples.size) { false }
    if (innerBox != null) {
        for (i in wordBoxTuples.indices) {
            insideInner[i] = insidePreview[i] && centerInRect(centers[i], innerBox)
        }
    }

    val keep = BooleanArray(wordBoxTuples.size) { false }
    for (i in wordBoxTuples.indices) if (insideInner[i]) keep[i] = true

    // Zone 3: MAD filter the border using trusted statistics.
    val trustedCenters = wordBoxTuples.indices.filter { insideInner[it] }.map { centers[it] }
    if (trustedCenters.size >= 3) {
        val medianX = median(trustedCenters.map { it[0] })
        val medianY = median(trustedCenters.map { it[1] })
        var madX = median(trustedCenters.map { abs(it[0] - medianX) })
        var madY = median(trustedCenters.map { abs(it[1] - medianY) })
        if (madX == 0.0) madX = 1.0
        if (madY == 0.0) madY = 1.0
        for (i in wordBoxTuples.indices) {
            if (keep[i] || !insidePreview[i]) continue
            val dx = abs(centers[i][0] - medianX) / madX
            val dy = abs(centers[i][1] - medianY) / madY
            if (kotlin.math.max(dx, dy) < threshold) keep[i] = true
        }
    } else {
        // Trusted set too thin — fall back to global MAD over in-preview survivors.
        val surviving = wordBoxTuples.indices.filter { insidePreview[it] }
        val surCenters = surviving.map { centers[it] }
        if (surCenters.size >= 3) {
            val medianX = median(surCenters.map { it[0] })
            val medianY = median(surCenters.map { it[1] })
            var madX = median(surCenters.map { abs(it[0] - medianX) })
            var madY = median(surCenters.map { abs(it[1] - medianY) })
            if (madX == 0.0) madX = 1.0
            if (madY == 0.0) madY = 1.0
            for (i in surviving) {
                val dx = abs(centers[i][0] - medianX) / madX
                val dy = abs(centers[i][1] - medianY) / madY
                if (kotlin.math.max(dx, dy) < threshold) keep[i] = true
            }
        } else {
            for (i in surviving) keep[i] = true
        }
    }

    // Protected-keys escape hatch.
    if (!protectedBboxes.isNullOrEmpty()) {
        val protectedCenters = protectedBboxes.map { b ->
            doubleArrayOf((b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0)
        }
        for (pc in protectedCenters) {
            for (i in wordBoxTuples.indices) {
                if (keep[i]) continue
                if (abs(centers[i][0] - pc[0]) < 1.0 && abs(centers[i][1] - pc[1]) < 1.0) {
                    keep[i] = true
                }
            }
        }
    }

    return wordBoxTuples.filterIndexed { i, _ -> keep[i] }
}

// --- Outlier removal (MAD filtering with protected keys) ---

internal fun removeOutlierBoxes(
    wordBoxTuples: List<WordBox>,
    threshold: Double = 5.0,
    protectedBboxes: List<DoubleArray>? = null,
): List<WordBox> {
    if (wordBoxTuples.size < 3) return wordBoxTuples

    val centers = wordBoxTuples.map { wb ->
        doubleArrayOf((wb.bbox[0] + wb.bbox[2]) / 2.0, (wb.bbox[1] + wb.bbox[3]) / 2.0)
    }

    val medianX = median(centers.map { it[0] })
    val medianY = median(centers.map { it[1] })

    val absDevsX = centers.map { abs(it[0] - medianX) }
    val absDevsY = centers.map { abs(it[1] - medianY) }
    var madX = median(absDevsX)
    var madY = median(absDevsY)
    if (madX == 0.0) madX = 1.0
    if (madY == 0.0) madY = 1.0

    val mask = BooleanArray(wordBoxTuples.size) { i ->
        abs(centers[i][0] - medianX) / madX < threshold &&
                abs(centers[i][1] - medianY) / madY < threshold
    }

    if (protectedBboxes.isNullOrEmpty()) {
        return wordBoxTuples.filterIndexed { i, _ -> mask[i] }
    }

    val nearBoundaryThreshold = threshold * 0.6
    var anyProtectedNearBoundary = false
    val protectedCenters = protectedBboxes.map { b ->
        doubleArrayOf((b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0)
    }
    for (pc in protectedCenters) {
        val scoreX = abs(pc[0] - medianX) / madX
        val scoreY = abs(pc[1] - medianY) / madY
        if (scoreX >= nearBoundaryThreshold || scoreY >= nearBoundaryThreshold) {
            anyProtectedNearBoundary = true
            break
        }
    }

    if (!anyProtectedNearBoundary) {
        return wordBoxTuples.filterIndexed { i, _ -> mask[i] }
    }

    val inlierCenters = centers.filterIndexed { i, _ -> mask[i] }
    if (inlierCenters.isEmpty()) return wordBoxTuples

    val inlierMinX = inlierCenters.minOf { it[0] }
    val inlierMaxX = inlierCenters.maxOf { it[0] }
    val inlierMinY = inlierCenters.minOf { it[1] }
    val inlierMaxY = inlierCenters.maxOf { it[1] }

    val protMinX = protectedCenters.minOf { it[0] }
    val protMaxX = protectedCenters.maxOf { it[0] }
    val protMinY = protectedCenters.minOf { it[1] }
    val protMaxY = protectedCenters.maxOf { it[1] }

    val extMinX = min(inlierMinX, protMinX) - madX
    val extMaxX = kotlin.math.max(inlierMaxX, protMaxX) + madX
    val extMinY = min(inlierMinY, protMinY) - madY
    val extMaxY = kotlin.math.max(inlierMaxY, protMaxY) + madY

    return wordBoxTuples.filterIndexed { i, _ ->
        mask[i] || (centers[i][0] in extMinX..extMaxX && centers[i][1] in extMinY..extMaxY)
    }
}

// --- Remove rotated words ---

/**
 * Drop words whose bbox is significantly taller than wide (h > w * threshold).
 *
 * Genuinely rotated text (90°/270°) has h/w ratios of 2+ (typically 3–12).
 * Short horizontal tokens like "1", "of", "E" have h/w around 1.2–1.6.
 * A 2.0× threshold leaves a clear gap.
 *
 * Exception: very-short tokens (count ≤ 2) bypass the ratio check entirely.
 * Glyphs like "1", "I", "l", "/", "|" can exceed 2.0× even when upright.
 */
internal fun removeRotatedWords(wordBoxTuples: List<WordBox>, ratioThreshold: Double = 2.0): List<WordBox> {
    return wordBoxTuples.filter { wb ->
        if (wb.text.length <= 2) return@filter true
        val w = wb.bbox[2] - wb.bbox[0]
        val h = wb.bbox[3] - wb.bbox[1]
        !(w > 0 && h > w * ratioThreshold)
    }
}

// --- Filter word boxes ---

internal fun filterWordBoxes(wordBoxTuples: List<WordBox>): List<WordBox> {
    return wordBoxTuples
        .filter { wb -> !(wb.text.length == 1 && !wb.text[0].isDigit()) }
        .map { wb ->
            val b = wb.bbox
            wb.copy(
                bbox = doubleArrayOf(
                    if (b[0] == 0.0) 1.0 else b[0],
                    if (b[1] == 0.0) 1.0 else b[1],
                    if (b[2] == 0.0) 1.0 else b[2],
                    if (b[3] == 0.0) 1.0 else b[3],
                ),
            )
        }
}

// --- Vertical stacking ---

/**
 * Return word boxes that sit directly below [bbox] with at least
 * [minXOverlapRatio] of [bbox]'s width overlapping horizontally, and whose top
 * edge is within [maxLineGap] pixels of [bbox]'s bottom. Sorted by top edge
 * ascending (closest neighbour first).
 *
 * Used by the multi-line-template stitching pass in [PredictionEngine] to find
 * OCR tokens belonging to the row(s) below a predicted value bbox — needed
 * because [expandText] is line-bounded and cannot cross newlines.
 */
internal fun findVerticallyStackedBelow(
    bbox: DoubleArray,
    words: List<WordBox>,
    maxLineGap: Double,
    minXOverlapRatio: Double = 0.3,
): List<WordBox> {
    if (bbox.size < 4) return emptyList()
    val bbWidth = bbox[2] - bbox[0]
    if (bbWidth <= 0) return emptyList()
    val minOverlapPx = bbWidth * minXOverlapRatio
    val hits = mutableListOf<Pair<WordBox, Double>>()
    for (wb in words) {
        if (wb.bbox.size < 4) continue
        val gap = wb.bbox[1] - bbox[3]
        if (gap <= 0 || gap > maxLineGap) continue
        val overlapLeft = kotlin.math.max(bbox[0], wb.bbox[0])
        val overlapRight = min(bbox[2], wb.bbox[2])
        val overlap = overlapRight - overlapLeft
        if (overlap < minOverlapPx) continue
        hits.add(wb to wb.bbox[1])
    }
    hits.sortBy { it.second }
    return hits.map { it.first }
}

// --- Scale boxes ---

internal fun scaleBoxesTuples(wordBoxTuples: List<WordBox>, minX: Double, minY: Double): List<WordBox> {
    return wordBoxTuples.map { wb ->
        wb.copy(
            bbox = doubleArrayOf(
                wb.bbox[0] - minX,
                wb.bbox[1] - minY,
                wb.bbox[2] - minX,
                wb.bbox[3] - minY,
            ),
        )
    }
}

// --- Find key word ---

/**
 * Precomputed normalized forms of one OCR word. The key-matching passes used to
 * re-derive these (lowercase / strip / alnum-filter / kv-suffix) for EVERY key
 * against EVERY word; in detection that's ~hundreds of redundant re-derivations
 * per word. Building them ONCE (see [buildNormWords]) and reusing across all
 * keys removes that. Fields mirror exactly the inline normalizations that
 * [findKeyWord] / [findKeyWordInner] / [wordMatchesKey] / [fuzzyWordMatch] /
 * [findKeyWordFuzzy] performed, so results are unchanged.
 */
internal class NormWord(
    val wb: WordBox,
    val text: String,          // wb.text (raw)
    val lower: String,         // text.lowercase()
    val stripped: String,      // stripNonAlnum(lower) — leading/trailing only
    val alnum: String,         // lower.filter { isLetterOrDigit() } — all removed
    val kvStrip: String,       // stripTrailingKvSuffix(text)
    val kvStripLower: String,  // kvStrip.lowercase()
)

internal fun buildNormWords(words: List<WordBox>): List<NormWord> = words.map { wb ->
    val lower = wb.text.lowercase()
    val kvStrip = stripTrailingKvSuffix(wb.text)
    NormWord(
        wb = wb,
        text = wb.text,
        lower = lower,
        stripped = stripNonAlnum(lower),
        alnum = lower.filter { it.isLetterOrDigit() },
        kvStrip = kvStrip,
        kvStripLower = kvStrip.lowercase(),
    )
}

internal fun findKeyWord(
    keyText: String,
    wordBoxTuples: List<WordBox>,
    maxGap: Double = 50.0,
    strictMultiword: Boolean = false,
    norms: List<NormWord>? = null,
): WordBox? {
    val trimmed = keyText.trim()
    if (trimmed.isEmpty()) return null
    // Reuse precomputed word normalizations when the caller supplies them
    // (detection scores many keys against the same OCR); else build inline so
    // existing callers behave exactly as before.
    val nw = norms ?: buildNormWords(wordBoxTuples)

    findKeyWordInner(trimmed, nw, maxGap, strictMultiword)?.let { return it }

    // Fallback: strip trailing punctuation that OCR drops or includes
    // inconsistently across scans, then search again.
    val trailingStripped = trimmed.trim { it in ":.#;,!?*()[]{}-_/\\|" }.trim()
    if (trailingStripped.isNotEmpty() && trailingStripped != trimmed) {
        findKeyWordInner(trailingStripped, nw, maxGap, strictMultiword)?.let { return it }
    }

    // Last-resort: strip non-alnum characters anywhere.
    val withoutSpecial = trimmed.replace(NON_ALNUM_SPACE_RE, "")
    val collapsed = withoutSpecial.replace(COLLAPSE_SPACE_RE, " ").trim()
    if (collapsed.isNotEmpty() && collapsed != trimmed && collapsed != trailingStripped) {
        findKeyWordInner(collapsed, nw, maxGap, strictMultiword)?.let { return it }
    }

    // 4) Symmetric `#` / `:` suffix tolerance. The passes above resolve most
    //    `#`/`:` mismatches indirectly through a tangle of fuzzy matching;
    //    this explicit final pass strips trailing `#`/`:` from BOTH the
    //    template key and each OCR token, then compares. Catches:
    //      - template "BAC#" vs OCR "BAC"
    //      - template "BAC"  vs OCR "BAC#" (or "BAC:")
    //      - template "PO:"  vs OCR "PO#"  (mixed marker)
    //    Hyphens / slashes / dots etc. are intentionally NOT included — those
    //    frequently belong to the key text proper (e.g. "P/N").
    val kvSuffixStripped = stripTrailingKvSuffix(trimmed)
    if (kvSuffixStripped.isNotEmpty()) {
        val kvLower = kvSuffixStripped.lowercase()
        for (w in nw) {
            if (w.kvStrip.isEmpty()) continue
            if (w.kvStrip == kvSuffixStripped) return w.wb
            if (w.kvStripLower == kvLower) return w.wb
        }
    }

    // 5) Fuzzy last-resort for SINGLE-WORD keys. OCR substitutions
    //    ("Serial#" → "Seria1#", "Qty" → "Oty") survive every strict/
    //    normalised pass above. Compare the most-normalised key form against
    //    each OCR token by edit distance and take the closest within budget.
    //    Multi-word keys already get their own fuzzy pass inside
    //    findKeyWordInner, so this only fills the single-word gap. See
    //    [findKeyWordFuzzy] for the precision guards (length floor +
    //    unique-closest requirement) that keep this from grabbing the wrong
    //    token when all it had to go on was a near-miss.
    val fuzzyKey = when {
        collapsed.isNotEmpty() -> collapsed
        trailingStripped.isNotEmpty() -> trailingStripped
        else -> trimmed
    }
    findKeyWordFuzzy(fuzzyKey, nw)?.let { return it }
    return null
}

/** Minimum alnum length (both key and candidate) for a fuzzy key match.
 *  Shields short keys like "PO" / "ID" from spurious edit-distance hits —
 *  at length 2 a single edit reaches too many unrelated tokens. */
private const val FUZZY_KEY_MIN_LEN = 3

/**
 * Fuzzy last-resort lookup for a SINGLE-WORD key, used by [findKeyWord] only
 * after every exact/normalised pass has failed — so it trades a little
 * precision for recall on OCR misreads.
 *
 * Compares the alnum-normalised [normalizedKey] against each alnum-normalised
 * OCR token by Levenshtein distance and returns the token at the MINIMUM
 * distance within [maxDist] — but only when that minimum is achieved by
 * exactly one token. Ambiguous near-ties (two tokens equally close) return
 * null rather than guessing, since picking the wrong one would shift the KV
 * relation-vector origin and mispredict the value. A [FUZZY_KEY_MIN_LEN]
 * length floor on both sides keeps short keys from matching unrelated tokens.
 *
 * Returns null for multi-word keys (those have their own fuzzy pass in
 * [findKeyWordInner]).
 */
private fun findKeyWordFuzzy(
    normalizedKey: String,
    words: List<NormWord>,
    maxDist: Int = 1,
): WordBox? {
    val key = stripNonAlnum(normalizedKey.lowercase())
    if (key.length < FUZZY_KEY_MIN_LEN || ' ' in key) return null

    var best: WordBox? = null
    var bestDist = Int.MAX_VALUE
    var bestCount = 0
    for (w in words) {
        val cand = w.stripped
        if (cand.length < FUZZY_KEY_MIN_LEN) continue
        // Levenshtein ≥ |len difference|, so a length gap over budget can't
        // be within distance — prune before the O(n·m) edit-distance call.
        if (abs(cand.length - key.length) > maxDist) continue
        val d = levenshtein(cand, key)
        if (d > maxDist) continue
        when {
            d < bestDist -> { bestDist = d; best = w.wb; bestCount = 1 }
            d == bestDist -> bestCount++
        }
    }
    return if (bestCount == 1) best else null
}

/**
 * Strip trailing `#` and `:` characters (the two glyphs OCR commonly captures
 * inconsistently on key markers like `BAC#`, `INV#`, `Qty:`). Only trailing —
 * leading `#`/`:` is rare and may be intentional, so the start is untouched.
 */
private fun stripTrailingKvSuffix(s: String): String {
    var end = s.length
    while (end > 0 && (s[end - 1] == '#' || s[end - 1] == ':')) end--
    return s.substring(0, end)
}

/**
 * Multi-match variant of [findKeyWord]. Returns every OCR token that matches
 * the given key text (across all matching passes), preserving OCR reading
 * order. Used for full-set exclusion — every key occurrence must be ineligible
 * as a value candidate regardless of which one was picked for KV scoring — and
 * by [findKeyWordByPosition] for multi-occurrence disambiguation.
 *
 * Iteratively calls [findKeyWord], removing each found match's constituent
 * words from the candidate pool and re-running, so multi-match semantics stay
 * in lockstep with single-match semantics.
 *
 * Removal is by full CONTAINMENT in the match bbox, NOT exact-bbox equality: a
 * multi-word key (e.g. "Sales Order") returns a MERGED bbox spanning its words,
 * which no individual OCR token equals — so an exact-equality filter removed
 * nothing, the loop re-found the same first occurrence, tripped the
 * [seenBboxes] guard, and returned only ONE occurrence. That silently disabled
 * multi-occurrence disambiguation for every multi-word key (they always
 * resolved to the first in reading order). Containment removal drops the
 * matched key's constituent tokens (their union IS the merged bbox) so the
 * next iteration finds the next occurrence; for single-word keys the match
 * bbox is the token's own bbox, so behaviour is unchanged. Mirrors Python
 * `find_key_word_all` and the Swift port exactly.
 */
internal fun findAllKeyWords(
    keyText: String,
    wordBoxTuples: List<WordBox>,
    maxGap: Double = 50.0,
    strictMultiword: Boolean = false,
): List<WordBox> {
    if (keyText.trim().isEmpty()) return emptyList()
    val hits = mutableListOf<WordBox>()
    val seenBboxes = HashSet<List<Double>>()
    var remaining = wordBoxTuples
    // Cap defends against the non-strict "first word only" fallback matching a
    // common token all over a dense page (mirrors Python _MAX_KEY_OCCURRENCES).
    while (hits.size < MAX_KEY_OCCURRENCES) {
        val next = findKeyWord(keyText, remaining, maxGap, strictMultiword) ?: break
        val key = next.bbox.toList()
        if (key in seenBboxes) break // defensive against loops
        seenBboxes.add(key)
        hits.add(next)
        // Drop the constituent tokens of this match (bbox fully CONTAINED in
        // the matched, possibly-merged, key bbox) so the next pass can find a
        // later occurrence rather than re-matching this one. Containment (not
        // center-inside) mirrors Python find_key_word_all and Swift exactly:
        // a bystander token merely overlapping the merged bbox stays in the
        // pool, so all three platforms collect identical occurrence sets.
        remaining = remaining.filter { wb ->
            !(wb.bbox[0] >= next.bbox[0] && wb.bbox[1] >= next.bbox[1] &&
                wb.bbox[2] <= next.bbox[2] && wb.bbox[3] <= next.bbox[3])
        }
    }
    return hits
}

/**
 * Disambiguation-aware key lookup. Finds every OCR match for [keyText] and
 * returns the one whose `(left, y_mid)` position — scaled by [scanMedianHeight]
 * — is closest to [expectedScaledPos] (template-time key position pre-scaled by
 * the template's own median height). Both sides in median-height units so the
 * comparison works across resolutions.
 *
 * Falls back to first-match when [expectedScaledPos] is null (legacy template),
 * [scanMedianHeight] is non-positive, or fewer than two matches exist.
 */
/** Defensive ceiling for [findAllKeyWords] — mirrors Python `_MAX_KEY_OCCURRENCES`. */
internal const val MAX_KEY_OCCURRENCES = 8

/**
 * Greedy 1-to-1 assignment of key INSTANCES to OCR occurrences. Used when one
 * key text is printed multiple times: each template-side instance (identified
 * by its owning field label) must bind to a distinct occurrence. All
 * (instance, occurrence) pairs are ranked by squared Euclidean distance with a
 * deterministic `(d2, label, occurrenceIndex)` tiebreak so every platform
 * produces the same assignment. Mirrors Python `assign_occurrences_greedy`.
 *
 * Returns `{label: occurrenceIndex}` for assigned instances only.
 */
internal fun assignOccurrencesGreedy(
    instancePoints: Map<String, Pair<Double, Double>>,
    occurrencePoints: List<Pair<Double, Double>>,
): Map<String, Int> {
    if (instancePoints.isEmpty() || occurrencePoints.isEmpty()) return emptyMap()
    data class Cand(val d2: Double, val label: String, val occIdx: Int)
    val pairs = mutableListOf<Cand>()
    for ((label, ip) in instancePoints) {
        for ((oi, op) in occurrencePoints.withIndex()) {
            val dx = ip.first - op.first
            val dy = ip.second - op.second
            pairs.add(Cand(dx * dx + dy * dy, label, oi))
        }
    }
    pairs.sortWith(compareBy({ it.d2 }, { it.label }, { it.occIdx }))
    val assigned = mutableMapOf<String, Int>()
    val usedOccurrences = mutableSetOf<Int>()
    for (p in pairs) {
        if (p.label in assigned || p.occIdx in usedOccurrences) continue
        assigned[p.label] = p.occIdx
        usedOccurrences.add(p.occIdx)
    }
    return assigned
}

/**
 * Center a point set on its own component-wise median. Used by the KV
 * matcher's multi-instance binding so template↔scan key assignment compares
 * the keys' RELATIVE layout (translation-invariant) instead of absolute
 * text-area positions, whose origin drifts with extra/missing OCR words.
 * Mirrors the Python `_median_center` inside `_resolve_key_instances`.
 */
internal fun medianCenter(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    fun med(vals: List<Double>): Double {
        val s = vals.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
    val cx = med(points.map { it.first })
    val cy = med(points.map { it.second })
    return points.map { Pair(it.first - cx, it.second - cy) }
}

internal fun findKeyWordByPosition(
    keyText: String,
    wordBoxTuples: List<WordBox>,
    expectedScaledPos: Pair<Double, Double>?,
    scanMedianHeight: Double,
    maxGap: Double = 50.0,
): WordBox? = pickKeyWordByPosition(
    findAllKeyWords(keyText, wordBoxTuples, maxGap), expectedScaledPos, scanMedianHeight,
)

/**
 * Position-disambiguation over PRECOMPUTED matches (see [findKeyWordByPosition]).
 * Split out so callers that already ran [findAllKeyWords] (e.g. the KV matcher's
 * shared per-key-text cache) don't scan the OCR a second time.
 */
internal fun pickKeyWordByPosition(
    matches: List<WordBox>,
    expectedScaledPos: Pair<Double, Double>?,
    scanMedianHeight: Double,
): WordBox? {
    if (matches.isEmpty()) return null
    if (matches.size == 1) return matches[0]
    if (expectedScaledPos == null || scanMedianHeight <= 0) return matches[0]
    var best = matches[0]
    var bestDist = Double.MAX_VALUE
    for (wb in matches) {
        val p = getLeftMidpoint(wb.bbox)
        val dx = p.first / scanMedianHeight - expectedScaledPos.first
        val dy = p.second / scanMedianHeight - expectedScaledPos.second
        val d2 = dx * dx + dy * dy
        if (d2 < bestDist) {
            bestDist = d2
            best = wb
        }
    }
    return best
}

private fun findKeyWordInner(
    keyText: String,
    words: List<NormWord>,
    maxGap: Double,
    strictMultiword: Boolean = false,
): WordBox? {
    val keyLower = keyText.lowercase()

    // Single-word key
    if (" " !in keyText) {
        words.firstOrNull { it.text == keyText }?.let { return it.wb }
        words.firstOrNull { it.lower == keyLower }?.let { return it.wb }
        words.firstOrNull { wordMatchesKey(it, keyText, keyLower) }?.let { return it.wb }
        return null
    }

    val keyParts = keyLower.split(" ")
    val nParts = keyParts.size

    // Exact multi-word match
    for (startIdx in words.indices) {
        val first = words[startIdx]
        if (!wordMatchesKey(first, keyParts[0], keyParts[0])) continue
        val matched = mutableListOf(first.wb)
        var nextIdx = startIdx + 1
        for (partI in 1 until nParts) {
            if (nextIdx >= words.size) break
            val nextW = words[nextIdx]
            if (!wordMatchesKey(nextW, keyParts[partI], keyParts[partI])) break
            val prevBbox = matched.last().bbox
            val gap = nextW.wb.bbox[0] - prevBbox[2]
            if (gap > maxGap) break
            matched.add(nextW.wb)
            nextIdx++
        }
        if (matched.size == nParts) return mergeWordBoxes(matched)
    }

    // Fuzzy multi-word match
    for (startIdx in words.indices) {
        val first = words[startIdx]
        if (!wordMatchesKey(first, keyParts[0], keyParts[0]) && !fuzzyWordMatch(first, keyParts[0])) continue
        val matched = mutableListOf(first.wb)
        var nextIdx = startIdx + 1
        for (partI in 1 until nParts) {
            if (nextIdx >= words.size) break
            val nextW = words[nextIdx]
            if (!wordMatchesKey(nextW, keyParts[partI], keyParts[partI]) && !fuzzyWordMatch(nextW, keyParts[partI])) break
            val prevBbox = matched.last().bbox
            val gap = nextW.wb.bbox[0] - prevBbox[2]
            if (gap > maxGap) break
            matched.add(nextW.wb)
            nextIdx++
        }
        if (matched.size == nParts) return mergeWordBoxes(matched)
    }

    // OCR-merged variant: when the engine returns the multi-word key as a
    // single concatenated token (e.g. "PARCEL ID" → "PARCELID"), try a
    // single-word lookup with whitespace removed and alnum-only comparison.
    val keyConcat = keyLower.replace(" ", "")
    val keyAlnum = keyConcat.filter { it.isLetterOrDigit() }
    if (keyAlnum.isNotEmpty()) {
        for (w in words) {
            if (w.alnum == keyAlnum) return w.wb
        }
    }

    // Last resort: match just the first word. Skipped in strict mode — matching
    // a whole multi-word key on its first word alone (e.g. "Ship" for "SHIP TO
    // POSTAL") is a false positive that must not count as key presence.
    if (!strictMultiword) {
        val firstPart = keyParts[0]
        words.firstOrNull { wordMatchesKey(it, firstPart, firstPart) }?.let { return it.wb }
    }

    return null
}

private fun mergeWordBoxes(matched: List<WordBox>): WordBox {
    val mergedText = matched.joinToString(" ") { it.text }
    val x1 = matched.minOf { it.bbox[0] }
    val y1 = matched.minOf { it.bbox[1] }
    val x2 = matched.maxOf { it.bbox[2] }
    val y2 = matched.maxOf { it.bbox[3] }
    return WordBox(mergedText, doubleArrayOf(x1, y1, x2, y2))
}

// Compiled ONCE (module scope). These run in the hot key-matching loop
// (findKeyWord × every key × every word); compiling per call was a large,
// avoidable cost. Behaviour is identical — same patterns.
private val STRIP_NON_ALNUM_RE = Regex("^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$")
private val NON_ALNUM_SPACE_RE = Regex("[^a-zA-Z0-9\\s]")
private val COLLAPSE_SPACE_RE = Regex("\\s+")

private fun stripNonAlnum(text: String): String =
    text.replace(STRIP_NON_ALNUM_RE, "")

private fun containsAlnum(s: String): Boolean = s.any { it.isLetterOrDigit() }

// `key` is the raw key token; `keyLower` its precomputed lowercase (callers
// already have it — multi-word parts come pre-lowercased). Word normalizations
// are read from the precomputed [NormWord]. Logic identical to the old
// (word: String, key: String) form.
private fun wordMatchesKey(w: NormWord, key: String, keyLower: String): Boolean {
    if (w.text == key) return true
    val wl = w.lower
    val kl = keyLower
    if (wl == kl) return true
    if (wl.startsWith(kl)) {
        val tail = wl.substring(kl.length)
        if (!containsAlnum(tail)) return true
    }
    if (wl.endsWith(kl)) {
        val head = wl.substring(0, wl.length - kl.length)
        if (!containsAlnum(head)) return true
    }
    if (w.stripped == kl) return true
    return false
}

private fun fuzzyWordMatch(w: NormWord, keyLower: String, maxDist: Int = 1): Boolean {
    val wl = w.stripped
    val kl = keyLower
    if (wl.length < 3 || kl.length < 3) return false
    return levenshtein(wl, kl) <= maxDist
}

// --- Find anchor words ---

internal data class AnchorWord(val text: String, val bbox: DoubleArray, val index: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnchorWord) return false
        return text == other.text && bbox.contentEquals(other.bbox) && index == other.index
    }
    override fun hashCode(): Int {
        var h = text.hashCode()
        h = 31 * h + bbox.contentHashCode()
        h = 31 * h + index
        return h
    }
}

/**
 * Locate the template's known anchor word(s) in the target OCR. When the same
 * anchor appears more than once in OCR, sort by area descending; if the largest
 * is clearly bigger (top1/top2 ≥ areaRatioThreshold) or [templateAnchorPositions]
 * is null, return the largest. Otherwise pick the candidate whose `[cx/h_a,
 * cy/h_a]` is closest to the template's stored value.
 */
internal fun findAnchorWords(
    entries: List<WordBox>,
    templateAnchors: Set<String>,
    templateAnchorPositions: Map<String, DoubleArray>? = null,
    areaRatioThreshold: Double = 1.5,
): List<AnchorWord> {
    if (templateAnchors.isEmpty()) return emptyList()
    val normalizedTemplate = templateAnchors
        .map { normalizeAnchor(it) }
        .filter { it.isNotEmpty() }
        .toSet()
    if (normalizedTemplate.isEmpty()) return emptyList()

    val normToTemplatePos = HashMap<String, Pair<Double, Double>>()
    if (templateAnchorPositions != null) {
        for ((tText, pos) in templateAnchorPositions) {
            if (pos.size < 2) continue
            val tNorm = normalizeAnchor(tText)
            if (tNorm.isNotEmpty()) normToTemplatePos[tNorm] = pos[0] to pos[1]
        }
    }

    data class Candidate(val area: Double, val anchor: AnchorWord)
    val candidatesPerNorm = HashMap<String, MutableList<Candidate>>()
    for ((i, wb) in entries.withIndex()) {
        val norm = normalizeAnchor(wb.text)
        if (norm.isEmpty() || norm !in normalizedTemplate) continue
        val area = (wb.bbox[2] - wb.bbox[0]) * (wb.bbox[3] - wb.bbox[1])
        candidatesPerNorm.getOrPut(norm) { mutableListOf() }
            .add(Candidate(area, AnchorWord(wb.text, wb.bbox, i)))
    }

    val selected = mutableListOf<AnchorWord>()
    for ((norm, candidates) in candidatesPerNorm) {
        val sorted = candidates.sortedByDescending { it.area }
        val topArea = sorted[0].area
        val templatePos = normToTemplatePos[norm]
        val close = sorted.filter { topArea / kotlin.math.max(it.area, 1.0) < areaRatioThreshold }

        if (close.size <= 1 || templatePos == null) {
            selected.add(sorted[0].anchor)
            continue
        }
        val (tx, ty) = templatePos
        val best = close.minByOrNull { cand ->
            val p = computeAnchorPositionNorm(cand.anchor.bbox)
            (p[0] - tx).pow(2) + (p[1] - ty).pow(2)
        }!!
        selected.add(best.anchor)
    }
    return selected
}

/** Anchor centroid in anchor-height units: `[cx/h_a, cy/h_a]`. */
internal fun computeAnchorPositionNorm(bbox: DoubleArray): DoubleArray {
    val center = getLeftMidpoint(bbox)
    val hA = getHeight(bbox)
    val denom = if (hA > 0.0) hA else 1.0
    return doubleArrayOf(center.first / denom, center.second / denom)
}

private fun normalizeAnchor(s: String): String =
    s.lowercase().filter { it.isLetterOrDigit() }

// --- Levenshtein distance ---

/**
 * Single-array Levenshtein DP parameterized by a per-substitution [subCost].
 * Insertions and deletions always cost 1.0; only the substitution cost varies,
 * so [levenshtein] (0/1 cost) and [confusableLevenshtein] (discounted glyph-
 * confusion cost) are two specializations of the same recurrence rather than
 * two hand-maintained copies that could drift apart. `inline` so the cost
 * lambda is folded into the inner loop with no per-cell call overhead.
 *
 * The longer string drives the outer loop so the `prev`/`curr` arrays stay
 * sized to the shorter one — safe because every cost here is symmetric, making
 * the distance itself symmetric.
 */
private inline fun editDistance(a: String, b: String, subCost: (Char, Char) -> Double): Double {
    val s1: String
    val s2: String
    if (a.length >= b.length) { s1 = a; s2 = b } else { s1 = b; s2 = a }
    val n = s2.length
    if (n == 0) return s1.length.toDouble()
    var prev = DoubleArray(n + 1) { it.toDouble() }
    var curr = DoubleArray(n + 1)
    for (i in 1..s1.length) {
        curr[0] = i.toDouble()
        for (j in 1..n) {
            curr[j] = minOf(
                prev[j - 1] + subCost(s1[i - 1], s2[j - 1]),
                prev[j] + 1.0,
                curr[j - 1] + 1.0,
            )
        }
        val tmp = prev; prev = curr; curr = tmp
    }
    return prev[n]
}

internal fun levenshtein(s1: String, s2: String): Int =
    editDistance(s1, s2) { c1, c2 -> if (c1 == c2) 0.0 else 1.0 }.toInt()

// --- Confusable-aware edit distance (OCR misread correction) ---

/**
 * Glyph-confusion groups, lower-cased. A substitution between two chars in the
 * same group costs [CONFUSABLE_SUB_COST] in [confusableLevenshtein] instead of
 * 1.0, so matching OCR text against a high-confidence reference (a barcode
 * payload) tolerates the errors OCR actually makes — O↔0, l↔1, S↔5, V↔W, &↔8 —
 * far more readily than arbitrary edits. Curated from observed device misreads
 * plus the usual seven-segment / serif confusions.
 */
private val CONFUSABLE_GROUPS: List<Set<Char>> = listOf(
    setOf('0', 'o', 'd', 'q'),
    setOf('1', 'l', 'i', 'j'),
    setOf('5', 's'),
    setOf('8', 'b', '&'),
    setOf('2', 'z'),
    setOf('6', 'g'),
    setOf('v', 'w', 'u', 'y'),
    setOf('7', 't'),
    setOf('c', 'e'),
    setOf('m', 'n'),
)

private const val CONFUSABLE_SUB_COST = 0.3

/** char → other chars it is commonly confused with (lower-cased). */
private val confusableMap: Map<Char, Set<Char>> = buildMap {
    for (group in CONFUSABLE_GROUPS) {
        for (c in group) put(c, (getOrElse(c) { emptySet() } + group) - c)
    }
}

private fun substitutionCost(a: Char, b: Char): Double {
    if (a == b) return 0.0
    val al = a.lowercaseChar()
    val bl = b.lowercaseChar()
    if (al == bl) return 0.0 // pure case difference
    if (confusableMap[al]?.contains(bl) == true) return CONFUSABLE_SUB_COST
    return 1.0
}

/**
 * Levenshtein distance with OCR-confusable substitutions discounted to
 * [CONFUSABLE_SUB_COST] (insertions/deletions and non-confusable substitutions
 * stay at 1.0). Used to match noisy OCR text against error-corrected reference
 * strings (barcode payloads) where the expected divergence is glyph confusion,
 * not arbitrary edits.
 */
internal fun confusableLevenshtein(s1: String, s2: String): Double =
    editDistance(s1, s2, ::substitutionCost)

/**
 * Find the substring of [haystack] that best matches [needle] under
 * [confusableLevenshtein], returning `(substring, length-normalised distance)`
 * or null when either string is empty. Normalised distance is
 * `dist / max(needle.length, sub.length)` so it's comparable across lengths;
 * lower = better.
 *
 * The substring START offset is searched fully, so a leading Application-
 * Identifier prefix on a barcode payload (e.g. "1Y…", "3S…", "W…") is skipped
 * regardless. Window LENGTHS span only `|needle| ± 1` — just enough for one
 * dropped/extra glyph — so a short needle can't match a substring much longer
 * than itself (which is how "A0" spuriously matched the 3-char "A80"). Cheap —
 * payloads and values are short.
 */
internal fun bestConfusableAlignedSubstring(haystack: String, needle: String): Pair<String, Double>? {
    if (haystack.isEmpty() || needle.isEmpty()) return null
    var bestSub: String? = null
    var bestNorm = Double.MAX_VALUE
    val lenLo = maxOf(1, needle.length - 1)
    val lenHi = minOf(haystack.length, needle.length + 1)
    for (len in lenLo..lenHi) {
        for (start in 0..(haystack.length - len)) {
            val sub = haystack.substring(start, start + len)
            val norm = confusableLevenshtein(needle, sub) / maxOf(needle.length, len).toDouble()
            if (norm < bestNorm) {
                bestNorm = norm
                bestSub = sub
            }
        }
    }
    return bestSub?.let { it to bestNorm }
}

// --- Skew detection & correction ---

/**
 * Angle (degrees) of a word's top edge from its 4 corner points.
 * atan2(y2-y1, x2-x1) from top-left (vertex 0) to top-right (vertex 1).
 */
internal fun angleFromCornerPoints(corners: List<Pair<Int, Int>>): Double? {
    if (corners.size < 2) return null
    val (x1, y1) = corners[0]
    val (x2, y2) = corners[1]
    return Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()))
}

/**
 * Centrality weight for a bbox: 1.0 at the image center, falling off linearly
 * toward the corners, clamped so edge text still casts a vote. Returns 1.0
 * (no weighting) when image dimensions are unknown.
 */
internal fun centerWeight(
    bbox: DoubleArray,
    imageWidth: Double,
    imageHeight: Double,
    minWeight: Double = 0.2,
): Double {
    if (imageWidth <= 0 || imageHeight <= 0) return 1.0
    val cx = (bbox[0] + bbox[2]) / 2.0
    val cy = (bbox[1] + bbox[3]) / 2.0
    val dx = (cx - imageWidth / 2.0) / (imageWidth / 2.0)
    val dy = (cy - imageHeight / 2.0) / (imageHeight / 2.0)
    val d = min(1.0, sqrt(dx * dx + dy * dy) / sqrt(2.0))
    return kotlin.math.max(minWeight, 1.0 - d)
}

/**
 * Find the dominant text angle using weighted histogram binning. Each angle
 * carries a weight (typically derived from how central the word is in the
 * frame); 1-degree bins are scored by total weight and the largest-weight
 * bin's weighted-mean angle is returned.
 */
internal fun computeDominantSkewAngleWeighted(
    items: List<Pair<Double, Double>>,
    minAngleDeg: Double = 0.5,
): Double {
    if (items.size < 3) return 0.0

    val bins = HashMap<Int, MutableList<Pair<Double, Double>>>()
    for ((a, w) in items) {
        val key = round(a).toInt()
        bins.getOrPut(key) { mutableListOf() }.add(a to w)
    }

    val largestBin = bins.values.maxByOrNull { lst -> lst.sumOf { it.second } } ?: return 0.0
    val sumW = largestBin.sumOf { it.second }
    if (sumW <= 0.0) return 0.0
    val weightedAvg = largestBin.sumOf { it.first * it.second } / sumW
    return if (abs(weightedAvg) < minAngleDeg) 0.0 else weightedAvg
}

/** Unweighted variant — kept for callers that don't have per-word weights. */
internal fun computeDominantSkewAngle(angles: List<Double>, minAngleDeg: Double = 0.5): Double {
    if (angles.size < 3) return 0.0
    return computeDominantSkewAngleWeighted(angles.map { it to 1.0 }, minAngleDeg)
}

/** Rotate a point (x, y) by angleDeg degrees around center (cx, cy). */
internal fun rotatePoint(x: Double, y: Double, angleDeg: Double, cx: Double, cy: Double): Pair<Double, Double> {
    val rad = Math.toRadians(angleDeg)
    val cosA = cos(rad)
    val sinA = sin(rad)
    val dx = x - cx
    val dy = y - cy
    return Pair(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
}

/**
 * Rotate corner points by `angleDeg` around `(cx, cy)`, then return the AABB
 * enclosing the rotated points. Used for barcode bbox-deskew where corners
 * capture the real oriented quad, avoiding the inflation from rotating an
 * axis-aligned hull.
 */
internal fun rotateCornersToBbox(
    corners: List<Pair<Int, Int>>,
    angleDeg: Double,
    cx: Double,
    cy: Double,
): DoubleArray {
    val rotated = corners.map {
        rotatePoint(it.first.toDouble(), it.second.toDouble(), angleDeg, cx, cy)
    }
    val xs = rotated.map { it.first }
    val ys = rotated.map { it.second }
    return doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
}

/**
 * Build a bbox using the polygon's perpendicular height instead of AABB height.
 * After rotation correction, polygons can retain a small per-word tilt, and the
 * AABB y-range inflates as `sin(θ)·w + cos(θ)·h_text` while the true text height
 * is roughly the polygon's perpendicular edge length.
 *
 * `corners` are expected in [TL, TR, BR, BL] order. Falls back to plain AABB
 * when perp_h is within 5% of aabb_h (no-op for axis-aligned text).
 */
internal fun perpHeightBbox(corners: List<Pair<Int, Int>>): DoubleArray {
    val tl = corners[0]
    val tr = corners[1]
    val br = corners[2]
    val bl = corners[3]

    val leftH = sqrt((bl.first - tl.first).toDouble().pow(2) + (bl.second - tl.second).toDouble().pow(2))
    val rightH = sqrt((br.first - tr.first).toDouble().pow(2) + (br.second - tr.second).toDouble().pow(2))
    val perpH = (leftH + rightH) / 2

    val xs = corners.map { it.first.toDouble() }
    val ys = corners.map { it.second.toDouble() }
    val xMin = xs.min()
    val xMax = xs.max()
    val yMin = ys.min()
    val yMax = ys.max()
    val aabbH = yMax - yMin

    if (aabbH <= 0 || perpH <= 0 || perpH >= aabbH * 0.95) {
        return doubleArrayOf(xMin, yMin, xMax, yMax)
    }
    val cyAvg = ys.average()
    val halfH = perpH / 2
    return doubleArrayOf(xMin, cyAvg - halfH, xMax, cyAvg + halfH)
}

/**
 * Combined deskew + perpendicular-height correction. Rotates corners by
 * `angleDeg` around `(cx, cy)` first, then applies [perpHeightBbox] on the
 * rotated corners. At `angleDeg=0` this is exactly [perpHeightBbox].
 */
internal fun rotateCornersToPerpHeightBbox(
    corners: List<Pair<Int, Int>>,
    angleDeg: Double,
    cx: Double,
    cy: Double,
): DoubleArray {
    if (angleDeg == 0.0) return perpHeightBbox(corners)
    val rotated = corners.map {
        val p = rotatePoint(it.first.toDouble(), it.second.toDouble(), angleDeg, cx, cy)
        p.first.toInt() to p.second.toInt()
    }
    return perpHeightBbox(rotated)
}

/**
 * Rotate an axis-aligned bbox [x1, y1, x2, y2] by angleDeg around (cx, cy).
 * Returns a new axis-aligned bbox that encloses the rotated corners.
 */
internal fun rotateBbox(bbox: DoubleArray, angleDeg: Double, cx: Double, cy: Double): DoubleArray {
    val corners = arrayOf(
        rotatePoint(bbox[0], bbox[1], angleDeg, cx, cy),
        rotatePoint(bbox[2], bbox[1], angleDeg, cx, cy),
        rotatePoint(bbox[2], bbox[3], angleDeg, cx, cy),
        rotatePoint(bbox[0], bbox[3], angleDeg, cx, cy),
    )
    return doubleArrayOf(
        corners.minOf { it.first },
        corners.minOf { it.second },
        corners.maxOf { it.first },
        corners.maxOf { it.second },
    )
}

// --- Utility ---

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
}
