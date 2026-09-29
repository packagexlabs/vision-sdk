package io.packagex.visiondemo.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.packagex.visiondemo.BuildConfig
import io.packagex.visiondemo.model.Box
import io.packagex.visiondemo.model.ScannerConfig
import io.packagex.visionsdk.camera.core.CameraStatus
import io.packagex.visionsdk.config.CameraOrientationMode
import io.packagex.visionsdk.config.CameraSettings
import io.packagex.visionsdk.config.FocusSettings
import io.packagex.visionsdk.core.CameraLensFace
import io.packagex.visionsdk.core.DetectionMode
import io.packagex.visionsdk.core.ScanningMode
import io.packagex.visionsdk.core.pricetag.PriceTagData
import io.packagex.visionsdk.dto.ScannedCodeResult
import io.packagex.visionsdk.exceptions.VisionSDKException
import io.packagex.visionsdk.interfaces.CameraLifecycleCallback
import io.packagex.visionsdk.interfaces.ScannerCallback
import io.packagex.visionsdk.ui.views.VisionCameraView
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Who currently owns the camera sensor. Only [Scanner] leaves [VisionCameraView] running. */
enum class CameraOwner { None, Scanner, Ar, Document }

/** Pure ownership rule, factored out so it's testable without a real [VisionCameraView]. */
internal fun scannerMustStop(owner: CameraOwner) = owner != CameraOwner.Scanner

/** Plain-Kotlin stand-in for [FocusSettings]'s `RectF` fields -- JVM-testable without android.graphics. */
internal data class FocusSpec(val rect: Box, val restrict: Boolean)

/** Pure focus-region rule, factored out so it's testable without a real [VisionCameraView]. */
internal fun focusSettingsFor(config: ScannerConfig, frame: Box?): FocusSpec {
    val useFrame = config.restrictToFrame && frame != null
    return FocusSpec(
        rect = if (useFrame) frame!! else Box(0, 0, 0, 0),
        restrict = useFrame,
    )
}

/**
 * Owns the single [VisionCameraView] instance, applies per-mode [ScannerConfig], and pauses the
 * camera under [PausePolicy] (idle timeout, thermal throttling, app background/foreground).
 */
