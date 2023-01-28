package com.example.visionsdkandroid.responsemodel


import com.google.gson.annotations.SerializedName

data class Output(
    @SerializedName("scanOutput")
    val scanOutput: ScanOutput?
)