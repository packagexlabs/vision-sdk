package com.example.vision_sdk_android


data class ScreenState(
    val scanningMode: ScanningMode = ScanningMode.Manual,
    val selectedMode: SelectedMode = SelectedMode.Barcode,
    val windowSize: WindowSize = WindowSize.Window,
    val flashStatus: Boolean = false
)


interface SelectedMode {
    object Barcode : SelectedMode
    object QRCode : SelectedMode
    object OCR : SelectedMode
}

interface WindowSize {
    object Window : WindowSize
    object FullScreen : WindowSize
}

interface ScanningMode {
    object Auto : ScanningMode
    object Manual : ScanningMode
}

