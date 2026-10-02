package io.packagex.texttemplates.prediction

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

// --- Dimension weights ---

// KV & barcode: [dx/mh, dy/mh, cx_b/mh, (pw-cx_b)/mh, cy_b/mh, (ph-cy_b)/mh, h_a/mh, h_b/mh]
internal val DIMENSION_WEIGHTS = doubleArrayOf(4.0, 4.0, 0.25, 0.25, 0.25, 0.25, 1.0, 1.0)

// Pairwise & anchor: [dist/mh, angle_deg, cx_b/mh, (pw-cx_b)/mh, cy_b/mh, (ph-cy_b)/mh, h_a/mh, h_b/mh]
internal val POLAR_DIMENSION_WEIGHTS = doubleArrayOf(4.0, 1.0, 0.25, 0.25, 0.25, 0.25, 1.0, 1.0)

// Anchor weights — used only when the template carries calibrated anchor scale
// factors (anchor_scale_factors_avg). dim0/dim1 are rescaled to a common target
// so distance no longer needs its 4× scale-compensation → drops to 1, equal to
// angle. Raw (uncalibrated) anchor path keeps POLAR_DIMENSION_WEIGHTS. See
// docs/ANCHOR_NORMALIZATION.md.
internal val ANCHOR_DIMENSION_WEIGHTS = doubleArrayOf(1.0, 1.0, 0.25, 0.25, 0.25, 0.25, 1.0, 1.0)

/** Copy of [vec] with dim0 (distance) and dim1 (angle) multiplied by the
 *  per-field calibration factors [f0, f1]. Applied identically to stored and
 *  live vectors so the expected value maps to the common target. */
private fun scaleAnchorDims01(vec: DoubleArray, factors: List<Double>): DoubleArray {
    if (factors.size < 2) return vec
    val out = vec.copyOf()
    out[0] *= factors[0]
    out[1] *= factors[1]
    return out
}

// Primary key: boost dx/dy 3x
internal val PRIMARY_KEY_DIMENSION_WEIGHTS = doubleArrayOf(12.0, 12.0, 0.25, 0.25, 0.25, 0.25, 1.0, 1.0)

// Primary only (flexible): boost dx/dy 3x, reduce absolute position dims
internal val PRIMARY_ONLY_DIMENSION_WEIGHTS = doubleArrayOf(12.0, 12.0, 0.15, 0.15, 0.15, 0.15, 1.0, 1.0)

/**
 * Barcode matcher: drop dim[6] (h_a / w_a — barcode height as a ratio to
 * barcode width). For 1D barcodes the height is cosmetic — printers stretch
 * them vertically — so h_a/w_a is noise. For 2D it's constant at 1.0 —
 * uninformative. The matcher already filters by typeId before comparing.
 */
internal val BARCODE_DIMENSION_WEIGHTS = doubleArrayOf(4.0, 4.0, 0.25, 0.25, 0.25, 0.25, 0.0, 1.0)

// Fusion constants
internal const val RRF_K = 5
internal const val RRF_TOP_N = 5
internal const val DEFAULT_N_CANDIDATES = 10
internal const val PRIMARY_KEY_WEIGHT = 3.0

internal val BASE_WEIGHTS = mapOf(
    "pairwise" to 0.15,
    "anchor" to 0.10,
    "barcode" to 0.30,
    "kv" to 0.45,
)

// --- Weighted L2 distance ---

internal fun weightedL2(a: DoubleArray, b: DoubleArray, weights: DoubleArray = DIMENSION_WEIGHTS): Double {
    var sum = 0.0
    for (i in a.indices) {
        val diff = a[i] - b[i]
        sum += weights[i] * diff * diff
    }
    return sqrt(sum)
}

/**
 * Multiply a fresh live relation vector by per-axis factors so it lands in the
 * same axis-scaled space as the (pre-scaled) stored template vector. Only
 * displacement dims (dx, dy) get the axis multiplier; absolute-position dims
 * and heights pass through unchanged.
 */
internal fun applyAxisScalingToLive(vec: DoubleArray, scales: AxisScale): DoubleArray = doubleArrayOf(
    vec[0] * scales.x,   // dx — displacement, scaled
    vec[1] * scales.y,   // dy — displacement, scaled
    vec[2],              // cx_b — absolute position, NOT scaled
    vec[3],              // pw - cx_b — absolute position, NOT scaled
    vec[4],              // cy_b — absolute position, NOT scaled
    vec[5],              // ph - cy_b — absolute position, NOT scaled
    vec[6],              // h_a — height, NOT scaled
    vec[7],              // h_b — height, NOT scaled
)

internal fun distanceToScore(distance: Double, sigma: Double = 6.0): Double = exp(-distance / sigma)

// --- Match Pairwise ---

/**
 * N×N×8 pairwise word vectors, computed on-demand.
 * wordVectors[i][j] = 8D polar relation vector from word i to word j.
 */
internal fun computeWordVectorsPolar(
    wordBoxTuples: List<WordBox>,
    medianHeight: Double,
    pageWidth: Double,
    pageHeight: Double,
    heightScale: Double,
): Array<Array<DoubleArray>> {
    val n = wordBoxTuples.size
    val result = Array(n) { Array(n) { DoubleArray(8) } }
    for (i in 0 until n) {
        for (j in 0 until n) {
            if (i == j) continue
            result[i][j] = computeRelationVectorPolar(
                wordBoxTuples[i].bbox, wordBoxTuples[j].bbox,
                medianHeight, pageWidth, pageHeight, heightScale,
            )
        }
    }
    return result
}

/** Avg-height-scaled variant — each (i, j) pair uses h_ref = (h_i + h_j)/2. */
internal fun computeWordVectorsPolarAvg(
    wordBoxTuples: List<WordBox>,
    pageWidth: Double,
    pageHeight: Double,
    heightScale: Double,
): Array<Array<DoubleArray>> {
    val n = wordBoxTuples.size
    val result = Array(n) { Array(n) { DoubleArray(8) } }
    for (i in 0 until n) {
        for (j in 0 until n) {
            if (i == j) continue
            result[i][j] = computeRelationVectorPolarAvg(
                wordBoxTuples[i].bbox, wordBoxTuples[j].bbox,
                pageWidth, pageHeight, heightScale,
            )
        }
    }
    return result
}

