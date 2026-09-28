package io.packagex.visiondemo.scanner

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Wires [ScannerViewModel] to [ScannerScreen]: camera permission (sent on start even when already
 * granted -- the ViewModel doesn't claim the camera until then, task-7-report.md), effects (toast,
 * haptic, clipboard).
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

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ScannerEffect.Toast -> snackbarHostState.showSnackbar(effect.text, duration = SnackbarDuration.Short)
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
                if (cameraViewRaw != null) {
                    CameraSurface(view = cameraViewRaw, paused = state.paused)
                } else {
                    Box(Modifier.fillMaxSize())
                }
            },
            onAction = viewModel::onAction,
        )
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp))
    }
}

/** [ScannerEffect.Copy]: scanned values can be personal data, so the clip is marked sensitive. */
private fun copyToClipboard(context: Context, text: String) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("VisionSDK", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboardManager.setPrimaryClip(clip)
}
