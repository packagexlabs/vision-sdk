package com.example.customscannerview.mlkit.modelclasses.ocr_response_demo


import com.google.gson.annotations.SerializedName

data class Output(
    @SerializedName("scanOutput")
    val scanOutput: ScanOutput?
)