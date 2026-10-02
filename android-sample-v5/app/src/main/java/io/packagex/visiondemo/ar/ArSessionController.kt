package io.packagex.visiondemo.ar

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.FatalException
import dagger.hilt.android.qualifiers.ApplicationContext
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ARCore session of AR Count on the shared camera (spec 5.2), owning Camera2 while [io.packagex.visiondemo.camera.CameraOwner.Ar]
 * holds the sensor; driven by [ArSurface] (attach/detach) and the ViewModel (pause/resume, commands). The spike's
 * session, measured on the Memor 35: `Session(SHARED_CAMERA)` with a 1280x720 CPU image for tracking, BLOCKING
 * updates, AUTO focus, no depth, planes or light estimation; the app's own YUV stream for the engine (the largest the
 * camera offers of [AppStream], the next one after a failed configure) through `SharedCamera.setAppSurfaces`;
 * `openCamera` with ARCore's wrapped callbacks; TEMPLATE_RECORD with continuous AF and EIS/OIS off, no AE settings
 * (ARCore replaces them, spec 3); `Session.resume()` once the capture session is active; per-frame metadata from
 * `SharedCamera.setCaptureCallback` into [CaptureMetaRing].
 *
 * Threads (spec 5.8): main (this API), camera (Camera2 callbacks, app-stream images, metadata), GL ([ArCountRenderer]),
 * engine ([BarcodeProcessor]) and mapper ([ArMapper], the single caller of the counter from [ArCounterFactory]).
 */
