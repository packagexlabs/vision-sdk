package com.example.customscannerview.mlkit.modelclasses


import com.example.customscannerview.mlkit.modelclasses.ocr_response_demo.Data
import com.google.gson.annotations.SerializedName

data class OCRResponse(
    @SerializedName("data")
    val `data`: Data?,
    @SerializedName("endpoint")
    val endpoint: String?,
    @SerializedName("error_code")
    val errorCode: Any?,
    @SerializedName("errors")
    val errors: List<Any>?,
    @SerializedName("message")
    val message: String?,
    @SerializedName("pagination")
    val pagination: Any?,
    @SerializedName("status")
    val status: Int?
)