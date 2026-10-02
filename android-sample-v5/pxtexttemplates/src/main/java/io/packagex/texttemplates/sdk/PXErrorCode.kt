package io.packagex.texttemplates.sdk

/**
 * Canonical, stable error-code identifiers carried by [PXException] and
 * [PXScanEvent.Failed]. Now an **enum** (previously a bag of `String` constants)
 * so a Kotlin host can branch exhaustively with `when`:
 * ```
 * catch (e: PXException) {
 *     when (e.errorCode) {
 *         PXErrorCode.NOT_LOADED -> …
 *         PXErrorCode.NETWORK_ERROR -> …
 *         else -> …
 *     }
 * }
 * ```
 * Each entry's [code] is the stable snake_case wire string, identical to iOS
 * `PXErrorCode` for a shared RN/Flutter contract. Compare against [code] when you
 * only have the raw string (`e.code == PXErrorCode.NOT_LOADED.code`), or against
 * the enum via [PXException.errorCode] / [PXScanEvent.Failed.errorCode].
 */
enum class PXErrorCode(val code: String) {
    /** No templates loaded — call `load(...)` before predicting/scanning. */
    NOT_LOADED("not_loaded"),
    /** Nothing in the on-disk cache — call `syncTemplates()` before `load()`. */
    NO_TEMPLATES_AVAILABLE("no_templates_available"),
    /** A requested template id isn't cached/loaded. */
    TEMPLATE_NOT_FOUND("template_not_found"),
    /** The image couldn't be loaded/decoded. */
    IMAGE_DECODING_FAILED("image_decoding_failed"),
    /** `repredict()` with no prior retained capture. */
    NO_RETAINED_FRAME("no_retained_frame"),
    /** `repredict()` on a `PXScannerView` controller whose view isn't mounted. */
    SCANNER_INACTIVE("scanner_inactive"),
    /** Aggregation produced no usable OCR frame. */
    NO_OCR_DATA("no_ocr_data"),
    /** An exception was thrown during the session's prediction step. */
    PREDICTION_FAILED("prediction_failed"),
    /** The request never reached the backend (transport error). */
    NETWORK_ERROR("network_error"),
    /** The backend responded with a non-2xx status. */
    SERVER_ERROR("server_error"),
    /** A backend response body couldn't be decoded. */
    DECODING_ERROR("decoding_error"),
    /** Fallback when no specific code applies. */
    UNKNOWN("unknown");

    companion object {
        /** Resolve a wire [code] string to its enum, or [UNKNOWN] if unrecognized
         *  (never throws — safe for values crossing the RN/Flutter bridge). */
        fun from(code: String?): PXErrorCode = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
