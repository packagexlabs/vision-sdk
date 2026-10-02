package io.packagex.texttemplates.session

import io.packagex.texttemplates.extraction.models.FrameExtractionResult

/**
 * Capture state machine. Replaces the earlier
 * `.idle` / `.collecting` / `.predicting` / `.result` / `.error` shape with a
 * richer set that distinguishes "no text seen yet" ([Seeking]), "text seen but
 * not pair-stable yet" ([Stabilising]), and "streak building" ([Capturing]).
 *
 * Transitions are driven by [AnalyzerEvent]s emitted from the per-frame
 * pipeline, plus timer-driven hysteresis. See [ScannerViewModel] for the
 * transition logic.
 */
internal sealed class ScannerState {
    data object Idle : ScannerState()
    data object Seeking : ScannerState()
    data object Stabilising : ScannerState()
    data class Capturing(val streak: Int, val target: Int) : ScannerState()
    data object Predicting : ScannerState()

    val isIdle: Boolean get() = this is Idle
    val isSeeking: Boolean get() = this is Seeking
    val isStabilising: Boolean get() = this is Stabilising
    val isCapturing: Boolean get() = this is Capturing
    val isPredicting: Boolean get() = this is Predicting

    /** Seeking, Stabilising, or Capturing — the active-capture states where
     *  the analyzer is running and the user sees the reticle/hint UI. */
    val isActiveCapture: Boolean get() = isSeeking || isStabilising || isCapturing
}

/**
 * Output of one camera frame's pass through the analyzer. Drives [ScannerState]
 * transitions and feeds the hint dispatcher (which surfaces "Hold steady",
 * "Move closer" etc. to the user). Each [QualityFailed] carries the gate that
 * actually rejected the frame so the hint layer can pick the right secondary
 * message; each [Unpaired] carries the disagreement type for the same reason.
 */
internal sealed class AnalyzerEvent {
    data class QualityFailed(val reason: QualityFailReason) : AnalyzerEvent()
    data class BootstrapSeeded(val frame: FrameExtractionResult) : AnalyzerEvent()
    data class Paired(val frame: FrameExtractionResult) : AnalyzerEvent()
    data class Unpaired(val kind: UnpairedKind) : AnalyzerEvent()
}

/**
 * Why a frame failed per-frame quality. Ordered roughly stage-by-stage in the
 * pipeline so the analyzer can pick the first gate that fired.
 */
internal enum class QualityFailReason {
    /** IMU loose threshold exceeded — device was rotating during the capture
     *  window. */
    Motion,

    /** Camera autofocus was hunting. */
    Focusing,

    /** Laplacian variance below threshold — image was soft, motion-blurred,
     *  or the scene lacked high-frequency content. */
    Blurry,

    /** OCR returned fewer than the minimum text blocks. */
    TooFewBlocks,

    /** OCR average word confidence below the floor. */
    LowConfidence,

    /** Text union bbox covers > ~65 % of the frame. */
    TooDense,
}

/** Why a pair-stability check returned `disagreed`. */
internal enum class UnpairedKind {
    /** Jaccard of word strings below threshold — substantially different
     *  text. Usually an OCR-quality / lighting issue. */
    WordDisagreement,

    /** Text-bbox centroid moved more than the threshold — document shifted
     *  in frame. Usually motion. */
    SpatialDrift,
}