@Singleton
class CameraController @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val scope: CoroutineScope,
) : DetectionGatedCamera() {
    override val view: VisionCameraView = VisionCameraView(ctx)

    private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<ScanEvent> = _events

    private val _owner = MutableStateFlow(CameraOwner.None)
    val owner: StateFlow<CameraOwner> = _owner.asStateFlow()

    val policy = PausePolicy(scope)

    /** Another client took the camera ([isCameraLoss]); reconnects by rescanning (the SDK's startCamera() is a
     *  no-op while its interrupted session is still bound). Main thread, like every other SDK call. */
    private val reclaim = CameraReclaim(
        scope = CoroutineScope(scope.coroutineContext + Dispatchers.Main),
        now = SystemClock::elapsedRealtime,
        stillLost = { view.currentCameraState().let { isCameraLoss(it.status, it.error) } },
        starting = { view.currentCameraState().status == CameraStatus.STARTING },
        onPersistentFailure = {
            _events.tryEmit(ScanEvent.Failure(VisionSDKException.UnknownException(IllegalStateException("The camera is in use by another app"))))
        },
    ) { if (mayRun) rescan() }

    /** [PausePolicy.paused], or the camera is lost to another client: either way the UI shows it paused. */
    override val paused: StateFlow<Boolean> =
        combine(policy.paused, reclaim.lost) { p, lost -> p || lost }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Last focus spec `apply()`ed; re-applied on `onCameraStarted` since `getFocusRegionManager()`
     *  throws [VisionSDKException.FocusRegionManagerNotAvailable] before the camera has started. */
    private var lastSpec: FocusSpec? = null
    private val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var nthFrame = 10
    private var lensFace: CameraLensFace = CameraLensFace.Back

    init {
        view.setScannerCallback(object : ScannerCallback {
            override fun onIndications(barcodeDetected: Boolean, qrCodeDetected: Boolean, textDetected: Boolean, documentDetected: Boolean) {
                _events.tryEmit(ScanEvent.Indications(barcodeDetected, qrCodeDetected, textDetected, documentDetected))
            }

            override fun onIndicationsBoundingBoxes(
                barcodeBoundingBoxes: List<ScannedCodeResult>,
                qrCodeBoundingBoxes: List<ScannedCodeResult>,
                documentBoundingBox: Rect?,
            ) {
                _events.tryEmit(ScanEvent.Boxes(barcodeBoundingBoxes, qrCodeBoundingBoxes, documentBoundingBox))
            }

            override fun onScanResult(barcodeList: List<ScannedCodeResult>) {
                _events.tryEmit(ScanEvent.Codes(barcodeList))
            }

            override fun onFailure(exception: VisionSDKException) {
                // A camera lost to another client is recovered by [reclaim] (cameraStateListener below), not reported.
                val state = view.currentCameraState()
                if (isCameraLoss(state.status, state.error)) return
                _events.tryEmit(ScanEvent.Failure(exception))
            }

            override fun onImageCaptured(bitmap: Bitmap, scannedCodeResults: List<ScannedCodeResult>, imageSharpnessScore: Float) {
                _events.tryEmit(ScanEvent.Captured(bitmap, scannedCodeResults, imageSharpnessScore))
            }

            override fun onPriceTagResult(priceTagData: PriceTagData) {
                _events.tryEmit(ScanEvent.PriceTag(priceTagData))
            }

            override fun onItemRetrievalResult(scannedCodeResults: ScannedCodeResult) {
                _events.tryEmit(ScanEvent.Retrieved(scannedCodeResults))
            }
        })

        view.setCameraLifecycleCallback(object : CameraLifecycleCallback {
            override fun onCameraStarted() {
                applyFocusSpec()
                reapplyPause()
                _events.tryEmit(ScanEvent.Started)
            }

            override fun onCameraStopped() {}
        })

        view.addCameraStateListener { state ->
            when {
                state.status == CameraStatus.RUNNING -> reclaim.onRunning()
                // Only while the scanner wants the camera in the foreground: backgrounding interrupts the SDK session too.
                isCameraLoss(state.status, state.error) && mayRun && foreground() -> {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Camera lost (${state.status}, ${state.error}); reconnecting when it is free")
                    reclaim.onLost(failed = state.status == CameraStatus.ERROR)
                }
            }
        }
        cameraManager.registerAvailabilityCallback(
            ContextCompat.getMainExecutor(ctx),
            object : CameraManager.AvailabilityCallback() {
                // Only the sensor the scanner uses (another app freeing a different camera changes nothing for us).
                override fun onCameraAvailable(cameraId: String) { if (cameraId == scannerCameraId()) reclaim.onAvailable() }
            },
        )

        val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(ctx)) { status -> policy.thermal(status) }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> policy.lifecycle(foreground = false)
                    Lifecycle.Event.ON_START -> policy.lifecycle(foreground = true)
                    else -> {}
                }
            },
        )

        scope.launch(Dispatchers.Main) {
            policy.paused.collect { paused ->
                if (paused) {
                    reclaim.reset()
                    view.stopCamera()
                } else if (_owner.value == CameraOwner.Scanner) {
                    start()
                }
            }
        }
    }

    /** Stops the scanner camera unless [owner] is [CameraOwner.Scanner]. */
    override fun claim(owner: CameraOwner) {
        _owner.value = owner
        if (scannerMustStop(owner)) {
            reclaim.reset()
            view.stopCamera()
        } else if (!policy.paused.value) {
            start()
        }
    }

    override fun sdkApply(config: ScannerConfig, frame: RectF?, scanning: ScanningMode) {
        config.detection?.let { view.configure(it, scanning, config.multiple); configured = true }
        nthFrame = config.nthFrame
        applyCameraSettings()
        // No enableTapToFocus: the SDK would draw its own focus square over the app's ring; taps go through focus().
        view.enablePinchPanToZoom()
        val frameBox = frame?.let { Box(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
        lastSpec = focusSettingsFor(config, frameBox)
        applyFocusSpec()
    }

    /** Needs a running camera: `getFocusRegionManager()` throws otherwise, e.g. while the session is interrupted. */
    private fun applyFocusSpec() {
        val spec = lastSpec ?: return
        if (!view.isCameraStarted()) return
        // Still throws if the session stops between the check and the call (e.g. evicted); re-applied on the next start.
        runCatching {
            view.getFocusRegionManager().setFocusSettings(
                FocusSettings(
                    context = ctx,
                    focusImageRect = RectF(spec.rect.left.toFloat(), spec.rect.top.toFloat(), spec.rect.right.toFloat(), spec.rect.bottom.toFloat()),
                    shouldScanInFocusImageRect = spec.restrict,
                    showCodeBoundariesInMultipleScan = false,   // the app draws the boxes (BoxesOverlay)
                    showDocumentBoundaries = false,
                ),
            )
        }.onFailure { if (BuildConfig.DEBUG) Log.d(TAG, "Focus settings not applied", it) }
    }

    override val mayRun get() = _owner.value == CameraOwner.Scanner && !policy.paused.value
    /** The SDK's startCamera() needs a detection mode (lateinit); Price/Retrieval set theirs only after the entitlement call. */
    private var configured = false
    override fun sdkStart() {
        if (!configured) { view.configure(DetectionMode.Barcode, ScanningMode.Manual, false); configured = true }
        view.startCamera()
    }
    override fun sdkPauseDetection() = view.pauseDetection()
    override fun sdkResumeDetection() = view.resumeDetection()
    override fun capture() = view.capture()
    override fun sdkRescan() = view.rescan()
    override fun torch(on: Boolean) = view.setFlashTurnedOn(on)
    override fun zoom(ratio: Float) = view.setZoomRatio(ratio)
    override fun sdkLens(front: Boolean) {
        lensFace = if (front) CameraLensFace.Front else CameraLensFace.Back
        applyCameraSettings()
    }
    override fun focus(x: Float, y: Float) = view.setFocusPoint(x, y)

    /** The system camera id of the lens the scanner uses (first one facing that way), or null if unknown. */
    private fun scannerCameraId(): String? = runCatching {
        val facing = if (lensFace == CameraLensFace.Front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        cameraManager.cameraIdList.firstOrNull { cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
    }.getOrNull()

    private fun foreground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun applyCameraSettings() {
        view.setCameraSettings(CameraSettings(nthFrameToProcess = nthFrame, cameraLensFace = lensFace, orientationMode = CameraOrientationMode.PORTRAIT))
    }
    override fun resume(): Boolean = policy.resume().also { if (it) reclaim.retryNow() }
    override fun userActive() = policy.userActive()
    override fun setBusy(busy: Boolean) = policy.setBusy(busy)

    private companion object {
        const val TAG = "CameraController"
    }
}
