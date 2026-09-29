package io.packagex.visiondemo.scanner

import android.graphics.RectF
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.RecomposeLog
import io.packagex.visiondemo.designsystem.Shutter
import io.packagex.visiondemo.designsystem.glass
import io.packagex.visiondemo.designsystem.mono
import io.packagex.visiondemo.designsystem.montserrat
import io.packagex.visiondemo.document.shown
import io.packagex.visiondemo.model.Feedback
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.ScanResult
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visiondemo.model.isCode
import io.packagex.visiondemo.model.isDocument
import io.packagex.visiondemo.model.viewfinder
import io.packagex.visiondemo.model.zooms
import kotlin.math.roundToInt

/**
 * Camera chrome: top icons, hint, context chip, capture-mode pill, zoom presets, mode dial and
 * shutter row. Ported from iOS `CameraScreen.chrome`. Hidden (opacity 0 in iOS) while a result is
 * shown -- [ScannerScreen] only composes this when `state.result == null`.
 */
@Composable
fun Chrome(state: ScannerUiState, onAction: (ScannerAction) -> Unit, modifier: Modifier = Modifier) {
    RecomposeLog("Chrome")
    val usesScanner = ownerFor(state.mode) == CameraOwner.Scanner
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.chromeInsets.only(WindowInsetsSides.Top))
                .padding(horizontal = 14.dp)
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (usesScanner || state.mode == ScanMode.DocAcq) {   // Document Acquisition has its own camera (iOS usesScanner)
                RoundIcon(
                    icon = if (state.torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    on = state.torch,
                    label = "Torch",
                    onClick = { onAction(ScannerAction.ToggleTorch) },
                )
                RoundIcon(
                    icon = Icons.Filled.Cameraswitch,
                    label = if (state.frontCamera) "Use back camera" else "Use front camera",
                    onClick = { onAction(ScannerAction.FlipCamera) },
                )
            }
            Spacer(Modifier.weight(1f))
            RoundIcon(icon = Icons.Filled.Tune, label = "Settings", onClick = { onAction(ScannerAction.OpenSheet(SheetKind.Settings)) })
        }

        if (state.prefs.showHints && !state.permissionDenied) {
            HintBar(text = hintFor(state))
        }

        Spacer(modifier = Modifier.weight(1f))

        val chip = chipFor(state)
        val showPill = (state.mode.isCode || state.mode.isDocument) && !state.gated
        val showZoom = state.mode.zooms.isNotEmpty() && !state.gated
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (chip != null) {
                ContextChip(label = chip.first, sub = chip.second, onClick = { onAction(chip.third) })
            }
            if (showPill || showZoom) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showPill) CapturePill(auto = state.prefs.autoCapture, onToggle = { onAction(ScannerAction.ToggleAuto) })
                    if (showZoom) ZoomPresets(zooms = state.mode.zooms, current = state.zoom, onZoom = { onAction(ScannerAction.Zoom(it)) })
                }
            }
        }

        ModeDial(selected = state.mode, onSelect = { onAction(ScannerAction.SetMode(it)) })
        ShutterRow(
            state = state,
            onAction = onAction,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.chromeInsets.only(WindowInsetsSides.Bottom))
                .padding(top = 12.dp, bottom = 8.dp),
        )
    }
}

/** System bars and the display cutout, not the IME: a sheet's keyboard must not move the camera chrome, nor the
 *  viewfinder (whose rect the SDK scans in). */
private val WindowInsets.Companion.chromeInsets: WindowInsets
    @Composable get() = systemBars.union(displayCutout)

/** The viewfinder brackets: the design's 390x844 artboard scaled to the screen, reported to the
 *  ViewModel in camera-view px via [ScannerAction.FrameChanged]. Ported from iOS `viewfinder(sx:sy:)`. */
