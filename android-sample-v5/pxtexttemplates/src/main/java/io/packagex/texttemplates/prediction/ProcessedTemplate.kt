package io.packagex.texttemplates.prediction

import com.google.gson.Gson

/**
 * Current template runtime version supported by this client. Kept in sync
 * with the server-side `TEMPLATE_VERSION` constant in `api/models.py`.
 * New versions must be added to [SUPPORTED_TEMPLATE_VERSIONS] and given a
 * dispatch branch in [PredictionEngine.predict].
 */
internal const val TEMPLATE_VERSION: String = "1.0"
internal val SUPPORTED_TEMPLATE_VERSIONS: Set<String> = setOf("1.0")

/**
 * Deserialized processed template from the server's getTemplateProcessed endpoint.
 *
 * Carries TWO sets of relation vectors:
 * - [vectors] / [anchorVectors] / [barcodeVectors] / [kvVectors] are normalized
 *   by the template's median word height (legacy mh-based scaling).
 * - [vectorsAvg] / [kvVectorsAvg] are normalized per-pair by `(h_a + h_b) / 2`
 *   (avg-height scaling). Pair-intrinsic; robust to OCR-engine bbox-padding
 *   differences and global resolution drift.
 * - [barcodeVectorsAvg] shares the `_avg` storage key but uses the barcode
 *   bbox **width** (w_a) as the normalizer.
 * - [anchorVectorsAvg] shares the `_avg` storage key but uses the anchor
 *   bbox **height** (h_a) alone as the normalizer (or `h_a + w_a` for the
 *   anchor-HW variant; see [PredictionEngine]).
 *
 * At predict time, [PredictionEngine] picks the avg variant when present and
 * falls back to mh otherwise so older templates keep working.
 */
