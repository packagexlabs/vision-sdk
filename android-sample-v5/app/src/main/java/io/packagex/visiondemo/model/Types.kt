package io.packagex.visiondemo.model

/**
 * Plain Kotlin rect for design viewfinders (JVM unit tests don't have android.graphics.Rect).
 */
data class DesignRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Plain Kotlin box for detected codes (JVM unit tests don't have android.graphics.Rect).
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

enum class ScanMode(val label: String) {
    Barcode("Barcode"),
    QR("QR code"),
    Ocr("Vision Scanner"),
    Price("Price tag"),
    Retrieval("Item retrieval"),
    Ar("AR Barcode"),
    DocAcq("Document Acquisition")
}

val ScanMode.isCode: Boolean
    get() = when (this) {
        ScanMode.Barcode, ScanMode.QR, ScanMode.Price, ScanMode.Retrieval -> true
        else -> false
    }

val ScanMode.isDocument: Boolean
    get() = this == ScanMode.Ocr || this == ScanMode.DocAcq

val ScanMode.gated: Boolean
    get() = when (this) {
        ScanMode.Price, ScanMode.Retrieval -> true
        else -> false
    }

val ScanMode.zooms: List<Float>
    get() = when (this) {
        ScanMode.Barcode, ScanMode.QR, ScanMode.Price, ScanMode.Retrieval -> listOf(1f, 2f, 3f)
        ScanMode.Ocr, ScanMode.DocAcq -> listOf(1f, 1.5f, 2f)
        else -> emptyList()
    }

/**
 * Design viewfinder frames, in the 390×844 artboard (from iOS Types.swift).
 */
val ScanMode.viewfinder: DesignRect?
    get() = when (this) {
        ScanMode.Barcode, ScanMode.Price -> DesignRect(x = 20f, y = 318f, width = 350f, height = 122f)
        ScanMode.QR -> DesignRect(x = 45f, y = 262f, width = 300f, height = 300f)
        ScanMode.Ocr -> DesignRect(x = 48f, y = 190f, width = 294f, height = 400f)
        ScanMode.DocAcq -> DesignRect(x = 20f, y = 190f, width = 350f, height = 400f)
        else -> null
    }

enum class DocType(val label: String) {
    SL("Shipping label"),
    BOL("Bill of lading"),
    IL("Item label"),
    DC("Document classification"),
    VLM("VLM"),
    Tire("Vehicle / Tire ID"),
    IdCard("ID Card / Passport"),
    Plate("License Plate")
}

enum class ModelSize {
    Micro, Large
}

enum class Processing {
    Cloud, Device
}

enum class SheetKind {
    Settings, DocType, Items, ArItems, Models
}

enum class Phase {
    Idle, Scanning, Processing
}

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Downloaded : ModelState
    data object Loaded : ModelState
    data object Failed : ModelState
}

data class DetectedCode(
    val value: String,
    val symbology: String,
    val box: Box
)
