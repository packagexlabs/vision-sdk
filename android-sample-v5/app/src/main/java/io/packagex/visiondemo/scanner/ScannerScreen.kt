package io.packagex.visiondemo.scanner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.RecomposeLog
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.model.DetectedCode
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.ScanMode
import kotlinx.coroutines.launch

/**
 * The camera screen -- one live camera for the module opened from [HomeScreen], one shutter. Ported from iOS
 * `UI/CameraScreen.swift`. A single Barcode/QR result is a [CodeHud] over the camera; every other result
 * fills the [drawer] slot. [drawer] and [sheets] are slots for [ResultDrawer] and
 * [io.packagex.visiondemo.settings.SheetHost], filled by [ScannerRoute]. Layer order follows iOS:
 * drawer above the chrome, alerts above the drawer (the report card is the drawer's own overlay).
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
    /** Live detection boxes, read only while drawing (see [BoxesOverlay]); [ScannerRoute] passes them apart from [state]. */
    boxes: () -> List<DetectedCode> = { state.boxes },
    /** A mode's own layer over the camera: above the scrim, below the chrome (AR Item Count's touch reporting). */
    overlay: @Composable () -> Unit = {},
    drawer: @Composable () -> Unit = {},
    sheets: @Composable () -> Unit = {},
    onAction: (ScannerAction) -> Unit,
) {
    RecomposeLog("ScannerScreen")
    Box(modifier = Modifier.fillMaxSize().background(PX.Ink)) {
        cameraView()

        state.focus?.let { f -> key(f.id) { FocusRing(f) } }

        val hud = state.codeHud
        // The captured code's box stays drawn under its code card (v6); otherwise the live boxes.
        BoxesOverlay(boxes = if (hud != null) { { listOf(hud) } } else boxes)

        if (state.result != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)))
        }

        // Torch glow (iOS :27-30).
        if (state.torch && state.result == null) {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(1f, 0.98f, 0.92f, 0.32f), Color.Transparent),
                            center = Offset(size.width * 0.5f, size.height * 0.42f),
                            radius = size.height * 0.6f,
                        ),
                    )
                },
            )
        }

        Box(Modifier.fillMaxSize().background(scrimBrush))

        Viewfinder(state = state, onAction = onAction)

        overlay()

        if (state.result == null || hud != null) {
            Chrome(state = state, onAction = onAction)
        }

        hud?.let { CodeHud(code = it, onAction = onAction) }

        if (state.mode == ScanMode.Ocr && state.phase == Phase.Processing) {
            ProcessingSpinner(onCancel = { onAction(ScannerAction.CancelProcessing) })
        }

        // White capture flash (iOS :44).
        if (state.flash) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)))

        if (state.gated && !state.permissionDenied && state.result == null) {
            GateCard(state = state, onAction = onAction, modifier = Modifier.align(Alignment.Center).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal = 20.dp))
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

        if (state.result != null && hud == null) drawer()

        state.alert?.let { AlertCard(alert = it, onAction = onAction) }

        // Nothing else works without a key; shown on top of everything (global-constraints.md).
        state.missingKey?.let { MissingKeyView(it) }

        sheets()
    }
}

/** Design's tap-to-focus feedback: a 64dp neon square that settles from 1.4x and fades out (iOS `FocusRing`). */
@Composable
private fun FocusRing(f: FocusTap) {
    val scale = remember { Animatable(1.4f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, tween(270, easing = LinearOutSlowInEasing)) }
        alpha.animateTo(0f, tween(600, delayMillis = 300, easing = LinearOutSlowInEasing))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset(x = maxWidth * f.x - 32.dp, y = maxHeight * f.y - 32.dp)
                .size(64.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }
                .border(2.dp, PX.Neon, RoundedCornerShape(12.dp)),
        )
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