internal data class ProcessedTemplate(
    /** Schema/runtime version. Defaults to "1.0" when absent on the wire —
     *  pre-versioning templates are treated as v1.0. */
    val version: String,
    val vectors: Map<String, PairVector>,
    val vectorsAvg: Map<String, PairVector>,
    val anchorVectors: Map<String, Map<String, List<Double>>>,
    val anchorVectorsAvg: Map<String, Map<String, List<Double>>>,
    /** Per-(anchor, field) calibration factors [f0, f1] for the anchor matcher's
     *  dim0 (distance) / dim1 (angle). Empty on older templates → matcher uses
     *  the legacy raw path. See docs/ANCHOR_NORMALIZATION.md. */
    val anchorScaleFactorsAvg: Map<String, Map<String, List<Double>>> = emptyMap(),
    val barcodeVectors: Map<String, Map<String, List<Double>>>,
    val barcodeVectorsAvg: Map<String, Map<String, List<Double>>>,
    val kvVectors: Map<String, Map<String, List<Double>>>,
    val kvVectorsAvg: Map<String, Map<String, List<Double>>>,
    /** Per-(field, key) axis scale factors baked into the stored kv_vectors at
     *  template-creation time. Runtime applies the same scaling to the live
     *  relation vector before computing weighted L2. */
    val kvScaleFactors: Map<String, Map<String, AxisScale>>,
    val kvScaleFactorsAvg: Map<String, Map<String, AxisScale>>,
    /** Per-(barcode_type, field) axis scale factors. Same role as [kvScaleFactors]. */
    val barcodeScaleFactors: Map<String, Map<String, AxisScale>>,
    val barcodeScaleFactorsAvg: Map<String, Map<String, AxisScale>>,
    /** Per-barcode-type aspect ratio `h / w` of the detected box at template-build
     *  time. Used by the barcode matcher's vertical-reconstruction gate: when a
     *  live detection's aspect disagrees, its vertical centre is untrustworthy
     *  (HRI-inflated / thin-line). Empty for legacy templates → reconstruction
     *  disabled, detected box used as-is. Android prediction only. */
    val barcodeAspect: Map<String, Double> = emptyMap(),
    /** Per-(barcode_type, anchor_word) height-weighted vertical offset
     *  `(barcode_cy - anchor_cy) / anchor_h`. When the aspect gate fires, the
     *  barcode centre is relocated to `anchor_cy_live + off * anchor_h_live`
     *  (averaged over matched anchors). Missing entry → fall back to the
     *  detected box (Tier 3). Empty for legacy templates. Android only. */
    val barcodeAnchorOffsets: Map<String, Map<String, Double>> = emptyMap(),
    /** Which normalizer the stored `barcode_vectors_avg` use: "height" (current)
     *  or "width" (legacy). The matcher picks the matching live-vector function
     *  off this flag, so a template built under either scheme scores correctly
     *  regardless of app version. Absent → "width" (pre-flag templates). */
    val barcodeNorm: String = "width",
    /** Barcode reference origin for HEIGHT-norm vectors: "center" (current) or
     *  "left" (legacy height templates built before the center-origin change).
     *  The live matcher picks center vs left off this so a height-LEFT template
     *  isn't mis-scored against center-origin live vectors. Absent → "left". */
    val barcodeOrigin: String = "left",
    /** Anchor centroid in anchor-height units `[cx/h_a, cy/h_a]`, one per
     *  anchor text. Used to break ties between same-text occurrences with
     *  similar bbox areas. */
    val anchorPositionsAvg: Map<String, List<Double>>,
    val primaryKeyMap: Map<String, String>,
    /** Look-alike / synonym printed keys per field: `{field_label: [alias, ...]}`.
     *  When a field's own [primaryKeyMap] key isn't found in the scanned OCR, the
     *  matcher falls back to the first alias that IS found. Empty for legacy
     *  templates (and templates whose alias LLM call failed) → exact-key behaviour.
     *  Mirrors the server-side `key_aliases` field. */
    val keyAliases: Map<String, List<String>>,
    /** Per-matcher RRF fusion weights `{pairwise, anchor, barcode, kv}`. Absent
     *  keys (and legacy templates with none) fall back to the code default
     *  [BASE_WEIGHTS]. Mirrors the server-side `fusion_weights`. */
    val fusionWeights: Map<String, Double>,
    /** Geo-vs-text-shape rerank weights `{text_weight_multiplier,
     *  text_weight_min, text_weight_max}`. Absent keys fall back to the
     *  TextPostProcessor code defaults. Mirrors the server-side `rerank_weights`. */
    val rerankWeights: Map<String, Double>,
    /** When true, the text-shape reranker's winner is always taken — the
     *  geometric-dominance top-1 lock (`geoDominant`) is disabled for this
     *  template. Absent on older templates → false. Mirrors the server-side
     *  `disable_geo_dominance`. */
    val disableGeoDominance: Boolean,
    val heightScale: Double,
    /** Template's OCR median word height, in cropped page-space pixels. Used to
     *  scale template-time key positions to a resolution-invariant space for
     *  multi-match key disambiguation. Defaults to 1 on legacy templates. */
    val medianHeight: Double,
    /** Per-keyText → (left, y_mid) in template [medianHeight] units, built from
     *  `_preview_kv_keys` at template-build time. Empty for legacy templates
     *  that don't carry `_preview_kv_keys` — callers fall back to first-match. */
    val keyPositionsMhNorm: Map<String, Pair<Double, Double>>,
    /** Multi-instance key support: `{key_label: key_text}` for key texts
     *  annotated on more than one field. Those instances are stored under the
     *  owning field LABEL in the kv maps above; readers resolve the printed
     *  text via `kvKeyTexts[innerKey] ?: innerKey`. Empty on legacy /
     *  all-unique-keys templates — this is the read-side feature gate.
     *  Mirrors the server-side `kv_key_texts`. */
    val kvKeyTexts: Map<String, String>,
    /** `{inner_key: [left/mh, y_mid/mh]}` — expected printed position of each
     *  resolved key instance, used at predict time to bind each instance to a
     *  distinct OCR occurrence. Supersedes [keyPositionsMhNorm] for
     *  multi-instance keys. Mirrors the server-side `kv_key_positions`. */
    val kvKeyPositions: Map<String, List<Double>>,
    val fieldTexts: Map<String, String>,
    val fieldBarcodeAssoc: Map<String, BarcodeAssoc>,
    val fieldDatatypes: Map<String, DatatypeInfo>,
    val flexible: Boolean,
    /** Words baked into the template that must be excluded from prediction —
     *  never matched as a field value, anchor, or key. Filtered out of the OCR
     *  word list before matching (see [PredictionEngine]). Empty for templates
     *  with no stop words. Mirrors the server-side `stop_words` field. */
    val stopWords: List<String> = emptyList(),
    /** Field labels scored/geometry-only and hidden from the results screen.
     *  Mirrors the server-side `prediction_only_fields`. Demo hides these;
     *  debug shows them flagged. Empty on older templates. */
    val predictionOnlyFields: Set<String> = emptySet(),
) {
    /** Distinct anchor keys, precomputed once (lazy) for template auto-detection
     *  so it isn't rebuilt on every detect call. `_avg` and plain variants carry
     *  identical keys. */
    val detectAnchorKeys: Set<String> by lazy {
        (if (anchorVectorsAvg.isNotEmpty()) anchorVectorsAvg else anchorVectors).keys
    }

    /** Per printed key text, how many INSTANCES this template defines.
     *  Supersedes the old distinct-key-text set (detection now counts
     *  instances, and inner keys may be field labels — see [kvKeyTexts]).
     *  Mirrors Python `_template_kv_key_counts`: distinct inner keys across
     *  all fields' kv maps resolved through [kvKeyTexts], plus [kvKeyTexts]
     *  entries whose label never appears as an inner key (instances that
     *  failed to bake vectors at creation still exist on the document and
     *  count toward template identity). Legacy templates → every distinct
     *  key text counts exactly 1, reproducing presence-set semantics. */
    val detectKvKeyCounts: Map<String, Int> by lazy {
        val src = if (kvVectorsAvg.isNotEmpty()) kvVectorsAvg else kvVectors
        val counts = HashMap<String, Int>()
        val seenInstances = HashSet<String>()
        for (fieldKv in src.values) {
            for (innerKey in fieldKv.keys) {
                if (!seenInstances.add(innerKey)) continue
                val text = kvKeyTexts[innerKey] ?: innerKey
                counts[text] = (counts[text] ?: 0) + 1
            }
        }
        for ((keyLabel, text) in kvKeyTexts) {
            if (seenInstances.add(keyLabel)) {
                counts[text] = (counts[text] ?: 0) + 1
            }
        }
        // Scan-side occurrence counting is capped at MAX_KEY_OCCURRENCES
        // (findAllKeyWords' ceiling) — cap both sides so a >8-instance
        // template's coverage denominator stays satisfiable.
        counts.mapValues { minOf(it.value, MAX_KEY_OCCURRENCES) }
    }
}