internal fun matchPairwise(
    templateVectors: Map<String, PairVector>,
    wordVectors: Array<Array<DoubleArray>>,
    wordBoxTuples: List<WordBox>,
    nCandidates: Int = DEFAULT_N_CANDIDATES,
    nAnchorPairs: Int = 5,
    excludedIndices: Set<Int>? = null,
    timingsOut: MutableMap<String, Double>? = null,
): Pair<Map<String, List<Pair<Int, Double>>>, Int> {
    if (templateVectors.isEmpty()) return Pair(emptyMap(), 0)

    // TEMP(pairwise-profiling): split build / sort / assign cost.
    var sortNs = 0L
    var assignNs = 0L

    val nWords = wordBoxTuples.size
    if (nWords == 0) return Pair(emptyMap(), 0)

    // Collect field names from pair keys
    val fieldNames = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (key in templateVectors.keys) {
        if ("->" !in key) continue
        val (a, b) = key.split("->")
        if (a !in seen) { fieldNames.add(a); seen.add(a) }
        if (b !in seen) { fieldNames.add(b); seen.add(b) }
    }

    // For each pair, compute distances across all word pairs.
    val tBuild = System.nanoTime()
    val pairDistances = mutableMapOf<String, Array<DoubleArray>>()
    for ((key, pv) in templateVectors) {
        if ("->" !in key) continue
        val tv = pv.vector.toDoubleArray()
        val dists = Array(nWords) { i ->
            DoubleArray(nWords) { j ->
                if (excludedIndices != null && (i in excludedIndices || j in excludedIndices)) {
                    Double.MAX_VALUE
                } else {
                    weightedL2(tv, wordVectors[i][j], POLAR_DIMENSION_WEIGHTS)
                }
            }
        }
        pairDistances[key] = dists
    }

    val buildNs = System.nanoTime() - tBuild
    val pairKeys = pairDistances.keys.toList()
    if (pairKeys.isEmpty()) return Pair(emptyMap(), 0)

    data class Assignment(val totalDist: Double, val fieldToWord: Map<String, Int>)
    val allAssignments = mutableListOf<Assignment>()

    for (anchorPairKey in pairKeys) {
        val anchorDists = pairDistances[anchorPairKey]!!
        // Partial top-K: keep only the nAnchorPairs smallest distances (K is
        // tiny) via bounded insertion — avoids a full O(N²·log N²) sort of every
        // word pair. Kept sorted ascending; ties keep the earlier-encountered
        // entry (deterministic, matched across platforms).
        val tSort = System.nanoTime()
        val flatIndices = ArrayList<Triple<Double, Int, Int>>(nAnchorPairs)
        for (i in 0 until nWords) {
            for (j in 0 until nWords) {
                val dv = anchorDists[i][j]
                if (dv >= Double.MAX_VALUE) continue
                if (flatIndices.size < nAnchorPairs) {
                    flatIndices.add(Triple(dv, i, j))
                    var pos = flatIndices.size - 1
                    while (pos > 0 && flatIndices[pos - 1].first > dv) {
                        flatIndices[pos] = flatIndices[pos - 1]; pos--
                    }
                    flatIndices[pos] = Triple(dv, i, j)
                } else if (dv < flatIndices[flatIndices.size - 1].first) {
                    var pos = flatIndices.size - 1
                    while (pos > 0 && flatIndices[pos - 1].first > dv) {
                        flatIndices[pos] = flatIndices[pos - 1]; pos--
                    }
                    flatIndices[pos] = Triple(dv, i, j)
                }
            }
        }
        sortNs += System.nanoTime() - tSort

        val (fieldA, fieldB) = anchorPairKey.split("->")

        val tAssign = System.nanoTime()
        for (k in 0 until flatIndices.size) {
            val (_, i, j) = flatIndices[k]
            val assignments = mutableMapOf(fieldA to i, fieldB to j)
            var totalDist = anchorDists[i][j]

            for ((pairKey, dists) in pairDistances) {
                if (pairKey == anchorPairKey) continue
                val (a, b) = pairKey.split("->")
                if (a in assignments) {
                    val row = assignments[a]!!
                    var bestCol = 0
                    var bestDist = Double.MAX_VALUE
                    for (col in 0 until nWords) {
                        if (dists[row][col] < bestDist) {
                            bestDist = dists[row][col]
                            bestCol = col
                        }
                    }
                    assignments.putIfAbsent(b, bestCol)
                    totalDist += bestDist
                } else if (b in assignments) {
                    val col = assignments[b]!!
                    var bestRow = 0
                    var bestDist = Double.MAX_VALUE
                    for (row in 0 until nWords) {
                        if (dists[row][col] < bestDist) {
                            bestDist = dists[row][col]
                            bestRow = row
                        }
                    }
                    assignments.putIfAbsent(a, bestRow)
                    totalDist += bestDist
                }
            }

            // Penalize duplicate assignments
            val wordIndices = assignments.values.toList()
            val nUnique = wordIndices.toSet().size
            if (nUnique < wordIndices.size) {
                totalDist += (wordIndices.size - nUnique) * 50.0
            }

            allAssignments.add(Assignment(totalDist, assignments))
        }
        assignNs += System.nanoTime() - tAssign
    }

    timingsOut?.put("pwBuildMs", buildNs / 1_000_000.0)
    timingsOut?.put("pwSortMs", sortNs / 1_000_000.0)
    timingsOut?.put("pwAssignMs", assignNs / 1_000_000.0)
    timingsOut?.put("pwPairs", pairKeys.size.toDouble())
    timingsOut?.put("pwWords", nWords.toDouble())

    allAssignments.sortBy { it.totalDist }

    val candidatesPerField = mutableMapOf<String, MutableList<Pair<Int, Double>>>()
    for (f in fieldNames) candidatesPerField[f] = mutableListOf()
    for (assignment in allAssignments) {
        for ((field, wordIdx) in assignment.fieldToWord) {
            candidatesPerField.getOrPut(field) { mutableListOf() }
                .add(Pair(wordIdx, assignment.totalDist))
        }
    }

    val result = mutableMapOf<String, List<Pair<Int, Double>>>()
    for ((field, cands) in candidatesPerField) {
        val seenIndices = mutableSetOf<Int>()
        val unique = mutableListOf<Pair<Int, Double>>()
        for ((idx, dist) in cands.sortedBy { it.second }) {
            if (idx !in seenIndices) {
                seenIndices.add(idx)
                unique.add(Pair(idx, dist))
            }
        }
        result[field] = unique.take(nCandidates)
    }

    return Pair(result, pairKeys.size)
}

// --- Match Anchor ---

private fun fuzzyMatchAnchorKey(targetWord: String, keys: Collection<String>, strict: Boolean = false): String? {
    if (targetWord in keys) return targetWord
    val targetLower = targetWord.lowercase()
    for (k in keys) {
        if (k.lowercase() == targetLower) return k
    }
    for (k in keys) {
        if (k.length >= 3 && targetWord.length >= 3) {
            if (levenshtein(k.lowercase(), targetLower) <= 1) return k
        }
    }
    // Containment: one contains the other (e.g. "ARISTA" ⊆ "ARISTA®"). In strict
    // mode only the anchor-⊆-token direction is allowed — a short OCR fragment
    // inside a long anchor (the date "Jun" ⊆ "Juniper") is a false positive that
    // strict mode drops. Used only in the very-ambiguous re-pass (see
    // detectTemplate). Kept in lockstep with Python/Swift.
    for (k in keys) {
        val kLower = k.lowercase()
        if (kLower.length >= 3 && targetLower.length >= 3) {
            if (kLower in targetLower) return k
            if (!strict && targetLower in kLower) return k
        }
    }
    return null
}

private fun fuzzyMatchAnchor(
    targetWord: String,
    templateAnchorVecs: Map<String, Map<String, List<Double>>>,
    strict: Boolean = false,
): String? = fuzzyMatchAnchorKey(targetWord, templateAnchorVecs.keys, strict)

/**
 * Anchor matching uses `(h_a + w_a)` normalisation — see the template-creation
 * path. `useAvg` and `medianHeight` are ignored for anchors (kept in the
 * signature for compatibility).
 */
internal fun matchAnchor(
    templateAnchorVecs: Map<String, Map<String, List<Double>>>,
    anchorWordsInTarget: List<AnchorWord>,
    wordBoxTuples: List<WordBox>,
    medianHeight: Double,
    pageWidth: Double,
    pageHeight: Double,
    nCandidates: Int = DEFAULT_N_CANDIDATES,
    heightScale: Double = 1.0,
    @Suppress("UNUSED_PARAMETER") useAvg: Boolean = false,
    templateAnchorScales: Map<String, Map<String, List<Double>>>? = null,
): Pair<Map<String, List<Pair<Int, Double>>>, Int> {
    if (templateAnchorVecs.isEmpty() || anchorWordsInTarget.isEmpty()) {
        return Pair(emptyMap(), 0)
    }

    val nWords = wordBoxTuples.size
    val fieldWordDists = mutableMapOf<String, MutableMap<Int, MutableList<Double>>>()
    var matchedCount = 0

    for (anchor in anchorWordsInTarget) {
        val matchedKey = fuzzyMatchAnchor(anchor.text, templateAnchorVecs) ?: continue
        matchedCount++
        val fieldVecs = templateAnchorVecs[matchedKey] ?: continue
        val scaleVecs = templateAnchorScales?.get(matchedKey)
        for ((fieldLabel, templateVec) in fieldVecs) {
            var tv = templateVec.toDoubleArray()
            // Calibrated path: rescale dim0/dim1 of the stored vector and use
            // anchor weights; else legacy raw path with POLAR weights.
            val factors = scaleVecs?.get(fieldLabel)
            val weights: DoubleArray
            if (factors != null && factors.size >= 2) {
                tv = scaleAnchorDims01(tv, factors)
                weights = ANCHOR_DIMENSION_WEIGHTS
            } else {
                weights = POLAR_DIMENSION_WEIGHTS
            }
            val wordDists = fieldWordDists.getOrPut(fieldLabel) { mutableMapOf() }
            // Do NOT exclude the anchor's own index: a field's value can
            // legitimately *be* the anchor word (e.g. UPS template where
            // Carrier value = "UPS" = anchor).
            for (wi in 0 until nWords) {
                var vec = computeRelationVectorPolarAnchorHW(
                    anchor.bbox, wordBoxTuples[wi].bbox,
                    pageWidth, pageHeight, heightScale,
                )
                if (factors != null && factors.size >= 2) {
                    vec = scaleAnchorDims01(vec, factors)
                }
                val d = weightedL2(tv, vec, weights)
                wordDists.getOrPut(wi) { mutableListOf() }.add(d)
            }
        }
    }

    val results = mutableMapOf<String, List<Pair<Int, Double>>>()
    for ((field, wordDists) in fieldWordDists) {
        val averaged = wordDists.map { (wi, ds) -> Pair(wi, ds.sum() / ds.size) }
            .sortedBy { it.second }
        results[field] = averaged.take(nCandidates)
    }
    return Pair(results, matchedCount)
}

// --- Match Barcode ---

private fun baseType(typeId: String): String {
    var base = typeId
    if ("-" in base) {
        val idx = base.lastIndexOf('-')
        val suffix = base.substring(idx + 1)
        if (suffix.isNotEmpty() && suffix.all { it.isDigit() }) base = base.substring(0, idx)
    }
    return base.replace("_", "")
}

private fun suffixIndex(tid: String): Int {
    val dash = tid.lastIndexOf('-')
    if (dash < 0) return -1
    return tid.substring(dash + 1).toIntOrNull() ?: -1
}

internal data class DetectedBarcodeForMatching(
    val typeId: String,
    val bbox: DoubleArray,
    val data: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetectedBarcodeForMatching) return false
        return typeId == other.typeId && bbox.contentEquals(other.bbox) && data == other.data
    }
    override fun hashCode(): Int {
        var h = typeId.hashCode()
        h = 31 * h + bbox.contentHashCode()
        h = 31 * h + data.hashCode()
        return h
    }
}

/**
 * Aspect-gate tolerance (relative) for barcode vertical reconstruction. When a
 * live detection's `h/w` is within this fraction of the template's stored
 * aspect, the box is trusted as-is (Tier 1); otherwise the vertical centre is
 * unreliable (HRI-inflated / thin-line) and is relocated from anchors (Tier 2).
 */
