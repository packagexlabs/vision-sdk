package io.packagex.visiondemo.scanner

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.PersistableBundle
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import io.packagex.visiondemo.ar.ArSurface
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.RecomposeLog
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.document.DocumentController
import io.packagex.visiondemo.document.DocumentFileProvider
import io.packagex.visiondemo.document.DocumentSurface
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.settings.SheetHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.roundToInt

/**
 * Wires [ScannerViewModel] to [ScannerScreen]: camera permission (sent on start even when already
 * granted -- the ViewModel doesn't claim the camera until then, task-7-report.md; rechecked on
 * resume while denied, iOS `recheckPermission` on `didBecomeActive` :70), effects (toast, haptic,
 * clipboard) and `UserActive` on camera-layer taps (iOS :17-18).
 */
@Composable
fun ScannerRoute(viewModel: ScannerViewModel = hiltViewModel()) {
    val uiState = viewModel.state.collectAsStateWithLifecycle()
    // The live detection boxes change on every analysed frame while codes are in view; only BoxesOverlay's draw
    // phase reads them. The rest of the screen sees the state without them, so it recomposes only on real changes.
    val state by remember { derivedStateOf { uiState.value.withoutBoxes() } }
    val boxes = remember { derivedStateOf { uiState.value.boxes } }
    RecomposeLog("ScannerRoute")
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onAction(ScannerAction.PermissionResult(granted))
    }

    // Vision Scanner's Photos button (iOS PhotosPicker): the picked image is extracted like a capture.
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                // iOS returns silently when the image can't be loaded (CameraScreen :76); log it here.
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { decodeBitmap(context, uri) }.onFailure { Log.w("ScannerRoute", "Photo decode failed: $uri", it) }.getOrNull()
                }
                if (bitmap != null) viewModel.onAction(ScannerAction.ImportPhoto(bitmap))
            }
        }
    }

    LaunchedEffect(Unit) {
        if (hasCameraPermission(context)) {
            viewModel.onAction(ScannerAction.PermissionResult(true))
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // iOS recheckPermission(): the user may have granted it from system Settings and come back.
    val activity = LocalActivity.current
    var arInstallPending by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (state.permissionDenied && hasCameraPermission(context)) {
            viewModel.onAction(ScannerAction.PermissionResult(true))
        }
        // Back from the Play Store's ARCore install: ask again without prompting (INSTALLED, or declined).
        if (arInstallPending && activity != null) {
            arInstallPending = false
            requestArInstall(activity, userRequested = false, onPending = { arInstallPending = true }, onResult = viewModel::onAction)
        }
        onPauseOrDispose { }
    }

    LaunchedEffect(viewModel) {
        // Toasts must not stall haptic/copy behind a suspended showSnackbar(): each toast is
        // launched as its own child job (replacing any still-showing one), capped at iOS's ~1.9s.
        var toastJob: Job? = null
        viewModel.effects.collect { effect ->
            when (effect) {
                is ScannerEffect.Toast -> {
                    toastJob?.cancel()
                    snackbarHostState.currentSnackbarData?.dismiss()
                    toastJob = launch { withTimeoutOrNull(1_900) { snackbarHostState.showSnackbar(effect.text) } }
                }
                ScannerEffect.Haptic -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                is ScannerEffect.Copy -> copyToClipboard(context, effect.text)
                ScannerEffect.PickPhoto -> photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                ScannerEffect.InstallArCore -> activity?.let {
                    requestArInstall(it, userRequested = true, onPending = { arInstallPending = true }, onResult = viewModel::onAction)
                }
                is ScannerEffect.SharePdf -> sharePdf(context, effect.file)
            }
        }
    }

    val cameraViewRaw = viewModel.camera.view
    Box(Modifier.fillMaxSize()) {
        ScannerScreen(
            state = state,
            cameraView = {
                RecomposeLog("cameraView")
                Box(
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        // Observed on the Initial pass without consuming, so the SDK's pinch-to-zoom
                        // (dispatched to the embedded AndroidView) still works. A single-finger tap
                        // focuses there and draws the ring (iOS SpatialTapGesture, :17-21).
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            viewModel.onAction(ScannerAction.UserActive)
                            var tap = true
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.size > 1 || event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) tap = false
                            } while (event.changes.any { it.pressed })
                            if (tap && size.width > 0 && size.height > 0) {
                                viewModel.onAction(ScannerAction.Focus(down.position.x / size.width, down.position.y / size.height))
                            }
                        }
                    },
                ) {
                    if (state.mode == ScanMode.Ar) {
                        if (!state.permissionDenied) ArSurface(controller = viewModel.ar, paused = state.paused)
                    } else if (cameraViewRaw != null) {
                        CameraSurface(view = cameraViewRaw, paused = state.paused)
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                    // Document Acquisition owns the sensor with its own CameraX pipeline (the SDK camera is stopped).
                    val doc = viewModel.document as? DocumentController
                    if (state.mode == ScanMode.DocAcq && doc != null && !state.permissionDenied) {
                        DocumentSurface(doc, paused = state.paused, showQuad = state.result == null && state.phase == Phase.Idle)
                    }
                }
            },
            boxes = { boxes.value },
            drawer = { state.result?.let { ResultDrawer(it, state.tags, state.items.size, state.resultExpanded, viewModel::onAction) } },
            sheets = { SheetHost(state, viewModel::onAction) },
            onAction = viewModel::onAction,
        )
        // Below the status bar / cutout: 96 dp from the top edge under a 24 dp status bar, as before.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 72.dp),
        ) { data ->
            Text(
                data.visuals.message,
                style = inter(14.sp, FontWeight.Medium),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .shadow(12.dp, RoundedCornerShape(12.dp))
                    .background(PX.Ink, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            )
        }
    }
}

/** A picked photo as a software bitmap (the extraction reads its pixels), upright per its EXIF,
 *  with the long edge capped at [MAX_PHOTO_EDGE] px (aspect kept) so a large photo can't exhaust memory. */
private fun decodeBitmap(context: Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val (w, h) = info.size.width to info.size.height
        val long = maxOf(w, h)
        if (long > MAX_PHOTO_EDGE) {
            val scale = MAX_PHOTO_EDGE.toFloat() / long
            decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }
    }

private const val MAX_PHOTO_EDGE = 4000

/** First AR entry (the original ArScannerActivity's `requestInstall`): [onPending] when the Play Store install
 *  was started (ask again on resume), otherwise the outcome as [ScannerAction.ArInstallResult]. */
private fun requestArInstall(activity: Activity, userRequested: Boolean, onPending: () -> Unit, onResult: (ScannerAction) -> Unit) {
    val result = try {
        when (ArCoreApk.getInstance().requestInstall(activity, userRequested)) {
            ArCoreApk.InstallStatus.INSTALLED -> ArInstall.Installed
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return onPending()
        }
    } catch (e: UnavailableUserDeclinedInstallationException) {
        ArInstall.Declined
    } catch (e: Exception) {   // device not compatible, SDK too old, ...
        Log.w("ScannerRoute", "ARCore unavailable", e)
        ArInstall.Unsupported
    }
    onResult(ScannerAction.ArInstallResult(result))
}

/** [ScannerEffect.SharePdf]: the system share sheet, reading the PDF through the app's [DocumentFileProvider]. */
private fun sharePdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.docs", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share PDF"))
}

/** [ScannerEffect.Copy]: scanned values can be personal data, so the clip is marked sensitive. */
private fun copyToClipboard(context: Context, text: String) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("VisionSDK", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboardManager.setPrimaryClip(clip)
}