internal data class PairVector(val vector: List<Double>)

/** Per-axis scale factor pair stored on the template. Heights derive from
 *  `y / heightScale` at apply time so the schema only carries `x` and `y`. */
internal data class AxisScale(val x: Double, val y: Double)

internal data class BarcodeAssoc(
    val barcodeData: String,
    val vector: List<Double>,
    val vectorAvg: List<Double> = emptyList(),
)

internal data class DatatypeInfo(
    val type: String,
    val source: String,
)

/** Parse the raw Map<String, Any> response from the API into a ProcessedTemplate. */
internal fun parseProcessedTemplate(raw: Map<String, Any>, gson: Gson): ProcessedTemplate {
    val vectors = parsePairVectorMap(raw["vectors"])
    val vectorsAvg = parsePairVectorMap(raw["vectors_avg"])

    val anchorVectors = parseNestedVectorMap(raw["anchor_vectors"])
    val anchorVectorsAvg = parseNestedVectorMap(raw["anchor_vectors_avg"])
    val anchorScaleFactorsAvg = parseNestedVectorMap(raw["anchor_scale_factors_avg"])
    val anchorPositionsAvg = mutableMapOf<String, List<Double>>()
    for ((k, v) in asMap(raw["anchor_positions_avg"])) {
        val list = asDoubleList(v)
        if (list.size >= 2) anchorPositionsAvg[k] = list
    }
    val barcodeVectors = parseNestedVectorMap(raw["barcode_vectors"])
    val barcodeVectorsAvg = parseNestedVectorMap(raw["barcode_vectors_avg"])
    val kvVectors = parseNestedVectorMap(raw["kv_vectors"])
    val kvVectorsAvg = parseNestedVectorMap(raw["kv_vectors_avg"])
    val kvScaleFactors = parseAxisScaleMap(raw["kv_scale_factors"])
    val kvScaleFactorsAvg = parseAxisScaleMap(raw["kv_scale_factors_avg"])
    val barcodeScaleFactors = parseAxisScaleMap(raw["barcode_scale_factors"])
    val barcodeScaleFactorsAvg = parseAxisScaleMap(raw["barcode_scale_factors_avg"])
    val barcodeAspect = mutableMapOf<String, Double>()
    for ((k, v) in asMap(raw["barcode_aspect"])) {
        (v as? Number)?.let { barcodeAspect[k] = it.toDouble() }
    }
    val barcodeAnchorOffsets = mutableMapOf<String, Map<String, Double>>()
    for ((typeId, offsetsObj) in asMap(raw["barcode_anchor_offsets"])) {
        val inner = mutableMapOf<String, Double>()
        for ((anchorWord, off) in asMap(offsetsObj)) {
            (off as? Number)?.let { inner[anchorWord] = it.toDouble() }
        }
        if (inner.isNotEmpty()) barcodeAnchorOffsets[typeId] = inner
    }
    val primaryKeyMap = asStringMap(raw["primary_key_map"])
    val keyAliases = mutableMapOf<String, List<String>>()
    for ((k, v) in asMap(raw["key_aliases"])) {
        val list = asStringList(v)
        if (list.isNotEmpty()) keyAliases[k] = list
    }
    val fusionWeights = mutableMapOf<String, Double>()
    for ((k, v) in asMap(raw["fusion_weights"])) {
        (v as? Number)?.let { fusionWeights[k] = it.toDouble() }
    }
    val rerankWeights = mutableMapOf<String, Double>()
    for ((k, v) in asMap(raw["rerank_weights"])) {
        (v as? Number)?.let { rerankWeights[k] = it.toDouble() }
    }
    // JSON bool → Boolean; absent (legacy templates) → false.
    val disableGeoDominance = raw["disable_geo_dominance"] as? Boolean ?: false
    val heightScale = asDouble(raw["height_scale"])
    val medianHeightRaw = asDouble(raw["median_height"])
    val medianHeight = if (medianHeightRaw > 0) medianHeightRaw else 1.0
    // Build keyText → (left/mh, y_mid/mh) from `_preview_kv_keys`. The preview
    // list is per-field; multiple fields can share the same key text and bbox,
    // so keep the first non-empty mapping per text. Uses the getLeftMidpoint
    // convention (x = bbox.left, y = vertical centre) so disambiguation lives
    // in the same anchor space as the relation-vector math.
    val keyPositionsMhNorm = mutableMapOf<String, Pair<Double, Double>>()
    (raw["_preview_kv_keys"] as? List<*>)?.forEach { entryObj ->
        val entry = asMap(entryObj)
        val keyText = entry["key_text"]?.toString() ?: return@forEach
        if (keyText.isEmpty() || keyPositionsMhNorm.containsKey(keyText)) return@forEach
        val bbox = asDoubleList(entry["bbox"])
        if (bbox.size >= 4) {
            val leftMh = bbox[0] / medianHeight
            val yMidMh = ((bbox[1] + bbox[3]) / 2.0) / medianHeight
            keyPositionsMhNorm[keyText] = Pair(leftMh, yMidMh)
        }
    }
    // Multi-instance key maps. Empty on legacy / all-unique-keys templates —
    // kvKeyTexts being non-empty is the read-side feature gate everywhere.
    val kvKeyTexts = asStringMap(raw["kv_key_texts"])
    val kvKeyPositions = mutableMapOf<String, List<Double>>()
    for ((k, v) in asMap(raw["kv_key_positions"])) {
        val pos = asDoubleList(v)
        if (pos.size >= 2) kvKeyPositions[k] = pos
    }

    val fieldTexts = asStringMap(raw["field_texts"])

    val fieldBarcodeAssocRaw = asMap(raw["field_barcode_assoc"])
    val fieldBarcodeAssoc = mutableMapOf<String, BarcodeAssoc>()
    for ((field, value) in fieldBarcodeAssocRaw) {
        val m = asMap(value)
        fieldBarcodeAssoc[field] = BarcodeAssoc(
            barcodeData = m["barcode_data"]?.toString() ?: "",
            vector = asDoubleList(m["vector"]),
            vectorAvg = asDoubleList(m["vector_avg"]),
        )
    }

    val fieldDatatypesRaw = asMap(raw["field_datatypes"])
    val fieldDatatypes = mutableMapOf<String, DatatypeInfo>()
    for ((field, value) in fieldDatatypesRaw) {
        val m = asMap(value)
        fieldDatatypes[field] = DatatypeInfo(
            type = m["type"]?.toString() ?: "string",
            source = m["source"]?.toString() ?: "inferred",
        )
    }

    val flexible = raw["flexible"] as? Boolean ?: false

    val stopWords = asStringList(raw["stop_words"])
    val predictionOnlyFields = asStringList(raw["prediction_only_fields"]).toSet()

    val barcodeNorm = (raw["barcode_norm"] as? String)?.takeIf { it.isNotEmpty() } ?: "width"
    val barcodeOrigin = (raw["barcode_origin"] as? String)?.takeIf { it.isNotEmpty() } ?: "left"

    // Version defaults to "1.0" for templates that pre-date the field.
    val version = (raw["version"] as? String)?.takeIf { it.isNotEmpty() } ?: "1.0"

    return ProcessedTemplate(
        version = version,
        vectors = vectors,
        vectorsAvg = vectorsAvg,
        anchorVectors = anchorVectors,
        anchorVectorsAvg = anchorVectorsAvg,
        anchorScaleFactorsAvg = anchorScaleFactorsAvg,
        barcodeVectors = barcodeVectors,
        barcodeVectorsAvg = barcodeVectorsAvg,
        kvVectors = kvVectors,
        kvVectorsAvg = kvVectorsAvg,
        kvScaleFactors = kvScaleFactors,
        kvScaleFactorsAvg = kvScaleFactorsAvg,
        barcodeScaleFactors = barcodeScaleFactors,
        barcodeScaleFactorsAvg = barcodeScaleFactorsAvg,
        barcodeAspect = barcodeAspect,
        barcodeAnchorOffsets = barcodeAnchorOffsets,
        anchorPositionsAvg = anchorPositionsAvg,
        primaryKeyMap = primaryKeyMap,
        keyAliases = keyAliases,
        fusionWeights = fusionWeights,
        rerankWeights = rerankWeights,
        disableGeoDominance = disableGeoDominance,
        heightScale = heightScale,
        medianHeight = medianHeight,
        keyPositionsMhNorm = keyPositionsMhNorm,
        kvKeyTexts = kvKeyTexts,
        kvKeyPositions = kvKeyPositions,
        fieldTexts = fieldTexts,
        fieldBarcodeAssoc = fieldBarcodeAssoc,
        fieldDatatypes = fieldDatatypes,
        flexible = flexible,
        stopWords = stopWords,
        predictionOnlyFields = predictionOnlyFields,
        barcodeNorm = barcodeNorm,
        barcodeOrigin = barcodeOrigin,
    )
}

