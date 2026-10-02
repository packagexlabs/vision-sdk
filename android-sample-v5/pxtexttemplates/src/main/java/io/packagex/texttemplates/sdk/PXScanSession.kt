package io.packagex.texttemplates.sdk

import android.graphics.Bitmap
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageProxy
import io.packagex.texttemplates.aggregation.FrameAggregator
import io.packagex.texttemplates.camera.FrameAnalyzer
import io.packagex.texttemplates.camera.IMUStabilityMonitor
import io.packagex.texttemplates.extraction.models.BarcodeFrameResult
import io.packagex.texttemplates.extraction.models.OcrFrameResult
import io.packagex.texttemplates.prediction.DebugBarcode
import io.packagex.texttemplates.prediction.PredictionEngine
import io.packagex.texttemplates.prediction.ProcessedTemplate
import io.packagex.texttemplates.prediction.RectD
import io.packagex.texttemplates.prediction.TemplateDetectionResult
import io.packagex.texttemplates.prediction.computeFocusRects
import io.packagex.texttemplates.prediction.rotateRect
import io.packagex.texttemplates.util.yPlaneToBitmap
import io.packagex.texttemplates.session.AnalyzerEvent
import io.packagex.texttemplates.session.QualityFailReason
import io.packagex.texttemplates.session.ScannerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Framework-free capture controller — the single owner of the per-frame
 * analyzer, the multi-frame aggregator, and the prediction engine for a scan.
 * The full [ScannerState] capture state machine (Seeking → Stabilising →
 * Capturing → Predicting → Result/Ambiguous/Error) and the staleness/
 * total-capture timeouts were ported out of the app's `ScannerViewModel` so the
 * app becomes a thin adapter. Capture is always automatic (Auto-only), matching
 * iOS.
 *
 * Feed frames two ways:
 *  - Host-Frames: call [process] with each CameraX `ImageProxy`.
 *  - SDK-Camera: hand the session to a [PXScannerView], which owns CameraX.
 *
 * Emits [PXScanEvent]s: [PXScanEvent.Guidance] (live hints), and
 * [PXScanEvent.Prediction] / [PXScanEvent.Failed] on a completed capture. The
 * completed [PXPredictionResult] carries everything the host needs for a result
 * screen (fields, detection, resolved region).
 *
 * [onEvent] fires on a background thread — marshal to your UI thread.
 */