internal const val BARCODE_ASPECT_GATE_TOL = 0.10

// 2-D symbologies encode data in a square/rectangular matrix with NO
// human-readable text line below them, so the detector returns a tight,
// reliable box — there is no HRI inflation to correct. The vertical-centre
// reconstruction (aspect gate + height rebuild + anchor relocation) exists
// purely for 1-D linear barcodes whose detected box swallows the HRI row; run
// on a 2-D code it only corrupts a good box (a QR drifting ~300px off its true
// centre). Keep the predicted box untouched for these. Compared against
// `baseType` output, which has already stripped `_` and any `-N` suffix.
private val BARCODE_2D_FORMATS = setOf("QRCODE", "PDF417", "DATAMATRIX", "AZTEC")

/**
 * Choose the barcode box to feed the width-normalised relation vector, fixing
 * the unreliable vertical centre when the detected aspect disagrees with the
 * template. Returns the (possibly reconstructed) box plus a tier tag for debug.
 *
 *  - Tier 1: aspect matches (or no stored aspect) → detected box unchanged.
 *  - Tier 2: aspect mismatch + usable anchors → centre relocated to the mean
 *    height-weighted anchor offset; width kept from detection, height = a·w.
 *  - Tier 3: aspect mismatch but no usable anchor/offset → detected box (safe).
 *
 * Width/x are always detection-derived (reliable); only the vertical changes.
 */
private fun reconstructBarcodeBox(
    bc: DetectedBarcodeForMatching,
    templateTid: String,
    barcodeAspect: Map<String, Double>?,
): Pair<DoubleArray, String> {
    // 2-D codes have reliable detected boxes — never reconstruct (keep detected).
    if (baseType(bc.typeId) in BARCODE_2D_FORMATS) return bc.bbox to "tier1_2d"
    val a = barcodeAspect?.get(templateTid) ?: return bc.bbox to "tier1_no_aspect"
    if (a <= 0.0) return bc.bbox to "tier1_no_aspect"
    val x1 = bc.bbox[0]; val y1 = bc.bbox[1]; val x2 = bc.bbox[2]; val y2 = bc.bbox[3]
    val w = (x2 - x1).let { if (it > 0.0) it else 1.0 }
    // Height is ALWAYS rebuilt to a·w — the matcher normalizes by barcode
    // height, and Android's detected height is unreliable (HRI). The tier only
    // decides the vertical CENTRE; width/x stay detection-derived.
    val halfH = a * w / 2.0
    val detCy = (y1 + y2) / 2.0
    val detAspect = (y2 - y1) / w
    // Rebuild the HEIGHT to the stable a·w and KEEP the detected vertical
    // CENTRE. The matcher normalizes by barcode height, and the detected height
    // is HRI-inflated/unreliable, so we always pin height to a·w. But the centre
    // stays detection-derived: HRI inflation can extend the box up OR down (not
    // necessarily the bottom), so no single edge is trustworthy, and the
    // detected centre is the least-biased estimate — its error is bounded by
    // ~half the inflation (tens of px).
    //
    // The previous anchor-offset relocation (Tier 2) is removed. With a single
    // distant anchor its multiplier ran 8–16 anchor-heights, so a 1px creation↔
    // prediction discrepancy in the anchor's height became 8–16px of barcode-
    // centre error — verified to drop a QR's centre 319px below its reliable
    // detected centre, and to overshoot the CODE128 by ~110px. The cure was far
    // worse than the ~26px detection drift it was meant to fix. Width/x stay
    // detection-derived.
    val tier = if (abs(detAspect - a) / a <= BARCODE_ASPECT_GATE_TOL) "tier1" else "tier2_height"
    return doubleArrayOf(x1, detCy - halfH, x2, detCy + halfH) to tier
}

internal fun matchBarcode(
    templateBcVecs: Map<String, Map<String, List<Double>>>,
    detectedBarcodes: List<DetectedBarcodeForMatching>,
    wordBoxTuples: List<WordBox>,
    medianHeight: Double,
    pageWidth: Double,
    pageHeight: Double,
    nCandidates: Int = DEFAULT_N_CANDIDATES,
    heightScale: Double = 1.0,
    useAvg: Boolean = false,
    scaleFactors: Map<String, Map<String, AxisScale>>? = null,
    barcodeAspect: Map<String, Double>? = null,
    anchorWordsInTarget: List<AnchorWord> = emptyList(),
    useHeightNorm: Boolean = false,
    useCenter: Boolean = false,
    tierDebugOut: MutableMap<String, Any?>? = null,
): Pair<Map<String, List<Pair<Int, Double>>>, Int> {
    if (templateBcVecs.isEmpty() || detectedBarcodes.isEmpty()) {
        return Pair(emptyMap(), 0)
    }

    // Group template typeIds by base type, sorted by numeric suffix so the
    // pairing order matches the template-build order (top-to-bottom).
    val templateTypeIdsByBase = mutableMapOf<String, MutableList<String>>()
    for (tid in templateBcVecs.keys) {
        val bt = baseType(tid)
        templateTypeIdsByBase.getOrPut(bt) { mutableListOf() }.add(tid)
    }
    for (bt in templateTypeIdsByBase.keys) {
        templateTypeIdsByBase[bt]?.sortBy { suffixIndex(it) }
    }

    // Group detected barcodes by base type, ordered top-to-bottom — BUT when
    // two barcodes overlap vertically (same row, side by side) break the tie by
    // x-centre (left-to-right). A y-only sort flips same-row barcodes under
    // sub-pixel y jitter / gross-rotation rounding, which (since pairing is by
    // sort index) swaps which barcode maps to `{TYPE}-0` vs `-1` and feeds every
    // barcode→field vector the WRONG barcode. Must match the creation-side order
    // (assign_barcode_type_ids / barcode_spatial_sort in api/barcode.py).
    val detectedByBase = mutableMapOf<String, MutableList<DetectedBarcodeForMatching>>()
    for (bc in detectedBarcodes) {
        val bt = baseType(bc.typeId)
        detectedByBase.getOrPut(bt) { mutableListOf() }.add(bc)
    }
    val barcodeSpatialComparator = Comparator<DetectedBarcodeForMatching> { a, b ->
        val overlap = minOf(a.bbox[3], b.bbox[3]) - maxOf(a.bbox[1], b.bbox[1])
        if (overlap > 0.0) {  // same row → tie-break by x-centre
            val acx = a.bbox[0] + a.bbox[2]; val bcx = b.bbox[0] + b.bbox[2]
            if (acx != bcx) return@Comparator acx.compareTo(bcx)
        }
        (a.bbox[1] + a.bbox[3]).compareTo(b.bbox[1] + b.bbox[3])
    }
    for (bt in detectedByBase.keys) {
        detectedByBase[bt]?.sortWith(barcodeSpatialComparator)
    }

    val nWords = wordBoxTuples.size
    val fieldWordDists = mutableMapOf<String, MutableMap<Int, MutableList<Double>>>()
    var matchedCount = 0

    for ((bt, templateTypeIds) in templateTypeIdsByBase) {
        val detectedList = detectedByBase[bt] ?: continue
        // Strict pairing: skip the whole base-type group when counts disagree,
        // otherwise mis-paired barcodes pollute the candidates with noise.
        if (templateTypeIds.size != detectedList.size) continue
        for (i in templateTypeIds.indices) {
            val templateTid = templateTypeIds[i]
            val bc = detectedList[i]
            val fieldVecs = templateBcVecs[templateTid] ?: continue
            matchedCount++

            // Fix the unreliable vertical centre once per detected barcode (the
            // tier decision doesn't depend on the field/word). Width & x stay
            // detection-derived; only the vertical may change. Legacy templates
            // (no stored aspect) keep the detected box → behaviour unchanged.
            val (effectiveBox, tier) = reconstructBarcodeBox(
                bc, templateTid, barcodeAspect,
            )
            if (tierDebugOut != null) {
                tierDebugOut[templateTid] = mapOf(
                    "tier" to tier,
                    "detected_bbox" to bc.bbox.toList(),
                    "effective_bbox" to effectiveBox.toList(),
                    "stored_aspect" to (barcodeAspect?.get(templateTid)),
                    "detected_aspect" to ((bc.bbox[3] - bc.bbox[1]) /
                        (bc.bbox[2] - bc.bbox[0]).let { if (it > 0.0) it else 1.0 }),
                )
            }

            val typeScales = scaleFactors?.get(templateTid)
            for ((fieldLabel, templateVec) in fieldVecs) {
                val tv = templateVec.toDoubleArray()
                val fieldScales = typeScales?.get(fieldLabel)
                val wordDists = fieldWordDists.getOrPut(fieldLabel) { mutableMapOf() }
                for (wi in 0 until nWords) {
                    var vec = if (useAvg) {
                        // Normalizer chosen off the template's barcode_norm flag.
                        // HEIGHT (current): effectiveBox carries the reconstructed
                        // a·w height (stable), so we never normalize by the flaky
                        // detected height. WIDTH (legacy templates): original
                        // width normalization. The y-centre reconstruction
                        // (effectiveBox) applies to both.
                        if (useHeightNorm) {
                            computeRelationVectorBarcodeHeight(
                                effectiveBox, wordBoxTuples[wi].bbox,
                                pageWidth, pageHeight, heightScale,
                                useCenterOrigin = useCenter,
                            )
                        } else {
                            computeRelationVectorBarcodeWidth(
                                effectiveBox, wordBoxTuples[wi].bbox,
                                pageWidth, pageHeight, heightScale,
                            )
                        }
                    } else {
                        computeRelationVector(
                            effectiveBox, wordBoxTuples[wi].bbox,
                            medianHeight, pageWidth, pageHeight, heightScale,
                        )
                    }
                    if (fieldScales != null) vec = applyAxisScalingToLive(vec, fieldScales)
                    val d = weightedL2(tv, vec, BARCODE_DIMENSION_WEIGHTS)
                    wordDists.getOrPut(wi) { mutableListOf() }.add(d)
                }
            }
        }
    }

    val results = mutableMapOf<String, List<Pair<Int, Double>>>()
    for ((field, wordDists) in fieldWordDists) {
        val averaged = wordDists.map { (wi, ds) -> Pair(wi, ds.sum() / ds.size) }
            .sortedBy { it.second }
        results[field] = averaged.take(nCandidates)
    }
    return Pair(results, matchedCount)
}