@Composable
fun Viewfinder(state: ScannerUiState, onAction: (ScannerAction) -> Unit, modifier: Modifier = Modifier) {
    RecomposeLog("Viewfinder")
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val f = state.mode.viewfinder
        val multiLive = state.prefs.multi && (state.mode == ScanMode.Barcode || state.mode == ScanMode.QR)
        // Document Acquisition: the live page outline replaces the brackets once a page is found (iOS :98).
        val docSeen = state.mode == ScanMode.DocAcq && state.codeInFrame && state.phase == Phase.Idle
        val visible = f != null && !multiLive && !docSeen && state.result == null && !state.gated && !state.permissionDenied
        if (f != null && visible) {
            val insets = WindowInsets.chromeInsets.asPaddingValues()
            val insetTop = insets.calculateTopPadding()
            val insetBottom = insets.calculateBottomPadding()
            val contentHeight = maxHeight - insetTop - insetBottom
            val sx = maxWidth / 390.dp
            val sy = contentHeight / 844.dp
            val bottomReserve = bottomReserveDp(state)
            val top = insetTop + f.y.dp * sy
            val maxBottom = maxHeight - insetBottom - bottomReserve
            val height = maxOf(60.dp, minOf(f.height.dp * sy, maxBottom - top))
            val left = f.x.dp * sx
            val width = f.width.dp * sx

            val density = LocalDensity.current
            LaunchedEffect(left, top, width, height) {
                with(density) {
                    onAction(ScannerAction.FrameChanged(RectF(left.toPx(), top.toPx(), (left + width).toPx(), (top + height).toPx())))
                }
            }

            Box(modifier = Modifier.offset(x = left, y = top).size(width = width, height = height)) {
                ViewfinderFrame(corner = cornerColor(state), fill = fillColor(state), pulsing = state.phase != Phase.Idle)
            }
        }
    }
}

@Composable
private fun ViewfinderFrame(corner: Color, fill: Color, pulsing: Boolean) {
    RecomposeLog("ViewfinderFrame")
    // Runs only while pulsing, and is read in the layer (draw) phase, so the pulse never recomposes.
    val pulseAlpha = if (pulsing) {
        rememberInfiniteTransition(label = "viewfinderPulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(animation = tween(550), repeatMode = RepeatMode.Reverse),
            label = "alpha",
        )
    } else {
        null
    }
    Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = pulseAlpha?.value ?: 1f }) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(color = fill, cornerRadius = CornerRadius(16.dp.toPx()))
            val l = 28.dp.toPx(); val c = 16.dp.toPx(); val w = size.width; val h = size.height
            val path = Path().apply {
                moveTo(0f, l); lineTo(0f, c); quadraticTo(0f, 0f, c, 0f); lineTo(l, 0f)
                moveTo(w, l); lineTo(w, c); quadraticTo(w, 0f, w - c, 0f); lineTo(w - l, 0f)
                moveTo(0f, h - l); lineTo(0f, h - c); quadraticTo(0f, h, c, h); lineTo(l, h)
                moveTo(w, h - l); lineTo(w, h - c); quadraticTo(w, h, w - c, h); lineTo(w - l, h)
            }
            drawPath(path, color = corner, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, on: Boolean = false, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .glass(22.dp, fill = if (on) Color.White else PX.Glass)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (on) PX.Ink else Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun HintBar(text: String) {
    RecomposeLog("HintBar")
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = montserrat(13.sp),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.glass(16.dp, fill = PX.Ink.copy(alpha = 0.72f)).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ContextChip(label: String, sub: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.heightIn(min = 44.dp).glass().clickable(onClick = onClick).padding(horizontal = 14.dp),
    ) {
        Text(label, style = montserrat(13.sp), color = Color.White)
        if (sub.isNotEmpty()) Text(sub, style = montserrat(13.sp, FontWeight.Medium), color = PX.Lilac)
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
    }
}

@Composable
private fun CapturePill(auto: Boolean, onToggle: () -> Unit) {
    Row(modifier = Modifier.glass().semantics { contentDescription = "Capture mode" }) {
        listOf("Manual" to false, "Auto" to true).forEach { (label, isAuto) ->
            val on = auto == isAuto
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(if (on) Color.White else Color.Transparent)
                    .clickable(enabled = !on, onClick = onToggle)
                    .padding(horizontal = 14.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = montserrat(12.sp), color = if (on) PX.Ink else Color.White)
            }
        }
    }
}

@Composable
private fun ZoomPresets(zooms: List<Float>, current: Float, onZoom: (Float) -> Unit) {
    Row(modifier = Modifier.glass()) {
        zooms.forEach { z ->
            val on = current == z
            val label = if (z == z.toInt().toFloat()) "${z.toInt()}x" else "${z}x"
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                    .clip(CircleShape)
                    .background(if (on) Color.White else Color.Transparent)
                    .clickable { onZoom(z) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = mono(12.sp), color = if (on) PX.Ink else Color.White)
            }
        }
    }
}

