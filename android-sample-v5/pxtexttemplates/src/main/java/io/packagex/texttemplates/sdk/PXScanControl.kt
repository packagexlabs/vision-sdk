package io.packagex.texttemplates.sdk

/**
 * The single control surface for a running scan, shared by both streaming modes:
 *  - **Host-Frames** — the host holds a [PXScanSession] (which also feeds frames
 *    via `process(...)` and tears down via `stop()`).
 *  - **SDK-Camera** — the host holds a [PXScannerController] handed to
 *    `PXScannerView`, which owns the session internally.
 *
 * The host only ever drives the flow through these three verbs; the SDK owns all
 * capture lifecycle and state (start/arm, the state machine, timeouts, auto-pause
 * after a result) internally. Mirrors iOS, where `PXScanSession` and
 * `PXScannerController` expose the same trio.
 */
interface PXScanControl {
    /** Pause processing (no events fire) while the host keeps its preview. */
    fun pause()

    /** Resume scanning the next item after a [pause] or the auto-pause that
     *  follows a result — starts a fresh capture. No-op if not paused. */
    fun resume()

    /** Re-run prediction on a persisted scan's retained capture against another
     *  loaded template (no re-scan/OCR). [scanId] is the id echoed on that scan's
     *  [PXPredictionResult]. Throws [PXException] `no_retained_frame` if the
     *  scanId is unknown/evicted, or `template_not_found` if the id isn't loaded. */
    suspend fun repredict(scanId: String, templateId: String): PXQuickResult
}
