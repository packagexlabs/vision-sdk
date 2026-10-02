package io.packagex.texttemplates.sdk

import android.graphics.Bitmap
import io.packagex.texttemplates.prediction.RectD
import io.packagex.texttemplates.prediction.computeFocusRects

/**
 * A normalized 0–1 region of interest in **edge** coordinates — `left`, `top`,
 * `right`, `bottom`, top-left origin. This is the SAME convention as the output
 * [PXRegionOfInterest.bounds] (`[[x0,y0],[x1,y1]]`), and it is identical across
 * iOS and Android — a host passes the exact same numbers on both platforms.
 *
 * (Deliberately not a platform rect type: `CGRect`/`RectF` disagree on whether
 * the third/fourth values are size vs. edges, which is a cross-platform trap.)
 */
data class PXRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        // Fail fast on a degenerate rect (zero/negative width or height): it would
        // drive the engine's geometry math into garbage / empty predictions. Edges
        // are expected normalized 0–1, top-left origin. Mirrors iOS `PXRegion`.
        require(left < right && top < bottom) {
            "PXRegion must have left<right and top<bottom (normalized 0–1, top-left origin); " +
                "got left=$left top=$top right=$right bottom=$bottom"
        }
    }
}

/**
 * The resolved zone-2 (trusted) region echoed back to the host so it can draw
 * the trusted overlay (brackets/reticle) and confirm whether its rect was
 * applied or the SDK default was used. Mirrors iOS `PXRegionOfInterest`.
 *
 * [bounds] are `[[x0, y0], [x1, y1]]`, normalized 0–1 in **upright-frame**
 * coordinates (top-left origin); [frameWidth]/[frameHeight] give the upright
 * frame the bounds are relative to. The SDK-Camera preview
 * ([PXScannerView]) is **aspect-fill** (`PreviewView.ScaleType.FILL_CENTER`,
 * matching iOS `.resizeAspectFill`). To map the region onto such a preview,
 * scale by `max(viewW/frameW, viewH/frameH)`, centre, and clip to the view —
 * the preview fills the view and center-crops any overflow.
 *
 * Recommended (and what the demo does): frame the preview in a container locked
 * to the frame's aspect ratio. Then aspect-fill crops nothing and the normalized
 * [bounds] map **directly** (identity) onto the container — no min/max/crop math.
 *
 * Gson-serializable (plain data class) for RN/Flutter bridging.
 */
data class PXRegionOfInterest(
    /** `[[x0, y0], [x1, y1]]`, normalized 0–1, top-left origin, upright-frame coords. */
    val bounds: List<List<Float>>,
    /** DEFAULT (SDK centred box; host passed null) or HOST (host rect applied). */
    val source: Source,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    /** Source of the resolved region, mirroring iOS raw values. */
    enum class Source {
        @com.google.gson.annotations.SerializedName("default") DEFAULT,
        @com.google.gson.annotations.SerializedName("host") HOST,
    }

    /** The trusted box in upright-frame pixels — what the engine consumes as
     *  `innerBox` (before any rotate-to-sensor transform). Mirrors iOS `imageRect`.
     *  Internal: consumed only by [PXScanSession] within the SDK module. */
    internal val imageRect: RectD
        get() {
            val fw = frameWidth.toDouble()
            val fh = frameHeight.toDouble()
            val x0 = bounds[0][0] * fw
            val y0 = bounds[0][1] * fh
            val x1 = bounds[1][0] * fw
            val y1 = bounds[1][1] * fh
            return RectD(x0, y0, x1 - x0, y1 - y0)
        }

    companion object {
        /**
         * Resolve the trusted region (zone 2) for an UPRIGHT frame size.
         * [regionOfInterest] is a normalized 0–1 rect (host override,
         * left/top/right/bottom) or null for the SDK default centred box. The
         * single source of truth used by [PXClient] and [PXScanSession].
         *
         * IMPORTANT: the default (null ROI) bounds are derived from the engine's
         * own [computeFocusRects] default inner box, normalized to 0–1, so the
         * drawn brackets match exactly the region the outlier filter trusts.
         */
        fun resolve(
            regionOfInterest: PXRegion?,
            frameWidth: Int,
            frameHeight: Int,
        ): PXRegionOfInterest {
            if (regionOfInterest != null) {
                val r = regionOfInterest
                return PXRegionOfInterest(
                    bounds = listOf(listOf(r.left, r.top), listOf(r.right, r.bottom)),
                    source = Source.HOST,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                )
            }
            val (_, inner) = computeFocusRects(frameWidth.toDouble(), frameHeight.toDouble())
            val bounds = if (inner != null && frameWidth > 0 && frameHeight > 0) {
                val fw = frameWidth.toDouble()
                val fh = frameHeight.toDouble()
                listOf(
                    listOf((inner.minX / fw).toFloat(), (inner.minY / fh).toFloat()),
                    listOf((inner.maxX / fw).toFloat(), (inner.maxY / fh).toFloat()),
                )
            } else {
                listOf(listOf(0f, 0f), listOf(1f, 1f))
            }
            return PXRegionOfInterest(bounds, Source.DEFAULT, frameWidth, frameHeight)
        }
    }
}

