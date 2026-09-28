package io.packagex.visiondemo.ar

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.packagex.visiondemo.designsystem.PX

/**
 * AR Barcode's camera view (iOS `V5ARView`): a [GLSurfaceView] that [controller] draws the ARCore feed and
 * markers into. A new session starts when this enters the composition and closes when it leaves (mode switch).
 * The ViewModel pauses and resumes the session; [paused] only draws the ink scrim over the stopped surface
 * (a SurfaceView can't be blurred like the scanner preview).
 */
@Composable
fun ArSurface(controller: ArCamera, paused: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { GLSurfaceView(it).also(controller::attach) }, modifier = Modifier.fillMaxSize())
        DisposableEffect(controller) { onDispose { controller.detach() } }
        if (paused) Box(Modifier.fillMaxSize().background(PX.Ink.copy(alpha = 0.7f)))
    }
}