class PXScanSession internal constructor(
    internal val frameAnalyzer: FrameAnalyzer,
    private val frameAggregator: FrameAggregator,
    private val imuMonitor: IMUStabilityMonitor,
    private val predictionEngine: PredictionEngine,
    private val resolveTemplate: (OcrFrameResult, BarcodeFrameResult) -> Pair<String, ProcessedTemplate>?,
    private val isPoolLoaded: () -> Boolean,
    private val lastDetectionProvider: () -> TemplateDetectionResult?,
    private val templateById: (String) -> ProcessedTemplate?,
    private val templateName: (String) -> String?,
    /** Persist a completed capture (extraction + pre-captured image-free debug
     *  logs) and return its scanId, echoed on the [PXPredictionResult] so the host
     *  can later [repredict] or `PXClient.report` it. The debug arg is captured
     *  atomically with the prediction (see [runResolveAndPredict]). Baked in at
     *  session creation via [PXClient.makeScanSession]. */
    private val persistScan: (ScanCapture, String?, com.google.gson.JsonObject?) -> String,
    /** Load a previously persisted capture by scanId for [repredict], or null if
     *  it was never stored / has been evicted. */
    private val loadScanCapture: (String) -> ScanCapture?,
    /** Host-supplied trusted region (zone 2), NORMALIZED 0–1 (left/top/right/
     *  bottom, top-left origin, upright-frame coords), or null for the SDK
     *  default centred box. Baked in at session creation via
     *  [PXClient.makeScanSession]. When non-null, prediction's zone-2 honors this
     *  region and the resolved [PXRegionOfInterest] is echoed via
     *  [PXScanEvent.RegionResolved]. */
    private val regionOfInterest: PXRegion? = null,
    /** Per-session tunables (blur gate, single-shot). Baked in at session
     *  creation via [PXClient.makeScanSession]. */
    private val configuration: PXScanConfiguration = PXScanConfiguration(),
    /** Event sink, supplied at creation (see [PXClient.makeScanSession]). Fires on
     *  a background thread — marshal to your UI thread. */
    private val onEvent: (PXScanEvent) -> Unit,
) : PXScanControl {
    companion object {
        private const val TARGET_FRAME_COUNT = 3
        private const val PER_FRAME_FAIL_HYSTERESIS = 5
        private const val PER_STATE_STALENESS_MS = 5_000L
        private const val TOTAL_CAPTURE_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lock = Any()

    private var state: ScannerState = ScannerState.Idle
    private var perFrameFailStreak = 0
    private var started = false
    /** True after a result auto-pauses the session, or after an explicit
     *  [pause]. Cleared by [resume] / [arm]. */
    private var paused = false

    private var predictionJob: Job? = null
    private var perStateStalenessJob: Job? = null
    private var totalCaptureTimeoutJob: Job? = null

    private var lastCapture: LastCapture? = null
    private var _lastDetection: TemplateDetectionResult? = null
    /** Resolved once the first frame's upright dimensions are known, then
     *  emitted via [PXScanEvent.RegionResolved] so the host can draw the
     *  trusted overlay up front. Also drives the prediction zone-2 override. */
    @Volatile
    private var resolvedRegion: PXRegionOfInterest? = null

    // ---- lifecycle ----

    /** Wire the analyzer + start motion sensors + warm the ML Kit models. Does
     *  not enable capture — call [arm] once a template pool is loaded. SDK-managed:
     *  [process] starts+arms lazily (Host-Frames) and [PXScannerView] drives it
     *  (SDK-Camera); hosts never call this directly. */
    internal fun start() {
        if (started) return
        started = true
        imuMonitor.start()
        frameAnalyzer.onAnalyzerEvent = { event -> handleEvent(event) }
        frameAnalyzer.onFirstFrameDims = { w, h, rot -> resolveRegionIfNeeded(w, h, rot) }
        frameAnalyzer.warmUp()
    }

    /** Compute the trusted region once the first frame's dimensions are known
     *  and emit it so the host can draw the overlay before any prediction.
     *  Fires once; the sensor dims are swapped to upright per [rotation]. */
    private fun resolveRegionIfNeeded(sensorWidth: Int, sensorHeight: Int, rotation: Int) {
        if (resolvedRegion != null || sensorWidth <= 0 || sensorHeight <= 0) return
        val swap = rotation == 90 || rotation == 270
        val uprightW = if (swap) sensorHeight else sensorWidth
        val uprightH = if (swap) sensorWidth else sensorHeight
        val region = PXRegionOfInterest.resolve(regionOfInterest, uprightW, uprightH)
        resolvedRegion = region
        onEvent(PXScanEvent.RegionResolved(region))
    }

    /** Full teardown: release sensors/models/analyzer. In Host-Frames the host
     *  owns the camera lifecycle, so it calls this when done (e.g. on screen
     *  dispose); in SDK-Camera [PXScannerView] calls it internally. Mirrors iOS
     *  `PXScanSession.stop()`. */
    fun stop() {
        started = false
        frameAnalyzer.enabled = false
        frameAnalyzer.onAnalyzerEvent = null
        frameAnalyzer.onFirstFrameDims = null
        frameAnalyzer.detachCamera()
        imuMonitor.stop()
        cancelTimers()
        predictionJob?.cancel()
        frameAggregator.reset()
        frameAnalyzer.resetCaptureState()
        setState(ScannerState.Idle)
    }

    /** Arm capture (Auto-only). Enables the analyzer, resets capture state, and
     *  surfaces Searching immediately (iOS resume() emits .searching too).
     *  SDK-managed: driven by [process]/[resume] and [PXScannerView]. */
    internal fun arm() {
        synchronized(lock) {
            paused = false
            cancelTimers()
            frameAggregator.reset()
            frameAnalyzer.resetCaptureState()
            perFrameFailStreak = 0
            predictionJob?.cancel()
            frameAnalyzer.enabled = true
            setState(ScannerState.Seeking)
            startTotalCaptureTimeoutIfNeeded()
            emitGuidance(PXGuidance.Searching)
        }
    }

    /** Host-initiated pause: stop processing frames (no events fire) while the
     *  host keeps its preview. Use it to hold on a result under review. */
    override fun pause() {
        synchronized(lock) {
            paused = true
            frameAnalyzer.enabled = false
        }
    }

    /** Resume scanning the next item after [pause] or the auto-pause that follows
     *  a prediction — starts a fresh capture (state reset). Mirrors iOS
     *  `resume()`. No-op if not paused. */
    override fun resume() {
        synchronized(lock) {
            if (!paused || !started) return
            arm()
        }
    }

    /** Host-Frames: feed one CameraX frame. The analyzer closes the proxy. The
     *  session starts + arms itself on the first frame (SDK-managed lifecycle), so
     *  a Host-Frames integrator only feeds frames and drives [pause]/[resume]. */
    fun process(frame: ImageProxy) {
        if (!started) {
            start()
            arm()
        }
        frameAnalyzer.analyze(frame)
    }

    internal fun attachCameraControl(control: CameraControl) = frameAnalyzer.attachCamera(control)

    internal fun detachCamera() = frameAnalyzer.detachCamera()

    internal fun updateAutoFocusHunting(hunting: Boolean) = frameAnalyzer.updateAutoFocusHunting(hunting)

    // ---- re-prediction on the retained capture ----

    /**
     * Re-run prediction against a specific template using a persisted scan's
     * retained capture — no re-scan/OCR reprocessing. Use when auto-detect chose
     * the wrong template. [scanId] is the id echoed on the [PXPredictionResult]
     * of the scan to re-predict; it need not be the most recent one (any scan
     * still in the cache works). Returns a minimal [PXQuickResult] (no detection/
     * region), matching iOS `PXScanSession.repredict`. Throws [PXException] with
     * code `no_retained_frame` if the scanId is unknown / evicted, or
     * `template_not_found` if the id isn't in the loaded pool.
     */
    override suspend fun repredict(scanId: String, templateId: String): PXQuickResult = withContext(Dispatchers.Default) {
        val cap = loadScanCapture(scanId) ?: throw PXException("No capture for scan $scanId", PXErrorCode.NO_RETAINED_FRAME)
        val tmpl = templateById(templateId) ?: throw PXException("Template $templateId not loaded", PXErrorCode.TEMPLATE_NOT_FOUND)
        // predict + read the engine's display-space detected barcodes atomically
        // under the engine lock, so a concurrent prediction can't swap the shared
        // lastDetectedBarcodes between the two. Mirrors iOS.
        val (response, detectedBarcodes) = synchronized(predictionEngine.engineLock) {
            val r = predictionEngine.predict(
                cap.ocr, cap.barcodes, tmpl,
                frameWidth = cap.frameWidth, frameHeight = cap.frameHeight,
                rotationDegrees = cap.rotation,
                previewRect = cap.previewRect, innerBox = cap.innerBox,
            )
            r to predictionEngine.lastDetectedBarcodes
        }
        // Normalize + report dims against the reused scan capture's frame — the
        // space the retained engine geometry is in. Mirrors iOS `quick(...)`.
        PXQuickResult(
            templateId = templateId,
            templateName = templateName(templateId),
            predictions = PXResultMapper.predictions(response, cap.frameWidth, cap.frameHeight),
            barcodes = PXResultMapper.barcodes(detectedBarcodes, cap.frameWidth, cap.frameHeight),
            imageWidth = cap.frameWidth,
            imageHeight = cap.frameHeight,
        )
    }

    // ---- state machine (ported from ScannerViewModel.handleEvent) ----

    private fun handleEvent(event: AnalyzerEvent) {
        synchronized(lock) {
            val target = TARGET_FRAME_COUNT

            if (state.isIdle) {
                setState(ScannerState.Seeking)
                startTotalCaptureTimeoutIfNeeded()
                emitGuidance(PXGuidance.Searching)
            }

            when (event) {
                is AnalyzerEvent.QualityFailed -> {
                    perFrameFailStreak += 1
                    if (state.isStabilising && perFrameFailStreak >= PER_FRAME_FAIL_HYSTERESIS) {
                        frameAggregator.reset()
                        frameAnalyzer.resetCaptureState()
                        setState(ScannerState.Seeking)
                    }
                    // Surface the gate that rejected the frame as a host hint
                    // (Android previously dropped these; iOS emits them).
                    emitGuidance(guidanceFor(event.reason))
                }
                is AnalyzerEvent.BootstrapSeeded -> {
                    perFrameFailStreak = 0
                    if (state.isSeeking) setState(ScannerState.Stabilising)
                    restartStalenessTimer()
                    emitGuidance(PXGuidance.Stabilizing(captured = 0, of = target))
                }
                is AnalyzerEvent.Paired -> {
                    perFrameFailStreak = 0
                    val newStreak = when (val s = state) {
                        is ScannerState.Stabilising -> 1
                        is ScannerState.Capturing -> s.streak + 1
                        else -> return
                    }
                    frameAggregator.addFrame(event.frame)
                    if (newStreak >= target) {
                        frameAnalyzer.enabled = false
                        cancelTimers()
                        setState(ScannerState.Predicting)
                        runPrediction()
                    } else {
                        setState(ScannerState.Capturing(streak = newStreak, target = target))
                        restartStalenessTimer()
                        emitGuidance(PXGuidance.Stabilizing(captured = newStreak, of = target))
                    }
                }
                is AnalyzerEvent.Unpaired -> {
                    perFrameFailStreak = 0
                    if (state.isCapturing) {
                        frameAggregator.reset()
                        setState(ScannerState.Stabilising)
                        restartStalenessTimer()
                        emitGuidance(PXGuidance.Stabilizing(captured = 0, of = target))
                    } else {
                        emitGuidance(PXGuidance.HoldStill)
                    }
                }
            }
        }
    }

    // ---- timers ----

    private fun restartStalenessTimer() {
        perStateStalenessJob?.cancel()
        perStateStalenessJob = scope.launch {
            delay(PER_STATE_STALENESS_MS)
            synchronized(lock) {
                if (state.isStabilising || state.isCapturing) {
                    frameAggregator.reset()
                    frameAnalyzer.resetCaptureState()
                    perFrameFailStreak = 0
                    setState(ScannerState.Seeking)
                    emitGuidance(PXGuidance.Searching)
                }
            }
        }
    }

    private fun startTotalCaptureTimeoutIfNeeded() {
        if (totalCaptureTimeoutJob != null) return
        totalCaptureTimeoutJob = scope.launch {
            delay(TOTAL_CAPTURE_TIMEOUT_MS)
            synchronized(lock) {
                if (!state.isActiveCapture) return@launch
                frameAggregator.reset()
                frameAnalyzer.resetCaptureState()
                perFrameFailStreak = 0
                totalCaptureTimeoutJob = null
                setState(ScannerState.Seeking)
                startTotalCaptureTimeoutIfNeeded()
                emitGuidance(PXGuidance.Searching)
            }
        }
    }

    private fun cancelTimers() {
        perStateStalenessJob?.cancel(); perStateStalenessJob = null
        totalCaptureTimeoutJob?.cancel(); totalCaptureTimeoutJob = null
    }

    // ---- prediction ----

    private fun runPrediction() {
        predictionJob = scope.launch {
            try {
                val aggregated = frameAggregator.aggregate()
                val bestOcr = aggregated.fusedOcrFrame ?: aggregated.bestOcrFrame
                if (bestOcr == null) {
                    emitFailed("No readable text was found — hold steady on the label and try again.", PXErrorCode.NO_OCR_DATA)
                    return@launch
                }
                val bestBarcodes = aggregated.bestBarcodeResult ?: BarcodeFrameResult(emptyList())

                // Focus rects in upright space (parity with ScannerViewModel).
                val sensorW = aggregated.bestFrameWidth
                val sensorH = aggregated.bestFrameHeight
                val rotation = aggregated.bestFrameRotation
                val swap = rotation == 90 || rotation == 270
                val uprightW = if (swap) sensorH else sensorW
                val uprightH = if (swap) sensorW else sensorH
                // Ensure the resolved region is available for the Prediction result
                // even if the first-frame event never fired — resolve from this
                // frame's upright dims. Non-null contract on PXPredictionResult.
                if (resolvedRegion == null && uprightW > 0 && uprightH > 0) {
                    resolvedRegion = PXRegionOfInterest.resolve(regionOfInterest, uprightW, uprightH)
                }
                val (uprightPreview, defaultUprightInner) =
                    computeFocusRects(uprightW.toDouble(), uprightH.toDouble())
                // Host ROI override: when a region was supplied, zone-2's trusted
                // inner box is the resolved region's box (in upright pixels) —
                // resolved fresh for this frame's upright dims so it matches the
                // brackets the host drew. Default path (null ROI) unchanged.
                val uprightInner = if (regionOfInterest != null) {
                    PXRegionOfInterest.resolve(regionOfInterest, uprightW, uprightH).imageRect
                } else {
                    defaultUprightInner
                }

                val previewRect: RectD?
                val innerBox: RectD?
                val predFrameW: Int
                val predFrameH: Int
                val predRotation: Int
                if (bestOcr.coordsPreRotated) {
                    previewRect = uprightPreview
                    innerBox = uprightInner
                    predFrameW = uprightW
                    predFrameH = uprightH
                    predRotation = 0
                } else {
                    val inverse = (360 - rotation) % 360
                    previewRect = uprightPreview?.let { rotateRect(it, inverse, uprightW.toDouble(), uprightH.toDouble()) }
                    innerBox = uprightInner?.let { rotateRect(it, inverse, uprightW.toDouble(), uprightH.toDouble()) }
                    predFrameW = sensorW
                    predFrameH = sensorH
                    predRotation = rotation
                }

                // Opt-in captured frame: render the retained luma plane to an
                // upright grayscale bitmap for the host (review screen / per-field
                // crops). Off unless includeCapturedImage was set, so the default
                // path pays no cost. Best-effort — a decode failure just leaves it
                // null. Native only — never serialized (see PXPredictionResult).
                val yBytes = aggregated.bestFrameYBytes
                val capturedBitmap: Bitmap? =
                    if (configuration.includeCapturedImage && yBytes != null && sensorW > 0 && sensorH > 0) {
                        runCatching { yPlaneToBitmap(yBytes, sensorW, sensorH, rotation) }.getOrNull()
                    } else {
                        null
                    }

                lastCapture = LastCapture(
                    finalOcr = bestOcr, bestBarcodes = bestBarcodes,
                    predFrameW = predFrameW, predFrameH = predFrameH, predRotation = predRotation,
                    previewRect = previewRect, innerBox = innerBox,
                )

                // Pass the opt-in frame as a transient argument (NOT retained in
                // lastCapture, which lives on for repredict) — parity with iOS,
                // whose SessionExtraction holds no image. The host owns it once
                // emitted.
                runResolveAndPredict(lastCapture!!, capturedBitmap)
            } catch (e: Exception) {
                emitFailed("Prediction failed: ${e.message}", PXErrorCode.PREDICTION_FAILED)
            }
        }
    }

    private fun runResolveAndPredict(cap: LastCapture, capturedBitmap: Bitmap?) {
        val resolved = resolveTemplate(cap.finalOcr, cap.bestBarcodes)
        _lastDetection = lastDetectionProvider()
        if (resolved == null) {
            // Distinguish "nothing to match against" from a genuine ambiguous /
            // no-confident-match. An empty pool is a caller error → descriptive
            // Failed; a loaded pool that didn't resolve is a normal Prediction
            // with templateId=null (ambiguous / no confident match).
            if (!isPoolLoaded()) {
                emitFailed(
                    "No templates are loaded. Call PXClient.load(...) before scanning.",
                    PXErrorCode.NOT_LOADED,
                )
                return
            }
            emitPrediction(null, response = null, detection = _lastDetection, capturedBitmap = capturedBitmap, debug = null)
            return
        }
        val (id, tmpl) = resolved
        // Run predict + capture its image-free debug AND the engine's display-space
        // detected barcodes atomically under the engine lock, so a concurrent
        // prediction on the shared engine can't swap either between them. Barcodes
        // are read here (a template was predicted) — the ambiguous branch above
        // emits an empty list, since lastDetectedBarcodes would be stale. Mirrors iOS.
        val (response, debug, detectedBarcodes) = synchronized(predictionEngine.engineLock) {
            val r = predictionEngine.predict(
                cap.finalOcr, cap.bestBarcodes, tmpl,
                frameWidth = cap.predFrameW, frameHeight = cap.predFrameH,
                rotationDegrees = cap.predRotation,
                previewRect = cap.previewRect, innerBox = cap.innerBox,
            )
            val d = runCatching {
                predictionEngine.imageFreeDebugMap(id)?.let { PXScanStore.gson.toJsonTree(it).asJsonObject }
            }.getOrNull()
            Triple(r, d, predictionEngine.lastDetectedBarcodes)
        }
        // Pass raw display-space barcodes through — emitPrediction normalizes them
        // (and the field geometry) against the resolved region's frame dims, so
        // both use one consistent frame size. Mirrors iOS `result(...)`.
        emitPrediction(
            id, response, detection = _lastDetection, capturedBitmap = capturedBitmap,
            debug = debug, barcodes = detectedBarcodes,
        )
    }

    private fun emitPrediction(
        templateId: String?,
        response: io.packagex.texttemplates.data.remote.dto.PredictionResponse?,
        detection: TemplateDetectionResult?,
        capturedBitmap: Bitmap? = null,
        debug: com.google.gson.JsonObject? = null,
        /** Raw display-space detected barcodes for this prediction (normalized
         *  here against the region's frame dims). Empty on the ambiguous branch
         *  (no prediction ran) so no stale barcodes leak. */
        barcodes: List<DebugBarcode> = emptyList(),
    ) {
        // Non-null region contract: prefer the resolved region; fall back to a
        // full-frame region if the first-frame dims were never available.
        val region = resolvedRegion ?: PXRegionOfInterest(
            bounds = listOf(listOf(0f, 0f), listOf(1f, 1f)),
            source = if (regionOfInterest != null) PXRegionOfInterest.Source.HOST else PXRegionOfInterest.Source.DEFAULT,
            frameWidth = 0,
            frameHeight = 0,
        )
        // Frame dims the engine geometry is in = the resolved region's frame (the
        // region is resolved from the same upright frame). Normalize predictions +
        // barcodes against these and echo them at the top level. Mirrors iOS.
        val frameW = region.frameWidth
        val frameH = region.frameHeight
        // Persist the retained capture + the debug snapshot captured atomically
        // with the prediction (see runResolveAndPredict), so the host can repredict
        // or PXClient.report this scan by its id. Best-effort — a storage failure
        // just leaves scanId null.
        val scanId = lastCapture?.let { lc ->
            runCatching {
                persistScan(
                    ScanCapture(
                        ocr = lc.finalOcr, barcodes = lc.bestBarcodes,
                        frameWidth = lc.predFrameW, frameHeight = lc.predFrameH,
                        rotation = lc.predRotation,
                        previewRect = lc.previewRect, innerBox = lc.innerBox,
                    ),
                    templateId,
                    debug,
                )
            }.getOrNull()
        }
        onEvent(PXScanEvent.Prediction(PXPredictionResult(
            templateId = templateId,
            templateName = templateId?.let { templateName(it) },
            predictions = PXResultMapper.predictions(response, frameW, frameH),
            detection = PXResultMapper.detection(detection, templateId),
            barcodes = PXResultMapper.barcodes(barcodes, frameW, frameH),
            resolvedRegionOfInterest = region,
            imageWidth = frameW,
            imageHeight = frameH,
            capturedImage = capturedBitmap,
            scanId = scanId,
        )))
        // Single-shot: fully stop after the first prediction (analyzer disabled,
        // onEvent detached — no more events). Otherwise auto-pause: the capture is
        // done and the analyzer is already disabled, so mark the session paused
        // and park at Idle silently (no StateChanged that would clobber the host's
        // just-set result UI). The host calls resume() to scan the next item.
        // Mirrors iOS resetOrStop().
        if (configuration.singleShot) {
            stop()
        } else {
            paused = true
            state = ScannerState.Idle
        }
    }

    private fun emitFailed(message: String, errorCode: PXErrorCode = PXErrorCode.UNKNOWN) {
        onEvent(PXScanEvent.Failed(message, errorCode.code))
        // Same auto-pause contract as a prediction — host calls resume() to retry.
        paused = true
        frameAnalyzer.enabled = false
        state = ScannerState.Idle
    }

    /** Internal state transition — does NOT emit to the host. Host-facing hints
     *  are surfaced separately as [PXGuidance] via [emitGuidance] at the same
     *  points iOS emits them, so the public event contract never exposes the
     *  internal [ScannerState] machine. */
    private fun setState(newState: ScannerState) {
        state = newState
    }

    private fun emitGuidance(guidance: PXGuidance) {
        onEvent(PXScanEvent.Guidance(guidance))
    }

    /** Map a per-frame quality-gate failure to a user hint. Mirrors iOS
     *  `guidance(for:)`. */
    private fun guidanceFor(reason: QualityFailReason): PXGuidance = when (reason) {
        QualityFailReason.Motion -> PXGuidance.HoldStill
        QualityFailReason.Focusing -> PXGuidance.Focusing
        QualityFailReason.Blurry -> PXGuidance.TooBlurry
        QualityFailReason.TooFewBlocks -> PXGuidance.MoveCloser
        QualityFailReason.LowConfidence -> PXGuidance.ImproveLighting
        QualityFailReason.TooDense -> PXGuidance.MoveBack
    }

    private data class LastCapture(
        val finalOcr: OcrFrameResult,
        val bestBarcodes: BarcodeFrameResult,
        val predFrameW: Int,
        val predFrameH: Int,
        val predRotation: Int,
        val previewRect: RectD?,
        val innerBox: RectD?,
    )
}