/** id + display name for a cached template. Result of [PXClient.getTemplates]. */
data class PXTemplateInfo(
    val id: String,
    val name: String,
)

/** Outcome of [PXClient.syncTemplates] — the server-vs-cache diff. Mirrors
 *  iOS `PXTemplateSyncResult`. */
data class PXTemplateSyncResult(
    val total: Int,
    val added: Int,
    val refreshed: Int,
    val removed: Int,
    /** Templates whose raw/processed payload couldn't be eagerly prefetched;
     *  they fall back to a lazy on-demand fetch at selection time. */
    val prefetchFailed: Int = 0,
)

// =========================================================================
// Public, customer-facing result surface
//
// These are the ONLY prediction types that cross the SDK boundary. They expose
// what a customer needs (value + confidence + geometry) and deliberately DROP
// internal scoring signals (geoRank / barcodeMatch / datatypeMatch) and
// prediction-only fields. The internal wire/engine types
// (PredictionResponse / PredictedField / TemplateDetectionResult / TemplateRank)
// stay inside the SDK. Every field is a plain data class → Gson/kotlinx
// serializable for RN/Flutter bridging.
// =========================================================================

/** One predicted (or suggested) field value. */
data class PXField(
    val text: String,
    /** Per-field confidence, normalized **0–1** (higher = more confident). Not
     *  comparable to [PXTemplateMatch.confidence], which is an unbounded score. */
    val confidence: Float,
    /** Axis-aligned box [minX, minY, maxX, maxY], NORMALIZED 0–1 (x by the frame
     *  width, y by the frame height; top-left origin), or null. Multiply by the
     *  result's [PXPredictionResult.imageWidth]/[PXPredictionResult.imageHeight]
     *  (or [PXQuickResult.imageWidth]/[PXQuickResult.imageHeight]) to get pixels.
     *  Same order as iOS (left, top, right, bottom) for a consistent
     *  cross-platform / RN-Flutter wire contract. */
    val bbox: List<Float>?,
    /** Oriented quad [TL, TR, BR, BL], each [x, y] NORMALIZED 0–1 (x by frame
     *  width, y by frame height), or null. */
    val corners: List<List<Float>>?,
    /** Alternate candidate values for this same field, most-confident first
     *  (what used to be the top-level `suggestions` map). Null when the field
     *  has no alternatives, and always null on a nested suggestion —
     *  suggestions are only one level deep. */
    val suggestions: List<PXField>? = null,
)

/**
 * A barcode detected in the scanned frame, returned alongside the predicted
 * fields. Geometry is NORMALIZED 0–1 in the SAME upright-frame space as
 * [PXField.bbox] / [PXField.corners], so a host can draw barcode and field
 * overlays on one image with no extra transform. Gson-serializable (plain data
 * class) for RN/Flutter bridging. Mirrors iOS `PXBarcode`.
 */
data class PXBarcode(
    /** The decoded payload string. */
    val data: String,
    /** The symbology, e.g. "QR", "Code128", "EAN13" (platform detector's name). */
    val format: String,
    /** Axis-aligned box [minX, minY, maxX, maxY], NORMALIZED 0–1 (x by frame
     *  width, y by frame height; top-left origin), or null. Multiply by the
     *  result's imageWidth/imageHeight to get pixels. */
    val bbox: List<Double>?,
    /** Oriented 4-corner quad [TL, TR, BR, BL] (each [x, y]) NORMALIZED 0–1,
     *  sitting on the real, possibly-tilted barcode, or null when the detector
     *  reported no corners. */
    val corners: List<List<Double>>?,
)

/** A candidate template + how strongly the scan matched it. */
data class PXTemplateMatch(
    val id: String,
    val name: String,
    /** Detection **score** — an unbounded relative ranking signal (higher =
     *  closer match), NOT a 0–1 probability. Use it only to rank/compare
     *  candidates, not as a confidence percentage. Different scale from
     *  [PXField.confidence]. */
    val confidence: Float,
)

/** Which template a scan resolved to, plus the ranked alternatives. For a
 *  single locked template (no detection ran) [candidates] is empty and
 *  [ambiguous] is false. */
data class PXDetection(
    val chosenId: String?,
    val ambiguous: Boolean,
    val candidates: List<PXTemplateMatch>,
)

/** Full result of [PXClient.predict] (One-Shot) or a [PXScanSession] capture.
 *  Each [PXField] in [predictions] carries its own alternate `suggestions`. */
