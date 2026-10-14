package io.packagex.visiondemo.ar

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.packagex.visiondemo.designsystem.PX

/**
 * AR Item Count's camera view: a [GLSurfaceView] that [controller] draws the ARCore feed and the markers into. A new
 * session starts when this enters the composition and closes when it leaves (mode switch). The ViewModel pauses and
 * resumes the session; [paused] only draws the ink scrim over the stopped surface (a SurfaceView can't be blurred like
 * the scanner preview).
 */
@Composable
fun ArSurface(controller: ArCount, paused: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        val attached = remember { arrayOfNulls<GLSurfaceView>(1) }
        AndroidView(factory = { GLSurfaceView(it).also { v -> attached[0] = v; controller.attach(v) } }, modifier = Modifier.fillMaxSize())
        DisposableEffect(controller) { onDispose { attached[0]?.let(controller::detach) } }
        if (paused) Box(Modifier.fillMaxSize().background(PX.Ink.copy(alpha = 0.7f)))
    }
}
