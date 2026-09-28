package io.packagex.visiondemo.model

import android.graphics.Bitmap

/** What the result drawer shows. Ported from iOS `DemoModel`'s `ScanResult`. */
sealed interface ScanResult {
    /** One code (single scan) or every code read at once (multiple scan). */
    data class Codes(val codes: List<DetectedCode>) : ScanResult
    data class Ocr(val result: OcrResult, val image: Bitmap?) : ScanResult
    data class Price(val sku: String, val price: String) : ScanResult
    /** Items from the list that were seen in view / not seen. */
    data class Retrieval(val found: List<String>, val missing: List<String>) : ScanResult
    /** (value, symbology, count) per AR payload. */
    data class Ar(val rows: List<Triple<String, String, Int>>) : ScanResult
    data class Document(val pageCount: Int) : ScanResult
}