// --- Parse helpers ---

private fun parsePairVectorMap(obj: Any?): Map<String, PairVector> {
    val m = asMap(obj)
    val out = mutableMapOf<String, PairVector>()
    for ((key, value) in m) {
        val vecMap = asMap(value)
        out[key] = PairVector(asDoubleList(vecMap["vector"]))
    }
    return out
}

/**
 * Parse a `{outer: {inner: { x, y }}}` map of axis scale factors. Returns an
 * empty map for legacy templates — the matcher treats absence as "no scaling."
 */
private fun parseAxisScaleMap(obj: Any?): Map<String, Map<String, AxisScale>> {
    val outer = asMap(obj)
    val result = mutableMapOf<String, Map<String, AxisScale>>()
    for ((outerKey, innerObj) in outer) {
        val inner = asMap(innerObj)
        val innerMap = mutableMapOf<String, AxisScale>()
        for ((innerKey, scaleObj) in inner) {
            val m = asMap(scaleObj)
            // Only register when both x and y are explicitly present.
            if (!m.containsKey("x") || !m.containsKey("y")) continue
            innerMap[innerKey] = AxisScale(x = asDouble(m["x"]), y = asDouble(m["y"]))
        }
        if (innerMap.isNotEmpty()) result[outerKey] = innerMap
    }
    return result
}

