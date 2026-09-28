package io.packagex.visiondemo.document

import android.graphics.Bitmap
import android.os.Build
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packagex.docscanner.DocumentQuad
import io.packagex.visiondemo.designsystem.PX
import kotlin.math.max

/**
 * Document Acquisition's camera: the CameraX preview of [controller] and the live page outline (iOS
 * CameraLayer `documentBoundary`: 2 dp neon border, 12 % neon fill) while [showQuad]. The pipeline is bound
 * only while composed and not [paused]; paused (idle, heat, background) it is unbound and the last frame is
 * shown blurred (dimmed on API 29-30, as [io.packagex.visiondemo.scanner.CameraSurface]).
 */
@Composable
fun DocumentSurface(controller: DocumentController, paused: Boolean, showQuad: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(paused, owner) {
        if (!paused) controller.bind(owner, preview)
        onDispose {
            if (!paused) frozen = preview.bitmap   // the frame to blur while paused
            controller.unbind()
        }
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        if (paused) {
            frozen?.let {
                Image(
                    it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = if (Build.VERSION.SDK_INT >= 31) Modifier.fillMaxSize().blur(24.dp) else Modifier.fillMaxSize(),
                )
            }
            if (Build.VERSION.SDK_INT < 31) Box(Modifier.fillMaxSize().background(PX.Ink.copy(alpha = 0.7f)))
        } else if (showQuad) {
            val quad by controller.quad.collectAsStateWithLifecycle()
            quad?.let { QuadOutline(it, mirrored = controller.front) }
        }
    }
}

/** The quad in analysis-frame px, mapped onto the FILL_CENTER preview (from the original's QuadOverlayView);
 *  [mirrored] for the front lens, whose preview PreviewView mirrors. */
@Composable
private fun QuadOutline(q: DocumentQuad, mirrored: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        if (q.frameWidth == 0 || q.frameHeight == 0) return@Canvas
        val (rotW, rotH) = if (q.rotationDegrees % 180 == 0) q.frameWidth.toFloat() to q.frameHeight.toFloat() else q.frameHeight.toFloat() to q.frameWidth.toFloat()
        val scale = max(size.width / rotW, size.height / rotH)
        val dx = (size.width - rotW * scale) / 2
        val dy = (size.height - rotH * scale) / 2
        val path = Path()
        q.corners.forEachIndexed { i, c ->
            val (rx, ry) = when (q.rotationDegrees) {
                90 -> (q.frameHeight - c.y) to c.x
                180 -> (q.frameWidth - c.x) to (q.frameHeight - c.y)
                270 -> c.y to (q.frameWidth - c.x)
                else -> c.x to c.y
            }
            val x = (rx * scale + dx).let { if (mirrored) size.width - it else it }
            val y = ry * scale + dy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, PX.Neon.copy(alpha = 0.12f))
        drawPath(path, PX.Neon, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
    }
}
