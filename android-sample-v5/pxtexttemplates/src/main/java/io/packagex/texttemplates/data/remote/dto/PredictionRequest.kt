package io.packagex.texttemplates.data.remote.dto

import com.google.gson.annotations.SerializedName

internal data class PredictionRequest(
    @SerializedName("image_b64")
    val imageB64: String
)