private fun parseNestedVectorMap(obj: Any?): Map<String, Map<String, List<Double>>> {
    val outer = asMap(obj)
    val result = mutableMapOf<String, Map<String, List<Double>>>()
    for ((outerKey, innerObj) in outer) {
        val inner = asMap(innerObj)
        val innerMap = mutableMapOf<String, List<Double>>()
        for ((innerKey, vecObj) in inner) {
            innerMap[innerKey] = asDoubleList(vecObj)
        }
        result[outerKey] = innerMap
    }
    return result
}

@Suppress("UNCHECKED_CAST")
private fun asMap(obj: Any?): Map<String, Any> {
    return (obj as? Map<String, Any>) ?: emptyMap()
}

private fun asStringMap(obj: Any?): Map<String, String> =
    asMap(obj).mapValues { it.value.toString() }

private fun asDouble(obj: Any?): Double = when (obj) {
    is Number -> obj.toDouble()
    is String -> obj.toDoubleOrNull() ?: 1.0
    else -> 1.0
}

@Suppress("UNCHECKED_CAST")
private fun asDoubleList(obj: Any?): List<Double> = when (obj) {
    is List<*> -> obj.mapNotNull { (it as? Number)?.toDouble() }
    else -> emptyList()
}

private fun asStringList(obj: Any?): List<String> = when (obj) {
    is List<*> -> obj.mapNotNull { it?.toString() }
    else -> emptyList()
}
