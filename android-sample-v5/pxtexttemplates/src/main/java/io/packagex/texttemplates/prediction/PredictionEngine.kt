package io.packagex.texttemplates.prediction

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import io.packagex.texttemplates.data.remote.dto.PredictedField
import io.packagex.texttemplates.data.remote.dto.PredictionResponse
import io.packagex.texttemplates.extraction.models.BarcodeFrameResult
import io.packagex.texttemplates.extraction.models.OcrFrameResult
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class PredictionEngine constructor() {

    companion object {
        private const val TAG = "[PredictionEngine]"

        /** Skip the pairwise matcher (and the n×n word-vector matrix) when the
         *  frame has more than this many words AND the template has at least
         *  one alternative matcher (anchor / barcode / kv). Pairwise is
         *  O(P·n²), so the n² term dominates at high word counts. */
        private const val PAIRWISE_SKIP_WORD_THRESHOLD = 100

        /** Minimum substring length for barcode-injection textual evidence. */
        private const val MIN_SUBSTRING_LEN = 4

        /** Spatial threshold multiplier for barcode injection — centre-to-centre
         *  distance must be < this × the prediction bbox's diagonal. */
        private const val BARCODE_INJECT_DIST_FACTOR = 2.5

        /** Bounds for the fine-deskew fallback to the orientation detector's
         *  fine residual when the modal skew estimator abstains. Below MIN the
         *  tilt is within per-word angle noise (the modal estimator's own
         *  deadband is 0.5°) and not worth a correction; above MAX a residual
         *  with zero modal consensus is more likely a bad area-weighted mean
         *  (e.g. one huge rotated word) than a real document tilt — MAX matches
         *  the near-horizontal gate on the modal estimator's votes. */
        private const val FINE_RESIDUAL_FALLBACK_MIN_DEG = 1.0
        private const val FINE_RESIDUAL_FALLBACK_MAX_DEG = 30.0

        /** Corroboration quorum for the fine-deskew fallback: at least this
         *  many individual word votes must agree with the fine residual
         *  (within [FINE_RESIDUAL_QUORUM_TOL_DEG]) before it may override the
         *  modal estimator's 0. The modal estimator returns 0.0 both when it
         *  abstains (<3 votes) AND when it confidently measures "straight"
         *  (winning bin inside its 0.5° deadband) — the fallback can't tell
         *  those apart from the return value alone. The quorum separates the
         *  two real-world cases: a genuinely tilted document carries its tilt
         *  in many words' corner points (quorum passes), while a straight
         *  document with one large rotated element — a logo or angled sticker
         *  dragging the area-weighted residual — yields only 1-2 agreeing
         *  votes (quorum fails, modal's correct 0 stands). Tolerance ≈
         *  per-word OCR angle noise; minimum 3 matches the sample floor both
         *  estimators already use. */
        private const val FINE_RESIDUAL_QUORUM_TOL_DEG = 2.0
        private const val FINE_RESIDUAL_QUORUM_MIN = 3

        /** Corroboration tolerance for the LARGE-residual (dead-zone) rescue,
         *  applied over the ungated word angles. Wider than the small-tilt
         *  [FINE_RESIDUAL_QUORUM_TOL_DEG] because a document held at a steep
         *  diagonal spreads per-word angles more under perspective. Still tight
         *  enough that a mixed-orientation label (whose words cluster near 0°
         *  and ±90°, not at the off-axis mean) fails the quorum and is left
         *  uncorrected. */
        private const val LARGE_RESIDUAL_QUORUM_TOL_DEG = 8.0

        /** Splits a stop-word entry into single OCR tokens (whitespace + the
         *  '×' quantity separator). See filterStopWords. */
        private val STOP_WORD_TOKEN_SPLIT = Regex("[\\s×]+")

        /** Minimum IoU between a barcode's oriented-quad AABB and its upright
         *  `bounds` box for the quad to be trusted by the matcher. A correctly
         *  normalised quad — even with real document skew — overlaps `bounds`
         *  heavily (IoU well above 0.5); a corner set that failed sensor→upright
         *  normalisation lands rotated ~90° and barely overlaps (IoU ≈ 0.17 in
         *  the observed square-sensor case). 0.5 cleanly separates the two while
         *  tolerating the modest box differences a genuine deskew produces. */
        private const val BARCODE_CORNERS_MIN_IOU = 0.5
    }

    var debugEnabled: Boolean = false

    /** Serializes a prediction and the debug snapshot taken from it. The `last*`
     *  diagnostic fields are last-write-wins; without this, two overlapping
     *  predictions on this engine (e.g. a One-Shot `predict` concurrent with a scan
     *  capture) could let one scan persist the other's debug. Callers hold this
     *  around `predict(...)` + [imageFreeDebugMap] so the snapshot is tied to that
     *  exact prediction. */
    val engineLock = Any()

    /** The current image-free debug payload (`buildDebugExport()` minus the frame
     *  image). Call under [engineLock], immediately after the `predict(...)` whose
     *  diagnostics you want. Null when debug is off. */
    fun imageFreeDebugMap(templateId: String): MutableMap<String, Any?>? =
        if (debugEnabled) buildDebugExport(templateId).toMutableMap().apply { remove("frameImageB64") } else null

    var lastDebugReport: DebugReport? = null
        private set
    var lastRawOcrText: String? = null
        private set
    var lastFrameBitmap: Bitmap? = null
        private set

    var lastTimings: PredictionTimings? = null
        private set

    /** Last auto-detection result (carries the scoring phase split for the
     *  debug screen). Set by [identifyTemplate]. */
    var lastDetection: TemplateDetectionResult? = null
        private set

    var lastDetectedBarcodes: List<DebugBarcode> = emptyList()
        private set
    var lastBarcodeMatchLog: List<String> = emptyList()
        private set
    var lastTextShapeLog: List<String> = emptyList()
        private set
    var lastOcrResult: OcrFrameResult? = null
        private set
    var lastTemplateId: String = ""

    /**
     * Version dispatcher: route to the prediction path matching the
     * template's recorded `version`. Templates created before versioning
     * existed parse as "1.0" — see [parseProcessedTemplate].
     */
    /**
     * Auto-detect which of [candidates] the captured scan belongs to, using the
     * lightweight identity-evidence scorer ([detectTemplate] in Matching.kt).
     *
     * Uses the UNFILTERED OCR word list (built here without stop-word removal,
     * since stop words are per-template and detection has to be fair across all
     * candidates). Text/type presence only — no geometry — so scoring the whole
     * template set once per capture is cheap. Run the returned [TemplateDetectionResult]
     * through [predict] on the chosen (or user-picked) template.
     */
    fun identifyTemplate(
        ocrResult: OcrFrameResult,
        barcodeResult: BarcodeFrameResult,
        candidates: List<TemplateCandidate>,
    ): TemplateDetectionResult {
        val ocrWords = ocrResult.blocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.words.mapNotNull { word ->
                    val bb = word.boundingBox ?: return@mapNotNull null
                    WordBox(
                        text = word.text,
                        bbox = doubleArrayOf(
                            bb.left.toDouble(), bb.top.toDouble(),
                            bb.right.toDouble(), bb.bottom.toDouble(),
                        ),
                    )
                }
            }
        }
        val formats = barcodeResult.barcodes.map { it.format }
        return detectTemplate(candidates, ocrWords, formats).also { lastDetection = it }
    }

    fun predict(
        ocrResult: OcrFrameResult,
        barcodeResult: BarcodeFrameResult,
        processedTemplate: ProcessedTemplate,
        frameWidth: Int = 0,
        frameHeight: Int = 0,
        rotationDegrees: Int = 0,
        previewRect: RectD? = null,
        innerBox: RectD? = null,
    ): PredictionResponse {
        val version = processedTemplate.version
        if (version !in SUPPORTED_TEMPLATE_VERSIONS) {
            android.util.Log.w(TAG, "Unsupported template version $version; supported: $SUPPORTED_TEMPLATE_VERSIONS")
            return PredictionResponse(predictions = emptyMap(), suggestions = null, timing = null)
        }
        return when (version) {
            "1.0" -> predictV1_0(
                ocrResult = ocrResult,
                barcodeResult = barcodeResult,
                processedTemplate = processedTemplate,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                rotationDegrees = rotationDegrees,
                previewRect = previewRect,
                innerBox = innerBox,
            )
            else -> {
                // Listed in SUPPORTED_TEMPLATE_VERSIONS but no dispatch
                // branch — programmer error.
                android.util.Log.e(TAG, "No prediction path registered for template version $version")
                PredictionResponse(predictions = emptyMap(), suggestions = null, timing = null)
            }
        }
    }

    private fun predictV1_0(
        ocrResult: OcrFrameResult,
        barcodeResult: BarcodeFrameResult,
        processedTemplate: ProcessedTemplate,
        frameWidth: Int = 0,
        frameHeight: Int = 0,
        rotationDegrees: Int = 0,
        previewRect: RectD? = null,
        innerBox: RectD? = null,
    ): PredictionResponse {
        val tStart = System.nanoTime()

        // --- Unpack template ---
        // Prefer avg-height-scaled vectors when the template has them. Older
        // templates that haven't been reprocessed fall back to mh-scaled.
        val useAvg = processedTemplate.vectorsAvg.isNotEmpty()
        val templateVectors = if (useAvg) processedTemplate.vectorsAvg else processedTemplate.vectors
        // Anchor matching uses `(h_a + w_a)` normalisation regardless of
        // useAvg. The pipeline stores the same values under both keys, so we
        // read `anchorVectors` for clarity.
        val templateAnchorVecs = processedTemplate.anchorVectors
        val templateBcVecs = if (useAvg) processedTemplate.barcodeVectorsAvg else processedTemplate.barcodeVectors
        val templateKvVecs = if (useAvg) processedTemplate.kvVectorsAvg else processedTemplate.kvVectors
        val templateKvScales = if (useAvg) processedTemplate.kvScaleFactorsAvg else processedTemplate.kvScaleFactors
        val templateBcScales = if (useAvg) processedTemplate.barcodeScaleFactorsAvg else processedTemplate.barcodeScaleFactors
        val templatePrimaryKeyMap = processedTemplate.primaryKeyMap
        val heightScale = processedTemplate.heightScale
        val fieldTexts = processedTemplate.fieldTexts
        val fieldBarcodeAssoc = processedTemplate.fieldBarcodeAssoc
        val fieldDatatypes = processedTemplate.fieldDatatypes
        val flexible = processedTemplate.flexible

        // --- Convert OCR to word_box_tuples ---
        // Carry cornerPoints (when available) so the deskew step below can
        // derive a tight AABB from the oriented quad, same pattern as the
        // barcode path.
        var wordBoxTuples = ocrResult.blocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.words.mapNotNull { word ->
                    val bb = word.boundingBox ?: return@mapNotNull null
                    WordBox(
                        text = word.text,
                        bbox = doubleArrayOf(
                            bb.left.toDouble(), bb.top.toDouble(),
                            bb.right.toDouble(), bb.bottom.toDouble(),
                        ),
                        cornerPoints = word.cornerPoints,
                        trailingSeparator = word.trailingSeparator,
                    )
                }
            }
        }

        // Drop template-baked stop words before anything else inspects the
        // word list, so they're excluded from orientation detection, matching,
        // anchors, and KV — mirrors `_filter_stop_words` in api/pipeline.py.
        wordBoxTuples = filterStopWords(wordBoxTuples, processedTemplate.stopWords)

        // --- Orientation detection + gross rotation (90° / 180° / 270° undo) ---
        // Detect the label's orientation relative to image axes from the
        // area-weighted circular mean of word top-edge angles (restricted to
        // Zone 2, the innerBox-framed region when available). Decompose into a
        // coarse axis-snap + fine residual; apply the coarse rotation here so
        // the rest of the pipeline runs in the template's coordinate space. The
        // fine residual is left for the skew-deskew block below. After a ±90°
        // rotation, frameWidth/frameHeight swap and all geometry moves to its
        // post-rotation position. Barcodes are rotated later (inside the
        // barcode-conversion block) using the pre-swap dimensions.
        var frameWidth = frameWidth
        var frameHeight = frameHeight
        var previewRect = previewRect
        var innerBox = innerBox
        var appliedGrossRotation = 0
        // Kept for the debug report: the detector's coarse axis-snap
        // (0/90/180/270) and fine residual, even when no rotation is applied.
        var detectedCoarseDegrees = 0
        var detectedFineDegrees = 0.0
        val detectedOrientationAngle = areaWeightedTopEdgeAngle(wordBoxTuples, innerBox)
        if (detectedOrientationAngle != null) {
            val (coarse, fine) = decomposeOrientationAngle(detectedOrientationAngle)
            detectedCoarseDegrees = coarse.toInt()
            detectedFineDegrees = fine
            appliedGrossRotation = grossRotationToApply(coarse)
            Log.d(
                TAG,
                "orientationDetector: raw=${"%.2f".format(detectedOrientationAngle)}° " +
                    "coarse=${coarse.toInt()}° fine=${"%.2f".format(fine)}° " +
                    "→ applyGrossRotation=$appliedGrossRotation° (words=${wordBoxTuples.size})",
            )
        } else {
            Log.d(TAG, "orientationDetector: no signal (fewer than 3 weighted samples) → coarse=0°")
        }
        if (appliedGrossRotation != 0) {
            val imgWGross = frameWidth.toDouble()
            val imgHGross = frameHeight.toDouble()
            wordBoxTuples = wordBoxTuples.map { wb ->
                val newBbox = rotateBboxAxisAligned(wb.bbox, appliedGrossRotation, imgWGross, imgHGross)
                val newCorners = wb.cornerPoints?.let {
                    rotateCornerPoints(it, appliedGrossRotation, frameWidth, frameHeight)
                }
                wb.copy(bbox = newBbox, cornerPoints = newCorners)
            }
            previewRect = previewRect?.let { rotateRect(it, appliedGrossRotation, imgWGross, imgHGross) }
            innerBox = innerBox?.let { rotateRect(it, appliedGrossRotation, imgWGross, imgHGross) }
            if (appliedGrossRotation == 90 || appliedGrossRotation == 270) {
                val tmp = frameWidth; frameWidth = frameHeight; frameHeight = tmp
            }
        }

        // --- Deskew: detect and correct document skew ---
        // Each word's angle vote is weighted by how central its bbox is in the
        // frame, so text near the focus point dominates over edge text.
        // The extractor emits upright coords (`coordsPreRotated`): OcrExtractor
        // normalises ML Kit's corner points to upright at extraction — so the
        // camera-rotation compensation below is 0 on every current path (the
        // rotationDegrees branch is a defensive fallback for a future
        // sensor-space extractor).
        // Iterate the (possibly gross-rotated) wordBoxTuples rather than the raw
        // ocrResult.blocks — otherwise the deskew angle is computed from the
        // *original* unrotated corner points and tries to undo the rotation we
        // just applied, double-rotating every bbox.
        // Do NOT additionally subtract `appliedGrossRotation` here: the
        // gross-rotation step above already rotated each word's corner points,
        // so the angle read from them is already near 0° for main-document
        // text. Subtracting it again re-introduces the rotation just undone —
        // at 180° the shifted votes (~±180°) slip through the near-horizontal
        // gate below, the "dominant skew" comes out ≈180°, and the fine-deskew
        // step flips every bbox back upside-down (90°/270° were masked because
        // ±90° votes fail the gate and the skew fell back to 0). Matches iOS,
        // which uses the camera compensation alone.
        val rotationCompensation = if (ocrResult.coordsPreRotated) 0 else rotationDegrees
        val imgW = frameWidth.toDouble()
        val imgH = frameHeight.toDouble()
        val weightedAngles = mutableListOf<Pair<Double, Double>>()
        // Every word's adjusted angle, UNGATED — used only to corroborate a
        // large-residual dead-zone rescue below. The gated `weightedAngles`
        // (≤30°) structurally can't confirm a >30° residual, so the large
        // branch needs the raw angles to check that a quorum of words actually
        // sit at that tilt (vs. a mixed-orientation label whose edge text
        // merely drags the detector's mean off-axis).
        val allAdjustedAngles = mutableListOf<Double>()
        for (wb in wordBoxTuples) {
            val pts = wb.cornerPoints ?: continue
            val raw = angleFromCornerPoints(pts) ?: continue
            var adjusted = raw + rotationCompensation
            while (adjusted > 180) adjusted -= 360
            while (adjusted <= -180) adjusted += 360
            allAdjustedAngles.add(adjusted)
            // After the gross rotation step, main-document text reads roughly
            // horizontal. Words still near ±90° (phone UI chrome, inset
            // thumbnails) would pull the deskew vote off to ±90°, after which
            // the per-word orientation filter below would drop the legitimate
            // label words. Restrict the vote to near-horizontal angles.
            val absAngle = abs(adjusted)
            val nearHorizontal = absAngle <= 30.0 || absAngle >= 150.0
            if (!nearHorizontal) continue
            val w = centerWeight(wb.bbox, imgW, imgH)
            weightedAngles.add(adjusted to w)
        }
        // Fine deskew only — the coarse 0/90/180/270 correction already
        // happened above and is never mixed in here. The modal estimator needs
        // a single 1°-bin to win the vote, which fails on small uniform tilts:
        // per-word angle noise spreads the true tilt across adjacent bins
        // while ML Kit's axis-snapped corner points stack exact-0° votes into
        // one coherent bin, so a genuinely tilted document comes out as 0.
        // When that happens, fall back to the orientation detector's fine
        // residual (area-weighted circular mean over all words) — but only
        // with a quorum of individual word votes agreeing with the residual,
        // so a confident modal "straight" verdict can't be overridden by one
        // large rotated element (see FINE_RESIDUAL_QUORUM_MIN). Both
        // estimators read the same top-edge atan2(dy, dx) convention, and the
        // residual is invariant under the exact 90° gross rotation, so it is
        // directly usable as the deskew angle. The fallback flows to every
        // downstream consumer of skewAngle: word-bbox deskew, barcode-bbox
        // deskew, and the inverse mapping back to display coords. This SMALL-
        // tilt fallback is Android-only by design: the axis-snap failure is an
        // ML Kit behavior — iOS Vision and the server's OCR return true oriented
        // quads, so their modal estimators get clean votes and need no small-
        // tilt fallback. (The LARGE-residual dead-zone rescue below is on both
        // platforms — it addresses the ±30° gate, independent of OCR engine.)
        val modalSkewAngle = computeDominantSkewAngleWeighted(weightedAngles)
        val residualQuorum = weightedAngles.count {
            abs(it.first - detectedFineDegrees) <= FINE_RESIDUAL_QUORUM_TOL_DEG
        }
        // Corroboration for the large-residual branch, over the UNGATED angles
        // (wider tolerance — large tilts spread more under perspective). A
        // mixed-orientation label (horizontal body + vertical edge text) yields
        // a large detector mean that NO cluster of words actually sits at, so
        // this quorum stays 0 and the rescue is (correctly) skipped.
        val largeResidualQuorum = allAdjustedAngles.count {
            abs(it - detectedFineDegrees) <= LARGE_RESIDUAL_QUORUM_TOL_DEG
        }
        // Near-horizontal cluster, for the large-residual guard below: a large-
        // AREA off-axis cluster (e.g. a diagonal watermark) can hijack the area-
        // weighted detector on an otherwise-upright label and satisfy the count
        // quorum with only a few big words. Requiring the residual cluster to
        // OUTNUMBER the near-horizontal one keeps a horizontal-dominant scan from
        // being rotated (which would drop its main text via the ±30° filter).
        val horizontalCluster = allAdjustedAngles.count {
            abs(it) <= LARGE_RESIDUAL_QUORUM_TOL_DEG
        }
        val skewAngle = when {
            // Small-tilt fallback: modal said straight but the gated word votes
            // corroborate the detector's small residual (ML Kit axis-snap case).
            modalSkewAngle == 0.0 &&
                abs(detectedFineDegrees) in FINE_RESIDUAL_FALLBACK_MIN_DEG..FINE_RESIDUAL_FALLBACK_MAX_DEG &&
                residualQuorum >= FINE_RESIDUAL_QUORUM_MIN -> {
                Log.d(
                    TAG,
                    "deskew: modal estimator returned 0, fine residual " +
                        "${"%.2f".format(detectedFineDegrees)}° corroborated by " +
                        "$residualQuorum word votes — applying fallback",
                )
                detectedFineDegrees
            }
            // Large-residual dead zone: the label sits at an awkward diagonal, so
            // the coarse 90° snap leaves a residual bigger than the ±30° deskew
            // gate. Every main-text word is gated out of weightedAngles, so the
            // modal estimator AND the gated quorum see nothing — structurally,
            // not because the document is straight. The orientation detector
            // (area-weighted circular mean over ≥3 coherent Zone-2 words) already
            // measured this residual robustly, so trust it directly. Without this
            // the tilt goes uncorrected, mis-orienting the drawn boxes AND
            // breaking the matchers + the per-word orientation filter (both
            // assume a near-0 residual). Gated on |fine| > 30° so scans within
            // the normal deskew range are completely unaffected. Post-gross word
            // angle equals the residual, so the sign is directly usable.
            abs(detectedFineDegrees) > FINE_RESIDUAL_FALLBACK_MAX_DEG &&
                largeResidualQuorum >= FINE_RESIDUAL_QUORUM_MIN &&
                largeResidualQuorum > horizontalCluster -> {
                Log.d(
                    TAG,
                    "deskew: large residual ${"%.2f".format(detectedFineDegrees)}° " +
                        "beyond ±30° gate, corroborated by $largeResidualQuorum word " +
                        "votes — applying detector residual (dead-zone rescue)",
                )
                detectedFineDegrees
            }
            else -> modalSkewAngle
        }

        // Compute skew center from all word bboxes.
        val skewCx: Double
        val skewCy: Double
        if (wordBoxTuples.isNotEmpty()) {
            val allBboxes = wordBoxTuples.map { it.bbox }
            skewCx = allBboxes.sumOf { (it[0] + it[2]) / 2.0 } / allBboxes.size
            skewCy = allBboxes.sumOf { (it[1] + it[3]) / 2.0 } / allBboxes.size
        } else {
            skewCx = 0.0
            skewCy = 0.0
        }

        // Drop per-word rotated tokens FIRST, using each word's corner-point
        // edge angle, before perpHeightBbox collapses tall+narrow rotated
        // AABBs into something the h>w rotated-word filter can't see.
        val orientationFiltered = wordBoxTuples.filter { wb ->
            if (wb.text.length <= 2) return@filter true
            val pts = wb.cornerPoints ?: return@filter true
            if (pts.size != 4) return@filter true
            val rawAngle = angleFromCornerPoints(pts) ?: return@filter true
            var residual = rawAngle - skewAngle
            while (residual > 180) residual -= 360
            while (residual <= -180) residual += 360
            val absRes = abs(residual)
            // Horizontal = within 30° of 0° (upright) or 180° (upside-down).
            absRes <= 30.0 || absRes >= 150.0
        }

        // Rebuild each surviving word's bbox from its oriented corner points
        // using perpendicular-height correction — brings live measurements
        // into the same definition the template was recorded with.
        val rotatedWordBoxTuples = orientationFiltered.map { wb ->
            val corners = wb.cornerPoints
            if (corners != null && corners.size == 4) {
                val bb = rotateCornersToPerpHeightBbox(corners, -skewAngle, skewCx, skewCy)
                wb.copy(bbox = bb)
            } else if (skewAngle != 0.0) {
                val bb = rotateBbox(wb.bbox, -skewAngle, skewCx, skewCy)
                wb.copy(bbox = bb)
            } else {
                wb
            }
        }

        // Backstop: any AABB still ending up taller than wide means a word had
        // no corner points and its raw AABB was already tall — drop those too.
        val deskewedWordBoxTuples = removeRotatedWords(rotatedWordBoxTuples)
        val wordBoxTuplesUnfiltered = deskewedWordBoxTuples.toList()

        if (deskewedWordBoxTuples.isEmpty()) {
            Log.w(TAG, "OCR returned no usable words")
            return PredictionResponse(predictions = emptyMap())
        }

        // --- Find protected key bboxes ---
        val protectedKeyBboxes = mutableListOf<DoubleArray>()
        val allTemplateKeys = mutableSetOf<String>()
        val templateKvKeyTexts = processedTemplate.kvKeyTexts
        for (fieldKv in templateKvVecs.values) {
            // Inner keys are printed texts, except multi-instance entries which
            // are keyed by owning field LABEL — resolve those back to the
            // printed text (labels must never be searched as key text).
            for (innerKey in fieldKv.keys) {
                allTemplateKeys.add(templateKvKeyTexts[innerKey] ?: innerKey)
            }
        }
        // Instances that failed to bake vectors still name a printed text.
        allTemplateKeys.addAll(templateKvKeyTexts.values)
        // Look-alike keys stand in for an absent primary key, so protect their
        // region (and adjacent value) from outlier filtering just like a real key.
        for (aliases in processedTemplate.keyAliases.values) allTemplateKeys.addAll(aliases)
        val multiInstanceTexts = templateKvKeyTexts.values.toSet()
        for (keyText in allTemplateKeys) {
            val keyBoxes = if (keyText in multiInstanceTexts) {
                // One printed occurrence per sibling field — protect them ALL
                // (and each one's adjacent value run), not just the first.
                findAllKeyWords(keyText, deskewedWordBoxTuples)
            } else {
                listOfNotNull(findKeyWord(keyText, deskewedWordBoxTuples))
            }
            for (keyBox in keyBoxes) {
                protectedKeyBboxes.add(keyBox.bbox)
                // Protect the key's adjacent value tokens too: a value just outside
                // the trusted focus zone (right-column "Quantity 1") is otherwise
                // MAD-filtered out while its in-zone key survives, leaving the field
                // with no candidate value.
                protectedKeyBboxes.addAll(collectAdjacentValueBboxes(keyBox, deskewedWordBoxTuples))
            }
        }

        // --- Remove outlier boxes ---
        // Three-zone focus-area filter: drops centres outside previewRect,
        // keeps centres inside innerBox unconditionally, MAD-filters the border
        // zone against trusted innerBox statistics. Falls back to legacy
        // global-MAD when no focus rects are supplied.
        var filtered = removeOutliersWithFocusArea(
            deskewedWordBoxTuples,
            previewRect = previewRect,
            innerBox = innerBox,
            protectedBboxes = protectedKeyBboxes.ifEmpty { null },
        )
        filtered = filterWordBoxes(filtered)

        if (filtered.isEmpty()) {
            Log.w(TAG, "All words filtered out")
            return PredictionResponse(predictions = emptyMap())
        }

        // --- Compute text area bounds ---
        val minX = filtered.minOf { it.bbox[0] }
        val minY = filtered.minOf { it.bbox[1] }
        val maxX = filtered.maxOf { it.bbox[2] }
        val maxY = filtered.maxOf { it.bbox[3] }

        var pageWidth = maxX - minX
        var pageHeight = maxY - minY
        if (pageWidth == 0.0) pageWidth = 1.0
        if (pageHeight == 0.0) pageHeight = 1.0

        // --- Scale word boxes ---
        val wordBoxTuplesScaled = scaleBoxesTuples(filtered, minX, minY)
        val wordBoxTuplesUnfilteredScaled = scaleBoxesTuples(wordBoxTuplesUnfiltered, minX, minY)

        // --- Compute median height ---
        var mh = medianHeight(wordBoxTuplesScaled)
        if (mh == 0.0) mh = 1.0

        // --- Compute word vectors for pairwise (skip on high word counts when
        //     alternative matchers can still produce predictions) ---
        val tWordVec = System.nanoTime()
        val hasAlternativeMatcher = templateAnchorVecs.isNotEmpty() ||
                templateBcVecs.isNotEmpty() || templateKvVecs.isNotEmpty()
        val skipPairwise = wordBoxTuplesScaled.size > PAIRWISE_SKIP_WORD_THRESHOLD && hasAlternativeMatcher
        val wordVectors: Array<Array<DoubleArray>>? = if (!flexible && templateVectors.isNotEmpty() && !skipPairwise) {
            if (useAvg) {
                computeWordVectorsPolarAvg(wordBoxTuplesScaled, pageWidth, pageHeight, heightScale)
            } else {
                computeWordVectorsPolar(wordBoxTuplesScaled, mh, pageWidth, pageHeight, heightScale)
            }
        } else {
            if (skipPairwise) Log.d(TAG, "Pairwise skipped: ${wordBoxTuplesScaled.size} words > $PAIRWISE_SKIP_WORD_THRESHOLD, alternatives available")
            null
        }

        // --- Find anchor words + barcode prep ---
        val tAnchorBc = System.nanoTime()
        val templateAnchorKeys: Set<String> = templateAnchorVecs.keys
        // Anchor matching uses (h_a + w_a) normalisation now, so the h_a-based
        // position tiebreak doesn't apply. findAnchorWords falls back to
        // largest-by-area selection.
        val anchorWordsInTarget = findAnchorWords(
            wordBoxTuplesScaled,
            templateAnchors = templateAnchorKeys,
            templateAnchorPositions = null,
        )

        // --- Convert barcodes ---
        data class RotatedBarcode(
            val bc: io.packagex.texttemplates.extraction.models.DetectedBarcode,
            val deskewedBbox: DoubleArray,
            val displayBbox: DoubleArray,
        )
        // For barcodes, capture the gross-rotation dimensions BEFORE the ±90°
        // width/height swap (the swap happened earlier when the gross rotation
        // was applied to word bboxes). Barcode coordinates arrive in the same
        // incoming frame as the word bboxes (upright space — ML Kit bounds are
        // rotation-compensated), so
        // they must be rotated using the pre-swap dimensions before being
        // expressed in post-rotation space.
        val preGrossImgW = if (appliedGrossRotation == 90 || appliedGrossRotation == 270)
            frameHeight.toDouble() else frameWidth.toDouble()
        val preGrossImgH = if (appliedGrossRotation == 90 || appliedGrossRotation == 270)
            frameWidth.toDouble() else frameHeight.toDouble()
        val rotatedBarcodes = barcodeResult.barcodes.mapNotNull { bc ->
            val bounds = bc.bounds ?: return@mapNotNull null
            // displayBbox stays in the incoming (upright, pre-gross-rotation)
            // coords so it can be drawn directly on the upright captured
            // image in debug.
            val displayBbox = doubleArrayOf(
                bounds.left.toDouble(), bounds.top.toDouble(),
                bounds.right.toDouble(), bounds.bottom.toDouble(),
            )
            // deskewedBbox is the post-gross-rotation + post-deskew version that
            // feeds the matcher pipeline (lines up with the deskewed word bboxes).
            //
            // When ML Kit hands us a trustworthy oriented quad, carry the CORNERS
            // through both rotations and take the AABB of the deskewed quad
            // (`rotateCornersToBbox`). Rotating the axis-aligned hull instead
            // inflates the box for any non-90° skew, and that inflation corrupts
            // the width normalizer + left-edge origin the barcode matcher keys on
            // — the same quantities the creation-time bar-region tightening pins
            // down. Mirrors the word deskew path and iOS. Fall back to the
            // bounds-AABB path when corners are absent, malformed, OR fail the
            // agreement check below.
            //
            // Bounds-derived deskewed box — ML Kit returns `bounds` already
            // rotation-compensated to upright space, so it is the authoritative
            // orientation reference. Always computed: it is the fallback both
            // when the oriented quad is absent AND when it can't be trusted.
            val boundsDeskewedBbox = run {
                val rotatedBbox = if (appliedGrossRotation != 0) {
                    rotateBboxAxisAligned(displayBbox, appliedGrossRotation, preGrossImgW, preGrossImgH)
                } else displayBbox
                if (skewAngle != 0.0) {
                    rotateBbox(rotatedBbox.copyOf(), -skewAngle, skewCx, skewCy)
                } else rotatedBbox
            }
            val corners = bc.corners?.takeIf { it.size == 4 }
            val deskewedBbox = if (corners != null) {
                val rotatedCorners = if (appliedGrossRotation != 0) {
                    rotateCornerPoints(
                        corners, appliedGrossRotation,
                        preGrossImgW.toInt(), preGrossImgH.toInt(),
                    )
                } else corners
                // angleDeg = 0 → no-op rotation, plain AABB of the corners.
                val cornersBbox = rotateCornersToBbox(rotatedCorners, -skewAngle, skewCx, skewCy)
                // Trust the oriented-quad AABB only when it agrees with the
                // upright `bounds` box. ML Kit sometimes returns cornerPoints in
                // the raw sensor frame while `bounds` is already upright; when
                // the sensor→upright normalisation in BarcodeExtractor doesn't
                // fully land (seen on square-sensor captures, where the corner
                // set comes back rotated ~90° from `bounds`), the corner AABB has
                // its width/height swapped and is mislocated. Feeding that to the
                // matcher shrinks the barcode-width normalizer ~3.5× and wrecks
                // every barcode→field vector (the true value drops from rank 1 to
                // rank 8). A correct quad — even with real skew — overlaps
                // `bounds` heavily; a 90°-rotated one barely overlaps, so a low
                // IoU is an unambiguous reject signal.
                if (bboxIou(cornersBbox, boundsDeskewedBbox) >= BARCODE_CORNERS_MIN_IOU) {
                    cornersBbox
                } else {
                    boundsDeskewedBbox
                }
            } else {
                boundsDeskewedBbox
            }
            RotatedBarcode(bc, deskewedBbox, displayBbox)
        }
        val detectedBarcodes = rotatedBarcodes.map { rb ->
            val scaledBox = doubleArrayOf(
                rb.deskewedBbox[0] - minX, rb.deskewedBbox[1] - minY,
                rb.deskewedBbox[2] - minX, rb.deskewedBbox[3] - minY,
            )
            // De-inflate: a 1-D barcode box should bound the bars, not the HRI
            // digit line or label text. On-device detectors sometimes return a
            // box that swallows those rows, which inflates its height and drags
            // the left-midpoint anchor the matcher keys on (the Tracking Number
            // failure). Clip to the bar region using the OCR words already in
            // hand (both are in the scaled frame). No-op for already-tight boxes.
            DetectedBarcodeForMatching(
                typeId = rb.bc.format,
                bbox = clipBarcodeBoxToBarRegion(scaledBox, wordBoxTuplesScaled, rb.bc.format),
                data = rb.bc.data,
            )
        }

        // --- Build raw OCR text for expansion ---
        val rawOcrText = ocrResult.fullText

        // --- Debug collector ---
        val debugCollector: MutableMap<String, Any?>? = if (debugEnabled) mutableMapOf() else null
        if (debugCollector != null) {
            debugCollector["preprocessing"] = mapOf(
                "filtered_word_count" to wordBoxTuplesScaled.size,
                "min_x" to minX,
                "min_y" to minY,
                "page_width" to pageWidth,
                "page_height" to pageHeight,
                "median_height" to mh,
                "skew_angle" to skewAngle,
                "raw_word_angles" to weightedAngles.map { it.first },
                "anchor_words" to anchorWordsInTarget.map { mapOf("text" to it.text, "index" to it.index) },
            )
        }

        // --- Fused prediction ---
        val fusedTimingsMap = mutableMapOf<String, Double>()
        val tFusedStart = System.nanoTime()
        val result = fusedPrediction(
            templateVectors = templateVectors,
            templateAnchorVecs = templateAnchorVecs,
            templateAnchorScales = processedTemplate.anchorScaleFactorsAvg,
            templateBcVecs = templateBcVecs,
            templateKvVecs = templateKvVecs,
            wordVectors = wordVectors,
            wordBoxTuples = wordBoxTuplesScaled,
            detectedBarcodes = detectedBarcodes,
            medianHeight = mh,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            anchorWordsInTarget = anchorWordsInTarget,
            heightScale = heightScale,
            templatePrimaryKeyMap = templatePrimaryKeyMap,
            flexible = flexible,
            wordBoxTuplesUnfiltered = wordBoxTuplesUnfilteredScaled,
            useAvg = useAvg,
            kvScaleFactors = templateKvScales,
            barcodeScaleFactors = templateBcScales,
            barcodeAspect = processedTemplate.barcodeAspect,
            barcodeUseHeightNorm = processedTemplate.barcodeNorm == "height",
            barcodeUseCenter = processedTemplate.barcodeOrigin == "center",
            keyPositionsMhNorm = processedTemplate.keyPositionsMhNorm,
            keyAliases = processedTemplate.keyAliases,
            kvKeyTexts = processedTemplate.kvKeyTexts,
            kvKeyPositions = processedTemplate.kvKeyPositions,
            fusionWeights = processedTemplate.fusionWeights,
            debugCollector = debugCollector,
            timingsOut = fusedTimingsMap,
        )
        val tFusedEnd = System.nanoTime()

        if (debugCollector != null) {
            debugCollector["predictions_before_expansion"] = result.predictions.mapValues { (_, pred) ->
                mapOf(
                    "text" to pred["text"],
                    "bbox" to pred["bbox"],
                    "confidence" to pred["confidence"],
                    "word_index" to pred["word_index"],
                )
            }
        }

        val predictions = result.predictions.toMutableMap()
        val suggestionsMap: MutableMap<String, MutableList<MutableMap<String, Any?>>> =
            result.suggestions.mapValues { it.value.toMutableList() }.toMutableMap()

        // --- Expand text predictions ---
        // Each candidate produces two expansions (whitespace-bounded and
        // newline-bounded). When they differ, the one whose text-shape is
        // closer to the template value becomes the primary; the other is
        // injected as an alternative candidate so the rerank stage can pick
        // by combined geo + text-shape score.
        for (field in predictions.keys.toList()) {
            val pred = predictions[field] ?: continue
            val templateValue = fieldTexts[field] ?: ""
            val keyTextStr = templatePrimaryKeyMap[field] ?: ""
            // OCR can misread the key glyphs (template "Qty" → scan "Oty"), and
            // stripLeadingKey matches literally — so the template key alone
            // can't peel a misread key prefix off an expanded value, leaving the
            // clean value out of the rerank pool. Resolve the ACTUAL matched OCR
            // token (findKeyWord is fuzzy-aware) and hand it to expandText too.
            // Empty when the key wasn't found at all; strip then falls back to
            // the template literal.
            // `primaryKeyMap` stores printed key TEXTS (never inner-key labels),
            // so this stays a valid single lookup for multi-instance keys too:
            // every occurrence prints the same text, and the strip only needs
            // the misread-glyph variant, not a particular occurrence. Which
            // occurrence's segment survives is decided positionally inside
            // stripLeadingKey via the value's own span.
            val matchedKeyText = if (keyTextStr.isNotEmpty()) {
                findKeyWord(keyTextStr, wordBoxTuplesScaled)?.text.orEmpty()
            } else {
                ""
            }
            val suggs = suggestionsMap.getOrPut(field) { mutableListOf() }

            // Expand prediction (dual + inject alt).
            val predText = pred["text"]?.toString() ?: ""
            if (predText.isNotEmpty() && rawOcrText.isNotEmpty()) {
                val wordIdx = (pred["word_index"] as? Number)?.toInt() ?: 0
                val exp = expandText(
                    predictedWord = predText,
                    wordIndex = wordIdx,
                    templateValue = templateValue,
                    rawOcrText = rawOcrText,
                    wordBoxTuplesFiltered = wordBoxTuplesScaled,
                    wordBoxTuplesUnfiltered = wordBoxTuplesUnfilteredScaled,
                    keyText = keyTextStr,
                    matchedKeyText = matchedKeyText,
                )
                val primary: String
                val alt: String
                if (exp.singleWord == exp.multiWord || exp.multiWord.isEmpty()) {
                    primary = exp.singleWord
                    alt = ""
                } else if (templateValue.isEmpty()) {
                    primary = exp.singleWord
                    alt = exp.multiWord
                } else {
                    val tFp = computeTextFingerprint(templateValue)
                    val swDist = textFingerprintDistance(tFp, computeTextFingerprint(exp.singleWord))
                    val mwDist = textFingerprintDistance(tFp, computeTextFingerprint(exp.multiWord))
                    if (swDist <= mwDist) { primary = exp.singleWord; alt = exp.multiWord }
                    else { primary = exp.multiWord; alt = exp.singleWord }
                }
                pred["text"] = primary
                // Widen the EMITTED geometry to the expansion's token span (rerank
                // still uses the untouched `bbox`; only the final corners/bbox
                // output reads `expanded_bbox`). Fixes returned corners/bbox
                // reflecting the initial word instead of the expanded value.
                val primaryBbox = if (primary == exp.multiWord) exp.multiWordBbox else exp.singleWordBbox
                if (primaryBbox != null) pred["expanded_bbox"] = primaryBbox.toList() else pred.remove("expanded_bbox")
                // Inject the alternative as a top suggestion: same bbox /
                // word_index / confidence so rerank compares fairly.
                if (alt.isNotEmpty()) {
                    val altSugg = HashMap(pred)
                    altSugg["text"] = alt
                    val altBbox = if (alt == exp.multiWord) exp.multiWordBbox else exp.singleWordBbox
                    if (altBbox != null) altSugg["expanded_bbox"] = altBbox.toList() else altSugg.remove("expanded_bbox")
                    altSugg["alt_expansion"] = true
                    suggs.add(0, altSugg)
                }
                // Per-occurrence alternate candidates: only admit alternates
                // whose source bbox OVERLAPS the matcher's predBbox. Alternates
                // from other OCR positions (a different "SKHYNIX" elsewhere)
                // are rejected — their text might match the template but their
                // bbox is wrong.
                val predBboxArr = (pred["bbox"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
                for (occCand in exp.alternateOccurrences) {
                    if (predBboxArr != null && predBboxArr.size == 4 &&
                        !bboxesOverlap(occCand.bbox, predBboxArr.toDoubleArray())
                    ) {
                        continue
                    }
                    val occSugg = HashMap(pred)
                    occSugg["text"] = occCand.text
                    occSugg["bbox"] = occCand.bbox.toList()
                    // Emitted geometry spans the occurrence's whole expansion.
                    occSugg["expanded_bbox"] = occCand.spanBbox.toList()
                    if (occCand.wordIndex >= 0) occSugg["word_index"] = occCand.wordIndex
                    occSugg["alt_expansion"] = true
                    suggs.add(0, occSugg)
                }
                // Key/value-separator strip: when the chosen expansion glued
                // the token to a key prefix via `#`/`:` (e.g. "BAC#200"), also
                // inject the post-separator value ("200") as a candidate.
                val strippedValue = stripKeyValuePrefix(primary)
                if (strippedValue.isNotEmpty() && strippedValue != primary) {
                    val rawSugg = HashMap(pred)
                    rawSugg["text"] = strippedValue
                    rawSugg["alt_expansion"] = true
                    suggs.add(0, rawSugg)
                }
                // Progressive-truncation candidates: when primary is
                // multi-token, inject every whitespace-bounded prefix/suffix so
                // the rerank can pick an intermediate truncation.
                for (truncation in expansionTruncations(primary)) {
                    val truncSugg = HashMap(pred)
                    truncSugg["text"] = truncation
                    truncSugg["alt_expansion"] = true
                    suggs.add(0, truncSugg)
                }
                // Multi-line stitching: only when the template value spans
                // newlines (e.g. "CANADA\nPOST"). expandText is line-bounded,
                // so cascade downward through OCR words below the predicted
                // bbox and inject each progressively-stitched form.
                if (templateValue.contains("\n") && predBboxArr != null && predBboxArr.size == 4) {
                    val maxExtraLines = templateValue.split("\n").size - 1
                    var cursor = predBboxArr.toDoubleArray()
                    var stitched = primary
                    val maxLineGap = 1.5 * mh
                    for (n in 0 until maxExtraLines) {
                        val below = findVerticallyStackedBelow(cursor, wordBoxTuplesUnfilteredScaled, maxLineGap)
                        val next = below.firstOrNull() ?: break
                        stitched += " " + next.text
                        cursor = next.bbox
                        val stitchSugg = HashMap(pred)
                        stitchSugg["text"] = stitched
                        stitchSugg["alt_expansion"] = true
                        suggs.add(0, stitchSugg)
                    }
                }
            }

            // Expand suggestions (single-word only). `suggs.size` is captured
            // once so strip/truncation candidates appended here aren't
            // re-iterated.
            val strippedSuggsToAppend = mutableListOf<MutableMap<String, Any?>>()
            val suggCount = suggs.size
            for (sIdx in 0 until suggCount) {
                val sugg = suggs[sIdx]
                if (sugg["alt_expansion"] == true) continue
                val suggText = sugg["text"]?.toString() ?: ""
                if (suggText.isEmpty() || rawOcrText.isEmpty()) continue
                val wordIdx = (sugg["word_index"] as? Number)?.toInt() ?: 0
                val exp = expandText(
                    predictedWord = suggText,
                    wordIndex = wordIdx,
                    templateValue = templateValue,
                    rawOcrText = rawOcrText,
                    wordBoxTuplesFiltered = wordBoxTuplesScaled,
                    wordBoxTuplesUnfiltered = wordBoxTuplesUnfilteredScaled,
                    keyText = keyTextStr,
                    matchedKeyText = matchedKeyText,
                )
                val expandedText = when {
                    exp.singleWord == exp.multiWord || exp.multiWord.isEmpty() -> exp.singleWord
                    templateValue.isEmpty() -> exp.singleWord
                    else -> {
                        val tFp = computeTextFingerprint(templateValue)
                        val swDist = textFingerprintDistance(tFp, computeTextFingerprint(exp.singleWord))
                        val mwDist = textFingerprintDistance(tFp, computeTextFingerprint(exp.multiWord))
                        if (swDist <= mwDist) exp.singleWord else exp.multiWord
                    }
                }
                sugg["text"] = expandedText
                val expandedBbox = if (expandedText == exp.multiWord) exp.multiWordBbox else exp.singleWordBbox
                if (expandedBbox != null) sugg["expanded_bbox"] = expandedBbox.toList() else sugg.remove("expanded_bbox")
                // Mirror the primary-side key/value strip + truncation so
                // multi-token suggestion expansions also enter the rerank pool.
                val strippedValue = stripKeyValuePrefix(expandedText)
                if (strippedValue.isNotEmpty() && strippedValue != expandedText) {
                    val rawSugg = HashMap(sugg)
                    rawSugg["text"] = strippedValue
                    rawSugg["alt_expansion"] = true
                    strippedSuggsToAppend.add(rawSugg)
                }
                for (truncation in expansionTruncations(expandedText)) {
                    val truncSugg = HashMap(sugg)
                    truncSugg["text"] = truncation
                    truncSugg["alt_expansion"] = true
                    strippedSuggsToAppend.add(truncSugg)
                }
            }
            suggs.addAll(strippedSuggsToAppend)
        }

        // --- Inject barcode suggestions (PRE-rerank) ---
        val tBarcodeAssoc = System.nanoTime()
        // Barcode-derived candidates are seeded into the suggestions pool
        // before rerank so the text-shape stage can pick them as the primary
        // prediction when a barcode's data is a closer match to the template
        // value than the OCR-picked word.
        //
        // Requires BOTH:
        //   (a) one of the field's top-3 candidate texts is a substring of
        //       the barcode's decoded data (length ≥ MIN_SUBSTRING_LEN), AND
        //   (b) the barcode is spatially close to the field's prediction
        //       (centre distance < BARCODE_INJECT_DIST_FACTOR × pred diagonal).
        for (field in predictions.keys.toList()) {
            val pred = predictions[field] ?: continue
            val pbb = (pred["bbox"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: continue
            if (pbb.size != 4 || detectedBarcodes.isEmpty()) continue

            // Collect the field's top-3 candidates as (text, geo_rank) pairs.
            // The injected barcode inherits the geo_rank of whichever candidate
            // text vouched for it — it's only as confident geometrically as the
            // candidate whose text matched. Stamping a hardcoded 0 inflated
            // barcode candidates above their evidence.
            // (text, geo_rank, geo_sum) of the vouching candidate. The injected
            // barcode inherits BOTH the geo_rank AND the geo_sum of whichever
            // candidate text matched it — it lands at that candidate's
            // word_index/bbox, so it is geometrically exactly as strong. Without
            // inheriting geo_sum the barcode candidate would carry geo_sum=0,
            // and `finalizeConfidence` (conf = 0.7·geoSum + 0.3·textScore) would
            // collapse its confidence to ≤0.3 whenever it wins the rerank.
            data class VouchCand(val text: String, val geoRank: Int, val geoSum: Double)
            val topCandidates = mutableListOf<VouchCand>()
            (pred["text"] as? String)?.let { t ->
                if (t.isNotEmpty()) topCandidates.add(
                    VouchCand(t, (pred["geo_rank"] as? Number)?.toInt() ?: 0, (pred["geo_sum"] as? Number)?.toDouble() ?: 0.0)
                )
            }
            for (s in suggestionsMap[field] ?: emptyList()) {
                if (topCandidates.size >= 3) break
                val t = s["text"] as? String ?: continue
                if (t.isNotEmpty() && topCandidates.none { it.text == t }) {
                    topCandidates.add(
                        VouchCand(t, (s["geo_rank"] as? Number)?.toInt() ?: topCandidates.size, (s["geo_sum"] as? Number)?.toDouble() ?: 0.0)
                    )
                }
            }
            if (topCandidates.isEmpty()) continue

            val predCx = (pbb[0] + pbb[2]) / 2.0
            val predCy = (pbb[1] + pbb[3]) / 2.0
            val predW = max(pbb[2] - pbb[0], 1.0)
            val predH = max(pbb[3] - pbb[1], 1.0)
            val predDiag = sqrt(predW * predW + predH * predH)
            val distThreshold = predDiag * BARCODE_INJECT_DIST_FACTOR

            for (bc in detectedBarcodes) {
                if (bc.data.isEmpty()) continue
                val existing = suggestionsMap[field] ?: emptyList()
                if (existing.any { (it["text"] as? String) == bc.data }) continue
                if ((pred["text"] as? String) == bc.data) continue

                // (a) Substring evidence + inherited geo_rank AND geo_sum.
                var matched: VouchCand? = null
                for (cand in topCandidates) {
                    if (cand.text.length >= MIN_SUBSTRING_LEN && cand.text in bc.data) {
                        matched = cand
                        break
                    }
                }
                val injectGeoRank = matched?.geoRank ?: continue
                val injectGeoSum = matched.geoSum

                // (b) Spatial proximity check.
                val bcCx = (bc.bbox[0] + bc.bbox[2]) / 2.0
                val bcCy = (bc.bbox[1] + bc.bbox[3]) / 2.0
                val spatialDist = hypot(predCx - bcCx, predCy - bcCy)
                if (spatialDist > distThreshold) continue

                val bcSugg = mutableMapOf<String, Any?>(
                    "text" to bc.data,
                    "bbox" to pred["bbox"],
                    "word_index" to pred["word_index"],
                    "confidence" to pred["confidence"],
                    "geo_rank" to injectGeoRank,
                    // Inherit the vouching candidate's geo_sum so finalizeConfidence
                    // blends a real geo component (else conf collapses to ≤0.3).
                    "geo_sum" to injectGeoSum,
                    "barcode_suggestion" to true,
                )
                val list = suggestionsMap.getOrPut(field) { mutableListOf() }
                list.add(0, bcSugg)
                break // one barcode injection per field is enough
            }
        }

        // --- Strip leading key-residue from all candidate texts ---
        // Single pass over predictions + suggestions immediately before rerank.
        for (field in predictions.keys.toList()) {
            predictions[field]?.let { pred ->
                val t = pred["text"] as? String
                if (t != null) pred["text"] = stripLeadingKeyResidue(t)
            }
            suggestionsMap[field]?.let { suggs ->
                for (s in suggs) {
                    val t = s["text"] as? String
                    if (t != null) s["text"] = stripLeadingKeyResidue(t)
                }
            }
        }

        // --- Text-shape reranking ---
        val tRerank = System.nanoTime()
        val mutableSuggestions: MutableMap<String, List<MutableMap<String, Any?>>> =
            suggestionsMap.mapValues { it.value.toList() }.toMutableMap()
        val textShapeLog = mutableListOf<String>()
        rerankWithTextShape(
            predictions, mutableSuggestions, fieldTexts,
            rerankWeights = processedTemplate.rerankWeights,
            // Per-field template barcode value, so barcode-sourced candidates
            // are reranked barcode-vs-barcode instead of against the OCR text.
            fieldBarcodeValues = fieldBarcodeAssoc.mapValues { it.value.barcodeData },
            disableGeoDominance = processedTemplate.disableGeoDominance,
            debugLog = textShapeLog,
        )

        // --- Text-score-based dedup ---
        // Per-strategy geometric dedup is removed (see Matching). When two
        // fields land on the same OCR word as their top-1, the field with the
        // smaller text_dist (better text-shape match) keeps it; losers fall
        // back to their next-best rerank suggestion.
        val mutableSuggestionsForDedup: MutableMap<String, List<MutableMap<String, Any?>>> =
            mutableSuggestions.toMutableMap()
        dedupByTextScore(predictions, mutableSuggestionsForDedup)
        for ((k, v) in mutableSuggestionsForDedup) mutableSuggestions[k] = v

        // --- Final confidence blend ---
        // confidence = 0.7·(geo_sum / maxGeoSum) + 0.3·text_score.
        // maxGeoSum is the rank-0-sum across the matchers this template
        // actually has, so a KV-only template (maxGeoSum = 0.45) still reads a
        // perfect prediction as 1.0 rather than 0.45.
        // Barcode-association bonus and the 1.0 cap are applied below.
        val maxGeoSum =
            (if (templateVectors.isEmpty()) 0.0 else PAIRWISE_RANK_WEIGHTS[0]) +
                (if (templateAnchorVecs.isEmpty()) 0.0 else ANCHOR_RANK_WEIGHTS[0]) +
                (if (templateBcVecs.isEmpty()) 0.0 else BARCODE_RANK_WEIGHTS[0]) +
                (if (templateKvVecs.isEmpty()) 0.0 else KV_RANK_WEIGHTS[0])
        finalizeConfidence(predictions, mutableSuggestions, maxGeoSum)

        // --- Barcode containment boost & datatype enforcement ---
        val bcMatchLog = mutableListOf<String>()
        bcMatchLog.add("fields_with_bc_assoc: ${fieldBarcodeAssoc.keys}")
        bcMatchLog.add("detected_barcodes: ${detectedBarcodes.size} (with bounds, rotated)")
        bcMatchLog.add("raw_barcodes: ${barcodeResult.barcodes.size} (total from frame)")
        bcMatchLog.add("templateBcVecs keys: ${templateBcVecs.keys}")
        bcMatchLog.add("frame: ${frameWidth}x${frameHeight}, rotation=${rotationDegrees}")

        for (field in predictions.keys.toList()) {
            val pred = predictions[field] ?: continue
            val predText = pred["text"]?.toString() ?: ""

            // Barcode-association bonus: if a detected barcode's data contains
            // the predicted text AND the barcode-to-field relation vector
            // matches the template's stored association vector, this
            // prediction earns +BARCODE_ASSOCIATION_BONUS confidence (capped
            // at 1.0 below). Detection logic is unchanged from the legacy
            // boost; only the magnitude differs.
            if (predText.isNotEmpty() && field in fieldBarcodeAssoc) {
                val assoc = fieldBarcodeAssoc[field]!!
                val templateVec = (if (useAvg) assoc.vectorAvg else assoc.vector).toDoubleArray()
                val predBbox = (pred["bbox"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.toDoubleArray()
                if (predBbox != null && predBbox.size == 4 && templateVec.isNotEmpty()) {
                    // Geometry is the reliable association: does a barcode sit
                    // where the template's barcode sat relative to this field's
                    // value? Gate on that BEFORE trusting the payload —
                    // independent of whether the OCR text matches. A shipping
                    // label routinely carries two barcodes both within
                    // BARCODE_BOOST_DIST_THRESHOLD (e.g. tracking + SKU), so
                    // collect EVERY geometry-passing barcode rather than
                    // stopping at the first — otherwise a leading non-matching
                    // barcode would mask a later one that exact-matches.
                    val geoMatched = detectedBarcodes.filter { bc ->
                        if (bc.data.isEmpty()) return@filter false
                        val targetVec = if (useAvg) {
                            computeRelationVectorBarcodeWidth(bc.bbox, predBbox, pageWidth, pageHeight, heightScale)
                        } else {
                            computeRelationVector(bc.bbox, predBbox, mh, pageWidth, pageHeight, heightScale)
                        }
                        weightedL2(templateVec, targetVec) < BARCODE_BOOST_DIST_THRESHOLD
                    }

                    val conf = (pred["confidence"] as? Number)?.toDouble() ?: 0.0
                    val exact = geoMatched.firstOrNull { predText in it.data }
                    if (exact != null) {
                        // OCR already agrees with a geo-matched barcode — boost
                        // only. Prefer this over any snap (exact substring is
                        // strictly more reliable than a confusable alignment).
                        pred["confidence"] = conf + BARCODE_ASSOCIATION_BONUS
                        pred["barcode_match"] = true
                    } else if (predText.length >= MIN_SUBSTRING_LEN) {
                        // OCR disagrees with every geometrically-matched barcode.
                        // The barcode is error-corrected ground truth, so align
                        // the OCR value to the payload (AI prefix skipped) with a
                        // confusable-aware distance and snap when close — fixes
                        // misreads like "1AVWIS&" → "1AVVIS8" that strict
                        // substring matching can't.
                        //
                        // Length gate (>= MIN_SUBSTRING_LEN): short values like
                        // "A0"/"A2"/"1" will always find a spurious near-match
                        // inside a long payload (e.g. "A0" snapped to the "A80"
                        // inside a Part Number barcode "AFF-A800A^33977237"), so
                        // the barcode carries no reliable signal for them —
                        // there's nothing to validate against. Mirrors the same
                        // floor used by barcode-suggestion injection above.
                        for (bc in geoMatched) {
                            val aligned = bestConfusableAlignedSubstring(bc.data, predText)
                            if (aligned != null && aligned.second <= BARCODE_SNAP_NORM_THRESHOLD) {
                                bcMatchLog.add(
                                    "[$field] barcode-snap \"$predText\" -> \"${aligned.first}\" " +
                                        "(norm=${"%.3f".format(java.util.Locale.US, aligned.second)}, payload=\"${bc.data}\")"
                                )
                                pred["text"] = aligned.first
                                pred["confidence"] = conf + BARCODE_ASSOCIATION_BONUS
                                pred["barcode_match"] = true
                                pred["barcode_corrected"] = true
                                break
                            }
                        }
                    }
                }
            }

            // Predefined-datatype enforcement: when the field has a
            // user-declared datatype and the prediction doesn't match it,
            // swap in the highest-ranked suggestion that does. The
            // datatype-inferred boost path is gone — the rank-decay model
            // already expresses text-shape agreement.
            if (predText.isNotEmpty() && field in fieldDatatypes) {
                val dtInfo = fieldDatatypes[field]!!
                if (dtInfo.source == "predefined") {
                    val dt = dtInfo.type
                    if (!matchesDatatype(predText, dt)) {
                        val fieldSuggs = mutableSuggestions[field]?.toMutableList() ?: mutableListOf()
                        var replacement: MutableMap<String, Any?>? = null
                        val remainingSuggs = mutableListOf<MutableMap<String, Any?>>()
                        for (s in fieldSuggs) {
                            val sText = s["text"]?.toString() ?: ""
                            if (replacement == null && sText.isNotEmpty() && matchesDatatype(sText, dt)) {
                                replacement = s
                            } else {
                                remainingSuggs.add(s)
                            }
                        }
                        if (replacement != null) {
                            val replConf = (replacement["confidence"] as? Number)?.toDouble() ?: 0.0
                            replacement["confidence"] = min(1.0, replConf)
                            remainingSuggs.add(0, HashMap(pred))
                            predictions[field] = replacement
                            mutableSuggestions[field] = remainingSuggs.take(2)
                            continue
                        } else {
                            remainingSuggs.add(0, HashMap(pred))
                            predictions[field] = mutableMapOf(
                                "text" to "",
                                "bbox" to emptyList<Float>(),
                                "word_index" to -1,
                                "confidence" to 0.0,
                            )
                            mutableSuggestions[field] = remainingSuggs
                            continue
                        }
                    }
                }
            }

            // Final 1.0 cap after all confidence adjustments.
            val finalConf = (pred["confidence"] as? Number)?.toDouble() ?: 0.0
            pred["confidence"] = min(1.0, finalConf)
        }

        // --- Dedup suggestions by normalized text ---
        // After all manipulation, collapse to one entry per unique text per
        // field. Case- and whitespace-insensitive so "JK377" / "jk377" /
        // " JK377 " all merge. Seeds with the prediction text so suggestions
        // that just echo the winner are dropped.
        for (field in mutableSuggestions.keys.toList()) {
            val suggs = mutableSuggestions[field] ?: continue
            val seen = mutableSetOf<String>()
            (predictions[field]?.get("text") as? String)?.let {
                val norm = it.trim().lowercase()
                if (norm.isNotEmpty()) seen.add(norm)
            }
            val deduped = mutableListOf<MutableMap<String, Any?>>()
            for (s in suggs) {
                val t = s["text"]?.toString() ?: ""
                val norm = t.trim().lowercase()
                if (norm.isEmpty()) { deduped.add(s); continue }
                if (norm in seen) continue
                seen.add(norm)
                deduped.add(s)
            }
            mutableSuggestions[field] = deduped
        }

        val tShiftStart = System.nanoTime()
        // Bring all prediction/suggestion bboxes back to the engine's INPUT
        // frame (upright space — what the extractors emitted and what the UI's
        // captured bitmap is rendered in) so the UI can overlay them directly.
        // Three reverse transforms — the ORDER matters: the forward pass
        // deskewed FIRST (around the pre-crop centroid skewCx/skewCy) and THEN
        // cropped, i.e. matched = R(orig) − (minX,minY). The exact inverse is
        // orig = R⁻¹(matched + (minX,minY)), so we must UN-CROP BEFORE
        // UN-DESKEWING. Un-deskewing the still-cropped coords (the old order)
        // rotates them around the pre-crop centroid while offset by (minX,minY),
        // leaving a constant (Rot(skew) − I)·(minX,minY) error — negligible near
        // 0° skew but hundreds of px at a large tilt (~305 px at 22°).
        //   1. Un-crop    — add back (minX, minY).
        //   2. Un-deskew  — rotate by +skewAngle around the pre-crop skew center.
        //   3. Un-gross-rotate — rotate by 360-appliedGrossRotation in the
        //      post-gross frame dims (imgW × imgH), undoing the 90/180/270°
        //      rotation applied at the top of predict().
        val inverseGrossRotation = if (appliedGrossRotation == 0) 0 else (360 - appliedGrossRotation) % 360
        fun backToInputFrame(bbox: List<Double>): List<Double> {
            var b = bbox.toDoubleArray()
            b = doubleArrayOf(b[0] + minX, b[1] + minY, b[2] + minX, b[3] + minY)
            if (skewAngle != 0.0) {
                b = rotateBbox(b, skewAngle, skewCx, skewCy)
            }
            if (inverseGrossRotation != 0) {
                b = rotateBboxAxisAligned(b, inverseGrossRotation, imgW, imgH)
            }
            return b.toList()
        }

        // Same three reverse transforms, but carried on the box's FOUR CORNERS
        // and NEVER re-bounded to an axis-aligned box. On a skewed/rotated
        // capture, rotating an AABB and re-taking the AABB inflates it (a 22°
        // tilt blows a 42×21 box up to 47×35); keeping the corners as a quad
        // stays tight and sits on the real (tilted) word. Returns [[x,y]×4]
        // (TL, TR, BR, BL) in the upright INPUT frame, ready to draw directly.
        fun backToInputFrameCorners(bbox: List<Double>): List<List<Double>> {
            val x1 = bbox[0]; val y1 = bbox[1]; val x2 = bbox[2]; val y2 = bbox[3]
            var pts = listOf(
                doubleArrayOf(x1, y1), doubleArrayOf(x2, y1),
                doubleArrayOf(x2, y2), doubleArrayOf(x1, y2),
            )
            // Un-crop before un-deskew (see backToInputFrame for why the order
            // matters — the forward pass was deskew-then-crop).
            pts = pts.map { doubleArrayOf(it[0] + minX, it[1] + minY) }
            if (skewAngle != 0.0) {
                pts = pts.map { val (rx, ry) = rotatePoint(it[0], it[1], skewAngle, skewCx, skewCy); doubleArrayOf(rx, ry) }
            }
            if (inverseGrossRotation != 0) {
                pts = pts.map { p ->
                    val x = p[0]; val y = p[1]
                    when (inverseGrossRotation) {
                        90 -> doubleArrayOf(imgH - y, x)
                        180 -> doubleArrayOf(imgW - x, imgH - y)
                        270 -> doubleArrayOf(y, imgW - x)
                        else -> p
                    }
                }
            }
            return pts.map { listOf(it[0], it[1]) }
        }

        // Emit geometry from `expanded_bbox` (the expansion's token span) when
        // present, else the per-word `bbox`, so an expanded value reports the box
        // spanning its tokens; corners follow (derived from the same box).
        fun srcBbox(d: Map<String, Any?>): List<Double>? {
            val exp = (d["expanded_bbox"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
            if (exp != null && exp.size == 4) return exp
            return (d["bbox"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
        }
        for (field in predictions.keys.toList()) {
            val pred = predictions[field] ?: continue
            val bbox = srcBbox(pred)
            if (bbox != null && bbox.size == 4) {
                pred["corners"] = backToInputFrameCorners(bbox)
                pred["bbox"] = backToInputFrame(bbox)
                pred.remove("expanded_bbox")   // internal only
            }
        }
        for ((_, suggs) in mutableSuggestions) {
            for (s in suggs) {
                val bbox = srcBbox(s)
                if (bbox != null && bbox.size == 4) {
                    s["corners"] = backToInputFrameCorners(bbox)
                    s["bbox"] = backToInputFrame(bbox)
                    s.remove("expanded_bbox")
                }
            }
        }

        // --- Convert to PredictionResponse ---
        fun cornersF(d: Map<String, Any?>): List<List<Float>>? {
            val c = d["corners"] as? List<*> ?: return null
            val pts = c.mapNotNull { pt ->
                (pt as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { it.size == 2 }
            }
            return pts.takeIf { it.size == 4 }
        }
        val finalPredictions = mutableMapOf<String, PredictedField>()
        for ((field, pred) in predictions) {
            val bbox = (pred["bbox"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
            finalPredictions[field] = PredictedField(
                text = pred["text"]?.toString() ?: "",
                bbox = bbox,
                corners = cornersF(pred),
                confidence = (pred["confidence"] as? Number)?.toFloat() ?: 0f,
                geoRank = (pred["geo_rank"] as? Number)?.toInt(),
                barcodeMatch = pred["barcode_match"] as? Boolean,
                datatypeMatch = pred["datatype_match"] as? Boolean,
            )
        }
        val finalSuggestions = mutableMapOf<String, List<PredictedField>>()
        for ((field, suggs) in mutableSuggestions) {
            finalSuggestions[field] = suggs.map { s ->
                val bbox = (s["bbox"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
                PredictedField(
                    text = s["text"]?.toString() ?: "",
                    bbox = bbox,
                    corners = cornersF(s),
                    confidence = (s["confidence"] as? Number)?.toFloat() ?: 0f,
                    geoRank = (s["geo_rank"] as? Number)?.toInt(),
                )
            }
        }

        lastOcrResult = ocrResult
        lastRawOcrText = rawOcrText
        lastBarcodeMatchLog = bcMatchLog
        lastTextShapeLog = textShapeLog
        // Build the debug barcode list directly from rotatedBarcodes (1:1 with
        // each detection that has a drawable box). Do NOT look the box up by
        // `bc.data`: two barcodes can share a payload (e.g. a QR + a CODE128
        // both encoding the same reference number), which collapses a
        // data-keyed map to one entry and makes every detection render at the
        // last-wins box — the QR then draws on top of the CODE128 (hidden) with
        // a completely wrong y. rotatedBarcodes already carries each box.
        lastDetectedBarcodes = rotatedBarcodes.map { rb ->
            DebugBarcode(
                data = rb.bc.data,
                format = rb.bc.format,
                bbox = listOf(rb.displayBbox[0], rb.displayBbox[1], rb.displayBbox[2], rb.displayBbox[3]),
                corners = rb.bc.corners?.map { listOf(it.first.toDouble(), it.second.toDouble()) },
            )
        }

        // --- Assemble debug report ---
        val tDebugReport = System.nanoTime()
        if (debugCollector != null) {
            @Suppress("UNCHECKED_CAST")
            val preprocessing = debugCollector["preprocessing"] as? Map<String, Any?> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val keyExcluded = debugCollector["key_excluded_indices"] as? List<Int> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val matcherCandidates = debugCollector["matcher_candidates"] as? Map<String, Map<String, List<List<Any>>>> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val matcherCandidatesAfterDedup = debugCollector["matcher_candidates_after_dedup"] as? Map<String, Map<String, List<List<Any>>>> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val rrfScoresRaw = debugCollector["rrf_scores"] as? Map<String, List<Map<String, Any>>> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val predsBeforeExpansion = debugCollector["predictions_before_expansion"] as? Map<String, Map<String, Any?>> ?: emptyMap()

            fun convertCandidates(raw: Map<String, Map<String, List<List<Any>>>>): Map<String, Map<String, List<CandidateEntry>>> {
                return raw.mapValues { (_, fields) ->
                    fields.mapValues { (_, entries) ->
                        entries.map { CandidateEntry((it[0] as Number).toInt(), (it[1] as Number).toDouble()) }
                    }
                }
            }

            lastDebugReport = DebugReport(
                templateId = "",
                input = InputSection(
                    wordCount = wordBoxTuplesScaled.size,
                    wordCountUnfiltered = wordBoxTuplesUnfiltered.size,
                    barcodeCount = detectedBarcodes.size,
                    wordBoxTuples = serializeWordBoxTuples(wordBoxTuplesScaled),
                    detectedBarcodes = detectedBarcodes.map { bc ->
                        DebugBarcode(data = bc.data, format = bc.typeId, bbox = bc.bbox.toList())
                    },
                ),
                preprocessing = PreprocessingSection(
                    filteredWordCount = (preprocessing["filtered_word_count"] as? Number)?.toInt() ?: wordBoxTuplesScaled.size,
                    minX = (preprocessing["min_x"] as? Number)?.toDouble() ?: minX,
                    minY = (preprocessing["min_y"] as? Number)?.toDouble() ?: minY,
                    pageWidth = (preprocessing["page_width"] as? Number)?.toDouble() ?: pageWidth,
                    pageHeight = (preprocessing["page_height"] as? Number)?.toDouble() ?: pageHeight,
                    medianHeight = (preprocessing["median_height"] as? Number)?.toDouble() ?: mh,
                    skewAngle = (preprocessing["skew_angle"] as? Number)?.toDouble() ?: 0.0,
                    appliedGrossRotationDegrees = appliedGrossRotation,
                    detectedOrientationDegrees = detectedOrientationAngle,
                    coarseOrientationDegrees = detectedCoarseDegrees,
                    fineResidualDegrees = detectedFineDegrees,
                    anchorWords = @Suppress("UNCHECKED_CAST") (preprocessing["anchor_words"] as? List<Map<String, Any>>) ?: emptyList(),
                    keyExcludedIndices = keyExcluded,
                ),
                matcherCandidates = convertCandidates(matcherCandidates),
                matcherCandidatesAfterDedup = convertCandidates(matcherCandidatesAfterDedup),
                rrfScores = rrfScoresRaw.mapValues { (_, entries) ->
                    entries.map { RrfEntry(
                        wordIndex = (it["word_index"] as Number).toInt(),
                        rrfScore = (it["rrf_score"] as Number).toDouble(),
                        nVotes = (it["n_votes"] as Number).toInt(),
                    ) }
                },
                predictionsBeforeExpansion = predsBeforeExpansion,
                finalPredictions = finalPredictions.mapValues { (_, pf) ->
                    // pf.bbox / pf.corners are already in the upright input frame
                    // (un-deskewed, un-cropped, un-gross-rotated), drawable
                    // directly. corners is the tight quad; bbox the inflated AABB.
                    mapOf("text" to pf.text, "bbox" to pf.bbox, "corners" to pf.corners, "confidence" to pf.confidence)
                },
                primaryKeyMap = templatePrimaryKeyMap,
                keyAliases = processedTemplate.keyAliases,
                predictionOnlyFields = processedTemplate.predictionOnlyFields,
                barcodeReconstruction = @Suppress("UNCHECKED_CAST")
                    (debugCollector["barcodeReconstruction"] as? Map<String, Map<String, Any?>>) ?: emptyMap(),
            )
        } else {
            lastDebugReport = null
        }

        val tEnd = System.nanoTime()
        val totalMs = (tEnd - tStart) / 1_000_000.0
        fun ms(a: Long, b: Long) = (b - a) / 1_000_000.0
        val fusedTimings = if (fusedTimingsMap.isNotEmpty()) {
            FusedTimings(
                keyExclusionMs = fusedTimingsMap["keyExclusionMs"] ?: 0.0,
                matchPairwiseMs = fusedTimingsMap["matchPairwiseMs"] ?: 0.0,
                matchAnchorMs = fusedTimingsMap["matchAnchorMs"] ?: 0.0,
                matchBarcodeMs = fusedTimingsMap["matchBarcodeMs"] ?: 0.0,
                matchKvMs = fusedTimingsMap["matchKvMs"] ?: 0.0,
                rrfAndAssignmentMs = fusedTimingsMap["rrfAndAssignmentMs"] ?: 0.0,
                kvSetupMs = fusedTimingsMap["kvSetupMs"] ?: 0.0,
                kvResolveMs = fusedTimingsMap["kvResolveMs"] ?: 0.0,
                kvExclusionMs = fusedTimingsMap["kvExclusionMs"] ?: 0.0,
                kvRelVecMs = fusedTimingsMap["kvRelVecMs"] ?: 0.0,
                kvPassCount = (fusedTimingsMap["kvPassCount"] ?: 0.0).toInt(),
                kvExcludedBoxes = (fusedTimingsMap["kvExcludedBoxes"] ?: 0.0).toInt(),
                kvWords = (fusedTimingsMap["kvWords"] ?: 0.0).toInt(),
                kvFindAllCalls = (fusedTimingsMap["kvFindAllCalls"] ?: 0.0).toInt(),
                pwBuildMs = fusedTimingsMap["pwBuildMs"] ?: 0.0,
                pwSortMs = fusedTimingsMap["pwSortMs"] ?: 0.0,
                pwAssignMs = fusedTimingsMap["pwAssignMs"] ?: 0.0,
                pwPairs = (fusedTimingsMap["pwPairs"] ?: 0.0).toInt(),
                pwWords = (fusedTimingsMap["pwWords"] ?: 0.0).toInt(),
            )
        } else null
        lastTimings = PredictionTimings(
            totalMs = totalMs,
            preprocessMs = ms(tStart, tWordVec),
            wordVectorsMs = ms(tWordVec, tAnchorBc),
            anchorAndBarcodePrepMs = ms(tAnchorBc, tFusedStart),
            fusedPredictionMs = ms(tFusedStart, tFusedEnd),
            textExpansionMs = ms(tFusedEnd, tBarcodeAssoc),
            barcodeAssocMs = ms(tBarcodeAssoc, tRerank),
            rerankAndDedupMs = ms(tRerank, tShiftStart),
            postprocessMs = ms(tShiftStart, tDebugReport),
            debugReportMs = ms(tDebugReport, tEnd),
            fused = fusedTimings,
        )
        Log.d(TAG, "Prediction complete in ${totalMs.toLong()}ms: ${finalPredictions.size} fields")
        lastTimings?.let { t ->
            fun i(x: Double) = x.toLong()
            Log.d(TAG, "  preprocess=${i(t.preprocessMs)}ms  wordVecs=${i(t.wordVectorsMs)}ms  " +
                "anchorPrep=${i(t.anchorAndBarcodePrepMs)}ms  fused=${i(t.fusedPredictionMs)}ms")
            Log.d(TAG, "  textExpand=${i(t.textExpansionMs)}ms  rerank=${i(t.rerankAndDedupMs)}ms  " +
                "bcAssoc=${i(t.barcodeAssocMs)}ms  post=${i(t.postprocessMs)}ms  debugReport=${i(t.debugReportMs)}ms")
            t.fused?.let { f ->
                Log.d(TAG, "  fused>  keyExcl=${i(f.keyExclusionMs)}ms  pairwise=${i(f.matchPairwiseMs)}ms  " +
                    "anchor=${i(f.matchAnchorMs)}ms  barcode=${i(f.matchBarcodeMs)}ms  " +
                    "kv=${i(f.matchKvMs)}ms  rrf+assign=${i(f.rrfAndAssignmentMs)}ms")
                Log.d(TAG, "  kv>  setup=${i(f.kvSetupMs)}ms  resolve=${i(f.kvResolveMs)}ms  " +
                    "exclusion=${i(f.kvExclusionMs)}ms  relVec+L2=${i(f.kvRelVecMs)}ms")
                Log.d(TAG, "  kv>  passes=${f.kvPassCount}  excludedBoxes=${f.kvExcludedBoxes}  " +
                    "words=${f.kvWords}  findAllKeyWordsCalls=${f.kvFindAllCalls}")
                Log.d(TAG, "  pw>  build=${i(f.pwBuildMs)}ms  sort=${i(f.pwSortMs)}ms  " +
                    "assign=${i(f.pwAssignMs)}ms  pairs=${f.pwPairs}  words=${f.pwWords}")
            }
        }

        return PredictionResponse(
            predictions = finalPredictions,
            suggestions = finalSuggestions,
            // Carry the template's prediction-only (scoring-only) field keys so the
            // SDK result mapper can filter them out of the customer-facing result —
            // matches iOS (which sets this on the returned response, not just debug).
            predictionOnlyFields = processedTemplate.predictionOnlyFields.toList(),
        )
    }

    fun buildDebugExport(templateId: String): Map<String, Any?> {
        val frameImageB64: String? = lastFrameBitmap?.let { bmp ->
            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
        val ocrBlocks = lastOcrResult?.blocks?.map { block ->
            mapOf(
                "text" to block.text,
                "confidence" to block.confidence,
                "bbox" to block.boundingBox?.let { listOf(it.left, it.top, it.right, it.bottom) },
                "lines" to block.lines.map { line ->
                    mapOf(
                        "text" to line.text,
                        "bbox" to line.boundingBox?.let { listOf(it.left, it.top, it.right, it.bottom) },
                        "words" to line.words.map { word ->
                            mapOf(
                                "text" to word.text,
                                "confidence" to word.confidence,
                                "bbox" to word.boundingBox?.let { listOf(it.left, it.top, it.right, it.bottom) },
                                // Per-word corner quad (as ML Kit returned it,
                                // before any gross-rotation/deskew). Null here
                                // means no usable corners — the case that makes
                                // a word fall back to the AABB-only path and get
                                // axis-swapped to "tall" under a gross rotation.
                                // `cornerAngle` is the corners[0]→corners[1] top-
                                // edge angle the orientation detector votes with,
                                // so it can be compared against the bbox aspect.
                                "cornerPoints" to word.cornerPoints?.map { listOf(it.first, it.second) },
                                "cornerAngle" to word.cornerPoints?.let { angleFromCornerPoints(it) },
                            )
                        },
                    )
                },
            )
        } ?: emptyList<Map<String, Any?>>()

        val report = lastDebugReport
        val debugReportMap: Map<String, Any?>? = report?.let {
            mapOf(
                "templateId" to it.templateId,
                "input" to mapOf(
                    "wordCount" to it.input.wordCount,
                    "wordCountUnfiltered" to it.input.wordCountUnfiltered,
                    "barcodeCount" to it.input.barcodeCount,
                    "wordBoxTuples" to it.input.wordBoxTuples,
                    "detectedBarcodes" to it.input.detectedBarcodes.map { bc ->
                        mapOf("data" to bc.data, "format" to bc.format, "bbox" to bc.bbox)
                    },
                ),
                "preprocessing" to mapOf(
                    "filteredWordCount" to it.preprocessing.filteredWordCount,
                    "minX" to it.preprocessing.minX,
                    "minY" to it.preprocessing.minY,
                    "pageWidth" to it.preprocessing.pageWidth,
                    "pageHeight" to it.preprocessing.pageHeight,
                    "medianHeight" to it.preprocessing.medianHeight,
                    "skewAngle" to it.preprocessing.skewAngle,
                    "appliedGrossRotationDegrees" to it.preprocessing.appliedGrossRotationDegrees,
                    "detectedOrientationDegrees" to it.preprocessing.detectedOrientationDegrees,
                    "coarseOrientationDegrees" to it.preprocessing.coarseOrientationDegrees,
                    "fineResidualDegrees" to it.preprocessing.fineResidualDegrees,
                    "anchorWords" to it.preprocessing.anchorWords,
                    "keyExcludedIndices" to it.preprocessing.keyExcludedIndices,
                ),
                "matcherCandidates" to it.matcherCandidates.mapValues { (_, fields) ->
                    fields.mapValues { (_, entries) ->
                        entries.map { e -> mapOf("wordIndex" to e.wordIndex, "distance" to e.distance) }
                    }
                },
                "matcherCandidatesAfterDedup" to it.matcherCandidatesAfterDedup.mapValues { (_, fields) ->
                    fields.mapValues { (_, entries) ->
                        entries.map { e -> mapOf("wordIndex" to e.wordIndex, "distance" to e.distance) }
                    }
                },
                "rrfScores" to it.rrfScores.mapValues { (_, entries) ->
                    entries.map { e -> mapOf("wordIndex" to e.wordIndex, "rrfScore" to e.rrfScore, "nVotes" to e.nVotes) }
                },
                "predictionsBeforeExpansion" to it.predictionsBeforeExpansion,
                "finalPredictions" to it.finalPredictions,
                // Per-barcode vertical-reconstruction trace (tier, detected vs
                // effective bbox, stored/detected aspect), keyed by template
                // type_id. Drives the amber centre-y overlay line; previously
                // omitted from the export so the line couldn't be diagnosed.
                "barcodeReconstruction" to it.barcodeReconstruction,
            )
        }

        val export = mapOf(
            "templateId" to templateId,
            "frameImageB64" to frameImageB64,
            "debugReport" to debugReportMap,
            "rawOcrText" to lastRawOcrText,
            "ocrBlocks" to ocrBlocks,
            "detectedBarcodes" to lastDetectedBarcodes.map { bc ->
                // Include the oriented corner quad (TL,TR,BR,BL): the overlay
                // prefers it over the AABB, so it must be exported to diagnose
                // the on-device barcode box shape.
                mapOf("data" to bc.data, "format" to bc.format, "bbox" to bc.bbox, "corners" to bc.corners)
            },
            "barcodeMatchLog" to lastBarcodeMatchLog,
            "textShapeLog" to lastTextShapeLog,
        )
        @Suppress("UNCHECKED_CAST")
        return sanitizeNaN(export) as Map<String, Any?>
    }

    private fun sanitizeNaN(value: Any?): Any? = when (value) {
        is Double -> if (value.isNaN() || value.isInfinite()) 0.0 else value
        is Float -> if (value.isNaN() || value.isInfinite()) 0f else value
        is Map<*, *> -> value.mapValues { sanitizeNaN(it.value) }
        is List<*> -> value.map { sanitizeNaN(it) }
        else -> value
    }

    fun storeFrameBitmap(yBytes: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        try {
            val pixels = IntArray(width * height)
            for (i in pixels.indices) {
                val y = yBytes[i].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
            }
            var bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }
            lastFrameBitmap = bmp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create frame bitmap", e)
            lastFrameBitmap = null
        }
    }

    /**
     * Remove OCR words baked into the template as stop words so they're never
     * matched as a field value, anchor, or KV element. Matching is trimmed and
     * case-insensitive, mirroring `_normalize_stop_word` in api/pipeline.py.
     * No-op when the template carries no stop words.
     */
    private fun filterStopWords(wordBoxTuples: List<WordBox>, stopWords: List<String>): List<WordBox> {
        if (stopWords.isEmpty()) return wordBoxTuples
        // Normalize a stop word / OCR token to a comparison key: lowercase, then
        // strip SURROUNDING punctuation. Word segmentation drops wrapping
        // punctuation from OCR tokens (label "(30P)" → token "30P"), so a stop
        // word entered as "(30P)" must reduce to the same key. Also split each
        // entry into tokens on whitespace + '×' so a blob "(4L)× (30P)× …" stored
        // as one entry blocks each token. Mirrors _normalize_stop_word /
        // _stop_word_tokens in api/pipeline.py + iOS.
        fun norm(s: String) = s.trim().lowercase().trim { !it.isLetterOrDigit() }
        val blocked = stopWords
            .flatMap { it.split(STOP_WORD_TOKEN_SPLIT) }
            .map { norm(it) }
            .filter { it.isNotEmpty() }
            .toHashSet()
        if (blocked.isEmpty()) return wordBoxTuples
        return wordBoxTuples.filter { norm(it.text) !in blocked }
    }
}
