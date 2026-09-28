package io.packagex.visiondemo.model

import android.graphics.Bitmap
import io.packagex.visiondemo.data.PriceTag

/** What the result drawer shows. Ported from iOS `Model/Types.swift`'s `ScanResult`. */
sealed interface ScanResult {
    /** One code (single scan) or every code read at once (multiple scan). */
    data class Codes(val codes: List<DetectedCode>) : ScanResult
    /** [title]/[subtitle] as iOS `OCRResult` (e.g. "Shipping label (wild card)", "Cloud · 1.2 s"). */
    data class Ocr(
        val result: OcrResult,
        val image: Bitmap?,
        val title: String = result.docType.label,
        val subtitle: String = "",
    ) : ScanResult
    /** Every unique tag read since entering the mode or the last close. */
    data class Price(val tags: List<PriceTag>) : ScanResult
    /** Codes in view when the shutter was pressed, each flagged when it is in the item list (iOS `.retrieval`). */
    data class Retrieval(val codes: List<Pair<String, Boolean>>) : ScanResult
    /** (value, symbology, count) per AR payload. */
    data class Ar(val rows: List<Triple<String, String, Int>>) : ScanResult
    data class Document(val pageCount: Int) : ScanResult
}
