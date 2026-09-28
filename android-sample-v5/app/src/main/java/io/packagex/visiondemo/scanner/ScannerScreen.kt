package io.packagex.visiondemo.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.ScanMode

/**
 * The camera screen -- one live camera, a mode dial, one shutter. Ported from iOS
 * `UI/CameraScreen.swift`. [drawer] and [sheets] are slots for the result drawer (Task 10) and
 * sheet host (Task 9); this task neither implements nor references them.
 *
 * The "camera paused" tap-to-resume affordance (global-constraints.md: no pause dialog) is
 * rendered here, driven by [ScannerUiState.paused] alone, so it works regardless of what
 * [cameraView] renders -- [CameraSurface] separately blurs the live view for the same state.
 *
 * [onAction] is declared last (not third, as the controller ruling's prose lists it) so the
 * task brief's own failing test -- `ScannerScreen(state, cameraView = {}) { got = it }` -- compiles:
 * Kotlin's trailing-lambda syntax only ever binds to the last formal parameter.
 */
@Composable
fun ScannerScreen(
    state: ScannerUiState,
    cameraView: @Composable () -> Unit,
    drawer: @Composable () -> Unit = {},
    sheets: @Composable () -> Unit = {},
    onAction: (ScannerAction) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(PX.Ink)) {
        cameraView()

        BoxesOverlay(boxes = state.boxes)

        if (state.result != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)))
        }

        Box(Modifier.fillMaxSize().background(scrimBrush))

        Viewfinder(state = state, onAction = onAction)

        if (state.result == null) {
            Chrome(state = state, onAction = onAction)
        }

        if (state.mode == ScanMode.Ocr && state.phase == Phase.Processing) {
            ProcessingSpinner(onCancel = { onAction(ScannerAction.CancelProcessing) })
        }

        if (state.gated && !state.permissionDenied && state.result == null) {
            GateCard(state = state, onAction = onAction, modifier = Modifier.align(Alignment.Center).padding(horizontal = 20.dp))
        }

        if (state.paused && !state.permissionDenied && state.result == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = "Camera paused"
                        role = Role.Button
                    }
                    .clickable { onAction(ScannerAction.Resume) },
            )
        }

        if (state.permissionDenied) {
            NoPermissionView()
        }

        if (state.result != null) drawer()

        state.alert?.let { AlertCard(alert = it, onAction = onAction) }

        // Nothing else works without a key; shown on top of everything (global-constraints.md).
        state.missingKey?.let { MissingKeyView(it) }

        sheets()
    }
}

private val scrimBrush = Brush.verticalGradient(
    0f to PX.Ink.copy(alpha = 0.7f),
    0.225f to Color.Transparent,
    0.64f to Color.Transparent,
    0.82f to PX.Ink.copy(alpha = 0.85f),
    1f to PX.Ink.copy(alpha = 0.85f),
)

@Preview(showBackground = true)
@Composable
private fun ScannerScreenPreview() {
    VisionTheme {
        ScannerScreen(state = ScannerUiState(codeInFrame = true), cameraView = {}, onAction = {})
    }
}
