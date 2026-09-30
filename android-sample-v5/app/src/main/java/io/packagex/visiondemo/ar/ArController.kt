package io.packagex.visiondemo.ar

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ARCore session behind AR Barcode: the session lifecycle of the original demo's `ArScannerActivity`,
 * driven by [ArSurface] (attach/detach) and the ViewModel (pause/resume/clear). Main thread only; the
 * renderer runs on the GL thread and is paused (`GLSurfaceView.onPause` blocks until it is) before the
 * session is.
 */
@Singleton
class ArController @Inject constructor(@param:ApplicationContext private val ctx: Context) : ArCamera {
    private val _counts = MutableStateFlow<List<PayloadCount>>(emptyList())
    override val counts: StateFlow<List<PayloadCount>> = _counts.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errors: Flow<String> = _errors

    override var catalog: Map<String, String> = emptyMap()
        set(value) { field = value; renderer?.catalog?.set(value) }

    /** The SDK's barcode decoder (native); one per process, reused across sessions. */
    private val processor by lazy { BarcodeProcessor(ctx) }

    private var view: GLSurfaceView? = null
    private var renderer: ArBarcodeRenderer? = null
    private var session: Session? = null
    /** Paused by the ViewModel (heat, idle, background, result drawer). */
    private var pauseWanted = false
    /** The session is resumed and the GL thread running. */
    private var running = false

    override fun installed(): Boolean =
        runCatching { ArCoreApk.getInstance().checkAvailability(ctx) == ArCoreApk.Availability.SUPPORTED_INSTALLED }.getOrDefault(false)

    override fun attach(view: GLSurfaceView) {
        detach(null)
        // The renderer must be set before the surface exists (GLSurfaceView has no GL thread until then).
        val r = ArBarcodeRenderer(processor, ctx.resources.displayMetrics.density, onStatus = { status ->
            if (status.counts != _counts.value) _counts.value = status.counts   // GL thread; StateFlow is thread-safe
        })
        r.catalog.set(catalog)
        view.preserveEGLContextOnPause = true
        view.setEGLContextClientVersion(2)
        view.setRenderer(r)
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        this.view = view
        renderer = r
        _counts.value = emptyList()
        session = createSession()
        if (session != null && !pauseWanted) start(retriesLeft = 3) else view.onPause()
    }

    override fun detach(view: GLSurfaceView?) {
        if (view != null && view !== this.view) return   // a stale view's dispose after a newer attach
        renderer?.let { r -> if (running) this.view?.queueEvent(r::releaseGl) }   // runs before onPause lets the GL thread stop
        stop()   // the GL thread is paused now, so no new decode starts
        renderer?.session = null
        session?.let { processor.awaitIdle(DECODE_DRAIN_MS); it.close() }
        session = null
        renderer = null
        this.view = null
    }

    override fun pause() {
        pauseWanted = true
        stop()
    }

    override fun resume() {
        pauseWanted = false
        start(retriesLeft = 3)
    }

    override fun clear() {
        renderer?.clearRequested = true
        _counts.value = emptyList()
    }

    // The scanner's CameraX close is async; ARCore can lose the race for the camera (as the original).
    private fun start(retriesLeft: Int) {
        val s = session ?: return
        val v = view ?: return
        val r = renderer ?: return
        if (running || pauseWanted) return
        try {
            s.resume()
        } catch (e: CameraNotAvailableException) {
            if (retriesLeft > 0) v.postDelayed({ if (view === v) start(retriesLeft - 1) }, 400) else _errors.tryEmit("Camera not available")
            return
        } catch (e: SecurityException) {
            _errors.tryEmit("Camera permission is required")
            return
        }
        running = true
        r.session = s   // the same session keeps its warm-up; a new session comes with a new renderer (attach)
        v.onResume()
    }

    private fun stop() {
        if (!running) return
        running = false
        view?.onPause()
        session?.pause()
    }

    private fun createSession(): Session? {
        val session = try {
            Session(ctx)
        } catch (e: UnavailableException) {
            Log.w(TAG, "ARCore unavailable", e)
            _errors.tryEmit("AR isn't supported on this device")
            return null
        }
        // The default CPU image is 640x480, too small to decode: take the largest at 30 fps, so AR reads what
        // Barcode mode reads. No 30 fps config: the same rule over every frame rate.
        val configs = session.getSupportedCameraConfigs(CameraConfigFilter(session).setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)))
            .ifEmpty { session.getSupportedCameraConfigs(CameraConfigFilter(session).setTargetFps(EnumSet.allOf(CameraConfig.TargetFps::class.java))) }
        Log.i(TAG, "camera configs offered: ${configs.joinToString { "${it.imageSize}@${it.fpsRange}" }}")
        pickCameraConfig(configs.map { it.imageSize.width to it.imageSize.height })?.let { session.cameraConfig = configs[it] }
        Log.i(TAG, "camera config ${session.cameraConfig.imageSize} fps=${session.cameraConfig.fpsRange}")
        // Devices vary; use the real sensor orientation of the camera ARCore picked.
        processor.configureRotation(session.cameraConfig.cameraId)

        val config = Config(session).apply {
            // AUTO: FIXED left the feed soft at barcode range (~20-50 cm) on the Memor 35.
            focusMode = Config.FocusMode.AUTO
            // BLOCKING paces the GL thread to the 30 fps camera.
            updateMode = Config.UpdateMode.BLOCKING
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            // Instant Placement guesses depth; that guess misplaced markers. Real plane / feature-point hits only.
            instantPlacementMode = Config.InstantPlacementMode.DISABLED
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
        }
        session.configure(config)
        return session
    }

    private companion object {
        const val TAG = "ArController"
        /** Longest the main thread waits for an in-flight decode before closing the session (decodes take ~5-40 ms). */
        const val DECODE_DRAIN_MS = 300L
    }
}
