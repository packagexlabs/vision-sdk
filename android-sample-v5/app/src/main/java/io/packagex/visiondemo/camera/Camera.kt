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
    /** [PausePolicy.resume]: false (still paused) while the device is critically hot. */
    fun resume(): Boolean
    /** [PausePolicy.userActive]: resets the idle timer. */
    fun userActive()
}