// --- Match KV ---

internal fun matchKv(
    templateKvVecs: Map<String, Map<String, List<Double>>>,
    wordBoxTuples: List<WordBox>,
    medianHeight: Double,
    pageWidth: Double,
    pageHeight: Double,
    nCandidates: Int = DEFAULT_N_CANDIDATES,
    heightScale: Double = 1.0,
    primaryKeyMap: Map<String, String>? = null,
    primaryOnly: Boolean = false,
    wordBoxTuplesUnfiltered: List<WordBox>? = null,
    useAvg: Boolean = false,
    scaleFactors: Map<String, Map<String, AxisScale>>? = null,
    keyPositionsMhNorm: Map<String, Pair<Double, Double>>? = null,
    keyAliases: Map<String, List<String>>? = null,
    kvKeyTexts: Map<String, String>? = null,
    kvKeyPositions: Map<String, List<Double>>? = null,
    timingsOut: MutableMap<String, Double>? = null,
): Pair<Map<String, List<Pair<Int, Double>>>, Int> {
    if (templateKvVecs.isEmpty()) return Pair(emptyMap(), 0)

    val nWords = wordBoxTuples.size
    val results = mutableMapOf<String, List<Pair<Int, Double>>>()
    val keySearchList = wordBoxTuplesUnfiltered ?: wordBoxTuples

    // TEMP(kv-profiling): split setup / resolve / exclusion / relvec+L2 cost.
    var setupNs = 0L
    var resolveNs = 0L
    var exclusionNs = 0L
    var relvecNs = 0L
    var passCount = 0
    var findAllKeyWordsCalls = 0

    // Cache the chosen key bbox per text. When the template carries
    // `_preview_kv_keys`-derived [keyPositionsMhNorm], multi-match
    // disambiguation picks the OCR occurrence whose (left, y_mid) in scan-mh
    // units is closest to the template-recorded key position. Falls back to
    // first-match for legacy templates or single-occurrence keys.
    val keyBboxCache = HashMap<String, DoubleArray?>()
    // Exclusion set covers EVERY occurrence of every key text — even unpicked
    // duplicates must be ineligible as value candidates.
    val keyExcludedAll = HashSet<List<Double>>()
    val tSetup = System.nanoTime()
    // Run findAllKeyWords ONCE per DISTINCT key text (not once per field ×
    // key). With cross-field KV keys every field references every key, so the
    // naive loop was F× redundant — the dominant matchKv cost. The cached
    // matches feed both the value-exclusion set and per-field key resolution.
    // Inner keys are printed key TEXTS, except multi-instance keys (same text
    // annotated on several fields) which are keyed by the owning field LABEL —
    // kvKeyTexts resolves those back to the printed text. Labels must never be
    // searched as key text.
    val kvTexts = kvKeyTexts ?: emptyMap()
    val allKeyMatches = HashMap<String, List<WordBox>>()
    for (keyVecs in templateKvVecs.values) {
        for (innerKey in keyVecs.keys) {
            val text = kvTexts[innerKey] ?: innerKey
            if (text in allKeyMatches) continue
            findAllKeyWordsCalls++
            allKeyMatches[text] = findAllKeyWords(text, keySearchList)
        }
    }
    // Occurrences of a duplicated text whose instance failed to bake at
    // creation are still printed keys — exclude them too.
    for (text in kvTexts.values.toSet()) {
        if (text !in allKeyMatches) {
            findAllKeyWordsCalls++
            allKeyMatches[text] = findAllKeyWords(text, keySearchList)
        }
    }
    for (matches in allKeyMatches.values) {
        for (match in matches) keyExcludedAll.add(match.bbox.toList())
    }

    // Multi-instance binding (mirrors Python _resolve_key_instances): per
    // duplicated text, greedy-assign template instances to DISTINCT scan
    // occurrences by (expected position ↔ occurrence position) distance in
    // median-height units; instances without a stored position fall back to
    // reading-order alignment over the leftover occurrences. Unassigned
    // instance → null (doesn't vote).
    val instanceBbox = HashMap<String, DoubleArray?>()
    if (kvTexts.isNotEmpty()) {
        val innerKeys = HashSet<String>()
        for (keyVecs in templateKvVecs.values) innerKeys.addAll(keyVecs.keys)
        val textInstances = HashMap<String, MutableList<String>>()
        for ((label, text) in kvTexts) {
            if (label in innerKeys) textInstances.getOrPut(text) { mutableListOf() }.add(label)
        }
        for ((text, instances) in textInstances) {
            val occurrences = allKeyMatches[text] ?: emptyList()
            if (occurrences.isEmpty()) {
                for (k in instances) instanceBbox[k] = null
                continue
            }
            val occPoints = if (medianHeight > 0) occurrences.map {
                val p = getLeftMidpoint(it.bbox)
                Pair(p.first / medianHeight, p.second / medianHeight)
            } else emptyList()
            var instancePoints: Map<String, Pair<Double, Double>> = buildMap {
                for (k in instances) {
                    val pos = kvKeyPositions?.get(k)
                    if (occPoints.isNotEmpty() && pos != null && pos.size >= 2) {
                        put(k, Pair(pos[0], pos[1]))
                    }
                }
            }
            // Relative-geometry binding: center each side on its own
            // component-wise median so assignment depends only on the keys'
            // layout among themselves. Absolute positions share a frame only
            // through the text-area origin, which drifts with extra/missing
            // OCR words — a constant offset that misbinds greedy-nearest
            // (instance 1 ↔ occurrence 5). Below 2 points a relative frame
            // is undefined — keep absolute positions.
            var occPointsForAssign = occPoints
            if (instancePoints.size >= 2 && occPoints.size >= 2) {
                val instLabels = instancePoints.keys.toList()
                val centeredInst = medianCenter(instLabels.map { instancePoints[it]!! })
                instancePoints = instLabels.zip(centeredInst).toMap()
                occPointsForAssign = medianCenter(occPoints)
            }
            val assigned = HashMap(assignOccurrencesGreedy(instancePoints, occPointsForAssign))
            val used = assigned.values.toMutableSet()
            val leftover = occurrences.indices.filter { it !in used }
                .sortedWith(compareBy(
                    { (occurrences[it].bbox[1] + occurrences[it].bbox[3]) / 2.0 },
                    { occurrences[it].bbox[0] },
                ))
                .toMutableList()
            for (k in instances.filter { it !in assigned }.sorted()) {
                if (leftover.isEmpty()) break
                assigned[k] = leftover.removeAt(0)
            }
            for (k in instances) instanceBbox[k] = assigned[k]?.let { occurrences[it].bbox }
        }
    }

    // Eagerly resolve single-instance keys too (value-identical to the lazy
    // per-field fill — same pickKeyWordByPosition over the same cached
    // matches) so the multi-instance alias fallback below sees the complete
    // "taken" set, as the Python reference does.
    if (kvTexts.isNotEmpty()) {
        for (keyVecs in templateKvVecs.values) {
            for (innerKey in keyVecs.keys) {
                if (innerKey in kvTexts || keyBboxCache.containsKey(innerKey)) continue
                val match = pickKeyWordByPosition(
                    allKeyMatches[innerKey] ?: emptyList(),
                    expectedScaledPos = keyPositionsMhNorm?.get(innerKey),
                    scanMedianHeight = medianHeight,
                )
                keyBboxCache[innerKey] = match?.bbox
            }
        }
    }
    setupNs += System.nanoTime() - tSetup
    val matchedKeys = mutableSetOf<String>()

    for ((fieldLabel, keyVecs) in templateKvVecs) {
        val wordDists = HashMap<Int, MutableList<Pair<Double, Double>>>()
        val ownKeyText = primaryKeyMap?.get(fieldLabel)
        // Inner-key form of the field's own key: its own LABEL when the key is
        // a multi-instance one, else the key text itself.
        val ownKeyId = if (fieldLabel in kvTexts) fieldLabel else ownKeyText

        if (primaryOnly && ownKeyText == null) continue

        // Resolve ONE bbox per inner key FOR THIS FIELD. Multi-instance keys
        // come from the shared instance binding above; exact-text lookups use
        // the shared cache. When the field's OWN key isn't printed, fall back
        // to the first alias that IS — that bbox then anchors the stored
        // primary-key vector (and is added to the value-exclusion set). Cross-
        // field keys never use aliases (each field resolves its own). An alias
        // bbox may not collide with an occurrence bound to a sibling instance.
        val fieldKeyBbox = HashMap<String, DoubleArray?>()
        val tResolve = System.nanoTime()
        for (innerKey in keyVecs.keys) {
            val isMultiInstance = innerKey in kvTexts
            if (!isMultiInstance && !keyBboxCache.containsKey(innerKey)) {
                // Reuse the shared findAllKeyWords result — no second OCR scan.
                val match = pickKeyWordByPosition(
                    allKeyMatches[innerKey] ?: emptyList(),
                    expectedScaledPos = keyPositionsMhNorm?.get(innerKey),
                    scanMedianHeight = medianHeight,
                )
                keyBboxCache[innerKey] = match?.bbox
            }
            var bbox = if (isMultiInstance) instanceBbox[innerKey] else keyBboxCache[innerKey]
            if (bbox == null && innerKey == ownKeyId && keyAliases != null) {
                if (isMultiInstance) {
                    val taken = HashSet<List<Double>>()
                    for (b in instanceBbox.values) if (b != null) taken.add(b.toList())
                    for (b in keyBboxCache.values) if (b != null) taken.add(b.toList())
                    for (alias in keyAliases[fieldLabel] ?: emptyList()) {
                        val cands = findAllKeyWords(alias, keySearchList)
                            .filter { it.bbox.toList() !in taken }
                        val pos = kvKeyPositions?.get(innerKey)
                        val expected = if (pos != null && pos.size >= 2) Pair(pos[0], pos[1]) else null
                        val am = pickKeyWordByPosition(cands, expected, medianHeight)
                        if (am != null) {
                            bbox = am.bbox
                            keyExcludedAll.add(am.bbox.toList())
                            break
                        }
                    }
                } else {
                    for (alias in keyAliases[fieldLabel] ?: emptyList()) {
                        val am = findKeyWord(alias, keySearchList)
                        if (am != null) {
                            bbox = am.bbox
                            keyExcludedAll.add(am.bbox.toList())
                            break
                        }
                    }
                }
            }
            fieldKeyBbox[innerKey] = bbox
        }
        resolveNs += System.nanoTime() - tResolve

        var nCrossKeys = 0
        for (kt in keyVecs.keys) {
            if (kt != ownKeyId && fieldKeyBbox[kt] != null) nCrossKeys++
        }
        val primaryWeight = kotlin.math.max(PRIMARY_KEY_WEIGHT, nCrossKeys.toDouble())

        for ((innerKey, templateVec) in keyVecs) {
            if (primaryOnly && innerKey != ownKeyId) continue
            val keyBbox = fieldKeyBbox[innerKey] ?: continue
            matchedKeys.add(innerKey)
            passCount++

            val isPrimary = innerKey == ownKeyId
            val weight = if (isPrimary) primaryWeight else 1.0
            val dimWeights = when {
                primaryOnly && isPrimary -> PRIMARY_ONLY_DIMENSION_WEIGHTS
                isPrimary -> PRIMARY_KEY_DIMENSION_WEIGHTS
                else -> DIMENSION_WEIGHTS
            }

            val tv = templateVec.toDoubleArray()
            val keyScales = scaleFactors?.get(fieldLabel)?.get(innerKey)

            for (wi in 0 until nWords) {
                val wordBbox = wordBoxTuples[wi].bbox
                // Skip key words: exact-bbox match OR contained-by-some-key.
                val tEx = System.nanoTime()
                var skip = wordBbox.toList() in keyExcludedAll
                if (!skip) {
                    for (kb in keyExcludedAll) {
                        if (wordBbox[0] >= kb[0] && wordBbox[1] >= kb[1]
                            && wordBbox[2] <= kb[2] && wordBbox[3] <= kb[3]
                        ) { skip = true; break }
                    }
                }
                exclusionNs += System.nanoTime() - tEx
                if (skip) continue

                val tRv = System.nanoTime()
                var vec = if (useAvg) {
                    // KV uses h_a normalisation (key bbox height) — mirrors
                    // template-time `compute_relation_vector_key_h`.
                    computeRelationVectorKeyH(
                        keyBbox, wordBbox,
                        pageWidth, pageHeight, heightScale,
                    )
                } else {
                    computeRelationVector(
                        keyBbox, wordBbox,
                        medianHeight, pageWidth, pageHeight, heightScale,
                    )
                }
                if (keyScales != null) vec = applyAxisScalingToLive(vec, keyScales)
                val d = weightedL2(tv, vec, dimWeights)
                wordDists.getOrPut(wi) { mutableListOf() }.add(Pair(d, weight))
                relvecNs += System.nanoTime() - tRv
            }
        }

        if (wordDists.isEmpty()) continue
        val averaged = wordDists.map { (wi, dw) ->
            val weightedSum = dw.sumOf { (d, w) -> d * w }
            val totalWeight = dw.sumOf { it.second }
            Pair(wi, weightedSum / totalWeight)
        }.sortedBy { it.second }
        results[fieldLabel] = averaged.take(nCandidates)
    }

    timingsOut?.put("kvSetupMs", setupNs / 1_000_000.0)
    timingsOut?.put("kvResolveMs", resolveNs / 1_000_000.0)
    timingsOut?.put("kvExclusionMs", exclusionNs / 1_000_000.0)
    timingsOut?.put("kvRelVecMs", relvecNs / 1_000_000.0)
    timingsOut?.put("kvPassCount", passCount.toDouble())
    timingsOut?.put("kvExcludedBoxes", keyExcludedAll.size.toDouble())
    timingsOut?.put("kvWords", nWords.toDouble())
    timingsOut?.put("kvFindAllCalls", findAllKeyWordsCalls.toDouble())

    return Pair(results, matchedKeys.size)
}

