package io.packagex.visiondemo.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.packagex.docscanner.DocumentQuad
import com.packagex.docscanner.DocumentScanner
import com.packagex.docscanner.DocumentScannerConfig
import com.packagex.docscanner.camera.DocumentAnalyzer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the scanner ViewModel needs from Document Acquisition; [DocumentController] is the real one, tests use a fake. */
interface DocumentCamera {
    /** Live page quad (valid ones only), null when no page is in view or detection is off. */
    val quad: StateFlow<DocumentQuad?>
    /** Perspective-cropped captures (auto or shutter); null when a capture failed. */
    val stills: Flow<Bitmap?>
    /** Auto capture once the quad holds still. */
    var auto: Boolean
    /** Live corner detection (and so auto capture) runs only while true: off under the drawer, sheets and processing. */
    var detecting: Boolean
    /** Shutter. */
    fun capture(): CaptureStart
    /** Zoom preset; kept across rebinds. */
    fun zoom(ratio: Float)
    /** Torch; kept across rebinds (off on a lens without a flash). */
    fun torch(on: Boolean)
    /** Front or back lens (iOS `flipCamera`); rebinds when bound. */
    fun lens(front: Boolean)
    /** Tap-to-focus at a view-normalized (0..1) point. */
    fun focus(x: Float, y: Float)
    /** Leaving Document Acquisition: release the camera before the next owner claims it (the surface also releases it on dispose). */
    fun release()
    /** Dewarp, quality and enhance for one captured page. */
    suspend fun process(original: Bitmap, index: Int): DocumentPage
    /** Recognises the text layers and writes a searchable PDF under cache/docs; null on failure. */
    suspend fun exportPdf(pages: List<DocumentPage>, enhanced: Boolean): File?
}

/** Output long edge cap after the perspective crop: each page keeps its Original and Enhanced bitmaps
 *  (2400 x 1800 ARGB is ~17 MB apiece), and dewarp time grows with it. */
internal const val MAX_OUTPUT_LONG_EDGE = 2400

/** The perspective crop's output size for a quad measuring [w] x [h] px: capped at [MAX_OUTPUT_LONG_EDGE], never upscaled. */
internal fun pageOutputSize(w: Int, h: Int): Pair<Int, Int> {
    val scale = min(1f, MAX_OUTPUT_LONG_EDGE.toFloat() / max(w, h))
    return max(8, (w * scale).roundToInt()) to max(8, (h * scale).roundToInt())
}

/** What a shutter press started. */
enum class CaptureStart { Started, Busy, NoPage }

/** No Document Acquisition (the default for callers that don't use it). */
object NoDocumentCamera : DocumentCamera {
    override val quad: StateFlow<DocumentQuad?> = MutableStateFlow(null)
    override val stills: Flow<Bitmap?> = emptyFlow()
    override var auto = false
    override var detecting = false
    override fun capture() = CaptureStart.NoPage
    override fun zoom(ratio: Float) {}
    override fun torch(on: Boolean) {}
    override fun lens(front: Boolean) {}
    override fun focus(x: Float, y: Float) {}
    override fun release() {}
    override suspend fun process(original: Bitmap, index: Int) = DocumentPage(original, index)
    override suspend fun exportPdf(pages: List<DocumentPage>, enhanced: Boolean): File? = null
}

/**
 * Document capture, from the original demo's DocumentCaptureActivity: CameraX preview + analysis + still,
 * live corner detection from the in-house `:docscanner` (native LiteRT, Kalman-smoothed quad), auto-capture
 * once the quad holds still, full-resolution still, perspective crop to the quad. The composable host
 * ([DocumentSurface]) binds it while Document Acquisition is live and unbinds it while the camera is paused.
 */
