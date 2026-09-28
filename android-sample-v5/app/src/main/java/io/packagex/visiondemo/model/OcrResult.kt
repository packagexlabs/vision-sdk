package io.packagex.visiondemo.model

/**
 * A single extracted field: raw key/value, its human label and section heading, and (for
 * on-device responses) the geometry and validation source used for spatial item-label feedback.
 * Ported from iOS `Model/Types.swift`'s `OCRField`.
 */
data class OcrField(
    val id: String,
    val key: String,
    val label: String,
    val value: String,
    val section: String?,
    /** Raw on-device corner points (TL, TR, BL, BR), sent with item-label feedback. */
    val vertices: List<List<Double>>?,
    /** On-device `validated_by` for the boxed entity (BARCODE, APRIORI, ML), when present. */
    val validatedBy: List<String>
) {
    /** Numbers, weights, dates and codes are shown in the mono font (iOS `OCRField.mono`). */
    val mono: Boolean get() = MONO_VALUE.matches(value)
}

private val MONO_VALUE = Regex("^[\\d\\s.,×xX#/:-]+[A-Za-z]{0,3}$")

/** A flat table of rows (e.g. BOL `inference.tables`, VLM invoice/receipt line items). */
data class OcrTable(val title: String, val headers: List<String>, val rows: List<List<String>>)

/** Result of [io.packagex.visiondemo.data.OcrParser.parse]. */
data class OcrResult(
    val docType: DocType,
    val fields: List<OcrField>,
    val tables: List<OcrTable>,
    /** The field shown large at the top of the result. */
    val primary: OcrField?,
    val rawJson: String,
    /** Read in the cloud (the processing actually used, including the wild-card route); iOS `OCRResult.cloud`. */
    val cloud: Boolean = false,
)
