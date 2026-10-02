package io.packagex.texttemplates.data.remote.dto

import com.google.gson.annotations.SerializedName

internal data class PredictionResponse(
    @SerializedName("predictions")
    val predictions: Map<String, PredictedField>,
    @SerializedName("suggestions")
    val suggestions: Map<String, List<PredictedField>>? = null,
    @SerializedName("timing")
    val timing: TimingInfo? = null,
    // Field labels scored/geometry-only and hidden from the results screen.
    // Null on legacy responses → none hidden. Demo mode filters these out;
    // debug mode shows them flagged.
    @SerializedName("prediction_only_fields")
    val predictionOnlyFields: List<String>? = null,
)

internal data class PredictedField(
    @SerializedName("text")
    val text: String,
    @SerializedName("bbox")
    val bbox: List<Float>? = null,
    // Tight 4-corner quad (TL,TR,BR,BL) in the upright input frame. On skewed/
    // rotated captures the bbox is the inflated AABB; these corners sit on the
    // real (tilted) word. Null on legacy paths → callers fall back to bbox.
    @SerializedName("corners")
    val corners: List<List<Float>>? = null,
    @SerializedName("confidence")
    val confidence: Float,
    @SerializedName("geo_rank")
    val geoRank: Int? = null,
    @SerializedName("barcode_match")
    val barcodeMatch: Boolean? = null,
    @SerializedName("datatype_match")
    val datatypeMatch: Boolean? = null
)

internal data class TimingInfo(
    @SerializedName("total_ms")
    val totalMs: Float,
    @SerializedName("ocr_ms")
    val ocrMs: Float? = null,
    @SerializedName("processing_ms")
    val processingMs: Float? = null
)