/** Horizontally scrolling mode names, all always composed (not lazy) so every label is present
 *  in the tree at once, as iOS's plain `HStack` inside a `ScrollView`. The selection is kept
 *  centred (iOS `proxy.scrollTo(selected, anchor: .center)`) and the edges fade out (iOS's
 *  gradient `.mask`). */
@Composable
private fun ModeDial(selected: ScanMode, onSelect: (ScanMode) -> Unit) {
    RecomposeLog("ModeDial")
    val scrollState = rememberScrollState()
    // Centres in window coordinates, so the Row's padding and the current scroll don't skew the target.
    var viewportCenter by remember { mutableFloatStateOf(Float.NaN) }
    val itemCenters = remember { mutableStateMapOf<ScanMode, Float>() }

    // The dial can be re-created when the chrome above it changes with the mode (e.g. the doc-type chip),
    // so wait for its first layout instead of giving up, and jump there without animating on a fresh dial.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        val (center, viewport) = snapshotFlow { itemCenters[selected] to viewportCenter }
            .first { (c, v) -> c != null && !v.isNaN() }
        val target = (scrollState.value + (center!! - viewport).roundToInt()).coerceIn(0, scrollState.maxValue)
        if (placed) scrollState.animateScrollTo(target) else scrollState.scrollTo(target)
        placed = true
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .onGloballyPositioned { viewportCenter = it.positionInWindow().x + it.size.width / 2f }
            .edgeFadeMask(),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().horizontalScroll(scrollState).padding(horizontal = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScanMode.entries.forEach { m ->
                val isSelected = m == selected
                Text(
                    m.label,
                    style = montserrat(14.sp, if (isSelected) FontWeight.Bold else FontWeight.Medium),
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.62f),
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            itemCenters[m] = coords.positionInWindow().x + coords.size.width / 2f
                        }
                        .height(44.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 12.dp)
                        .clickable { onSelect(m) },
                )
            }
        }
    }
}

