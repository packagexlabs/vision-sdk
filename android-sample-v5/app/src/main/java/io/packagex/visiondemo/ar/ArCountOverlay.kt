package io.packagex.visiondemo.ar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Over [ArSurface]: every touch goes to [onTouch], for the idle timer. AR Item Count has no controls of its own on the
 * camera (spec 5.10: no section bracket, no gap markers, no manual add); the GL thread draws the markers.
 */
@Composable
fun ArCountOverlay(onTouch: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize()
            .pointerInput(Unit) {
                // Observed on the Initial pass without consuming, so the chrome under the overlay still gets it
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onTouch()
                }
            },
    )
}