data class PXPredictionResult(
    val templateId: String?,
    val templateName: String?,
    val predictions: Map<String, PXField>,
    val detection: PXDetection,
    /** Barcodes detected in the frame, geometry (bbox/corners) NORMALIZED 0–1 in
     *  the SAME upright-frame space as [PXField.bbox]/[PXField.corners] — a host
     *  can draw field and barcode overlays on one image with no extra transform.
     *  Empty when none were found, or when auto-detect was ambiguous (no
     *  prediction geometry ran). Part of the JSON/RN-Flutter wire contract;
     *  mirrors iOS. */
    val barcodes: List<PXBarcode> = emptyList(),
    /** The resolved trusted region (zone 2) used for this prediction — the same
     *  box the streaming `RegionResolved` event reports, so a One-Shot host can
     *  draw the overlay from the result. Mirrors iOS `resolvedRegionOfInterest`. */
    val resolvedRegionOfInterest: PXRegionOfInterest,
    /** Width of the upright frame the normalized [predictions]/[barcodes] geometry
     *  is relative to — multiply a normalized x/minX/maxX by this to get pixels.
     *  Equals [resolvedRegionOfInterest].frameWidth; surfaced at the top level for
     *  hosts that only need the image size. Part of the JSON/RN-Flutter wire
     *  contract; mirrors iOS `imageWidth`. */
    val imageWidth: Int,
    /** Height of the upright frame the normalized geometry is relative to —
     *  multiply a normalized y/minY/maxY by this to get pixels. Equals
     *  [resolvedRegionOfInterest].frameHeight; mirrors iOS `imageHeight`. */
    val imageHeight: Int,
    /** The captured upright frame this prediction was made from — a **grayscale**
     *  (luma-plane) rendering, since the Android pipeline only retains luma.
     *  Populated ONLY by the streaming modes when
     *  [PXScanConfiguration.includeCapturedImage] is true; always null for
     *  One-Shot `predict(...)` (the caller already holds the image). `@Transient`
     *  so Gson skips it — the raw frame stays native and never crosses the
     *  RN/Flutter wire (mirrors iOS `capturedImage`, excluded from Codable).
     *
     *  Cross-platform note: named `capturedImage` on both platforms; the Android
     *  type is a grayscale `Bitmap`, iOS a full-color `CGImage`. Don't assume
     *  identical pixels across platforms. */
    @Transient val capturedImage: Bitmap? = null,
    /** Stable id for this scan, retained on-device (debug logs + the extraction
     *  needed to re-predict). Pass it to [PXClient.repredict] to re-run against
     *  another template, or to [PXClient.report] to file a report (you supply the
     *  image). Null only if the scan couldn't be persisted. */
    val scanId: String? = null,
)

/** Minimal result of [PXClient.repredict] / [PXScanSession.repredict] — the
 *  retained extraction re-scored against a chosen template. Each [PXField]
 *  carries its own alternate `suggestions`. */
data class PXQuickResult(
    val templateId: String,
    val templateName: String?,
    val predictions: Map<String, PXField>,
    /** Barcodes detected in the reused frame, geometry NORMALIZED 0–1 in the same
     *  upright-frame space as the fields. Empty when none were found. Mirrors iOS
     *  `PXQuickResult`. */
    val barcodes: List<PXBarcode> = emptyList(),
    /** Width of the reused scan's upright frame the normalized geometry is
     *  relative to — multiply a normalized x by this to get pixels. Mirrors iOS
     *  `imageWidth`. */
    val imageWidth: Int,
    /** Height of the reused scan's upright frame the normalized geometry is
     *  relative to. Mirrors iOS `imageHeight`. */
    val imageHeight: Int,
)

// =========================================================================
// Scan guidance + events
// =========================================================================

/**
 * User-facing guidance emitted while a session is searching for and stabilizing
 * on a label. Hosts render these as on-screen hints. Mirrors iOS `PXGuidance`
 * case-for-case; [code] is the stable snake_case identifier for the
 * cross-platform (RN/Flutter) wire contract.
 */
sealed class PXGuidance {
    /** Stable snake_case identifier for the wire contract. */
    abstract val code: String

    /** No label found yet — keep pointing at one. */
    data object Searching : PXGuidance() { override val code = "searching" }
    /** Device / document moving — hold steady. */
    data object HoldStill : PXGuidance() { override val code = "hold_still" }
    /** Autofocus is hunting. */
    data object Focusing : PXGuidance() { override val code = "focusing" }
    /** Frame too soft / motion-blurred. */
    data object TooBlurry : PXGuidance() { override val code = "too_blurry" }
    /** OCR confidence low — usually lighting. */
    data object ImproveLighting : PXGuidance() { override val code = "improve_lighting" }
    /** Too little text in frame — move closer. */
    data object MoveCloser : PXGuidance() { override val code = "move_closer" }
    /** Text overflows the frame — move back. */
    data object MoveBack : PXGuidance() { override val code = "move_back" }
    /** A label is locked; [captured] of [of] stable frames collected. */
    data class Stabilizing(val captured: Int, val of: Int) : PXGuidance() { override val code = "stabilizing" }
}

