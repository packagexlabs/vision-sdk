package io.packagex.visiondemo.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.texttemplates.sdk.PXTemplateInfo
import io.packagex.visiondemo.data.TtPath
import io.packagex.visiondemo.data.TtState
import io.packagex.visiondemo.designsystem.Badge
import io.packagex.visiondemo.designsystem.BadgeTone
import io.packagex.visiondemo.designsystem.LinkLabel
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.PXButton
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.designsystem.SectionLabel
import io.packagex.visiondemo.designsystem.Segmented
import io.packagex.visiondemo.designsystem.SheetScaffold
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.mono
import io.packagex.visiondemo.scanner.ScannerAction
import io.packagex.visiondemo.scanner.ScannerUiState

/**
 * Text Templates: account, server, template cache and pool, integration mode, caches. Ported from iOS
 * `UI/Sheets.swift` `TTSheet`. Runs on PXTextTemplates (the `:pxtexttemplates` module), not VisionSDK.
 */
@Composable
fun TtSheet(state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    val tt = state.tt
    var confirmClearCache by remember { mutableStateOf(false) }

    Note("Runs on PXTextTemplates, a separate module from VisionSDK. Load the templates, then scan a label.")
    TtAccountField(tt, isSetupCard = false, onAction = onAction)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Server")
        Text(
            tt.serverUrl,
            style = mono(12.sp),
            color = Color(0xFF2D0C57),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, FieldBorder, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
        Note("Override with -PpxBaseUrl (or PX_BASE_URL) when building.")
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Templates cached: ${tt.cached.size}", style = inter(14.sp), color = PX.Ink, modifier = Modifier.weight(1f))
            LinkLabel(if (tt.syncing) "Syncing…" else "Sync templates") { if (!tt.syncing) onAction(ScannerAction.TtSync) }
        }
        HorizontalDivider(color = PX.Hairline)
        tt.syncProgress?.let {
            LinearProgressIndicator(progress = { it }, color = PX.Purple, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        Text("Loaded into pool: ${tt.loadedIds.size}", style = inter(14.sp), color = PX.Ink, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        // Buttons on their own row: beside the label they wrap on a phone-width sheet.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Box(Modifier.weight(1f)) { PXButton(title = "Load templates", height = 44.dp) { onAction(ScannerAction.TtLoad) } }
            Box(Modifier.weight(1f)) { PXButton(title = "Unload", kind = PXButtonKind.Secondary, height = 44.dp) { onAction(ScannerAction.TtUnload) } }
        }
        HorizontalDivider(color = PX.Hairline)
    }

    Column {
        SectionLabel("Templates")
        if (tt.cached.isEmpty()) Note("No templates cached. Tap Sync templates.", Modifier.padding(vertical = 10.dp))
        tt.cached.forEach { t ->
            val loaded = t.id in tt.loadedIds
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(t.name, style = inter(14.sp, FontWeight.Medium), color = PX.Ink, modifier = Modifier.weight(1f))
                Badge(text = if (loaded) "Loaded" else "Cached", tone = if (loaded) BadgeTone.Success else BadgeTone.Brand)
            }
            HorizontalDivider(color = PX.Hairline)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("SDK integration mode")
        Segmented(
            items = listOf("One-Shot" to TtPath.OneShot, "Stream frames" to TtPath.Stream),
            selection = tt.path,
            onSelect = { onAction(ScannerAction.TtSetPath(it)) },
        )
        Note(if (tt.stream) "Predicts continuously from the live camera." else "Predicts from a single captured frame.")
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Scans cached: ${tt.scanCount}", style = inter(14.sp), color = PX.Ink, modifier = Modifier.weight(1f))
        LinkLabel("Clear scan cache", color = PX.RedText) { onAction(ScannerAction.TtClearScans) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Note("Removes all downloaded templates and the loaded pool.", Modifier.weight(1f))
        LinkLabel("Clear template cache", color = PX.RedText) { confirmClearCache = true }
    }

    PXButton(title = "Done") { onAction(ScannerAction.DismissSheet) }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("Clear template cache?") },
            text = { Text("Removes all downloaded templates and the loaded pool. Sync again to predict.") },
            confirmButton = {
                TextButton(onClick = { confirmClearCache = false; onAction(ScannerAction.TtClearTemplateCache) }) { Text("Clear", color = PX.RedText) }
            },
            dismissButton = { TextButton(onClick = { confirmClearCache = false }) { Text("Cancel", color = PX.Purple) } },
            containerColor = Color.White,
        )
    }
}

/** Shown first while no email is set (iOS `TTSetupSheet`): the mode's account gate. */
@Composable
fun TtSetupSheet(state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = PX.Purple, modifier = Modifier.size(30.dp))
        Note("Enter your email to start. It's stored on this device and scopes your templates on the server.", Modifier.weight(1f))
    }
    TtAccountField(state.tt, isSetupCard = true, onAction = onAction)
}

/**
 * Account email for Text Templates: validated, lowercased, stored on this device (iOS `TTAccountField`).
 * Used in the Text Templates sheet and on the setup card that gates the mode.
 */
@Composable
private fun TtAccountField(tt: TtState, isSetupCard: Boolean, onAction: (ScannerAction) -> Unit) {
    var draft by remember(tt.email) { mutableStateOf(tt.email) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = TtState.isValidEmail(draft)
    fun save() {
        if (!valid) { error = "Please enter a valid email address."; return }
        error = null
        onAction(ScannerAction.TtSetEmail(draft, fromSetup = isSetupCard))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!isSetupCard) SectionLabel("Account")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; error = null },
                placeholder = { Text("you@example.com", style = inter(15.sp), color = PX.Muted) },
                textStyle = inter(15.sp),
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PX.Purple, unfocusedBorderColor = FieldBorder),
                modifier = Modifier.weight(1f),
            )
            if (!isSetupCard && tt.hasEmail && draft == tt.email) {
                LinkLabel("Sign out", color = PX.RedText) { onAction(ScannerAction.TtSignOut) }
            }
        }
        error?.let { Text(it, style = inter(12.sp), color = PX.RedText) }
        if (isSetupCard) {
            Box(Modifier.alpha(if (valid) 1f else 0.4f)) { PXButton(title = "Continue") { save() } }
        } else {
            Note(
                if (tt.hasEmail) "Sent on every Text Templates request as X-User-Email. Stored on this device."
                else "Required before syncing or scanning. Stored on this device only.",
            )
            if (draft != tt.email && valid) PXButton(title = "Save email", height = 44.dp) { save() }
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = inter(13.sp), color = PX.Muted, modifier = modifier)
}

private val FieldBorder = Color(0xFFD9D0E3)

@Preview(showBackground = true)
@Composable
private fun TtSheetPreview() {
    VisionTheme {
        SheetScaffold(title = "Text Templates", onClose = {}) {
            TtSheet(
                state = ScannerUiState(
                    tt = TtState(
                        email = "you@example.com",
                        cached = listOf(PXTemplateInfo("a", "Shipping label"), PXTemplateInfo("b", "Pallet tag")),
                        loadedIds = listOf("a"),
                        serverUrl = "https://text-templates.web.app",
                    ),
                ),
                onAction = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TtSetupSheetPreview() {
    VisionTheme { SheetScaffold(title = "Set up Text Templates", onClose = {}) { TtSetupSheet(ScannerUiState(), onAction = {}) } }
}
