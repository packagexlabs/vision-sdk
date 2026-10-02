package io.packagex.visiondemo.model

import android.graphics.Bitmap
import io.packagex.texttemplates.sdk.PXField
import io.packagex.texttemplates.sdk.PXPredictionResult
import io.packagex.texttemplates.sdk.PXQuickResult
import io.packagex.visiondemo.data.TtPath
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
    /** The photo just taken while its extraction runs (or after it failed), with the header's [title]/[subtitle]
     *  meanwhile; the result replaces it. Never kept as the last result. */
    data class Pending(val image: Bitmap, val title: String, val subtitle: String) : ScanResult
    /** A Text Templates prediction (iOS `.tt`): the captured [image] (One-Shot's still, Stream's grayscale frame),
     *  the [path] it came from, and [repredicted] once the user re-ran it against another loaded template. */
    data class TextTemplate(
        val prediction: PXPredictionResult,
        val image: Bitmap?,
        val path: TtPath,
        val repredicted: PXQuickResult? = null,
    ) : ScanResult {
        val templateName: String? get() = repredicted?.templateName ?: prediction.templateName
        val fields: Map<String, PXField> get() = repredicted?.predictions ?: prediction.predictions
    }
}

/** A row of the AR Item Count drawer: [countLow]..[countHigh] units counted of a listed code; null for an unlisted one. */
data class RetrievalRow(val code: String, val inList: Boolean, val countLow: Int?, val countHigh: Int?)
