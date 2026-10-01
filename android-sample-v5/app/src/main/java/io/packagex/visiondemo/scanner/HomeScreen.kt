package io.packagex.visiondemo.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.RecomposeLog
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.montserrat
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.gated

/**
 * v6 entry point: one card per module, each opening its own camera ([ScannerAction.SetMode]).
 * Ported from the VisionSDK Demo v6 design's Home screen. AR Barcode, which the design has no card for,
 * sits with the other code modes; Dimensioning and Text Templates are not in this sample.
 */
@Composable
fun HomeScreen(state: ScannerUiState, onAction: (ScannerAction) -> Unit, modifier: Modifier = Modifier) {
    RecomposeLog("HomeScreen")
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            // Cards scroll under nothing: the status bar / cutout band stays white above them.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 16.dp, end = 16.dp, top = 17.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("PackageX", style = montserrat(16.sp, FontWeight.Bold), color = PX.Ink)
            Text("VisionSDK", style = montserrat(28.sp), color = PX.Ink, modifier = Modifier.padding(top = 6.dp))
            Text("Pick a module to open its camera.", style = inter(14.sp), color = PX.Text2)
        }
        CardSection("SCAN CODES", HOME_CODES, state, onAction)
        CardSection("CAPTURE DATA", HOME_DATA, state, onAction)
    }
}

/** The design's two sections, in its order. */
internal val HOME_CODES = listOf(ScanMode.Barcode, ScanMode.QR, ScanMode.Price, ScanMode.Retrieval, ScanMode.Ar)
internal val HOME_DATA = listOf(ScanMode.Ocr, ScanMode.DocAcq)

@Composable
private fun CardSection(title: String, modes: List<ScanMode>, state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = montserrat(11.sp).copy(letterSpacing = 0.6.sp), color = PX.Text2, modifier = Modifier.padding(horizontal = 4.dp))
        // A two-column grid; the cards of a row share the taller one's height.
        modes.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m ->
                    ModuleCard(
                        mode = m,
                        locked = m.gated && m in state.notEntitled,
                        onClick = { onAction(ScannerAction.SetMode(m)) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModuleCard(mode: ScanMode, locked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .heightIn(min = 132.dp)
            .shadow(12.dp, shape, ambientColor = PX.Ink.copy(alpha = 0.08f), spotColor = PX.Ink.copy(alpha = 0.08f))
            .background(Color.White, shape)
            .border(1.dp, PX.Hairline, shape)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(cardIcon(mode), contentDescription = null, tint = PX.Purple, modifier = Modifier.size(40.dp))
            Text(mode.label, style = montserrat(15.sp).copy(lineHeight = 19.sp), color = PX.Purple)
            Text(cardDescription(mode), style = inter(12.sp).copy(lineHeight = 16.sp), color = PX.Text2)
        }
        if (locked) {
            Text(
                "Locked",
                style = montserrat(11.sp),
                color = LockedText,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .background(LockedFill, RoundedCornerShape(11.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private val LockedFill = Color(0xFFF1EAFB)
private val LockedText = Color(0xFF5A17B0)

private fun cardIcon(mode: ScanMode): ImageVector = when (mode) {
    ScanMode.Barcode -> Icons.Filled.ViewWeek
    ScanMode.QR -> Icons.Filled.QrCode2
    ScanMode.Price -> Icons.Filled.Sell
    ScanMode.Retrieval -> Icons.Filled.Inventory
    ScanMode.Ar -> Icons.Filled.ViewInAr
    ScanMode.Ocr -> Icons.Filled.DocumentScanner
    ScanMode.DocAcq -> Icons.Filled.Description
}

/** The design's card copy (v6 `DESC`). */
private fun cardDescription(mode: ScanMode): String = when (mode) {
    ScanMode.Barcode -> "1D codes, one at a time or in bulk."
    ScanMode.QR -> "QR and 2D codes."
    ScanMode.Price -> "Read shelf tags and check prices."
    ScanMode.Retrieval -> "Find items from a pick list."
    ScanMode.Ar -> "Pin markers on every code in view."
    ScanMode.Ocr -> "Labels, BOLs, IDs, plates, tires."
    ScanMode.DocAcq -> "Scan pages to a searchable PDF."
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    VisionTheme { HomeScreen(state = ScannerUiState(notEntitled = setOf(ScanMode.Price)), onAction = {}) }
}
