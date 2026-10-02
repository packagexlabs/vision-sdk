package io.packagex.visiondemo.ar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.arcount.Bracket
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.glass
import io.packagex.visiondemo.designsystem.mono
import io.packagex.visiondemo.designsystem.montserrat
import kotlin.math.roundToInt

/**
 * AR Count's controls over [ArSurface] (spec 5.5): the prompt; the section's count (its bracket: "GTIN × N", "N–M ?"
 * while unresolved, a lock while frozen) where the GL thread drew the section anchor, docked above the shutter while
 * the anchor is not in view; a tap on the count adds a unit by hand, a long press takes the last one back. Finish,
 * Restart and Accept range show when the section's state offers them ([arButtons]); a tap near a gap marker fills it.
 * [view] is the UI copy of the counter's view ([forUi]); [screen] is read only while placing and on taps.
 */
@Composable
fun ArCountOverlay(view: CountView, screen: State<ArScreen>, onCommand: (Command) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val tapRadiusPx = with(density) { 32.dp.toPx() }
    Box(
        modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { pos -> screen.value.gapAt(pos.x, pos.y, tapRadiusPx)?.let { onCommand(Command.FillGap(it)) } }
        },
    ) {
        view.prompt?.let { p ->
            Text(
                promptText(p, view.bracket),
                style = montserrat(15.sp),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(top = 104.dp, start = 24.dp, end = 24.dp)
                    .glass(16.dp, fill = PX.Ink.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        view.bracket?.let { b -> BracketCount(b, screen, onCommand) }
        val buttons = arButtons(view)
        if (buttons.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(bottom = BUTTONS_BOTTOM),
            ) {
                buttons.forEach { c ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.heightIn(min = 44.dp).glass().clickable { onCommand(c) }.padding(horizontal = 16.dp),
                    ) {
                        Text(buttonLabel(c), style = montserrat(13.sp), color = Color.White)
                    }
                }
            }
        }
    }
}

/** The count chip: above the bracket's point while the GL thread drew one, else docked above the buttons. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BracketCount(b: Bracket, screen: State<ArScreen>, onCommand: (Command) -> Unit) {
    val density = LocalDensity.current
    val dockBottomPx = WindowInsets.safeDrawing.getBottom(density) + with(density) { (BUTTONS_BOTTOM + 60.dp).toPx() }
    val abovePx = with(density) { 14.dp.toPx() }
    val text = bracketText(b)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .layout { measurable, constraints ->
                val chip = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val maxX = (constraints.maxWidth - chip.width).coerceAtLeast(0)
                    val maxY = (constraints.maxHeight - chip.height).coerceAtLeast(0)
                    val at = screen.value.bracket
                    val x = if (at != null) at.x - chip.width / 2f else (constraints.maxWidth - chip.width) / 2f
                    val y = if (at != null) at.y - chip.height - abovePx else constraints.maxHeight - dockBottomPx - chip.height
                    chip.place(x.roundToInt().coerceIn(0, maxX), y.roundToInt().coerceIn(0, maxY))
                }
            }
            .semantics { contentDescription = "Section count $text. Tap to add one by hand, long-press to take it back." }
            .glass(18.dp, fill = PX.Ink.copy(alpha = 0.85f))
            .combinedClickable(onClick = { onCommand(Command.AddUnit) }, onLongClick = { onCommand(Command.RemoveManualUnit) })
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp),
    ) {
        if (b.frozen) Icon(Icons.Filled.Lock, contentDescription = "Frozen", tint = PX.Neon, modifier = Modifier.size(16.dp))
        Text(text, style = mono(16.sp), color = if (b.unresolved) PX.Neon else Color.White)
    }
}

/** The gap whose marker is within [radius] of ([x], [y]), the nearest one; null when none is. */
fun ArScreen.gapAt(x: Float, y: Float, radius: Float): Int? =
    gaps.map { it to (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
        .filter { it.second <= radius * radius }
        .minByOrNull { it.second }?.first?.gapId

private val BUTTONS_BOTTOM = 160.dp