// --- Fused Prediction ---

internal data class FusedResult(
    val predictions: Map<String, MutableMap<String, Any?>>,
    val suggestions: Map<String, List<MutableMap<String, Any?>>>,
    val typeResults: Map<String, Map<String, Map<String, Any?>>>,
)

internal fun fusedPrediction(
    templateVectors: Map<String, PairVector>,
    templateAnchorVecs: Map<String, Map<String, List<Double>>>,
    templateAnchorScales: Map<String, Map<String, List<Double>>>? = null,
    templateBcVecs: Map<String, Map<String, List<Double>>>,
    templateKvVecs: Map<String, Map<String, List<Double>>>,
    wordVectors: Array<Array<DoubleArray>>?,
    wordBoxTuples: List<WordBox>,
    detectedBarcodes: List<DetectedBarcodeForMatching>,
    medianHeight: Double,
    pageWidth: Double,
    pageHeight: Double,
    anchorWordsInTarget: List<AnchorWord>,
    heightScale: Double = 1.0,
    templatePrimaryKeyMap: Map<String, String>? = null,
    flexible: Boolean = false,
    wordBoxTuplesUnfiltered: List<WordBox>? = null,
    useAvg: Boolean = false,
    kvScaleFactors: Map<String, Map<String, AxisScale>>? = null,
    barcodeScaleFactors: Map<String, Map<String, AxisScale>>? = null,
    barcodeAspect: Map<String, Double>? = null,
    barcodeUseHeightNorm: Boolean = false,
    barcodeUseCenter: Boolean = false,
    keyPositionsMhNorm: Map<String, Pair<Double, Double>>? = null,
    keyAliases: Map<String, List<String>>? = null,
    kvKeyTexts: Map<String, String>? = null,
    kvKeyPositions: Map<String, List<Double>>? = null,
    fusionWeights: Map<String, Double>? = null,
    debugCollector: MutableMap<String, Any?>? = null,
    timingsOut: MutableMap<String, Double>? = null,
): FusedResult {
    fun ms(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000.0
    val keySearchList = wordBoxTuplesUnfiltered ?: wordBoxTuples

    // Find indices of all key words to exclude from matchers.
    val tKeyExcl = System.nanoTime()
    val keyExcludedIndices = mutableSetOf<Int>()
    val keyBboxes = mutableListOf<DoubleArray>()
    val kvTexts = kvKeyTexts ?: emptyMap()
    // Multi-instance key texts: EVERY printed occurrence is a key (one per
    // sibling field), so all of them are excluded wholesale — including
    // occurrences whose instance failed to bake at creation.
    val dupOccCounts = HashMap<String, Int>()
    for (dupText in kvTexts.values.toSet()) {
        val occs = findAllKeyWords(dupText, keySearchList)
        dupOccCounts[dupText] = occs.size
        for (o in occs) keyBboxes.add(o.bbox)
    }
    // When the scan shows fewer occurrences of a duplicated text than the
    // template has instances, matchKv may stand an alias in for the unbound
    // instance(s) — exclude every occurrence of those aliases too (alias
    // texts are key-shaped look-alikes, never legitimate values).
    val dupInstanceCounts = HashMap<String, Int>()
    for (text in kvTexts.values) dupInstanceCounts[text] = (dupInstanceCounts[text] ?: 0) + 1
    for ((fieldLabel, keyVecs) in templateKvVecs) {
        val ownKeyText = templatePrimaryKeyMap?.get(fieldLabel)
        val ownKeyId = if (fieldLabel in kvTexts) fieldLabel else ownKeyText
        for (innerKey in keyVecs.keys) {
            if (innerKey in kvTexts) {
                val text = kvTexts[innerKey] ?: continue
                if (innerKey == ownKeyId && keyAliases != null
                    && (dupOccCounts[text] ?: 0) < (dupInstanceCounts[text] ?: 0)
                ) {
                    for (alias in keyAliases[fieldLabel] ?: emptyList()) {
                        for (am in findAllKeyWords(alias, keySearchList)) keyBboxes.add(am.bbox)
                    }
                }
                continue
            }
            val match = findKeyWord(innerKey, keySearchList)
            if (match != null) {
                keyBboxes.add(match.bbox)
            } else if (innerKey == ownKeyId && keyAliases != null) {
                // Own key absent — a look-alike alias stands in for it, so the
                // alias word is acting as the key and must be excluded as a value.
                for (alias in keyAliases[fieldLabel] ?: emptyList()) {
                    val am = findKeyWord(alias, keySearchList)
                    if (am != null) { keyBboxes.add(am.bbox); break }
                }
            }
        }
    }
    for ((wi, wb) in wordBoxTuples.withIndex()) {
        val bbox = wb.bbox
        var excluded = false
        for (kb in keyBboxes) {
            if (bbox.contentEquals(kb)) { excluded = true; break }
            if (bbox[0] >= kb[0] && bbox[1] >= kb[1]
                && bbox[2] <= kb[2] && bbox[3] <= kb[3]
            ) { excluded = true; break }
        }
        if (excluded) keyExcludedIndices.add(wi)
    }
    timingsOut?.put("keyExclusionMs", ms(tKeyExcl))

    if (debugCollector != null) {
        debugCollector["key_excluded_indices"] = keyExcludedIndices.sorted()
    }

    // Run matchers
    val pairwiseCandidates: MutableMap<String, List<Pair<Int, Double>>>
    val anchorCandidates: MutableMap<String, List<Pair<Int, Double>>>
    val barcodeCandidates: MutableMap<String, List<Pair<Int, Double>>>

    if (flexible) {
        pairwiseCandidates = mutableMapOf()
        anchorCandidates = mutableMapOf()
        barcodeCandidates = mutableMapOf()
    } else {
        val tPw = System.nanoTime()
        val (pw, _) = if (wordVectors != null) {
            matchPairwise(templateVectors, wordVectors, wordBoxTuples,
                excludedIndices = keyExcludedIndices, timingsOut = timingsOut)
        } else Pair(emptyMap(), 0)
        timingsOut?.put("matchPairwiseMs", ms(tPw))
        pairwiseCandidates = pw.toMutableMap()

        // Anchor uses (h_a + w_a) normalisation regardless of useAvg.
        val tAn = System.nanoTime()
        val (an, _) = matchAnchor(
            templateAnchorVecs, anchorWordsInTarget,
            wordBoxTuples, medianHeight, pageWidth, pageHeight,
            heightScale = heightScale, useAvg = false,
            templateAnchorScales = templateAnchorScales,
        )
        timingsOut?.put("matchAnchorMs", ms(tAn))
        anchorCandidates = an.toMutableMap()

        val tBc = System.nanoTime()
        val barcodeTierDebug = if (debugCollector != null) mutableMapOf<String, Any?>() else null
        val (bc, _) = matchBarcode(
            templateBcVecs, detectedBarcodes,
            wordBoxTuples, medianHeight, pageWidth, pageHeight,
            heightScale = heightScale, useAvg = useAvg,
            scaleFactors = barcodeScaleFactors,
            barcodeAspect = barcodeAspect,
            anchorWordsInTarget = anchorWordsInTarget,
            useHeightNorm = barcodeUseHeightNorm,
            useCenter = barcodeUseCenter,
            tierDebugOut = barcodeTierDebug,
        )
        if (barcodeTierDebug != null) debugCollector?.put("barcodeReconstruction", barcodeTierDebug)
        timingsOut?.put("matchBarcodeMs", ms(tBc))
        barcodeCandidates = bc.toMutableMap()
    }

    val tKv = System.nanoTime()
    val (kv, _) = matchKv(
        templateKvVecs, wordBoxTuples,
        medianHeight, pageWidth, pageHeight,
        heightScale = heightScale,
        primaryKeyMap = templatePrimaryKeyMap,
        primaryOnly = flexible,
        wordBoxTuplesUnfiltered = keySearchList,
        useAvg = useAvg,
        scaleFactors = kvScaleFactors,
        keyPositionsMhNorm = keyPositionsMhNorm,
        keyAliases = keyAliases,
        kvKeyTexts = kvKeyTexts,
        kvKeyPositions = kvKeyPositions,
        timingsOut = timingsOut,
    )
    timingsOut?.put("matchKvMs", ms(tKv))
    val kvCandidates = kv.toMutableMap()

    // Key words are identified keys and must never be selected as values.
    // matchKv (excluded bboxes) and matchPairwise (excludedIndices) already
    // exclude them, but matchAnchor and matchBarcode do not, and the RRF pool
    // never filtered them — so a key word that a barcode/anchor matcher ranks
    // highly can win the text-shape rerank (the "Prod.#:" vs
    // "CS-CAM-RVPTZ-LBUN" failure). Drop key-excluded indices from those two
    // lists here, the single fusion chokepoint; filtering pairwise/kv too
    // would be a guaranteed no-op rebuilding lists per frame.
    if (keyExcludedIndices.isNotEmpty()) {
        for (cands in listOf(anchorCandidates, barcodeCandidates)) {
            for (field in cands.keys.toList()) {
                cands[field] = cands.getValue(field).filter { it.first !in keyExcludedIndices }
            }
        }
    }

    if (debugCollector != null) {
        debugCollector["matcher_candidates"] = mapOf(
            "pairwise" to serializeCandidates(pairwiseCandidates),
            "anchor" to serializeCandidates(anchorCandidates),
            "barcode" to serializeCandidates(barcodeCandidates),
            "kv" to serializeCandidates(kvCandidates),
        )
    }

    // Per-strategy geometric dedup intentionally removed. Dedup now runs at
    // the end of the pipeline (after text-shape rerank) using the
    // text-matching score so that fields with strong text-shape matches keep
    // their candidates over fields with only weak geometric claims. See
    // `dedupByTextScore` in TextPostProcessor.

    if (debugCollector != null) {
        debugCollector["matcher_candidates_after_dedup"] = mapOf(
            "pairwise" to serializeCandidates(pairwiseCandidates),
            "anchor" to serializeCandidates(anchorCandidates),
            "barcode" to serializeCandidates(barcodeCandidates),
            "kv" to serializeCandidates(kvCandidates),
        )
    }

    // Collect all fields
    val allFields = mutableSetOf<String>()
    listOf(pairwiseCandidates, anchorCandidates, barcodeCandidates, kvCandidates).forEach {
        allFields.addAll(it.keys)
    }

    // Phase 1: RRF scoring
    val tRrf = System.nanoTime()
    data class RankedEntry(val wordIndex: Int, val rrfScore: Double, val nVotes: Int)
    val fieldRanked = mutableMapOf<String, List<RankedEntry>>()

    for (field in allFields) {
        val typeResults = mutableMapOf<String, List<Pair<Int, Double>>>()
        for ((typeName, allCands) in listOf(
            "pairwise" to pairwiseCandidates,
            "anchor" to anchorCandidates,
            "barcode" to barcodeCandidates,
            "kv" to kvCandidates,
        )) {
            val cands = allCands[field]
            if (!cands.isNullOrEmpty()) typeResults[typeName] = cands
        }
        if (typeResults.isEmpty()) continue

        // Per-matcher fusion weights from the template when present, falling
        // back to the code default BASE_WEIGHTS for any missing matcher.
        val activeWeights = typeResults.keys.associateWith {
            fusionWeights?.get(it) ?: BASE_WEIGHTS[it]!!
        }
        val totalWeight = activeWeights.values.sum()

        val wordRrf = mutableMapOf<Int, Double>()
        val wordVoteCount = mutableMapOf<Int, Int>()

        for ((typeName, candidates) in typeResults) {
            val w = activeWeights[typeName]!! / totalWeight
            val topCandidates = candidates.take(RRF_TOP_N)
            if (topCandidates.isEmpty()) continue
            val dists = topCandidates.map { it.second }
            val minD = dists.first()
            val maxD = dists.last()
            val distRange = maxD - minD

            for ((wordIdx, dist) in topCandidates) {
                val normScore = if (distRange > 1e-9) 1.0 - (dist - minD) / distRange else 1.0
                val rrfContrib = w * normScore
                wordRrf[wordIdx] = (wordRrf[wordIdx] ?: 0.0) + rrfContrib
                wordVoteCount[wordIdx] = (wordVoteCount[wordIdx] ?: 0) + 1
            }
        }

        val ranked = wordRrf.entries
            .sortedByDescending { it.value }
            .map { RankedEntry(it.key, it.value, wordVoteCount[it.key]!!) }
        fieldRanked[field] = ranked
    }

    if (debugCollector != null) {
        debugCollector["rrf_scores"] = fieldRanked.mapValues { (_, entries) ->
            entries.map {
                mapOf(
                    "word_index" to it.wordIndex,
                    "rrf_score" to "%.6f".format(it.rrfScore).toDouble(),
                    "n_votes" to it.nVotes,
                )
            }
        }
    }

    // Phase 2: Greedy 1-to-1 assignment
    val assignedWords = mutableSetOf<Int>()
    val predictions = mutableMapOf<String, MutableMap<String, Any?>>()
    val suggestions = mutableMapOf<String, List<MutableMap<String, Any?>>>()

    // Per-signal rank lookup: position of `wordIdx` in `candidates`. Returns
    // -1 when the word is absent from this signal's candidate list, which
    // rankWeight() interprets as a zero contribution.
    fun rankIn(candidates: List<Pair<Int, Double>>?, wordIdx: Int): Int {
        if (candidates == null) return -1
        for ((i, pair) in candidates.withIndex()) {
            if (pair.first == wordIdx) return i
        }
        return -1
    }

    // geo_sum = Σ rank_weight(signal_table, rank_in_signal)
    // Stashed on each prediction dict for the post-rerank finalizer in
    // TextPostProcessor.finalizeConfidence to blend with the text component.
    fun geoSum(field: String, wordIdx: Int): Double {
        val pwRank = rankIn(pairwiseCandidates[field], wordIdx)
        val anRank = rankIn(anchorCandidates[field], wordIdx)
        val bcRank = rankIn(barcodeCandidates[field], wordIdx)
        val kvRank = rankIn(kvCandidates[field], wordIdx)
        return rankWeight(PAIRWISE_RANK_WEIGHTS, pwRank) +
                rankWeight(ANCHOR_RANK_WEIGHTS, anRank) +
                rankWeight(BARCODE_RANK_WEIGHTS, bcRank) +
                rankWeight(KV_RANK_WEIGHTS, kvRank)
    }

    fun makeResult(wordIdx: Int, geoSumVal: Double, geoRank: Int = 0, rrfScore: Double = 0.0, nVotes: Int = 0): MutableMap<String, Any?> {
        val wb = wordBoxTuples[wordIdx]
        return mutableMapOf(
            "text" to wb.text,
            "bbox" to wb.bbox.toList(),
            "word_index" to wordIdx,
            "geo_sum" to geoSumVal,
            "confidence" to GEO_BLEND_WEIGHT * geoSumVal,
            "geo_rank" to geoRank,
            // Normalized RRF score and matcher vote count — the text-shape
            // rerank's geo-dominance guard reads both to decide whether
            // geometry is decisive enough to keep.
            "rrf_score" to rrfScore,
            "n_votes" to nVotes,
        )
    }

    val fieldsByConfidence = fieldRanked.keys.sortedByDescending { field ->
        fieldRanked[field]?.firstOrNull()?.rrfScore ?: 0.0
    }

    for (field in fieldsByConfidence) {
        val ranked = fieldRanked[field] ?: continue

        var chosen: RankedEntry? = null
        var chosenGeoSum = 0.0
        val remaining = mutableListOf<Pair<RankedEntry, Double>>()

        for (entry in ranked) {
            if (entry.wordIndex in assignedWords) continue
            val gs = geoSum(field, entry.wordIndex)
            if (chosen == null) {
                chosen = entry
                chosenGeoSum = gs
                assignedWords.add(entry.wordIndex)
            } else {
                remaining.add(entry to gs)
            }
        }

        if (chosen != null) {
            predictions[field] = makeResult(chosen.wordIndex, chosenGeoSum, geoRank = 0, rrfScore = chosen.rrfScore, nVotes = chosen.nVotes)
            suggestions[field] = remaining.take(4).mapIndexed { rank, (entry, gs) ->
                makeResult(entry.wordIndex, gs, geoRank = rank + 1, rrfScore = entry.rrfScore, nVotes = entry.nVotes)
            }
        }
    }

    // Per-type top-1 for debugging
    val typeTop1 = mutableMapOf<String, Map<String, Map<String, Any?>>>()
    for ((typeName, candidatesDict) in listOf(
        "pairwise" to pairwiseCandidates,
        "anchor" to anchorCandidates,
        "barcode" to barcodeCandidates,
        "kv" to kvCandidates,
    )) {
        val fields = mutableMapOf<String, Map<String, Any?>>()
        for ((field, cands) in candidatesDict) {
            if (cands.isNotEmpty()) {
                val (wi, dist) = cands[0]
                val wb = wordBoxTuples[wi]
                fields[field] = mapOf(
                    "text" to wb.text,
                    "bbox" to wb.bbox.toList(),
                    "word_index" to wi,
                    "distance" to dist,
                )
            }
        }
        typeTop1[typeName] = fields
    }

    timingsOut?.put("rrfAndAssignmentMs", ms(tRrf))
    return FusedResult(predictions, suggestions, typeTop1)
}

private fun serializeCandidates(
    candidates: Map<String, List<Pair<Int, Double>>>,
): Map<String, List<List<Any>>> {
    return candidates.mapValues { (_, entries) ->
        entries.map { (wi, d) -> listOf(wi, "%.6f".format(d).toDouble()) }
    }
}

// --- Template auto-detection --------------------------------------------------
//
// Kotlin port of `detect_template` in api/matching.py — kept in strict parity.
// Ranks candidate templates by how much of each template's identity evidence
// (anchor words, KV keys, barcode types) is PRESENT in the captured OCR. It is
// a pure text/type-presence test — no geometric matchers, no RRF — so it is
// cheap enough to score every cached template once per capture. The heavy
// `predict()` then runs a single time on the chosen template.
//
// The `ocrWords` list MUST be the UNFILTERED word list (no per-template stop
// words) so detection is fair across all candidates. See the Python source for
// the coverage/weight/threshold rationale.

// Relevance = POOLED coverage of anchors + keys: (matched) / (total + k).
// Anchors and keys are pooled equally into one ratio (not a per-type weighted
// blend), so a matched anchor can never LOWER the score below a no-anchor
// competitor. BARCODES ARE NOT SCORED — easily missed at capture, so they only
// break ties between near-equal matches. Laplace smoothing k caps tiny
// templates (1/2 = 0.5) so a lone anchor/key can't score 1.0. Kept in lockstep
// with the Python/Swift ports; see api/matching.py.
internal const val DETECT_COVERAGE_SMOOTHING = 1.0

// Absent-anchor penalty: subtract from a template's score when it has a
// DISTINCTIVE anchor (len >= DETECT_DISTINCTIVE_ANCHOR_MIN_LEN) defined but the
// scan contains none of it — a brand anchor's absence is strong evidence
// against. GATED on frame word-count (only when >= MIN_WORDS), so a sparse
// weak capture's missing anchor (likely an OCR miss) isn't penalised — this
// protects weaker OCR. Tuned on eval; lockstep with Python/Swift.
internal const val DETECT_ABSENT_ANCHOR_PENALTY = 0.1
internal const val DETECT_ABSENT_ANCHOR_MIN_WORDS = 50
internal const val DETECT_DISTINCTIVE_ANCHOR_MIN_LEN = 3

// "Very ambiguous" gate: when the best score is below this, detectTemplate
// re-ranks with STRICT anchor matching (no fragment containment, e.g. "Jun" ⊆
// "Juniper") so a fragment can't win a content-less scan. Above the gate,
// matching stays lenient. Lockstep with Python/Swift.
internal const val DETECT_AMBIGUOUS_SCORE_GATE = 0.15

/** One template to rank, paired with the id/name that live on the DB entity
 *  (ProcessedTemplate itself carries neither). */
internal data class TemplateCandidate(
    val id: String,
    val name: String,
    val template: ProcessedTemplate,
)

internal data class TemplateRank(
    val id: String,
    val name: String,
    val score: Double,
    val evidence: Int,
    val matchedAnchors: Int,
    val matchedKeys: Int,
    val matchedBarcodes: Int,
)

internal data class TemplateDetectionResult(
    /** null when ambiguous. */
    val chosenId: String?,
    val ambiguous: Boolean,
    /** Best first. */
    val ranked: List<TemplateRank>,
    // TEMP(detect-profiling): where the scoring loop spends time.
    val detAnchorMs: Double = 0.0,
    val detKvMs: Double = 0.0,
    val detFindKeyCalls: Int = 0,
    val detTotalKvKeys: Int = 0,
    val detDistinctKvKeys: Int = 0,
)

/** TEMP(detect-profiling): accumulators for the detection scoring split. */
internal class DetectProfile {
    var anchorNs = 0L
    var kvNs = 0L
    var findKeyCalls = 0
}

private data class DetectScore(
    val score: Double,
    val evidence: Int,
    val matchedAnchors: Int,
    val matchedKeys: Int,
    val matchedBarcodes: Int,
    val totalIdent: Int,
)

private fun round4(x: Double): Double = kotlin.math.round(x * 1e4) / 1e4

/** Pull anchor / kv / barcode structures from a template. The `_avg` and plain
 *  variants carry identical keys (only the vector values differ) and detection
 *  cares only about the keys, so either works. */
private fun detectFields(
    t: ProcessedTemplate,
): Map<String, Map<String, List<Double>>> =
    if (t.barcodeVectorsAvg.isNotEmpty()) t.barcodeVectorsAvg else t.barcodeVectors

private fun detectScoreOne(
    anchorKeys: Set<String>,
    templateKeyCounts: Map<String, Int>,
    presentKvKeyCounts: Map<String, Int>,
    bcVecs: Map<String, Map<String, List<Double>>>,
    ocrWords: List<WordBox>,
    targetBaseCounts: Map<String, Int>,
    applyAbsentAnchorPenalty: Boolean = false,
    strictAnchor: Boolean = false,
    prof: DetectProfile? = null,
): DetectScore {
    // Anchors: distinct template anchor keys that a scan word fuzzy-matches.
    // Anchor keys are precomputed on the template (see ProcessedTemplate).
    val tAnchor = System.nanoTime()
    var matchedAnchors = 0
    if (anchorKeys.isNotEmpty()) {
        val matched = HashSet<String>()
        for (w in ocrWords) {
            val k = fuzzyMatchAnchorKey(w.text, anchorKeys, strictAnchor)
            if (k != null) matched.add(k)
        }
        matchedAnchors = matched.size
    }
    prof?.let { it.anchorNs += System.nanoTime() - tAnchor }

    // Key-values: per key text, min(template instances, scan occurrences). The
    // occurrence scan runs ONCE per distinct key text by the caller
    // (presentKvKeyCounts); here it's just count arithmetic. Keys are counted
    // per INSTANCE, so count variants of a template separate naturally: a
    // 3×MAC scan scores the 3×MAC template 3/(3+k) over the 2×MAC template's
    // 2/(2+k), and a 2×MAC scan flips the order. Legacy templates (all counts
    // 1) reproduce the old presence-set behavior exactly.
    val totalKv = templateKeyCounts.values.sum()
    val matchedKeys = templateKeyCounts.entries.sumOf { (text, tc) ->
        minOf(tc, presentKvKeyCounts[text] ?: 0)
    }

    // Barcodes: template type_ids whose base type appears in the scan with a
    // matching per-base-type count (same gate as matchBarcode). Barcodes are NOT
    // scored — matchedBarcodes only breaks ties between near-equal matches (see
    // detectTemplate) — so a missed barcode never demotes the true template.
    var matchedBarcodes = 0
    if (bcVecs.isNotEmpty()) {
        val templateBaseCounts = HashMap<String, Int>()
        for (tid in bcVecs.keys) {
            val bt = baseType(tid)
            templateBaseCounts[bt] = (templateBaseCounts[bt] ?: 0) + 1
        }
        val allowed = templateBaseCounts.filter { (bt, c) -> c == (targetBaseCounts[bt] ?: 0) }.keys
        matchedBarcodes = bcVecs.keys.count { tid ->
            val bt = baseType(tid)
            bt in allowed && (targetBaseCounts[bt] ?: 0) > 0
        }
    }

    // Relevance = POOLED coverage of anchors + keys: (matched) / (total + k). Not
    // a per-type blend — pooling ensures a matched anchor can never LOWER the
    // score below a no-anchor competitor.
    val totalIdent = anchorKeys.size + totalKv
    var score = if (totalIdent > 0) {
        (matchedAnchors + matchedKeys).toDouble() / (totalIdent + DETECT_COVERAGE_SMOOTHING)
    } else {
        0.0
    }

    // Absent-anchor penalty (gated by the caller on frame word-count): a
    // distinctive brand anchor nowhere in a text-rich scan is strong evidence
    // against this template. May go negative — intended.
    if (applyAbsentAnchorPenalty &&
        matchedAnchors == 0 &&
        anchorKeys.any { it.length >= DETECT_DISTINCTIVE_ANCHOR_MIN_LEN }
    ) {
        score -= DETECT_ABSENT_ANCHOR_PENALTY
    }

    return DetectScore(
        score = score,
        evidence = matchedAnchors + matchedKeys + matchedBarcodes,
        matchedAnchors = matchedAnchors,
        matchedKeys = matchedKeys,
        matchedBarcodes = matchedBarcodes,
        totalIdent = totalIdent,
    )
}

/**
 * Rank [candidates] by relevance and pick the closest.
 *
 * Always commits to the highest-relevance template (rank-1) — there is no
 * "ambiguous" outcome. The caller predicts on [TemplateDetectionResult.chosenId]
 * immediately and, if wrong, lets the user re-pick from
 * [TemplateDetectionResult.ranked] (ordered closest-first, ready to render as
 * the override list). Relevance = anchor + KV coverage only; barcodes only break
 * ties between near-equal matches, so a missed barcode never demotes the truth.
 *
 * @param ocrWords UNFILTERED [(text, bbox)] from the captured scan.
 * @param barcodeFormats detected barcode format strings (e.g. "CODE128").
 */
internal fun detectTemplate(
    candidates: List<TemplateCandidate>,
    ocrWords: List<WordBox>,
    barcodeFormats: List<String>,
): TemplateDetectionResult {
    val targetBaseCounts = HashMap<String, Int>()
    for (f in barcodeFormats) {
        val bt = baseType(f)
        if (bt.isNotEmpty()) targetBaseCounts[bt] = (targetBaseCounts[bt] ?: 0) + 1
    }

    // Gate the absent-anchor penalty on frame richness (see the constant).
    val applyPenalty = ocrWords.size >= DETECT_ABSENT_ANCHOR_MIN_WORDS

    val prof = DetectProfile()

    // Precompute KV-key presence ONCE across all candidates. findKeyWord is the
    // expensive per-key OCR scan; the OCR is fixed, so a given key text only
    // needs testing once even if many templates share it. Pass-independent
    // (findKeyWord ignores strictAnchor), so this is reused by the strict
    // re-pass too. Exact-preserving: per-template matchedKeys is just the count
    // of that template's keys present here.
    // Per-template instance counts are cached on the template
    // (detectKvKeyCounts); occurrence counting (findAllKeyWords) only runs for
    // texts some template defines >1 time — presence (0/1) elsewhere, which is
    // identical to the legacy scan for every legacy template.
    val neededCounts = HashMap<String, Int>()
    for (c in candidates) {
        for ((text, count) in c.template.detectKvKeyCounts) {
            neededCounts[text] = maxOf(neededCounts[text] ?: 0, count)
        }
    }
    val tKv = System.nanoTime()
    // Normalize the OCR words ONCE and reuse across every key's findKeyWord —
    // avoids re-lowercasing/stripping the same words per key (the dominant cost).
    val norms = buildNormWords(ocrWords)
    val presentKvKeyCounts = HashMap<String, Int>()
    for ((text, needed) in neededCounts) {
        presentKvKeyCounts[text] = if (needed > 1) {
            findAllKeyWords(text, ocrWords, strictMultiword = true).size
        } else {
            if (findKeyWord(text, ocrWords, strictMultiword = true, norms = norms) != null) 1 else 0
        }
    }
    prof.kvNs = System.nanoTime() - tKv
    prof.findKeyCalls = neededCounts.size

    fun rank(strictAnchor: Boolean): List<TemplateRank> = candidates.map { cand ->
        val bcVecs = detectFields(cand.template)
        val s = detectScoreOne(
            cand.template.detectAnchorKeys, cand.template.detectKvKeyCounts, presentKvKeyCounts, bcVecs,
            ocrWords, targetBaseCounts, applyPenalty, strictAnchor, prof,
        )
        val row = TemplateRank(
            id = cand.id,
            name = cand.name,
            score = round4(s.score),
            evidence = s.evidence,
            matchedAnchors = s.matchedAnchors,
            matchedKeys = s.matchedKeys,
            matchedBarcodes = s.matchedBarcodes,
        )
        row to s.totalIdent
    }.sortedWith(
        // Closest first: relevance score, then barcode match (tiebreak for
        // near-equal anchor+KV matches), then total matched evidence. Final
        // tiebreak: FEWER defined keys+anchors wins — on a signal-less scan
        // (everything tied at 0), a content-rich template that matched none of
        // its keys is less likely than a sparse one. Deterministic, so both
        // platforms order identically.
        compareByDescending<Pair<TemplateRank, Int>> { it.first.score }
            .thenByDescending { it.first.matchedBarcodes }
            .thenByDescending { it.first.evidence }
            .thenBy { it.second },
    ).map { it.first }

    var ranked = rank(strictAnchor = false)
    // Very-ambiguous re-pass: when nothing matched well, the top pick may be a
    // spurious fragment-only anchor match ("Jun" ⊆ "Juniper" on a content-less
    // label). Re-rank strictly so a fragment can't win with no real signal.
    // Confident results (top >= gate) keep lenient matching — can't hurt clear cases.
    if (ranked.isNotEmpty() && ranked[0].score < DETECT_AMBIGUOUS_SCORE_GATE) {
        ranked = rank(strictAnchor = true)
    }

    // Always pick the closest; ambiguous retained for API compat (always false).
    val chosenId = ranked.firstOrNull()?.id
    var totalKvKeys = 0
    for (c in candidates) totalKvKeys += c.template.detectKvKeyCounts.values.sum()
    return TemplateDetectionResult(
        chosenId, ambiguous = false, ranked = ranked,
        detAnchorMs = prof.anchorNs / 1_000_000.0,
        detKvMs = prof.kvNs / 1_000_000.0,
        detFindKeyCalls = prof.findKeyCalls,
        detTotalKvKeys = totalKvKeys,
        detDistinctKvKeys = neededCounts.size,
    )
}
