package com.example.vision_sdk_android

import com.example.customscannerview.mlkit.enums.ViewType
import com.example.customscannerview.mlkit.views.DetectionMode


data class ScreenState(
    val scanningWindow: ViewType = ViewType.RECTANGLE,
    val detectionMode: DetectionMode = DetectionMode.Barcode,
    val scanningMode: com.example.customscannerview.mlkit.views.ScanningMode = com.example.customscannerview.mlkit.views.ScanningMode.Manual,
    val flashStatus: Boolean = false
)
