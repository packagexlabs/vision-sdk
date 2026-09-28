package io.packagex.visiondemo.document

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.designsystem.LinkLabel
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.Segmented
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.mono

/** The page shown for the Enhanced / Original toggle (a page whose processing failed shows as captured). */
fun DocumentPage.shown(enhanced: Boolean): Bitmap = (if (enhanced) this.enhanced else page) ?: original

/**
 * Document Acquisition's drawer content, the Compose rewrite of DocumentReviewActivity laid out as iOS
 * ResultDrawer `docContent`: Enhanced / Original toggle, the page (tap opens it full screen, [onZoom]),
 * "Page i of n", its size and quality note, previous / next, and "Add page". Retake and Export PDF are
 * the drawer footer's buttons.
 */
@Composable
fun DocumentReview(
    pages: List<DocumentPage>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    enhanced: Boolean,
    onEnhancedChange: (Boolean) -> Unit,
    onZoom: (Bitmap) -> Unit,
    onAddPage: () -> Unit,
) {
    if (pages.isEmpty()) {
        Text(
            "No pages captured.", style = inter(14.sp), color = PX.Muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        )
        return
    }
    val i = index.coerceIn(0, pages.size - 1)
    val page = pages[i]
    val image = page.shown(enhanced)
    Segmented(items = listOf("Enhanced" to true, "Original" to false), selection = enhanced, onSelect = onEnhancedChange)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .widthIn(max = 150.dp)
                .heightIn(max = 210.dp)
                .shadow(12.dp, RoundedCornerShape(6.dp), ambientColor = PX.Ink.copy(alpha = 0.18f), spotColor = PX.Ink.copy(alpha = 0.18f))
                .clip(RoundedCornerShape(6.dp))
                .clickable { onZoom(image) }
                .semantics { contentDescription = "Open page full screen"; role = Role.Button },
        ) {
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp).background(PX.Ink.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.OpenInFull, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Page ${i + 1} of ${pages.size}", style = mono(13.sp), color = PX.Ink)
            Text(pageNote(page), style = inter(13.sp), color = PX.Text2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StepButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") { onIndexChange(maxOf(0, i - 1)) }
                StepButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") { onIndexChange(minOf(pages.size - 1, i + 1)) }
            }
            LinkLabel(text = "Add page", onClick = onAddPage)
        }
    }
}

/** iOS: "<w> × <h> px · " + the quality warning, or "Sharp[ · creases and curl corrected] · exports as a searchable PDF". */
internal fun pageNote(page: DocumentPage): String {
    val img = page.enhanced ?: page.page ?: page.original
    val note = page.quality?.warning ?: "Sharp${if (page.wasDewarped) " · creases and curl corrected" else ""} · exports as a searchable PDF"
    return "${img.width} × ${img.height} px · $note"
}

@Composable
private fun StepButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, PX.Hairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = PX.Ink, modifier = Modifier.size(18.dp))
    }
}
