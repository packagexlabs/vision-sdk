package io.packagex.visiondemo.scanner

import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.RecomposeLog

/**
 * Hosts the SDK's live camera view. Ported from iOS `CameraLayer`. When [paused] the frozen frame
 * is blurred (API 31+, via [androidx.compose.ui.draw.blur]) or dimmed with an ink scrim (29-30,
 * where the RenderEffect-backed blur modifier is a no-op). The "tap anywhere to resume" affordance
 * itself lives in [ScannerScreen] (global-constraints.md: no pause dialog), not here, so it still
 * works when this composable isn't the one hosting the real camera (e.g. in tests / previews).
 */
@Composable
fun CameraSurface(view: View, paused: Boolean, modifier: Modifier = Modifier) {
    RecomposeLog("CameraSurface")
    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { view.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = if (paused && Build.VERSION.SDK_INT >= 31) Modifier.fillMaxSize().blur(24.dp) else Modifier.fillMaxSize(),
        )
        if (paused && Build.VERSION.SDK_INT < 31) {
            Box(Modifier.fillMaxSize().background(PX.Ink.copy(alpha = 0.7f)))
        }
    }
}
