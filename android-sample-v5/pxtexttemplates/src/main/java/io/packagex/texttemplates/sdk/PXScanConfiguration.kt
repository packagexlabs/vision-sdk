package io.packagex.texttemplates.sdk

/** Tunable knobs for a scan session, passed in explicitly by the host. Mirrors iOS `PXScanConfiguration`.
 *
 *  Note: the per-frame blur gate is not a host knob — it is a tuned internal
 *  default (a raw Laplacian-variance floor is device/resolution-dependent and not
 *  something a host can reason about). */
data class PXScanConfiguration(
    /** When true, the session stops after emitting its first prediction; when false (default) it auto-pauses and the host resume()s for the next item. */
    val singleShot: Boolean = false,
    /** When true, each streaming [PXScanEvent.Prediction] carries the captured
     *  upright frame in [PXPredictionResult.capturedImage] (e.g. for a review
     *  screen or per-field crops). Off by default so a host that doesn't need the
     *  frame never pays the copy. The bitmap is a grayscale (luma-plane) rendering
     *  of the scanned frame and stays native — it is NOT part of the Gson /
     *  RN-Flutter wire contract (see [PXPredictionResult]). Only applies to the
     *  streaming modes; One-Shot `predict(...)` never sets it because the caller
     *  already holds the image. Mirrors iOS `includeCapturedImage`. */
    val includeCapturedImage: Boolean = false,
)
