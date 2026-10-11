package io.packagex.visiondemo.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.designsystem.LinkLabel
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.PXButton
import io.packagex.visiondemo.designsystem.glass
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.mono
import io.packagex.visiondemo.designsystem.montserrat
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.SheetKind

// AR Item Count in two steps (design v6): 1, scan the SKUs into the list one at a time ([ArBuildTray]);
// 2, count them on the shelf ([ArCountHud]).

/** Step 1's tray is up: AR Item Count before Start count, on the live camera. */
internal val ScannerUiState.arBuildTray: Boolean
    get() = mode == ScanMode.Retrieval && !countStep && !gated && !permissionDenied && result == null

/** Step 2's progress card: counting a non-empty list on the live camera. */
internal val ScannerUiState.arCountHud: Boolean
    get() = mode == ScanMode.Retrieval && countStep && items.isNotEmpty() && !gated && !permissionDenied && result == null

/** The tray's height, the design's 344 of 844; the chrome leaves the same space under the shutter. */
@Composable
internal fun arTrayHeight(): Dp = (LocalConfiguration.current.screenHeightDp * 0.41f).dp

/** Step 1: the SKUs scanned so far, newest first, each with an optional product name, and Start count. */
@Composable
internal fun ArBuildTray(state: ScannerUiState, onAction: (ScannerAction) -> Unit, modifier: Modifier = Modifier) {
    var naming by remember { mutableStateOf<String?>(null) }
    val n = state.items.size
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(arTrayHeight())
            .shadow(24.dp, shape)
            .background(Color.White, shape)
            // Touches here are activity (the idle timer), and stay off the camera's tap-to-focus underneath
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onAction(ScannerAction.UserActive)
                }
            }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("STEP 1 OF 2", style = montserrat(11.sp).copy(letterSpacing = 0.4.sp), color = PX.Purple)
                Text(if (n == 0) "Scan SKUs" else "$n ${if (n == 1) "SKU" else "SKUs"} added", style = montserrat(18.sp), color = PX.Ink)
            }
            LinkLabel("Type a SKU") { onAction(ScannerAction.OpenSheet(SheetKind.Items)) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (n == 0) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Scan one barcode at a time", style = inter(14.sp, FontWeight.Medium), color = PX.Ink)
                    Text(
                        "Point at a barcode and press the shutter. Add a product name after each scan.",
                        style = inter(13.sp), color = PX.Muted, textAlign = TextAlign.Center,
                    )
                }
            }
            state.items.asReversed().forEach { code ->
                SkuRow(code, nameOf(code, state.itemNames), onName = { naming = code }, onRemove = { onAction(ScannerAction.RemoveItem(code)) })
            }
        }
        PXButton(title = if (n == 0) "Start count" else "Start count · $n ${if (n == 1) "SKU" else "SKUs"}") { onAction(ScannerAction.StartCount) }
    }
    naming?.let { code ->
        SkuNameDialog(code, nameOf(code, state.itemNames).orEmpty(), onSave = { onAction(ScannerAction.NameItem(code, it)); naming = null }, onDismiss = { naming = null })
    }
}

@Composable
private fun SkuRow(code: String, name: String?, onName: () -> Unit, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
    ) {
        Box(Modifier.size(28.dp).background(if (name != null) PX.Green else PX.Purple, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (name != null) Icons.Filled.Check else Icons.Filled.Edit, contentDescription = null, tint = if (name != null) PX.Ink else Color.White, modifier = Modifier.size(14.dp))
        }
        Column(Modifier.weight(1f).clickable(onClick = onName).semantics { contentDescription = "Name $code" }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name ?: "Add a name (optional)", style = inter(15.sp, FontWeight.Medium), color = if (name != null) PX.Ink else PX.Purple, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(code, style = mono(12.sp), color = PX.Muted)
        }
        Box(Modifier.size(width = 40.dp, height = 44.dp).clickable(onClick = onRemove).semantics { contentDescription = "Remove $code" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = PX.Muted, modifier = Modifier.size(14.dp))
        }
    }
    HorizontalDivider(color = PX.Hairline)
}

/** Names or renames a listed SKU; a blank name clears it. */
@Composable
internal fun SkuNameDialog(code: String, name: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(name) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Product name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(code, style = mono(13.sp), color = PX.Muted)
                OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Product name") }, singleLine = true, modifier = Modifier.focusRequester(focus))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Step 2: how many listed SKUs were found on the shelf so far, which are missing, one segment per SKU; opens the list. */
@Composable
internal fun ArCountHud(state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    val found = foundSkus(state.items, state.seen, state.pinCounts)
    val missing = state.items.filter { it !in found }
    val missingLabel = when (missing.size) {
        0 -> "All found"
        1 -> "Missing: ${nameOf(missing[0], state.itemNames) ?: missing[0]}"
        else -> "${missing.size} missing"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
            .glass(14.dp, fill = PX.Ink.copy(alpha = 0.78f))
            .clickable { onAction(ScannerAction.OpenSheet(SheetKind.Items)) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("STEP 2 OF 2 · COUNT ON SHELF", style = montserrat(11.sp).copy(letterSpacing = 0.3.sp), color = PX.Neon)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${found.size} of ${state.items.size} SKUs found", style = montserrat(14.sp), color = Color.White, modifier = Modifier.weight(1f))
            Text(missingLabel, style = inter(12.sp, FontWeight.Medium), color = PX.Neon, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            state.items.forEach { c ->
                Box(Modifier.weight(1f).height(4.dp).background(if (c in found) PX.Green else Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp)))
            }
        }
    }
}
