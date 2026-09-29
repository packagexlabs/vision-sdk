package io.packagex.visiondemo.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VisionColorScheme = lightColorScheme(
    primary = PX.Purple,
    surface = Color.White,
    onSurface = PX.Ink,
)

@Composable
fun VisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VisionColorScheme, content = content)
}
