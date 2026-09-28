package io.packagex.visiondemo.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.packagex.visiondemo.model.Box
import io.packagex.visiondemo.model.ScannerConfig
import io.packagex.visionsdk.config.CameraOrientationMode
import io.packagex.visionsdk.config.CameraSettings
import io.packagex.visionsdk.config.FocusSettings
import io.packagex.visionsdk.core.ScanningMode
import io.packagex.visionsdk.core.pricetag.PriceTagData
import io.packagex.visionsdk.dto.ScannedCodeResult
import io.packagex.visionsdk.exceptions.VisionSDKException
import io.packagex.visionsdk.interfaces.CameraLifecycleCallback
import io.packagex.visionsdk.interfaces.ScannerCallback
import io.packagex.visionsdk.ui.views.VisionCameraView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Who currently owns the camera sensor. Only [Scanner] leaves [VisionCameraView] running. */
enum class CameraOwner { None, Scanner, Ar, Document }

/** Pure ownership rule, factored out so it's testable without a real [VisionCameraView]. */
internal fun scannerMustStop(owner: CameraOwner) = owner != CameraOwner.Scanner

/** Plain-Kotlin stand-in for [FocusSettings]'s `RectF` fields -- JVM-testable without android.graphics. */
internal data class FocusSpec(val rect: Box, val restrict: Boolean, val showBoxes: Boolean)

/** Pure focus-region rule, factored out so it's testable without a real [VisionCameraView]. */
internal fun focusSettingsFor(config: ScannerConfig, frame: Box?): FocusSpec {
    val useFrame = config.restrictToFrame && frame != null
    return FocusSpec(
        rect = if (useFrame) frame!! else Box(0, 0, 0, 0),
        restrict = useFrame,
        showBoxes = config.showBoxes,
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
) {
    val view: VisionCameraView = VisionCameraView(ctx)

    private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ScanEvent> = _events

    private val _owner = MutableStateFlow(CameraOwner.None)
    val owner: StateFlow<CameraOwner> = _owner.asStateFlow()

    val policy = PausePolicy(scope)

    /** Last focus spec `apply()`ed; re-applied on `onCameraStarted` since `getFocusRegionManager()`
     *  throws [VisionSDKException.FocusRegionManagerNotAvailable] before the camera has started. */
    private var lastSpec: FocusSpec? = null
    private var started = false

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
                _events.tryEmit(ScanEvent.Boxes(barcodeBoundingBoxes + qrCodeBoundingBoxes, documentBoundingBox))
            }

            override fun onScanResult(barcodeList: List<ScannedCodeResult>) {
                _events.tryEmit(ScanEvent.Codes(barcodeList))
            }

            override fun onFailure(exception: VisionSDKException) {
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
                started = true
                applyFocusSpec()
                _events.tryEmit(ScanEvent.Started)
            }

            override fun onCameraStopped() {
                started = false
            }
        })

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
                if (paused) view.stopCamera()
                else if (_owner.value == CameraOwner.Scanner) view.startCamera()
            }
        }
    }

    /** Stops the scanner camera unless [owner] is [CameraOwner.Scanner]. */
    fun claim(owner: CameraOwner) {
        _owner.value = owner
        if (scannerMustStop(owner)) view.stopCamera() else if (!policy.paused.value) view.startCamera()
    }

    fun apply(config: ScannerConfig, frame: RectF?, scanning: ScanningMode) {
        config.detection?.let { view.configure(it, scanning, config.multiple) }
        view.setCameraSettings(CameraSettings(nthFrameToProcess = config.nthFrame, orientationMode = CameraOrientationMode.PORTRAIT))
        view.enableTapToFocus()
        view.enablePinchPanToZoom()
        val frameBox = frame?.let { Box(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
        lastSpec = focusSettingsFor(config, frameBox)
        if (started) applyFocusSpec()
    }

    private fun applyFocusSpec() {
        val spec = lastSpec ?: return
        view.getFocusRegionManager().setFocusSettings(
            FocusSettings(
                context = ctx,
                focusImageRect = RectF(spec.rect.left.toFloat(), spec.rect.top.toFloat(), spec.rect.right.toFloat(), spec.rect.bottom.toFloat()),
                shouldScanInFocusImageRect = spec.restrict,
                showCodeBoundariesInMultipleScan = spec.showBoxes,
                showDocumentBoundaries = false,
            ),
        )
    }

    fun pauseDetection() = view.pauseDetection()
    fun resumeDetection() = view.resumeDetection()
    fun capture() = view.capture()
    fun rescan() = view.rescan()
    fun torch(on: Boolean) = view.setFlashTurnedOn(on)
    fun zoom(ratio: Float) = view.setZoomRatio(ratio)
}
