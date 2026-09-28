package io.packagex.visiondemo.camera

import android.graphics.RectF
import io.packagex.visiondemo.model.ScannerConfig
import io.packagex.visionsdk.core.ScanningMode
import io.packagex.visionsdk.ui.views.VisionCameraView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the scanner ViewModel needs from the camera; [CameraController] is the real one, tests use a fake.
 * All calls are made on the main thread.
 */
interface Camera {
    val events: Flow<ScanEvent>
    /** [PausePolicy.paused]: idle timeout, heat, background. */
    val paused: StateFlow<Boolean>
    /** The live SDK view, for the entitlement check (which *is* `enablePriceTagMode`/`enableItemRetrievalMode`
     *  on the view). Null only in fakes. */
    val view: VisionCameraView?
    fun claim(owner: CameraOwner)
    fun apply(config: ScannerConfig, frame: RectF?, scanning: ScanningMode)
    fun pauseDetection()
    fun resumeDetection()
    fun capture()
    fun rescan()
    fun torch(on: Boolean)
    fun zoom(ratio: Float)
    /** Front or back lens (iOS `flipCamera`). */
    fun lens(front: Boolean)
    /** Focus at a point in view-normalized (0..1) display coordinates. */
    fun focus(x: Float, y: Float)
    /** [PausePolicy.resume]: false (still paused) while the device is critically hot. */
    fun resume(): Boolean
    /** [PausePolicy.userActive]: resets the idle timer. */
    fun userActive()
}

/**
 * Keeps a requested detection pause across SDK pipeline rebuilds. `VisionCameraView` clears its pause
 * whenever it rebuilds (rescan, start after a stop, facing switch: v2.7.0 VisionCameraView :1148,
 * :1039, :1474), so the pause is re-applied after each of them. Subclasses implement the raw `sdk*` calls.
 */
abstract class DetectionGatedCamera : Camera {
    private var pauseWanted = false
    /** A [rescan] asked for while the camera was stopped; applied by the next [start]. */
    private var rescanPending = false

    /** The SDK camera may run now: the scanner owns it and [PausePolicy] isn't pausing it. */
    protected abstract val mayRun: Boolean
    protected abstract fun sdkStart()

    protected abstract fun sdkPauseDetection()
    protected abstract fun sdkResumeDetection()
    protected abstract fun sdkRescan()
    protected abstract fun sdkLens(front: Boolean)
    protected abstract fun sdkApply(config: ScannerConfig, frame: RectF?, scanning: ScanningMode)

    final override fun pauseDetection() { pauseWanted = true; sdkPauseDetection() }
    final override fun resumeDetection() { pauseWanted = false; sdkResumeDetection() }
    /** The SDK's `rescan()` rebinds, i.e. starts a stopped camera, so while it may not run the rescan waits for [start]. */
    final override fun rescan() {
        if (!mayRun) { rescanPending = true; return }
        rescanPending = false
        sdkRescan(); reapplyPause()
    }
    final override fun lens(front: Boolean) { sdkLens(front); reapplyPause() }
    final override fun apply(config: ScannerConfig, frame: RectF?, scanning: ScanningMode) { sdkApply(config, frame, scanning); reapplyPause() }

    /** (Re)starts the SDK camera (resume, claim), as a rescan if one was asked for while it was stopped. */
    protected fun start() {
        if (rescanPending) { rescanPending = false; sdkRescan() } else sdkStart()
        reapplyPause()
    }

    /** Call after anything else that (re)starts the SDK camera. */
    protected fun reapplyPause() { if (pauseWanted) sdkPauseDetection() }
}