@Singleton
class ArSessionController @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val counters: ArCounterFactory,
) : ArCount {
    private val _count = MutableStateFlow(CountView.EMPTY)
    override val count: StateFlow<CountView> = _count.asStateFlow()

    private val _stream = MutableStateFlow<AppStream?>(null)
    override val stream: StateFlow<AppStream?> = _stream.asStateFlow()

    private val _screen = MutableStateFlow(ArScreen.NONE)
    override val screen: StateFlow<ArScreen> = _screen.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errors: Flow<String> = _errors

    override var tracing = false
        set(value) {
            if (field == value) return
            field = value
            if (running) mapper?.post(ArEvent.Trace(if (value) SessionRecorder.open(ctx, metas) else null))
        }

    private val main = Handler(Looper.getMainLooper())
    private val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Camera2 callbacks, the app stream's images and the capture metadata; as long-lived as this singleton. */
    private val cameraHandler = Handler(HandlerThread("ArCamera").apply { start() }.looper)
    private val metas = CaptureMetaRing()

    /** The engine (native); one per process, reused across sessions. */
    private val engine by lazy { BarcodeProcessor(ctx) }

    // One attached view's session (main thread; the camera thread reads the @Volatile ones)
    private var view: GLSurfaceView? = null
    private var renderer: ArCountRenderer? = null
    @Volatile private var session: Session? = null
    @Volatile private var mapper: ArMapper? = null
    @Volatile private var reader: ImageReader? = null
    private var cameraId = ""
    private var streams: List<AppStream> = emptyList()
    private var streamIndex = 0

    // The camera: every open gets a generation; letting the camera go bumps it, so callbacks of an older open close
    // what they get and stop
    @Volatile private var generation = 0
    @Volatile private var device: CameraDevice? = null
    @Volatile private var deviceCallback: DeviceCallback? = null
    @Volatile private var captureSession: CameraCaptureSession? = null
    private var pauseWanted = false
    private var opening = false
    private var running = false
    private var runningSinceMs = 0L
    private var rebuilds = 0

    override fun installed(): Boolean =
        runCatching { ArCoreApk.getInstance().checkAvailability(ctx) == ArCoreApk.Availability.SUPPORTED_INSTALLED }.getOrDefault(false)

    override fun attach(view: GLSurfaceView) {
        detach(null)
        val m = ArMapper(counters.create(), onView = { _count.value = it.forUi() })
        val r = ArCountRenderer(m, metas, ctx.resources.displayMetrics.density, onScreen = { _screen.value = it }, onFatal = ::onUpdateFailed)
        // The renderer must be set before the surface exists; the GL thread waits paused until ARCore runs.
        view.preserveEGLContextOnPause = true
        view.setEGLContextClientVersion(2)
        view.setRenderer(r)
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        view.onPause()
        this.view = view
        renderer = r
        mapper = m
        rebuilds = 0
        _count.value = CountView.EMPTY
        _screen.value = ArScreen.NONE
        val s = createSession() ?: return
        session = s
        m.start()
        if (setUpStreams(s)) start()
    }

    override fun detach(view: GLSurfaceView?) {
        if (view != null && view !== this.view) return // a stale view's dispose after a newer attach
        stopCamera()
        reader?.let { engine.closeAfterDecode(it) }
        reader = null
        renderer?.session = null
        session?.close() // and its anchors
        session = null
        mapper?.close()
        mapper = null
        renderer = null
        this.view = null
        metas.clear()
        _count.value = CountView.EMPTY
        _stream.value = null
        _screen.value = ArScreen.NONE
    }

    override fun pause() {
        pauseWanted = true
        stopCamera()
    }

    override fun resume() {
        pauseWanted = false
        start()
    }

    override fun command(command: Command) {
        mapper?.post(ArEvent.Cmd(command))
    }

    override fun reset() {
        mapper?.post(ArEvent.Reset(counters.create()))
    }

    private fun createSession(): Session? {
        val s = try {
            Session(ctx, EnumSet.of(Session.Feature.SHARED_CAMERA))
        } catch (e: Exception) { // UnavailableException, SecurityException
            Log.w(TAG, "ARCore unavailable", e)
            _errors.tryEmit("AR isn't supported on this device")
            return null
        }
        // ARCore's CPU image is for tracking only: 1280x720 at 30 fps (spec 5.2); the engine reads the app stream.
        val configs = s.getSupportedCameraConfigs(CameraConfigFilter(s).setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)))
            .ifEmpty { s.getSupportedCameraConfigs(CameraConfigFilter(s)) }
        (configs.firstOrNull { it.imageSize.width == 1280 && it.imageSize.height == 720 } ?: configs.minByOrNull { it.imageSize.width * it.imageSize.height })
            ?.let { s.cameraConfig = it }
        s.configure(
            Config(s).apply {
                focusMode = Config.FocusMode.AUTO // FIXED left the feed soft at barcode range on the Memor 35
                updateMode = Config.UpdateMode.BLOCKING // paces the GL thread to the camera
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.DISABLED
                depthMode = Config.DepthMode.DISABLED
                instantPlacementMode = Config.InstantPlacementMode.DISABLED
            },
        )
        Log.i(TAG, "ARCore CPU image ${s.cameraConfig.imageSize} at ${s.cameraConfig.fpsRange} fps on camera ${s.cameraConfig.cameraId}")
        return s
    }

    /** The app streams the camera offers as YUV, largest first; the first one is set up. */
    private fun setUpStreams(s: Session): Boolean {
        cameraId = s.cameraConfig.cameraId
        engine.configureRotation(cameraId)
        val offered = runCatching {
            cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().map { it.width to it.height }
        }.getOrNull().orEmpty()
        streams = AppStream.offered(offered)
        streamIndex = 0
        Log.i(TAG, "camera $cameraId offers YUV ${offered.sortedByDescending { it.first * it.second }.take(8)}; app streams to try: $streams")
        if (streams.isEmpty()) {
            _errors.tryEmit("AR Count can't read this camera: none of 4K, 1440p, 1080p or 720p is offered")
            return false
        }
        useStream(streams[0])
        return true
    }

    private fun useStream(stream: AppStream) {
        reader?.let { engine.closeAfterDecode(it) }
        reader = ImageReader.newInstance(stream.width, stream.height, ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener(::onImage, cameraHandler)
        }
        renderer?.stream = stream
        _stream.value = stream
        Log.i(TAG, "app stream $stream")
    }

    /**
     * Camera thread: an app-stream image goes to the engine, decoded now or as the newest after the decode in flight
     * (spec 5.8). At most three are held then, the reader's maxImages: the one decoded, the one waiting and the one
     * acquired here, which replaces it; while two are held acquireLatestImage cannot skip ahead, so each frame's call
     * takes the next queued image and the last call leaves the newest one waiting.
     */
    private fun onImage(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        val m = mapper
        if (m == null) {
            image.close()
            return
        }
        engine.process(image) { ts, reads, stats -> m.post(ArEvent.Reads(ts, reads, stats)) }
    }

    private fun start() {
        val s = session ?: return
        if (reader == null || running || opening || pauseWanted) return
        opening = true
        val gen = ++generation
        openCamera(s, gen, RETRIES)
        // An open that never gets ARCore running (no onActive, a callback that never comes) fails rather than stay black
        main.postDelayed({
            if (gen != generation || !opening) return@postDelayed
            Log.w(TAG, "ARCore not running $OPEN_TIMEOUT_MS ms after the camera open began")
            fail(gen, "AR Count could not start the camera")
        }, OPEN_TIMEOUT_MS)
    }

    private fun openCamera(s: Session, gen: Int, retriesLeft: Int) {
        if (gen != generation) return
        val r = reader ?: return
        // Before every open, retries too, as Google's shared-camera sample does: ARCore then feeds the app stream too.
        if (runCatching { s.sharedCamera.setAppSurfaces(cameraId, listOf(r.surface)) }.onFailure { Log.w(TAG, "setAppSurfaces", it) }.isFailure) {
            fail(gen, "AR Count could not start the camera")
            return
        }
        val callback = DeviceCallback(gen, retriesLeft)
        try {
            cameraManager.openCamera(cameraId, s.sharedCamera.createARDeviceStateCallback(callback, cameraHandler), cameraHandler)
            deviceCallback = callback
        } catch (e: SecurityException) {
            fail(gen, "Camera permission is required")
        } catch (e: Exception) { // CameraAccessException: in use by the scanner a moment longer, or disabled
            Log.w(TAG, "openCamera failed, $retriesLeft retries left", e)
            retryOrFail(gen, retriesLeft)
        }
    }

    /** The scanner's CameraX close is asynchronous: the camera can be busy for a moment after the claim (3 x 400 ms). */
    private fun retryOrFail(gen: Int, retriesLeft: Int) {
        main.postDelayed({
            val s = session
            if (gen != generation || s == null) return@postDelayed
            if (retriesLeft > 0) openCamera(s, gen, retriesLeft - 1) else fail(gen, "Camera not available")
        }, RETRY_MS)
    }

    private fun fail(gen: Int, message: String) {
        if (gen != generation) return
        stopCamera()
        _errors.tryEmit(message)
    }

    /** Main thread, once the capture session is active: ARCore takes the camera over. */
    private fun resumeArCore(gen: Int) {
        val s = session ?: return
        val v = view ?: return
        val r = renderer ?: return
        if (gen != generation || running || pauseWanted) return
        try {
            s.resume()
        } catch (e: Exception) { // CameraNotAvailableException
            Log.w(TAG, "ARCore resume failed", e)
            fail(gen, "Camera not available")
            return
        }
        // Per-frame metadata while ARCore owns the request: the app's own repeating-request callback stops after resume().
        s.sharedCamera.setCaptureCallback(metaCallback, cameraHandler)
        running = true
        runningSinceMs = SystemClock.elapsedRealtime()
        opening = false
        r.session = s
        r.resumed()
        if (tracing) mapper?.post(ArEvent.Trace(SessionRecorder.open(ctx, metas)))
        v.onResume()
        Log.i(TAG, "ARCore resumed on the shared camera, app stream ${streams.getOrNull(streamIndex)}")
    }

    /** Main thread, after a failed configure: the next smaller app stream (spec 5.2), on a camera opened again. */
    private fun nextStream(gen: Int) {
        if (session == null || gen != generation) return
        val failed = streams[streamIndex]
        stopCamera()
        if (++streamIndex >= streams.size) {
            Log.e(TAG, "no app stream configures; the last tried was $failed")
            _errors.tryEmit("AR Count could not configure the camera")
            return
        }
        Log.w(TAG, "configure failed with the app stream $failed; trying ${streams[streamIndex]}")
        useStream(streams[streamIndex])
        start()
    }

    /** GL thread: ARCore's update() failed for good. Spec 6 treats it as a lost camera: the session is rebuilt. */
    private fun onUpdateFailed(e: FatalException) {
        Log.e(TAG, "ARCore update failed", e)
        val gen = generation
        main.post { rebuild(gen, "update failed") }
    }

    /** Main thread: the camera failed while ARCore ran (spec 6). The section freezes on the gap; the camera opens again. */
    private fun rebuild(gen: Int, why: String) {
        if (gen != generation) return
        Log.w(TAG, "camera lost ($why), rebuilding")
        if (SystemClock.elapsedRealtime() - runningSinceMs >= HEALTHY_RUN_MS) rebuilds = 0 // the budget is for a camera that keeps failing
        stopCamera()
        if (++rebuilds > MAX_REBUILDS) {
            _errors.tryEmit("Camera not available")
            return
        }
        start()
    }

    /**
     * Lets the camera go, in the order of Google's shared-camera sample: the GL thread (onPause blocks until it has
     * paused, so no update() runs), ARCore, then the capture session and the device. A capture session still being
     * configured is waited for first, as the sample waits for onActive: ARCore's wrapped callbacks are at work on it,
     * and closing it under them can throw on the camera thread. Then waits for the device to close, so the next owner
     * of the sensor can open it. Each wait is at most [CLOSE_WAIT_MS].
     */
    private fun stopCamera() {
        if (device != null && deviceCallback?.settled?.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) == false) {
            Log.w(TAG, "capture session not active after $CLOSE_WAIT_MS ms, closing it anyway")
        }
        generation++
        opening = false
        if (running) {
            running = false
            view?.onPause()
            session?.pause()
            mapper?.post(ArEvent.Trace(null)) // a trace ends with every pause, flushed
        }
        captureSession?.close()
        captureSession = null
        val d = device
        val cb = deviceCallback
        device = null
        deviceCallback = null
        if (d != null) {
            d.close()
            if (cb?.closed?.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) == false) Log.w(TAG, "camera not closed after $CLOSE_WAIT_MS ms")
        }
        if (reader != null) engine.dropPending() // of the capture just stopped: never decoded after a pause or a reopen
    }

    private inner class DeviceCallback(private val gen: Int, private val retriesLeft: Int) : CameraDevice.StateCallback() {
        val closed = CountDownLatch(1)

        /** Counted down once the capture session is active, or failed, or the device closed: until then, once the
         *  device has opened, the capture session is being configured. */
        val settled = CountDownLatch(1)

        override fun onOpened(d: CameraDevice) {
            val s = session
            val r = reader
            if (gen != generation || s == null || r == null) {
                d.close()
                return
            }
            device = d
            if (gen != generation) { // let go while this was opening: stopCamera may have missed it
                d.close()
                return
            }
            try {
                val surfaces = s.sharedCamera.arCoreSurfaces + r.surface
                val request = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    surfaces.forEach(::addTarget)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    // Stabilisation warps frames on its own: ARCore's tracking and the counter's rays need raw ones.
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                }.build()
                @Suppress("DEPRECATION") // the session-configuration form takes no ARCore-wrapped callback
                d.createCaptureSession(surfaces, s.sharedCamera.createARSessionStateCallback(SessionCallback(gen, request, settled), cameraHandler), cameraHandler)
            } catch (e: Exception) { // CameraAccessException, IllegalStateException (closed meanwhile)
                Log.w(TAG, "capture session not made", e)
                settled.countDown()
                main.post { fail(gen, "AR Count could not start the camera") }
            }
        }

        override fun onClosed(d: CameraDevice) {
            settled.countDown()
            closed.countDown()
        }

        override fun onDisconnected(d: CameraDevice) {
            d.close()
            main.post {
                if (gen != generation) return@post
                if (running) {
                    rebuild(gen, "disconnected")
                } else { // still opening: the scanner may not have let go yet
                    device = null
                    retryOrFail(gen, retriesLeft)
                }
            }
        }

        override fun onError(d: CameraDevice, error: Int) {
            d.close()
            main.post {
                if (gen != generation) return@post
                if (running) {
                    rebuild(gen, "error $error")
                } else if (device != null) { // opened: the stream configuration failed, which some HALs report here
                    Log.w(TAG, "camera error $error while configuring the capture session")
                    nextStream(gen)
                } else { // still opening: the scanner may not have let go yet
                    retryOrFail(gen, retriesLeft)
                }
            }
        }
    }

    private inner class SessionCallback(
        private val gen: Int,
        private val request: CaptureRequest,
        private val settled: CountDownLatch,
    ) : CameraCaptureSession.StateCallback() {
        override fun onConfigured(cs: CameraCaptureSession) {
            if (gen != generation) {
                cs.close()
                return
            }
            captureSession = cs
            try {
                cs.setRepeatingRequest(request, null, cameraHandler)
            } catch (e: Exception) { // no onActive will come: this open failed (a stale one's fail is ignored)
                Log.w(TAG, "capture session gone before it started", e)
                settled.countDown()
                main.post { fail(gen, "AR Count could not start the camera") }
            }
        }

        override fun onConfigureFailed(cs: CameraCaptureSession) {
            settled.countDown()
            main.post { nextStream(gen) }
        }

        override fun onActive(cs: CameraCaptureSession) {
            settled.countDown()
            main.post { resumeArCore(gen) }
        }
    }

    private val metaCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(cs: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            metas.add(
                CaptureMeta(
                    sensorTimestampNs = ts,
                    exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: -1L,
                    sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: -1,
                    rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: -1L,
                    aeCompensation = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) ?: Int.MIN_VALUE,
                    fpsRange = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.toString().orEmpty(),
                    afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: -1,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "ArSession"
        const val RETRIES = 3
        const val RETRY_MS = 400L

        /** Longest an open may take to get ARCore running: the retries (3 x 400 ms) and a 4K configure fit with room. */
        const val OPEN_TIMEOUT_MS = 5_000L
        const val MAX_REBUILDS = 3

        /** A run this long shows the camera works: a failure after it starts on a full rebuild budget again. */
        const val HEALTHY_RUN_MS = 10_000L
        const val CLOSE_WAIT_MS = 1_000L
    }
}
