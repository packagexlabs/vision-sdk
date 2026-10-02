package io.packagex.texttemplates.data.remote.dto

import com.google.gson.annotations.SerializedName

internal data class DebugProcessRequest(
    @SerializedName("word_box_tuples")
    val wordBoxTuples: List<List<Any>>,
    @SerializedName("word_box_tuples_unfiltered")
    val wordBoxTuplesUnfiltered: List<List<Any>>,
    @SerializedName("detected_barcodes")
    val detectedBarcodes: List<Map<String, Any>>,
    @SerializedName("raw_ocr_text")
    val rawOcrText: String,
)
