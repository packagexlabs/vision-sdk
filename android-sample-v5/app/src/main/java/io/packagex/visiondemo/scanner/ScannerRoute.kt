package io.packagex.visiondemo.scanner

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.inter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wires [ScannerViewModel] to [ScannerScreen]: camera permission (sent on start even when already
 * granted -- the ViewModel doesn't claim the camera until then, task-7-report.md; rechecked on
 * resume while denied, iOS `recheckPermission` on `didBecomeActive` :70), effects (toast, haptic,
 * clipboard) and `UserActive` on camera-layer taps (iOS :17-18).
 */
@Composable
fun ScannerRoute(viewModel: ScannerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onAction(ScannerAction.PermissionResult(granted))
    }

    LaunchedEffect(Unit) {
        if (hasCameraPermission(context)) {
            viewModel.onAction(ScannerAction.PermissionResult(true))
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // iOS recheckPermission(): the user may have granted it from system Settings and come back.
    LifecycleResumeEffect(Unit) {
        if (state.permissionDenied && hasCameraPermission(context)) {
            viewModel.onAction(ScannerAction.PermissionResult(true))
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
            }
        }
    }

    val cameraViewRaw = viewModel.camera.view
    Box(Modifier.fillMaxSize()) {
        ScannerScreen(
            state = state,
            cameraView = {
                Box(
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        // Observed on the Initial pass without consuming, so the SDK's own
                        // tap-to-focus (dispatched to the embedded AndroidView) still fires.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            viewModel.onAction(ScannerAction.UserActive)
                            waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        }
                    },
                ) {
                    if (cameraViewRaw != null) {
                        CameraSurface(view = cameraViewRaw, paused = state.paused)
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            },
            onAction = viewModel::onAction,
        )
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp)) { data ->
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

/** [ScannerEffect.Copy]: scanned values can be personal data, so the clip is marked sensitive. */
private fun copyToClipboard(context: Context, text: String) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("VisionSDK", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboardManager.setPrimaryClip(clip)
}
