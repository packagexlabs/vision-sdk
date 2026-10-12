package io.packagex.visiondemo.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Ported 1:1 from iOS `UI/Sheets.swift` `DocTypeSheet`. */
@Composable
fun DocTypeSheet(state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    val p = state.prefs

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DocType.entries.forEach { d ->
            ChoiceRow(
                title = d.label,
                on = p.docType == d,
                trailing = { Text(if (d.onDevice) "Cloud · On-device" else "Cloud only", style = mono(11.sp), color = PX.Muted) },
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
