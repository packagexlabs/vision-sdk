package io.packagex.texttemplates.sdk

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * App-held handle for the SDK-Camera [PXScannerView]. The host creates one,
 * passes it to `PXScannerView(controller = ...)`, and drives the scan flow —
 * the application controls pause/resume/repredict; the view only owns the
 * camera + session plumbing. Mirrors iOS `PXScannerController`.
 */
class PXScannerController : PXScanControl {
    private var session: PXScanSession? = null

    private val _isPaused = MutableStateFlow(false)
    /** Observable paused state, mirroring iOS `PXScannerController.isPaused`.
     *  Reflects explicit [pause]/[resume] calls (not the session's internal
     *  auto-pause after a result). */
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    internal fun attach(session: PXScanSession) { this.session = session }
    internal fun detach() { session = null }

    /** Pause processing (host keeps its preview). No events until [resume]. */
    override fun pause() { session?.pause(); _isPaused.value = true }
    /** Resume scanning the next item after a pause or the auto-pause following a result. */
    override fun resume() { session?.resume(); _isPaused.value = false }
    /** Re-run a persisted scan against another template (no reprocessing). Throws
     *  PXException(PXErrorCode.SCANNER_INACTIVE) if the scanner view isn't mounted
     *  (distinct from NO_RETAINED_FRAME, which means the scanId itself is gone). */
    override suspend fun repredict(scanId: String, templateId: String): PXQuickResult =
        (session ?: throw PXException("Scanner not active", PXErrorCode.SCANNER_INACTIVE)).repredict(scanId, templateId)
}

