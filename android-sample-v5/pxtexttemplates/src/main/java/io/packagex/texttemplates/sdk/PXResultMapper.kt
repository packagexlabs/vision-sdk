package io.packagex.texttemplates.sdk

import io.packagex.texttemplates.data.remote.dto.PredictedField
import io.packagex.texttemplates.data.remote.dto.PredictionResponse
import io.packagex.texttemplates.prediction.DebugBarcode
import io.packagex.texttemplates.prediction.TemplateDetectionResult

/**
 * Maps the SDK's internal engine/wire types to the public, customer-facing
 * result surface ([PXField] / [PXDetection] / …). This is the single boundary
 * where internal scoring signals (geoRank / barcodeMatch / datatypeMatch) and
 * prediction-only fields are dropped, so no internal detail leaks to callers.
 */
internal object PXResultMapper {

    // Geometry crosses the SDK boundary NORMALIZED 0–1 (x by frame width, y by
    // frame height, top-left origin). The engine's internal geometry stays in
    // pixels — matchers, debug report, etc. are untouched; normalization happens
    // only here at the public-result assembly layer. A degenerate frame size
    // (<= 0) passes the value through unchanged rather than dividing by zero.

    /** Normalize a pixel-space [Float] bbox `[minX,minY,maxX,maxY]` to 0–1. */
    private fun normBboxF(bbox: List<Float>?, w: Int, h: Int): List<Float>? {
        if (bbox == null || bbox.size != 4 || w <= 0 || h <= 0) return bbox
        val dw = w.toFloat(); val dh = h.toFloat()
        return listOf(bbox[0] / dw, bbox[1] / dh, bbox[2] / dw, bbox[3] / dh)
    }

    /** Normalize a pixel-space [Float] corner quad (`[[x,y]…]`) to 0–1. */
    private fun normCornersF(corners: List<List<Float>>?, w: Int, h: Int): List<List<Float>>? {
        if (corners == null || w <= 0 || h <= 0) return corners
        val dw = w.toFloat(); val dh = h.toFloat()
        return corners.map { p -> if (p.size >= 2) listOf(p[0] / dw, p[1] / dh) else p }
    }

    /** Normalize a pixel-space [Double] bbox `[minX,minY,maxX,maxY]` to 0–1. */
    private fun normBboxD(bbox: List<Double>?, w: Int, h: Int): List<Double>? {
        if (bbox == null || bbox.size != 4 || w <= 0 || h <= 0) return bbox
        val dw = w.toDouble(); val dh = h.toDouble()
        return listOf(bbox[0] / dw, bbox[1] / dh, bbox[2] / dw, bbox[3] / dh)
    }

    /** Normalize a pixel-space [Double] corner quad (`[[x,y]…]`) to 0–1. */
    private fun normCornersD(corners: List<List<Double>>?, w: Int, h: Int): List<List<Double>>? {
        if (corners == null || w <= 0 || h <= 0) return corners
        val dw = w.toDouble(); val dh = h.toDouble()
        return corners.map { p -> if (p.size >= 2) listOf(p[0] / dw, p[1] / dh) else p }
    }

    /** A leaf field (no nested suggestions) from an engine [PredictedField], with
     *  geometry normalized to 0–1 against the [w]×[h] frame. */
    private fun leaf(pf: PredictedField, w: Int, h: Int): PXField =
        PXField(
            text = pf.text, confidence = pf.confidence,
            bbox = normBboxF(pf.bbox, w, h), corners = normCornersF(pf.corners, w, h),
        )

    /**
     * Customer-facing predictions: prediction-only field keys removed. Each
     * field's alternate candidates are nested under [PXField.suggestions]. All
     * geometry is normalized to 0–1 against the [frameWidth]×[frameHeight] frame.
     */
    fun predictions(response: PredictionResponse?, frameWidth: Int, frameHeight: Int): Map<String, PXField> {
        if (response == null) return emptyMap()
        val hidden = response.predictionOnlyFields?.toSet() ?: emptySet()
        val sugg = response.suggestions
        return response.predictions
            .filterKeys { it !in hidden }
            .mapValues { (key, pf) ->
                val alts = sugg?.get(key)?.map { leaf(it, frameWidth, frameHeight) }?.takeIf { it.isNotEmpty() }
                leaf(pf, frameWidth, frameHeight).copy(suggestions = alts)
            }
    }

    /** Map the engine's display-space detected barcodes to the public type,
     *  normalizing geometry to 0–1 against the [frameWidth]×[frameHeight] frame.
     *  The engine's [DebugBarcode] geometry is in the same upright-frame pixel
     *  space as the predicted fields' geometry. */
    fun barcodes(list: List<DebugBarcode>, frameWidth: Int, frameHeight: Int): List<PXBarcode> =
        list.map {
            PXBarcode(
                data = it.data, format = it.format,
                bbox = normBboxD(it.bbox, frameWidth, frameHeight),
                corners = normCornersD(it.corners, frameWidth, frameHeight),
            )
        }

    /**
     * Build [PXDetection]. When no detection ran (single locked template),
     * [detection] is null → chosen = [lockedTemplateId], not ambiguous, no
     * candidates. Otherwise project the internal ranking.
     */
    fun detection(detection: TemplateDetectionResult?, lockedTemplateId: String?): PXDetection {
        if (detection == null) {
            return PXDetection(chosenId = lockedTemplateId, ambiguous = false, candidates = emptyList())
        }
        return PXDetection(
            chosenId = detection.chosenId,
            ambiguous = detection.ambiguous,
            candidates = detection.ranked.map { PXTemplateMatch(it.id, it.name, it.score.toFloat()) },
        )
    }
}
