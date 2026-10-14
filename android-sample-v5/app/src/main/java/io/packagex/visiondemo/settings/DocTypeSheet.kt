package io.packagex.visiondemo.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.data.VLM_MODELS
import io.packagex.visiondemo.data.vlmModel
import io.packagex.visiondemo.data.vlmModelName
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.PXButton
import io.packagex.visiondemo.designsystem.SectionLabel
import io.packagex.visiondemo.designsystem.Segmented
import io.packagex.visiondemo.designsystem.SheetScaffold
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.mono
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.Processing
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visiondemo.scanner.ScannerAction
import io.packagex.visiondemo.scanner.ScannerUiState
import io.packagex.visiondemo.scanner.activeModel
import io.packagex.visiondemo.scanner.onDevice

/** VLM type [d]'s backend [model], as a dropdown on its row: each type keeps its own */
@Composable
private fun VlmModelDropdown(d: DocType, model: String, onAction: (ScannerAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(CircleShape)
                .border(1.dp, PX.Purple.copy(alpha = 0.4f), CircleShape)
                .clickable { open = true }
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = "${d.label} model, ${vlmModelName(model)}" },
        ) {
            Text(vlmModelName(model), style = mono(12.sp), color = PX.Purple)
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = PX.Purple, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            VLM_MODELS.forEach { m ->
                DropdownMenuItem(
                    text = { Text(vlmModelName(m)) },
                    trailingIcon = { if (m == model) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                    onClick = {
                        open = false
                        onAction(ScannerAction.UpdatePrefs { it.copy(vlmModels = it.vlmModels + (d.name to m)) })
                    },
                )
            }
        }
    }
}

/** Ported 1:1 from iOS `UI/Sheets.swift` `DocTypeSheet`. */
@Composable
fun DocTypeSheet(state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    val p = state.prefs

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DocType.entries.forEach { d ->
            ChoiceRow(
                title = d.label,
                on = p.docType == d,
                // A VLM type's row picks its backend model (the cloud-only types are the VLM ones); the others say where they run
                trailing = {
                    if (!d.onDevice) VlmModelDropdown(d, p.vlmModel(d), onAction)
                    else Text("Cloud · On-device", style = mono(11.sp), color = PX.Muted)
                },
                onClick = {
                    onAction(ScannerAction.UpdatePrefs { prefs ->
                        prefs.copy(docType = d, processing = if (!d.onDevice) Processing.Cloud else prefs.processing)
                    })
                },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Processing")
        Segmented(
            items = listOf("Cloud" to Processing.Cloud, "On-device" to Processing.Device),
            selection = p.processing,
            onSelect = { onAction(ScannerAction.UpdatePrefs { prefs -> prefs.copy(processing = it) }) },
            disabled = if (p.docType.onDevice) emptySet() else setOf(Processing.Device),
        )
        Text(deviceNote(state), style = inter(13.sp), color = PX.Muted)
    }

    PXButton(title = "Done") { onAction(ScannerAction.DismissSheet) }
}

private fun deviceNote(state: ScannerUiState): String {
    val p = state.prefs
    if (!p.docType.onDevice) return "${p.docType.label} runs in the cloud only."
    if (p.processing == Processing.Cloud) return "Sends the image to the PackageX API. Field locations are not returned."
    val (t, s) = activeModel(p) ?: return ""
    val status = when (state.models[t to s] ?: ModelState.NotDownloaded) {
        is ModelState.Downloading -> "downloading"
        ModelState.Loaded -> "loaded"
        ModelState.Downloaded -> "downloaded, not loaded"
        else -> "not downloaded"
    }
    return "On-device model: ${t.label} · ${if (s == ModelSize.Micro) "micro" else "large"} · $status."
}

/** Selectable 52dp row with a purple 2dp outline when chosen. Ported from iOS `ChoiceRow`. */
@Composable
private fun ChoiceRow(title: String, on: Boolean, trailing: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(if (on) 2.dp else 1.dp, if (on) PX.Purple else PX.Hairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp),
    ) {
        Text(title, style = inter(15.sp, FontWeight.Medium), color = PX.Ink)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Preview(showBackground = true)
@Composable
private fun DocTypeSheetPreview() {
    VisionTheme {
        SheetScaffold(title = "Document type", onClose = {}) {
            DocTypeSheet(state = ScannerUiState(sheet = SheetKind.DocType), onAction = {})
        }
    }
}