/**
 * Drop-in SDK-Camera view: owns CameraX (preview + analysis at 1920×1440, AF
 * state feed, centre metering) AND the [PXScanSession] built over [client]'s
 * loaded pool. Renders the camera preview ONLY — the host draws its own overlays
 * on top using the [onEvent] stream (guidance → hints, prediction → results).
 *
 * Client-driven (mirrors iOS): the view builds and owns the session, keyed on
 * [client] + [regionOfInterest] + [configuration], so a changed ROI or
 * configuration rebuilds the session (and rebinds CameraX) automatically. The
 * application drives the scan flow through a [PXScannerController] —
 * pause/resume/repredict stay under app control; the view only manages the
 * camera + session lifecycle tied to composition.
 *
 * Empty pool: [PXClient.makeScanSession] fails fast, so no session is built — but
 * the view still mounts and shows the camera preview, emitting a single
 * [PXScanEvent.Failed] with code `not_loaded` so the host can surface it. Load a
 * pool (`PXClient.load(...)`) and recompose to start scanning.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun PXScannerView(
    client: PXClient,
    modifier: Modifier = Modifier,
    regionOfInterest: PXRegion? = null,
    configuration: PXScanConfiguration = PXScanConfiguration(),
    controller: PXScannerController? = null,
    onEvent: (PXScanEvent) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Kept for the composable's whole lifetime; shut down only on final disposal
    // (NOT on a session/ROI rebind, which would leave the new binding with a dead
    // executor).
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // Always deliver the LATEST onEvent lambda without recreating the session.
    val onEventState = rememberUpdatedState(onEvent)

    // Build + OWN the session. Rebuilt when the ROI (or client) changes — the new
    // session drives a CameraX rebind below (both are keyed on `session`).
    // makeScanSession fails fast on an empty pool; the view tolerates that (session
    // stays null) so it still mounts and shows the preview — parity with iOS, which
    // sets session = nil and emits .failed(notLoaded).
    val session: PXScanSession? = remember(client, regionOfInterest, configuration) {
        runCatching {
            client.makeScanSession(
                regionOfInterest = regionOfInterest,
                configuration = configuration,
                onEvent = { onEventState.value(it) },
            )
        }.getOrNull()
    }

    // Surface the empty-pool failure once (side effect, not during composition),
    // mirroring iOS's onEvent(.failed(notLoaded)) at creation.
    LaunchedEffect(session) {
        if (session == null) {
            onEventState.value(
                PXScanEvent.Failed(
                    "No templates are loaded. Call PXClient.load(...) before scanning.",
                    PXErrorCode.NOT_LOADED.code,
                ),
            )
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // FILL_CENTER: fill the container exactly so a host overlay (drawn
            // from the reported region) lines up with the visible camera without
            // an internal letterbox gap. The host frames this view at the desired
            // aspect (see the demo's CameraFrameBox).
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Session lifecycle tied to the view. Rebuilds with the session (ROI change).
    // No-op when the session couldn't be created (empty pool).
    DisposableEffect(session) {
        if (session != null) {
            controller?.attach(session)
            session.start()
            session.arm()
        }
        onDispose {
            if (session != null) {
                session.stop()
                controller?.detach()
            }
        }
    }

    // CameraX bind/unbind, keyed on the session so it rebinds to the new session
    // when the ROI changes. Does NOT shut down the shared analysis executor here.
    DisposableEffect(session, lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // Empty pool → no session/analysis. Still bind the preview so the view
            // mounts and shows the camera; the host already got Failed(not_loaded).
            if (session == null) {
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                    )
                } catch (e: Exception) {
                    Log.e("[PXScannerView]", "Camera bind (preview-only) failed", e)
                }
                return@addListener
            }

            val imageAnalysisBuilder = ImageAnalysis.Builder()
                // Force a 4:3 analysis stream near 1920×1440. NOT setTargetResolution:
                // that is a soft hint some HALs (observed on Samsung) ignore, handing
                // back a 1:1 square buffer (2992×2992). A square frame makes the
                // centred 4:3 focus box (computeFocusRects) degenerate to full height
                // — innerH = min(0.75·w·4/3, h) clamps to h when w == h — so the
                // resolved region's top-y collapses to 0 and the bracket overlay jumps
                // to the frame's top edge (and zone-2 / OCR run on the wrong FOV).
                // AspectRatioStrategy RATIO_4_3 filters the square candidate out.
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1440),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            Camera2Interop.Extender(imageAnalysisBuilder).apply {
                setSessionCaptureCallback(
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session2: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            val af = result.get(CaptureResult.CONTROL_AF_STATE)
                            session.updateAutoFocusHunting(
                                af == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                                    af == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
                            )
                        }
                    },
                )
                // Deterministic capture config — pins the same baseline iOS
                // (PXCameraController) uses, so the same label yields comparable
                // frames across platforms and the Android device matrix rather than
                // inheriting per-device camera defaults. Best-effort: a device HAL
                // may silently ignore an unsupported key.
                // EIS off — its per-frame crop/warp distorts text geometry and fights
                // the pipeline's own IMU + pair-stability gating.
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                )
                // CONTROL_MODE=AUTO → no scene-mode HDR tone-mapping (deterministic exposure).
                setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                // Pin 30 fps (iOS pins 30) for consistent frame timing.
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                // Continuous AF / AE / AWB, set explicitly (don't rely on defaults).
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                )
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }

            val imageAnalysis = imageAnalysisBuilder
                .build()
                .also { it.setAnalyzer(analysisExecutor, session.frameAnalyzer) }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
                session.attachCameraControl(camera.cameraControl)
                applyCenterMeteringRegions(camera)
            } catch (e: Exception) {
                Log.e("[PXScannerView]", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            session?.detachCamera()
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (e: Exception) {
                Log.e("[PXScannerView]", "Camera unbind failed", e)
            }
            // NOTE: the analysis executor is NOT shut down here — a session/ROI
            // rebind re-runs this effect, and a dead executor would break the new
            // binding. It is shut down only on final disposal below.
        }
    }

    // Shut the shared analysis executor down only when the composable leaves for
    // good (keyed on Unit → survives session/ROI rebinds).
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Bias continuous AF/AE toward the central region where the label sits. */
@OptIn(ExperimentalCamera2Interop::class)
private fun applyCenterMeteringRegions(camera: Camera) {
    try {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val active = info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
        ) ?: return
        val maxAfRegions = info.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_MAX_REGIONS_AF,
        ) ?: 0
        val maxAeRegions = info.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_MAX_REGIONS_AE,
        ) ?: 0
        if (maxAfRegions <= 0 && maxAeRegions <= 0) return

        val region = MeteringRectangle(
            active.left + active.width() / 4,
            active.top + active.height() / 4,
            active.width() / 2,
            active.height() / 2,
            MeteringRectangle.METERING_WEIGHT_MAX,
        )

        val optionsBuilder = CaptureRequestOptions.Builder()
        if (maxAfRegions > 0) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        }
        if (maxAeRegions > 0) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
        Camera2CameraControl.from(camera.cameraControl)
            .setCaptureRequestOptions(optionsBuilder.build())
    } catch (e: Exception) {
        Log.w("[PXScannerView]", "Center metering regions not applied", e)
    }
}
