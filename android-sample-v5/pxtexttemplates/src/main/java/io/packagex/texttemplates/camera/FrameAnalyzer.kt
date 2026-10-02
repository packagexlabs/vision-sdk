package io.packagex.texttemplates.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.packagex.texttemplates.extraction.BarcodeExtractor
import io.packagex.texttemplates.extraction.OcrExtractor
import io.packagex.texttemplates.extraction.models.BoundingBox
import io.packagex.texttemplates.extraction.models.FrameExtractionResult
import io.packagex.texttemplates.extraction.models.OcrFrameResult
import io.packagex.texttemplates.prediction.RectD
import io.packagex.texttemplates.prediction.rotateRect
import io.packagex.texttemplates.session.AnalyzerEvent
import io.packagex.texttemplates.session.QualityFailReason
import io.packagex.texttemplates.util.bufferHeight
import io.packagex.texttemplates.util.bufferWidth
import io.packagex.texttemplates.util.toInputImage
import io.packagex.texttemplates.util.yPlaneBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-frame pipeline:
 *  1. In-flight gate (single concurrent frame).
 *  2. IMU stability — reject frames captured while the device was moving.
 *  3. AF state — reject frames captured while autofocus was hunting (fed by
 *     the Camera2 capture callback wired in CameraPreview).
 *  4. Blur — full-frame Laplacian variance on bootstrap, ROI-bounded on
 *     subsequent frames (ROI = previous accepted frame's text union bbox
 *     with 15 % padding, mapped back to sensor space).
 *  5. OCR + barcode in parallel.
 *  6. OCR quality (`minTextBlocks` / `minConfidence` thresholds).
 *  7. Density gate — text union bbox not overflowing the frame.
 *  8. Pair-stability — Jaccard of word strings + centroid-Δ vs previous
 *     accepted frame (rolling reference, no privileged frame).
 *
 * Also feeds each frame's mean Y-plane luma to [LowLightTorchController]
 * (auto-torch in low light) — a sample, not a gate.
 *
 * Emits one [AnalyzerEvent] per frame that crossed the in-flight gate.
 */
