package com.example.visionsdkandroid

import com.example.customscannerview.mlkit.enums.ViewType
import com.example.customscannerview.mlkit.views.DetectionMode


data class ScreenState(
    val scanningWindow: ViewType = ViewType.WINDOW,
    val detectionMode: DetectionMode = DetectionMode.Barcode,
    val scanningMode: com.example.customscannerview.mlkit.views.ScanningMode = com.example.customscannerview.mlkit.views.ScanningMode.Manual,
    val flashStatus: Boolean = false
)
