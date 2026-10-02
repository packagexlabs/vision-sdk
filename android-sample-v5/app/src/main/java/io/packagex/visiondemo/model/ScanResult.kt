package io.packagex.visiondemo.model

import android.graphics.Bitmap
import io.packagex.visiondemo.document.DocumentPage

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
    /** Price tag drawer; it reads `ScannerUiState.tags` live (iOS `.price`), so ClearTags empties an open drawer. */
    data object Price : ScanResult
    /** AR Item Count's shutter (spec 5.10): the codes in view, then the listed codes counted so far. */
    data class Retrieval(val rows: List<RetrievalRow>) : ScanResult
    /** Every page of the document so far (iOS `.docacq`). */
    data class Document(val pages: List<DocumentPage>) : ScanResult
}

/** A row of the AR Item Count drawer: [countLow]..[countHigh] units counted of a listed code; null for an unlisted one. */
data class RetrievalRow(val code: String, val inList: Boolean, val countLow: Int?, val countHigh: Int?)
