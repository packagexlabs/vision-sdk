package io.packagex.texttemplates.prediction

/** Per-phase wall-clock breakdown of one predict() call, in milliseconds.
 *  Surfaced in the debug screen (mirrors the iOS Timings section). */
internal data class PredictionTimings(
    val totalMs: Double,
    val preprocessMs: Double,            // OCR→wordbox, gross rotation, deskew, outlier filter, scale, median height
    val wordVectorsMs: Double,           // computeWordVectorsPolar(Avg)
    val anchorAndBarcodePrepMs: Double,  // findAnchorWords + barcode rotation/conversion
    val fusedPredictionMs: Double,       // matchPairwise + matchAnchor + matchBarcode + matchKv + RRF + assignment
    val textExpansionMs: Double,         // expand single/multi word + alt injection
    val barcodeAssocMs: Double,          // barcode suggestion injection + datatype enforcement + key-residue strip
    val rerankAndDedupMs: Double,        // rerankWithTextShape + dedupByTextScore
    val postprocessMs: Double,           // bbox shift-back + build response
    val debugReportMs: Double,           // assemble debug report (≈0 when debug disabled)
    val fused: FusedTimings? = null,     // per-matcher sub-breakdown of fusedPredictionMs
)

/** Sub-breakdown of the fused-prediction phase (mirrors iOS FusedTimings). */
internal data class FusedTimings(
    val keyExclusionMs: Double,
    val matchPairwiseMs: Double,
    val matchAnchorMs: Double,
    val matchBarcodeMs: Double,
    val matchKvMs: Double,
    val rrfAndAssignmentMs: Double,
    // TEMP(kv-profiling): matchKv internal split.
    val kvSetupMs: Double = 0.0,
    val kvResolveMs: Double = 0.0,
    val kvExclusionMs: Double = 0.0,
    val kvRelVecMs: Double = 0.0,
    val kvPassCount: Int = 0,
    val kvExcludedBoxes: Int = 0,
    val kvWords: Int = 0,
    val kvFindAllCalls: Int = 0,
    // TEMP(pairwise-profiling): matchPairwise phase split.
    val pwBuildMs: Double = 0.0,
    val pwSortMs: Double = 0.0,
    val pwAssignMs: Double = 0.0,
    val pwPairs: Int = 0,
    val pwWords: Int = 0,
)

internal data class DebugReport(
    val source: String = "android",
    val templateId: String,
    val input: InputSection,
    val preprocessing: PreprocessingSection,
    val matcherCandidates: Map<String, Map<String, List<CandidateEntry>>>,
    val matcherCandidatesAfterDedup: Map<String, Map<String, List<CandidateEntry>>>,
    val rrfScores: Map<String, List<RrfEntry>>,
    val predictionsBeforeExpansion: Map<String, Map<String, Any?>>,
    val finalPredictions: Map<String, Map<String, Any?>>,
    // Field → primary-key text registered on the template (the label the
    // kv-matcher uses to locate this field's value). Empty when no key was
    // associated. Mirrors iOS DebugReport.primaryKeyMap.
    val primaryKeyMap: Map<String, String> = emptyMap(),
    // Field → look-alike / synonym alternate keys (`key_aliases`). The
    // kv-matcher falls back to one of these when the primary key isn't found
    // in the scan. Surfaced so post-scan inspection shows the alternates that
    // were available (e.g. Dealer Code "DLR" → ["Dealer", "Dealer No"]).
    val keyAliases: Map<String, List<String>> = emptyMap(),
    // Field labels scored/geometry-only and hidden from the results screen
    // (prediction-only). Debug shows every field but flags these; demo omits
    // them. Mirrors iOS DebugReport.predictionOnlyFields.
    val predictionOnlyFields: Set<String> = emptySet(),
    // Per-barcode-type vertical-reconstruction trace. typeId → {tier,
    // detected_bbox, effective_bbox, stored_aspect, detected_aspect}. "tier1"
    // = detected box trusted; "tier2" = centre relocated from anchors;
    // "tier3"/"tier1_no_aspect" = detected box used (no/failed reconstruction).
    // Empty when no barcodes matched or debug disabled. Android-only feature.
    val barcodeReconstruction: Map<String, Map<String, Any?>> = emptyMap(),
)

internal data class InputSection(
    val wordCount: Int,
    val wordCountUnfiltered: Int,
    val barcodeCount: Int,
    val wordBoxTuples: List<List<Any>>,
    val detectedBarcodes: List<DebugBarcode> = emptyList(),
)

internal data class DebugBarcode(
    val data: String,
    val format: String,
    val bbox: List<Double>? = null,
    /** Oriented quad corners [TL, TR, BR, BL]; each inner list is [x, y] in the
     *  same display space as [bbox]. Null when corners are unavailable. */
    val corners: List<List<Double>>? = null,
)

internal data class PreprocessingSection(
    val filteredWordCount: Int,
    val minX: Double,
    val minY: Double,
    val pageWidth: Double,
    val pageHeight: Double,
    val medianHeight: Double,
    val skewAngle: Double = 0.0,
    /** Gross 90°/180°/270° rotation applied (CW degrees) to undo a sideways
     *  label. 0 when no rotation was applied. */
    val appliedGrossRotationDegrees: Int = 0,
    /** Raw label orientation the detector measured (area-weighted circular
     *  mean of word top-edge angles, degrees in (-180, +180]). Null when the
     *  detector had no signal (< 3 weighted samples). */
    val detectedOrientationDegrees: Double? = null,
    /** Coarse axis-snap of [detectedOrientationDegrees]: 0 / 90 / 180 / 270.
     *  Logged even when no gross rotation ends up applied. */
    val coarseOrientationDegrees: Int = 0,
    /** Fine residual after the coarse snap — handed to the deskew stage. */
    val fineResidualDegrees: Double = 0.0,
    val anchorWords: List<Map<String, Any>>,
    val keyExcludedIndices: List<Int>,
)

internal data class CandidateEntry(
    val wordIndex: Int,
    val distance: Double,
)

internal data class RrfEntry(
    val wordIndex: Int,
    val rrfScore: Double,
    val nVotes: Int,
)

/**
 * Convert a list of WordBox to the serializable format [[text, [x1, y1, x2, y2]], ...]
 */
internal fun serializeWordBoxTuples(wordBoxTuples: List<WordBox>): List<List<Any>> {
    return wordBoxTuples.map { (text, bbox) ->
        listOf(text, bbox.toList())
    }
}