internal class FrameAnalyzer constructor(
    private val ocrExtractor: OcrExtractor,
    private val barcodeExtractor: BarcodeExtractor,
    private val blurDetector: BlurDetector,
    private val imuMonitor: IMUStabilityMonitor,
    private val perFrameOcrLog: PerFrameOcrLog,
    private val torchController: LowLightTorchController,
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "[FrameAnalyzer]"
        private const val MIN_TEXT_BLOCKS = 3
        private const val MIN_CONFIDENCE = 0.5f
        // Upper bound on `textUnionArea / frameArea`. Raised from 0.65 → 0.9 on
        // iOS after observing handheld labels routinely fill 70-80 % of the
        // frame — that's the desired framing, not over-cropping.
        private const val MAX_TEXT_DENSITY = 0.9
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val inFlight = AtomicBoolean(false)
    private val pairTracker = PairStabilityTracker()

    // Tuned internal default for the per-frame blur gate (not host-configurable).
    private val blurThreshold: Float = 150f

    /** True while the camera reports autofocus mid-scan (PASSIVE_SCAN /
     *  ACTIVE_SCAN). Updated from the Camera2 session capture callback wired
     *  in CameraPreview; read on the analysis path. Fall-open: stays false if
     *  the device never reports AF state (fixed-focus cameras). */
    @Volatile
    private var afHunting: Boolean = false

    /** Frames since the pair-stability reference was last updated. Drives the
     *  one-frame ROI-grace rule (mirrors iOS): if > 1, fall back to full-frame
     *  blur even though the tracker still holds a reference — the cached ROI
     *  is too stale to trust. Accesses are serialised by the in-flight gate. */
    private var framesSinceReferenceUpdate: Int = Int.MAX_VALUE

    @Volatile
    var enabled: Boolean = true

    /** Manual-mode bypass. When set, pair-stability is skipped entirely and
     *  every quality-passing frame is emitted as [AnalyzerEvent.Paired]. The
     *  manual capture flow drives its own fixed-size burst, so the rolling
     *  reference (which exists to find a *stable streak* in auto streaming) is
     *  not wanted — the user has explicitly asked us to commit this frame.
     *
     *  Resets the tracker on transitions (mirrors the iOS `didSet`) so
     *  auto-mode resumption doesn't carry a stale reference forward. */
    @Volatile
    var bypassPairStability: Boolean = false
        set(value) {
            if (field != value) {
                pairTracker.reset()
                framesSinceReferenceUpdate = Int.MAX_VALUE
            }
            field = value
        }

    /** Single per-frame event sink. The ScannerViewModel consumes these and
     *  drives the state machine. */
    var onAnalyzerEvent: ((AnalyzerEvent) -> Unit)? = null

    /** Fires exactly once, on the very first frame this analyzer ever sees,
     *  with the raw sensor buffer dimensions + rotation — regardless of
     *  [enabled] / capture mode. Lets a session resolve the trusted region and
     *  emit it up front (before any prediction), so the host can draw the
     *  bracket overlay as soon as frames flow. Reports raw sensor space; the
     *  session swaps to upright per the rotation. */
    @Volatile
    var onFirstFrameDims: ((sensorWidth: Int, sensorHeight: Int, rotation: Int) -> Unit)? = null

    @Volatile
    private var firstFrameDimsSent = false

    /** AF-state feed from the Camera2 session capture callback (CameraPreview).
     *  Hunting = PASSIVE_SCAN / ACTIVE_SCAN; everything else (settled, locked,
     *  failed, fixed-focus null) counts as settled — gating on the failed
     *  states would deadlock the pipeline, and the blur gate catches whatever
     *  soft frames they produce. */
    fun updateAutoFocusHunting(hunting: Boolean) {
        afHunting = hunting
    }

    /** Camera hand-off from CameraPreview: gives the torch controller the
     *  active CameraControl. Cleared in [detachCamera] on preview dispose. */
    fun attachCamera(control: androidx.camera.core.CameraControl) {
        torchController.attach(control)
    }

    fun detachCamera() {
        torchController.detach()
    }

    /** Clears pair-stability state so the next frame to pass per-frame quality
     *  re-seeds the rolling reference. */
    fun resetCaptureState() {
        pairTracker.reset()
        framesSinceReferenceUpdate = Int.MAX_VALUE
    }

    /**
     * Pre-loads the ML Kit text + barcode models off the capture path. ML Kit
     * initialises models lazily on the FIRST `process()` call, so the first
     * real frame pays model load + native init on top of inference (observed
     * ~570 ms vs ~250 ms steady state). Running one throwaway pass on a tiny
     * blank bitmap at scanner-open hides that cost before frames flow.
     * Idempotent and safe to call repeatedly — warm models return in a few ms.
     */
    fun warmUp() {
        scope.launch {
            try {
                val t = System.nanoTime()
                val bmp = android.graphics.Bitmap.createBitmap(
                    32, 32, android.graphics.Bitmap.Config.ARGB_8888,
                )
                val img = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
                ocrExtractor.extract(img)
                barcodeExtractor.extract(img)
                bmp.recycle()
                Log.d(TAG, "warmUp: ML Kit models initialised in ${"%.1f".format((System.nanoTime() - t) / 1_000_000.0)}ms")
            } catch (e: Exception) {
                Log.w(TAG, "warmUp failed (non-fatal)", e)
            }
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        // First-frame dims hook: fire once as soon as ANY frame arrives, even
        // while disabled / parked (Manual mode), so the region resolves and the
        // brackets can be drawn before capture starts. Cheap metadata reads only.
        if (!firstFrameDimsSent) {
            // Only consume the one-shot once the callback is actually wired —
            // otherwise a frame that arrives before session.start() sets it would
            // silently burn the slot and RegionResolved would never fire (brackets
            // would then fall back to their centred default).
            val cb = onFirstFrameDims
            if (cb != null) {
                firstFrameDimsSent = true
                runCatching {
                    cb.invoke(
                        imageProxy.bufferWidth,
                        imageProxy.bufferHeight,
                        imageProxy.imageInfo.rotationDegrees,
                    )
                }
            }
        }

        if (!enabled) {
            imageProxy.close()
            return
        }

        // In-flight gate: drop new frames while the previous is still being
        // processed. Replaces the earlier 100 ms throttle.
        if (!inFlight.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        // ---- IMU stability (loose threshold) ----
        if (!imuMonitor.passesLooseGate()) {
            imageProxy.close()
            inFlight.set(false)
            bumpReferenceStaleness()
            emit(AnalyzerEvent.QualityFailed(QualityFailReason.Motion))
            return
        }

        // ---- AF state ----
        // Frames captured mid-focus-hunt are soft in a way the Laplacian
        // sometimes passes (high-frequency noise survives defocus better than
        // text strokes). Reject them outright, like iOS's isAdjustingFocus
        // gate.
        if (afHunting) {
            imageProxy.close()
            inFlight.set(false)
            bumpReferenceStaleness()
            emit(AnalyzerEvent.QualityFailed(QualityFailReason.Focusing))
            return
        }

        // Copy Y-plane bytes and dimensions before launching coroutine (safe
        // after proxy close).
        val yBytes: ByteArray
        val width: Int
        val height: Int
        val rotation: Int
        try {
            yBytes = imageProxy.yPlaneBytes()
            width = imageProxy.bufferWidth
            height = imageProxy.bufferHeight
            rotation = imageProxy.imageInfo.rotationDegrees
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy Y-plane bytes", e)
            imageProxy.close()
            inFlight.set(false)
            return
        }

        scope.launch {
            var proxyClosed = false
            try {
                // ---- Low-light torch (sample, not a gate) ----
                torchController.onLumaSample(meanLuma(yBytes, width, height))

                // ---- Blur (Laplacian variance) ----
                // Full-frame on bootstrap; ROI-bounded to the previous
                // accepted frame's text union bbox once a fresh reference
                // exists (one-frame grace — a stale ROI would bias the
                // variance off-document).
                val roi = blurRoiInSensorSpace(width, height, rotation)
                val variance = if (roi != null) {
                    blurDetector.computeLaplacianVarianceInRoi(yBytes, width, height, roi)
                } else {
                    blurDetector.computeLaplacianVariance(yBytes, width, height)
                }
                if (variance < blurThreshold) {
                    bumpReferenceStaleness()
                    emit(AnalyzerEvent.QualityFailed(QualityFailReason.Blurry))
                    return@launch
                }

                // ---- OCR + barcode in parallel (need proxy open) ----
                val inputImage = imageProxy.toInputImage()
                val ocrDeferred = async {
                    val tOcr = System.nanoTime()
                    val r = ocrExtractor.extract(inputImage)
                    val ocrMs = (System.nanoTime() - tOcr) / 1_000_000.0
                    val wordCount = r.blocks.sumOf { b -> b.lines.sumOf { l -> l.words.size } }
                    // Locale.US: the debug screen parses the leading ms back
                    // out with toDoubleOrNull(), which only accepts a '.'
                    // decimal — locale-default formatting emits ',' on de/fr.
                    val entry = "${"%.1f".format(java.util.Locale.US, ocrMs)}ms  " +
                        "${r.blocks.size} blk  $wordCount w  " +
                        "conf ${"%.2f".format(java.util.Locale.US, r.averageConfidence)}"
                    Log.d(TAG, "MLKit OCR: $entry")
                    perFrameOcrLog.add(entry)
                    r
                }
                val barcodeDeferred = async { barcodeExtractor.extract(inputImage) }

                val ocrResult = ocrDeferred.await()
                val barcodeResult = barcodeDeferred.await()

                imageProxy.close()
                proxyClosed = true

                // ---- OCR quality gate ----
                if (ocrResult.blocks.size < MIN_TEXT_BLOCKS) {
                    bumpReferenceStaleness()
                    emit(AnalyzerEvent.QualityFailed(QualityFailReason.TooFewBlocks))
                    return@launch
                }
                if (ocrResult.averageConfidence < MIN_CONFIDENCE) {
                    bumpReferenceStaleness()
                    emit(AnalyzerEvent.QualityFailed(QualityFailReason.LowConfidence))
                    return@launch
                }

                // ---- Pair-stability inputs ----
                val words = flattenWordStrings(ocrResult)
                val textUnion = textUnionBbox(ocrResult)
                if (textUnion == null) {
                    // Quality passed but no word-level bboxes available — treat
                    // as too-few-blocks (most likely reason) so the hint layer
                    // still gets a usable signal.
                    bumpReferenceStaleness()
                    emit(AnalyzerEvent.QualityFailed(QualityFailReason.TooFewBlocks))
                    return@launch
                }

                // ---- Density gate ----
                val frameArea = width.toDouble() * height.toDouble()
                val textArea = textUnion.width.toDouble() * textUnion.height.toDouble()
                if (frameArea > 0 && textArea / frameArea > MAX_TEXT_DENSITY) {
                    bumpReferenceStaleness()
                    emit(AnalyzerEvent.QualityFailed(QualityFailReason.TooDense))
                    return@launch
                }

                // ---- Pair-stability ----
                val frameResult = FrameExtractionResult(
                    ocrResult = ocrResult,
                    barcodeResult = barcodeResult,
                    timestamp = System.currentTimeMillis(),
                    yBytes = yBytes,
                    frameWidth = width,
                    frameHeight = height,
                    rotationDegrees = rotation,
                    blurScore = variance.toDouble(),
                )
                if (bypassPairStability) {
                    // Manual burst: commit every quality-passing frame directly.
                    // The tracker (and so the blur ROI) is deliberately not
                    // updated — the burst is short and full-frame blur is fine.
                    emit(AnalyzerEvent.Paired(frameResult))
                } else {
                    // evaluate() rolls the reference forward unconditionally,
                    // so the ROI is fresh regardless of outcome.
                    framesSinceReferenceUpdate = 0
                    when (val outcome = pairTracker.evaluate(words, textUnion)) {
                        PairStabilityTracker.Outcome.BootstrapSeeded ->
                            emit(AnalyzerEvent.BootstrapSeeded(frameResult))
                        PairStabilityTracker.Outcome.Agreed ->
                            emit(AnalyzerEvent.Paired(frameResult))
                        is PairStabilityTracker.Outcome.Disagreed ->
                            emit(AnalyzerEvent.Unpaired(outcome.kind))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            } finally {
                if (!proxyClosed) imageProxy.close()
                inFlight.set(false)
            }
        }
    }

    private fun emit(event: AnalyzerEvent) {
        onAnalyzerEvent?.invoke(event)
    }

    /** Saturating increment — the Int.MAX_VALUE sentinel ("no reference yet")
     *  must not wrap negative and accidentally satisfy the `<= 1` grace rule. */
    private fun bumpReferenceStaleness() {
        if (framesSinceReferenceUpdate != Int.MAX_VALUE) framesSinceReferenceUpdate++
    }

    /**
     * Blur ROI for the current frame: the pair-stability reference bbox
     * (previous accepted frame's text union), valid only within the one-frame
     * grace window. Returns null when full-frame blur should be used.
     *
     * The reference bbox lives in upright (display) space — OcrExtractor
     * normalises ML Kit output to upright coords — while the Y plane is raw
     * sensor space, so the rect is rotated back by the inverse rotation before
     * indexing into the plane.
     */
    private fun blurRoiInSensorSpace(sensorW: Int, sensorH: Int, rotation: Int): BoundingBox? {
        if (bypassPairStability) return null
        if (framesSinceReferenceUpdate > 1) return null
        val ref = pairTracker.referenceBbox ?: return null

        val swapDims = rotation == 90 || rotation == 270
        val uprightW = (if (swapDims) sensorH else sensorW).toDouble()
        val uprightH = (if (swapDims) sensorW else sensorH).toDouble()
        val sensorRect = rotateRect(
            RectD(ref.left.toDouble(), ref.top.toDouble(), ref.width.toDouble(), ref.height.toDouble()),
            (360 - rotation) % 360,
            uprightW,
            uprightH,
        )
        return BoundingBox(
            left = sensorRect.minX.toInt(),
            top = sensorRect.minY.toInt(),
            width = sensorRect.width.toInt(),
            height = sensorRect.height.toInt(),
        )
    }

    /** Mean Y-plane luma (0-255), sampled on a 16 px grid — ~11k samples at
     *  1920×1440, cheap enough to run on every frame for the torch controller. */
    private fun meanLuma(yBytes: ByteArray, width: Int, height: Int): Double {
        val step = 16
        var sum = 0L
        var count = 0
        var y = 0
        while (y < height) {
            val row = y * width
            var x = 0
            while (x < width) {
                sum += yBytes[row + x].toInt() and 0xFF
                count++
                x += step
            }
            y += step
        }
        return if (count == 0) 255.0 else sum.toDouble() / count
    }

    private fun flattenWordStrings(ocr: OcrFrameResult): List<String> {
        val out = ArrayList<String>()
        for (block in ocr.blocks) {
            for (line in block.lines) {
                for (word in line.words) {
                    if (word.text.isNotEmpty()) out.add(word.text)
                }
            }
        }
        return out
    }

    private fun textUnionBbox(ocr: OcrFrameResult): BoundingBox? {
        var minLeft = Int.MAX_VALUE
        var minTop = Int.MAX_VALUE
        var maxRight = Int.MIN_VALUE
        var maxBottom = Int.MIN_VALUE
        var any = false
        for (block in ocr.blocks) {
            for (line in block.lines) {
                for (word in line.words) {
                    val bb = word.boundingBox ?: continue
                    any = true
                    if (bb.left < minLeft) minLeft = bb.left
                    if (bb.top < minTop) minTop = bb.top
                    if (bb.right > maxRight) maxRight = bb.right
                    if (bb.bottom > maxBottom) maxBottom = bb.bottom
                }
            }
        }
        if (!any || maxRight <= minLeft || maxBottom <= minTop) return null
        return BoundingBox(
            left = minLeft,
            top = minTop,
            width = maxRight - minLeft,
            height = maxBottom - minTop,
        )
    }
}