/**
 * Events emitted by a [PXScanSession]. The host renders [Guidance] as live hints
 * (searching / hold steady / stabilizing N/target), presents [Prediction]
 * results, draws the [RegionResolved] bracket overlay, and surfaces [Failed]
 * errors. Convert to [PXScanEventPayload] for a serializable wire form. Mirrors
 * iOS `PXScanEvent`.
 */
sealed class PXScanEvent {
    /** The resolved trusted region (zone 2), delivered once the first frame's
     *  dimensions are known — before any prediction — so the host can draw the
     *  trusted overlay (brackets) up front. Emitted a single time per session. */
    data class RegionResolved(val region: PXRegionOfInterest) : PXScanEvent()

    /** Live guidance for the user (searching / hold still / stabilizing …). */
    data class Guidance(val guidance: PXGuidance) : PXScanEvent()

    /** A capture completed. May be [PXDetection.ambiguous]. */
    data class Prediction(val result: PXPredictionResult) : PXScanEvent()

    /** A capture attempt failed (e.g. OCR unavailable, no template loaded).
     *  [code] is a **non-null** stable snake_case identifier (defaults to
     *  `"unknown"`) mirroring the One-Shot `predict` error codes and iOS
     *  `PXFailure.code`. Prefer the typed [errorCode] for exhaustive `when`. */
    data class Failed(
        val message: String,
        val code: String = PXErrorCode.UNKNOWN.code,
    ) : PXScanEvent() {
        /** Typed view of [code]; [PXErrorCode.UNKNOWN] if unrecognized. Never null. */
        val errorCode: PXErrorCode get() = PXErrorCode.from(code)
    }
}

/**
 * Serializable form of a [PXScanEvent] — the RN/Flutter wire shape and the
 * payload the SDK Demos app prints as JSON. A `type` discriminator selects which
 * nested field is populated. Mirrors iOS `PXScanEventPayload` field-for-field:
 *  - region_resolved: `{ type, region }`
 *  - guidance:        `{ type, guidance: { code, captured?, of? } }`
 *  - prediction:      `{ type, prediction }`
 *  - failed:          `{ type, error: { code, message } }`
 */
data class PXScanEventPayload(
    /** Discriminator selecting the populated field. Serializes to the snake_case
     *  wire string ("region_resolved" | "guidance" | "prediction" | "failed"),
     *  matching iOS `PXScanEventPayload.Kind`. */
    val type: Kind,
    val region: PXRegionOfInterest? = null,
    val guidance: PXGuidancePayload? = null,
    val prediction: PXPredictionResult? = null,
    val error: PXErrorPayload? = null,
) {
    /** Typed discriminator; each entry serializes to its iOS-matching wire string. */
    enum class Kind {
        @com.google.gson.annotations.SerializedName("region_resolved") REGION_RESOLVED,
        @com.google.gson.annotations.SerializedName("guidance") GUIDANCE,
        @com.google.gson.annotations.SerializedName("prediction") PREDICTION,
        @com.google.gson.annotations.SerializedName("failed") FAILED,
    }
}

/** Flattened guidance for the wire contract: a stable [code] plus the
 *  stabilizing counters when present. Mirrors iOS `PXGuidancePayload`. */
data class PXGuidancePayload(
    val code: String,
    val captured: Int? = null,
    val of: Int? = null,
)

/** Serializable error: a stable [code] plus a human-readable [message]. Mirrors
 *  iOS `PXErrorPayload`. */
data class PXErrorPayload(
    val code: String,
    val message: String,
)

/** Serializable projection of this event. */
fun PXScanEvent.toPayload(): PXScanEventPayload = when (this) {
    is PXScanEvent.RegionResolved -> PXScanEventPayload(type = PXScanEventPayload.Kind.REGION_RESOLVED, region = region)
    is PXScanEvent.Guidance -> PXScanEventPayload(
        type = PXScanEventPayload.Kind.GUIDANCE,
        guidance = PXGuidancePayload(
            code = guidance.code,
            captured = (guidance as? PXGuidance.Stabilizing)?.captured,
            of = (guidance as? PXGuidance.Stabilizing)?.of,
        ),
    )
    is PXScanEvent.Prediction -> PXScanEventPayload(type = PXScanEventPayload.Kind.PREDICTION, prediction = result)
    is PXScanEvent.Failed -> PXScanEventPayload(
        type = PXScanEventPayload.Kind.FAILED,
        error = PXErrorPayload(code = code, message = message),
    )
}