@Singleton
class DocumentController @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
) : DocumentCamera {
    private val _quad = MutableStateFlow<DocumentQuad?>(null)
    override val quad: StateFlow<DocumentQuad?> = _quad.asStateFlow()

    private val _stills = Channel<Bitmap?>(Channel.BUFFERED)
    override val stills: Flow<Bitmap?> = _stills.receiveAsFlow()

    @Volatile override var auto = false

    @Volatile override var detecting = false
        set(v) {
            if (field == v) return
            field = v
            applyAnalyzer()
        }

    private val session = DocumentSession(ctx)
    private val main = ContextCompat.getMainExecutor(ctx)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val cropExecutor = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var useCases: List<UseCase> = emptyList()
    private var analysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var zoomRatio = 1f
    private var torchOn = false
    /** The lens in use; the preview mirrors the front lens, so the outline must too. */
    var front = false
        private set
    /** bind() was called and unbind() not since; the provider future may complete after an unbind. */
    private var wanted = false
    private var bound: Pair<LifecycleOwner, PreviewView>? = null

    @Volatile private var scanner: DocumentScanner? = null

    /**
     * Second detector for the captured still: single inference, no Kalman/1€/ROI/
     * dead-band, so the corners describe exactly the image we crop. The live
     * detector's smoothed quad lags and sat a few % inside the paper.
     */
    private val stillScanner by lazy {
        DocumentScanner.fromAsset(
            ctx,
            config = DocumentScannerConfig(
                inferenceInterval = 1,
                useOneEuro = false,
                useRoiTracking = false,
                deadBandFraction = 0f,
                synthesizeGatedCorners = false,
                adaptiveMeasurementNoise = false,
                processNoise = 1e6f, // trust the measurement outright
            ),
        )
    }

    private val gate = AutoCaptureGate(STILL_FRAMES, STILL_FRACTION)

    @Volatile private var lastQuad: DocumentQuad? = null

    @Volatile private var capturing = false

    /** Main thread. Claim [io.packagex.visiondemo.camera.CameraOwner.Document] first so the SDK camera is stopped. */
    fun bind(owner: LifecycleOwner, preview: PreviewView) {
        wanted = true
        bound = owner to preview
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            // The corner model loads off the main thread; binding is main-thread only.
            if (scanner == null) scanner = DocumentScanner.fromAsset(ctx)
            main.execute { if (wanted && bound == owner to preview) bindNow(future.get(), owner, preview) }
        }, analysisExecutor)
    }

    private fun bindNow(p: ProcessCameraProvider, owner: LifecycleOwner, preview: PreviewView) {
        provider = p
        // Same 4:3 for analysis and still so the quad scales straight across.
        val fourByThree = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
        val pv = Preview.Builder().setResolutionSelector(fourByThree).build().also { it.surfaceProvider = preview.surfaceProvider }
        val an = ImageAnalysis.Builder()
            .setResolutionSelector(fourByThree)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        val cap = ImageCapture.Builder()
            .setResolutionSelector(fourByThree)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(97)
            .build()
        try {
            // The SDK camera is stopped while Document Acquisition owns the sensor.
            p.unbindAll()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            camera = p.bindToLifecycle(owner, selector, pv, an, cap).also {
                it.cameraControl.setZoomRatio(zoomRatio)
                if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(torchOn)
            }
            useCases = listOf(pv, an, cap)
            analysis = an
            imageCapture = cap
            applyAnalyzer()
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
        }
    }

    override fun release() = unbind()

    /** Main thread. Releases only this pipeline's use cases (the SDK camera may already be restarting). Idempotent. */
    fun unbind() {
        wanted = false
        bound = null
        analysis?.clearAnalyzer()
        if (useCases.isNotEmpty()) provider?.unbind(*useCases.toTypedArray())
        useCases = emptyList()
        analysis = null
        imageCapture = null
        camera = null
        _quad.value = null
        lastQuad = null
    }

    private fun applyAnalyzer() {
        val an = analysis ?: return
        if (detecting) {
            gate.detectionOn()   // Add page / Retake / closing the drawer re-arms auto capture
            scanner?.let { s ->
                s.reset()
                an.setAnalyzer(analysisExecutor, DocumentAnalyzer(s) { q -> onQuad(q) })
            }
        } else {
            an.clearAnalyzer()
            _quad.value = null
            lastQuad = null
        }
    }

    /** Analysis thread. */
    private fun onQuad(q: DocumentQuad) {
        if (!detecting) return
        lastQuad = q
        _quad.value = q.takeIf { it.valid }
        val inside = q.corners.all {
            it.x > q.frameWidth * EDGE_MARGIN && it.x < q.frameWidth * (1 - EDGE_MARGIN) &&
                it.y > q.frameHeight * EDGE_MARGIN && it.y < q.frameHeight * (1 - EDGE_MARGIN)
        }
        if (gate.frame(q.valid, inside, q.corners.map { it.x to it.y }, q.frameWidth, q.frameHeight, auto, capturing)) main.execute { capture() }
    }

    override fun capture(): CaptureStart {
        if (capturing) return CaptureStart.Busy
        val q = lastQuad?.takeIf { it.valid } ?: return CaptureStart.NoPage
        val cap = imageCapture ?: return CaptureStart.NoPage
        capturing = true
        cap.takePicture(
            main,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    cropExecutor.execute {
                        val page = runCatching { image.use { cropToQuad(it, q) } }.onFailure { Log.e(TAG, "crop failed", it) }.getOrNull()
                        if (page != null) gate.captured()
                        capturing = false
                        _stills.trySend(page)
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "capture failed", e)
                    capturing = false
                    _stills.trySend(null)
                }
            },
        )
        return CaptureStart.Started
    }

    override fun torch(on: Boolean) {
        torchOn = on
        camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(on) }
    }

    override fun lens(front: Boolean) {
        if (front == this.front) return
        this.front = front
        val (owner, preview) = bound ?: return
        unbind()
        bind(owner, preview)
    }

    override fun focus(x: Float, y: Float) {
        val (_, preview) = bound ?: return
        val cam = camera ?: return
        val point = preview.meteringPointFactory.createPoint(x * preview.width, y * preview.height)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    override fun zoom(ratio: Float) {
        zoomRatio = ratio
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    override suspend fun process(original: Bitmap, index: Int): DocumentPage = withContext(Dispatchers.Default) {
        DocumentPage(original, index).also(session::process)
    }

    override suspend fun exportPdf(pages: List<DocumentPage>, enhanced: Boolean): File? = withContext(Dispatchers.IO) {
        pages.forEach(session::recognize)
        val dir = File(ctx.cacheDir, "docs").apply { mkdirs() }
        // Keep the last few exports (a share target may still be reading one); cache/ is the system's to clear.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEPT_EXPORTS - 1)?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())
        File(dir, "Document-$stamp.pdf").takeIf { DocumentPdf.write(pages, enhanced, it) }
    }

    /**
     * Still → page, without ever decoding the full 12 MP frame upright:
     *  1. decode a ~640 px version in sensor orientation, re-detect + edge-snap the quad on it;
     *  2. region-decode only the quad's bounding box at full resolution;
     *  3. one perspective warp to an upright rectangle (the display rotation is
     *     folded into the corner ordering, so no rotate copy).
     */
    private fun cropToQuad(image: ImageProxy, q: DocumentQuad): Bitmap? {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val rot = image.imageInfo.rotationDegrees
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        // 1. Small decode (sensor orientation) for detection.
        var sample = 1
        while (max(rawW, rawH) / sample > 1280) sample *= 2
        val small0 = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val toDetect = 640f / max(small0.width, small0.height)
        val small = Bitmap.createScaledBitmap(small0, (small0.width * toDetect).roundToInt(), (small0.height * toDetect).roundToInt(), true)
        val smallToRaw = rawW.toFloat() / small.width // uniform: same aspect as the raw frame
        val detected = detectOnStill(small)
        small.recycle()
        if (small !== small0) small0.recycle()

        // Quad in raw (sensor-orientation, full-res) pixels.
        val raw = FloatArray(8)
        if (detected != null) {
            detected.forEachIndexed { i, p ->
                raw[i * 2] = p.x * smallToRaw
                raw[i * 2 + 1] = p.y * smallToRaw
            }
        } else {
            // Fallback: live quad, same orientation as the still, just scaled.
            q.corners.forEachIndexed { i, c ->
                raw[i * 2] = c.x * rawW / q.frameWidth
                raw[i * 2 + 1] = c.y * rawH / q.frameHeight
            }
        }
        // Grow slightly from the centroid so edge-snap error can't clip a letter.
        var cx = 0f
        var cy = 0f
        for (i in 0 until 4) {
            cx += raw[i * 2] / 4
            cy += raw[i * 2 + 1] / 4
        }
        for (i in 0 until 4) {
            raw[i * 2] = (cx + (raw[i * 2] - cx) * (1 + QUAD_MARGIN)).coerceIn(0f, rawW - 1f)
            raw[i * 2 + 1] = (cy + (raw[i * 2 + 1] - cy) * (1 + QUAD_MARGIN)).coerceIn(0f, rawH - 1f)
        }
        // Order TL,TR,BR,BL as the page will appear upright: rotate the corners
        // virtually by the display rotation, sort there, keep the raw coordinates.
        val order = (0 until 4).map { i ->
            val x = raw[i * 2]
            val y = raw[i * 2 + 1]
            val (ux, uy) = when (rot) {
                90 -> (rawH - y) to x
                180 -> (rawW - x) to (rawH - y)
                270 -> y to (rawW - x)
                else -> x to y
            }
            Triple(i, ux, uy)
        }.let { pts ->
            val byY = pts.sortedBy { it.third }
            val top = byY.take(2).sortedBy { it.second }
            val bottom = byY.drop(2).sortedBy { it.second }
            listOf(top[0].first, top[1].first, bottom[1].first, bottom[0].first)
        }
        val src = FloatArray(8) { k -> raw[order[k / 2] * 2 + (k % 2)] }

        // 2. Region decode of the quad's bounding box only.
        val left = floor(src.filterIndexed { i, _ -> i % 2 == 0 }.min()).toInt().coerceIn(0, rawW - 1)
        val top = floor(src.filterIndexed { i, _ -> i % 2 == 1 }.min()).toInt().coerceIn(0, rawH - 1)
        val right = ceil(src.filterIndexed { i, _ -> i % 2 == 0 }.max()).toInt().coerceIn(left + 1, rawW)
        val bottom = ceil(src.filterIndexed { i, _ -> i % 2 == 1 }.max()).toInt().coerceIn(top + 1, rawH)

        @Suppress("DEPRECATION") // the non-deprecated overload is API 31+
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return null
        val region = try {
            decoder.decodeRegion(Rect(left, top, right, bottom), BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } finally {
            decoder.recycle()
        } ?: return null
        for (i in 0 until 4) {
            src[i * 2] -= left
            src[i * 2 + 1] -= top
        }

        // 3. Warp to an upright rectangle sized by the quad's edge lengths.
        fun len(a: Int, b: Int) = hypot(src[b * 2] - src[a * 2], src[b * 2 + 1] - src[a * 2 + 1])
        val (outW, outH) = pageOutputSize(((len(0, 1) + len(3, 2)) / 2).roundToInt(), ((len(0, 3) + len(1, 2)) / 2).roundToInt())
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(src, 0, dst, 0, 4)) return region
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(region, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        region.recycle()
        Log.i(TAG, "still ${rawW}x$rawH rot=$rot -> page ${outW}x$outH (quad from ${if (detected != null) "still" else "live"})")
        return out
    }

    /**
     * Runs the corner detector once on a ~640 px copy of the still (sensor
     * orientation; the network is orientation-agnostic), fed as a grey YUV_420
     * frame, then snaps the edges. Returns corners in that bitmap's pixels, or
     * null when it finds no valid document.
     */
    private fun detectOnStill(bitmap: Bitmap): List<PointF>? {
        val w = bitmap.width and 1.inv()
        val h = bitmap.height and 1.inv()
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val luma = FloatArray(w * h) { i -> (((px[i] ushr 16 and 0xff) * 299 + (px[i] ushr 8 and 0xff) * 587 + (px[i] and 0xff) * 114) / 1000).toFloat() }
        val y = ByteBuffer.allocateDirect(w * h)
        for (v in luma) y.put(v.toInt().toByte())
        y.rewind()
        val uvSize = (w / 2) * (h / 2)
        fun grey() = ByteBuffer.allocateDirect(uvSize).also { b ->
            repeat(uvSize) { b.put(128.toByte()) }
            b.rewind()
        }
        val u = grey()
        val v = grey()
        return try {
            val s = stillScanner
            s.reset()
            // Two passes of the same frame: the first seeds the tracker, the second
            // is the settled estimate. `valid` also folds in tracking gates that a
            // single frame can't satisfy, so accept on per-corner confidence.
            s.process(y, w, u, v, w / 2, 1, w, h, 0, 0.0)
            y.rewind()
            u.rewind()
            v.rewind()
            val r = s.process(y, w, u, v, w / 2, 1, w, h, 0, 0.033)
            if (!r.valid && r.minConfidence < STILL_MIN_CONFIDENCE) null else snapEdges(r.corners.map { PointF(it.x, it.y) }, luma, w, h)
        } catch (e: Exception) {
            Log.w(TAG, "still detect failed", e)
            null
        }
    }

    /**
     * The corner network lands a few px inside the paper. For each quad edge,
     * sample the luminance along the outward normal at points along the edge,
     * find the paper→background transition (largest gradient), shift the edge by
     * the median offset, and re-intersect the four lines. Edges without a clear
     * transition (paper on a white desk) are left where the detector put them.
     */
    private fun snapEdges(c: List<PointF>, luma: FloatArray, w: Int, h: Int): List<PointF> {
        if (c.size != 4) return c

        fun at(x: Float, y: Float): Float = luma[y.roundToInt().coerceIn(0, h - 1) * w + x.roundToInt().coerceIn(0, w - 1)]

        // Edge i runs c[i] -> c[(i+1)%4]; the quad is clockwise, so outward is the left normal.
        val shifted = ArrayList<Pair<PointF, PointF>>(4)
        var snapped = 0
        for (i in 0 until 4) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len < 8f) return c
            val nx = -(b.y - a.y) / len
            val ny = (b.x - a.x) / len
            val reach = (min(w, h) * EDGE_SEARCH).roundToInt().coerceAtLeast(4)
            val offsets = ArrayList<Int>()
            for (k in 0 until EDGE_SAMPLES) {
                val t = 0.12f + 0.76f * k / (EDGE_SAMPLES - 1)
                val px0 = a.x + (b.x - a.x) * t
                val py0 = a.y + (b.y - a.y) * t
                // Profile from a little inside (-reach/3) to well outside (+reach).
                val from = -reach / 3
                val prof = FloatArray(reach - from + 1) { j -> at(px0 + nx * (from + j), py0 + ny * (from + j)) }
                var best = 0
                var bestG = 0f
                for (j in 2 until prof.size - 2) {
                    val g = abs((prof[j + 2] + prof[j + 1]) - (prof[j - 1] + prof[j - 2])) / 2f
                    if (g > bestG) {
                        bestG = g
                        best = j
                    }
                }
                if (bestG >= EDGE_MIN_STEP) offsets.add(from + best)
            }
            val d = if (offsets.size >= EDGE_SAMPLES / 2) {
                snapped++
                offsets.sorted()[offsets.size / 2].toFloat()
            } else {
                0f
            }
            shifted.add(PointF(a.x + nx * d, a.y + ny * d) to PointF(b.x + nx * d, b.y + ny * d))
        }
        if (snapped == 0) return c
        // Corner i = intersection of edge (i-1) and edge i.
        val out = ArrayList<PointF>(4)
        for (i in 0 until 4) {
            val (p1, p2) = shifted[(i + 3) % 4]
            val (p3, p4) = shifted[i]
            val den = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x)
            if (abs(den) < 1e-3f) return c
            val t = ((p1.x - p3.x) * (p3.y - p4.y) - (p1.y - p3.y) * (p3.x - p4.x)) / den
            out.add(PointF(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y)))
        }
        return out
    }

    private companion object {
        const val TAG = "DocumentController"

        /** Exported PDFs kept in cache/docs, the new one included. */
        const val KEPT_EXPORTS = 3

        /** Analysis frames the quad must hold within [STILL_FRACTION] before auto-capture. */
        const val STILL_FRAMES = 12
        const val STILL_FRACTION = 0.01f

        /** Corners closer than this (fraction of frame size) to an edge block auto-capture: the page is clipped. */
        const val EDGE_MARGIN = 0.02f

        /** Edge snap: samples per edge, outward search reach (fraction of the short side), minimum luminance step. */
        const val EDGE_SAMPLES = 24
        const val EDGE_SEARCH = 0.06f
        const val EDGE_MIN_STEP = 10f

        /** Accept the still re-detection on corner confidence alone when the tracker gates say otherwise. */
        const val STILL_MIN_CONFIDENCE = 0.6f

        /** Outward growth of the detected quad before cropping, as a fraction of its half-size. */
        const val QUAD_MARGIN = 0.01f
    }
}