/** iOS `ModeDial`'s edge fade: transparent at 0%/100%, opaque from 11% to 89%. */
private fun Modifier.edgeFadeMask(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.11f to Color.Black,
                0.89f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun ShutterRow(state: ScannerUiState, onAction: (ScannerAction) -> Unit, modifier: Modifier = Modifier) {
    RecomposeLog("ShutterRow")
    Box(modifier = modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = shutterLabel(state) }) {
            Shutter(
                ringColor = if (state.prefs.autoCapture) PX.Neon else Color.White.copy(alpha = 0.92f),
                dimmed = state.gated || state.paused || !state.detectionEnabled,
                onTap = { onAction(ScannerAction.Shutter) },
                onLongPress = { onAction(ScannerAction.ToggleAuto) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.mode == ScanMode.Ocr) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(PX.Glass, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onAction(ScannerAction.PickPhoto) }
                        .semantics { contentDescription = "Import from Photos" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Photo, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            val last = state.lastResult
            if (last != null && last.first == state.mode) {
                LastThumb(
                    result = last.second,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                        .clickable { onAction(ScannerAction.ReopenLast) }
                        .semantics { contentDescription = "Open last result" },
                )
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun LastThumb(result: ScanResult, modifier: Modifier = Modifier) {
    val image = (result as? ScanResult.Ocr)?.image ?: (result as? ScanResult.Document)?.pages?.lastOrNull()?.shown(enhanced = true)
    if (image != null) {
        Image(bitmap = image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier = modifier.background(PX.Ink), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.DocumentScanner, contentDescription = null, tint = PX.Neon, modifier = Modifier.size(20.dp))
        }
    }
}

private fun shutterLabel(state: ScannerUiState): String = when {
    state.mode.isCode || state.mode.isDocument ->
        if (state.prefs.autoCapture) "Capture, long-press to turn Auto off" else "Capture, long-press for Auto"
    else -> "Capture"
}

/** iOS `codeSeen`/`live`/`cornerColor`/`fillColor`. Corner/fill color and the viewfinder-brackets swap
 *  ([Viewfinder]'s `docSeen`) still read [ScannerUiState.codeInFrame] (barcode/QR box presence, or
 *  Document Acquisition's boundary); the hint text reads [ScannerUiState.seesDocument] instead, since
 *  that's the correct SDK-Indications-driven signal for Vision Scanner (see `hintFor`). */
private fun ScannerUiState.isLive(): Boolean =
    !permissionDenied && result == null && phase == Phase.Idle && sheet == null && alert == null && detectionEnabled && !gated && !paused

private fun cornerColor(state: ScannerUiState): Color = when {
    state.feedback == Feedback.Error -> PX.Red
    state.feedback == Feedback.Success -> PX.Green
    state.phase != Phase.Idle -> Color.White
    state.codeInFrame && state.isLive() -> PX.Green
    state.isLive() -> Color.White
    else -> Color.White.copy(alpha = 0.45f)
}

private fun fillColor(state: ScannerUiState): Color = when (state.feedback) {
    Feedback.Success -> PX.Green.copy(alpha = 0.22f)
    Feedback.Error -> PX.Red.copy(alpha = 0.18f)
    null -> Color.Transparent
}

private fun chipFor(state: ScannerUiState): Triple<String, String, ScannerAction>? {
    if (state.gated || state.permissionDenied) return null
    return when (state.mode) {
        ScanMode.Ocr -> if (state.prefs.wildCard) {
            Triple("Wild card", "", ScannerAction.OpenSheet(SheetKind.Settings))
        } else {
            Triple(state.prefs.docType.label, "", ScannerAction.OpenSheet(SheetKind.DocType))
        }
        ScanMode.Retrieval -> Triple(
            "Item list",
            "· ${state.items.size} ${if (state.items.size == 1) "code" else "codes"}",
            ScannerAction.OpenSheet(SheetKind.Items),
        )
        ScanMode.Ar -> Triple("Items", "· ${state.itemNames.size}", ScannerAction.OpenSheet(SheetKind.ArItems))
        else -> null
    }
}

/** Height of the bottom controls above the safe area, as iOS `bottomReserve`. */
private fun bottomReserveDp(state: ScannerUiState): Dp {
    val pills = (state.mode.isCode || state.mode.isDocument || state.mode.zooms.isNotEmpty()) && !state.gated
    val chip = chipFor(state)
    return 56.dp + 80.dp + (if (pills) 56.dp else 0.dp) + (if (chip != null) 54.dp else 0.dp) + 14.dp
}

private fun hintFor(state: ScannerUiState): String {
    if (state.gated) return "Authentication required"
    if (!state.detectionEnabled && ownerFor(state.mode) == CameraOwner.Scanner) return "Detection paused"
    when (state.phase) {
        Phase.Scanning -> return "Capturing…"
        Phase.Processing -> return if (state.mode == ScanMode.DocAcq) {
            "Processing page…"
        } else {
            "Extracting · ${if (cloudSelected(state.prefs)) "Cloud" else "On-device"}"
        }
        Phase.Idle -> {}
    }
    val auto = state.prefs.autoCapture
    val codeSeen = state.codeInFrame
    var h = when (state.mode) {
        ScanMode.Ocr -> if (state.seesDocument) {
            if (auto) "Hold Still" else "Hold Still · tap to capture"
        } else {
            "Point camera to document"
        }
        ScanMode.Ar -> state.arCounts.sumOf { it.count }.let { n ->
            if (n > 0) "$n markers pinned · tap the shutter to finish" else "One marker per barcode value, pinned where it was last seen"
        }
        ScanMode.DocAcq -> if (state.seesDocument) {
            if (auto) "Page edges found · hold still" else "Page edges found · tap to capture"
        } else {
            "Fit the page inside the frame"
        }
        ScanMode.Retrieval -> {
            val n = state.codesInView.count { it in state.items }
            when {
                state.items.isEmpty() -> "Add item codes to find"
                state.codesInView.isEmpty() -> "Pan across the shelf"
                n == 0 -> "No listed items in view"
                else -> "$n listed item${if (n == 1) "" else "s"} in view"
            }
        }
        ScanMode.Price -> if (state.tags.isEmpty()) {
            "Point at a price tag"
        } else {
            "${state.tags.size} tag${if (state.tags.size == 1) "" else "s"} found · tap to review"
        }
        ScanMode.Barcode, ScanMode.QR -> {
            val multi = state.prefs.multi
            if (codeSeen) {
                if (multi) "Codes in view · tap to capture all" else if (auto) "Code detected · capturing" else "Code detected · tap to capture"
            } else {
                if (multi) "Point at all the codes you want" else "Align the code inside the frame"
            }
        }
    }
    if (auto && state.isLive() && !codeSeen) h += " · Auto capture on"
    return h
}
