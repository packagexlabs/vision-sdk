package io.packagex.visiondemo.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.PXButton
import io.packagex.visiondemo.designsystem.RecomposeLog
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.montserrat
import io.packagex.visiondemo.model.DetectedCode
import io.packagex.visiondemo.model.ScanMode

/** Gate card, no-permission screen, alert card, processing spinner and the live boxes overlay.
 *  Ported from iOS `Overlays.swift`. */

/** Price/Retrieval are the only gated modes in this port (Dimensioning is not in the mode dial). */
@Composable
fun GateCard(state: ScannerUiState, onAction: (ScannerAction) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .background(Color.White, RoundedCornerShape(20.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = PX.Purple, modifier = Modifier.size(36.dp))
        Text("Authentication required", style = montserrat(18.sp), color = PX.Ink)
        Text(
            if (state.mode == ScanMode.Price) {
                "Please authenticate first for Price Tag mode usage."
            } else {
                "Please authenticate first for Item Retrieval mode usage."
            },
            style = inter(14.sp),
            color = PX.Text2,
        )
        PXButton(title = if (state.entitlementChecking) "Authenticating…" else "Authenticate") {
            if (!state.entitlementChecking) onAction(ScannerAction.Authenticate)
        }
    }
}

@Composable
fun NoPermissionView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxSize().background(PX.Ink).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Filled.VideocamOff, contentDescription = null, tint = PX.Neon, modifier = Modifier.size(48.dp))
            Text("No Camera Access", style = montserrat(22.sp), color = Color.White)
            Text(
                "Allow camera access in Settings to start scanning.",
                style = inter(15.sp),
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            PXButton(title = "Open Settings") { openAppSettings(context) }
        }
    }
}

/** global-constraints.md: "Empty key = app shows 'Add STAGING_API_KEY to secrets.properties'." */
@Composable
fun MissingKeyView(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(PX.Ink).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = inter(15.sp),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
fun AlertCard(alert: Alert, onAction: (ScannerAction) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(PX.Ink.copy(alpha = 0.55f)).safeDrawingPadding().padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .background(Color.White, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(alert.title, style = montserrat(17.sp), color = PX.Ink)
            Text(alert.message, style = inter(14.sp), color = PX.Text2)
            Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                alert.actions.forEach { a -> PXButton(title = a.label, kind = a.kind) { onAction(a.action) } }
            }
        }
    }
}

/** iOS shows this only while `mode == .ocr && phase == .processing`; [ScannerScreen] gates the call the same way. */
@Composable
fun ProcessingSpinner(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .background(PX.Ink.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 18.dp)
                .semantics { contentDescription = "Extracting" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(color = Color.White)
            Text(
                "Cancel",
                style = montserrat(13.sp),
                color = PX.Neon,
                modifier = Modifier.clickable(onClick = onCancel),
            )
        }
    }
}

/** Live-detection boxes, drawn in camera-view px. Captions are the symbology with any "Vision"
 *  prefix stripped. [boxes] is read only while drawing, so a moving code redraws this layer without
 *  recomposing anything. */
@Composable
fun BoxesOverlay(boxes: () -> List<DetectedCode>, modifier: Modifier = Modifier) {
    RecomposeLog("BoxesOverlay")
    val textMeasurer = rememberTextMeasurer()
    val captionStyle = remember { TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium) }
    Canvas(modifier = modifier.fillMaxSize()) {
        boxes().forEach { code ->
            val left = code.box.left.toFloat()
            val top = code.box.top.toFloat()
            val w = (code.box.right - code.box.left).toFloat()
            val h = (code.box.bottom - code.box.top).toFloat()
            drawRoundRect(
                color = PX.Neon,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
            val caption = code.symbology.removePrefix("Vision")
            if (caption.isNotEmpty()) {
                val layout = textMeasurer.measure(caption, captionStyle)
                val padding = 4.dp.toPx()
                val labelTop = (top - layout.size.height - padding).coerceAtLeast(0f)
                drawRect(
                    color = PX.Ink.copy(alpha = 0.72f),
                    topLeft = Offset(left, labelTop),
                    size = Size(layout.size.width.toFloat() + padding * 2, layout.size.height.toFloat()),
                )
                drawText(layout, topLeft = Offset(left + padding, labelTop))
            }
        }
    }
}
