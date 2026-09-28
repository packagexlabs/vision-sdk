package io.packagex.visiondemo.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.data.modelRows
import io.packagex.visiondemo.designsystem.Badge
import io.packagex.visiondemo.designsystem.BadgeTone
import io.packagex.visiondemo.designsystem.LinkLabel
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.SheetScaffold
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.montserrat
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visiondemo.scanner.ScannerAction
import io.packagex.visiondemo.scanner.ScannerUiState

/** Ported from iOS `UI/Sheets.swift` `SettingsSheet`'s "On-device models" section, as its own sheet
 *  (the "Models" row in [SettingsSheet] opens it). One row per [modelRows] combo, matching iOS `ModelRow`. */
@Composable
fun ModelsSheet(state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${modelRows.size} models", style = inter(13.sp), color = PX.Muted)
        LinkLabel("Check for updates") { onAction(ScannerAction.CheckUpdates) }
    }

    Column {
        modelRows.forEach { (t, s) ->
            ModelRow(t = t, s = s, state = state.models[t to s] ?: ModelState.NotDownloaded, onAction = onAction)
        }
    }
}

@Composable
private fun ModelRow(t: DocType, s: ModelSize, state: ModelState, onAction: (ScannerAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${t.label} · ${modelSizeLabel(s)}", style = inter(14.sp, FontWeight.Medium), color = PX.Ink)
                val (label, tone) = statusFor(state)
                Badge(text = label, tone = tone)
            }
            when (state) {
                ModelState.NotDownloaded -> ModelActionButton("Download", ActionKind.Primary) { onAction(ScannerAction.DownloadModel(t, s)) }
                ModelState.Failed -> ModelActionButton("Retry", ActionKind.Primary) { onAction(ScannerAction.DownloadModel(t, s)) }
                is ModelState.Downloading -> ModelActionButton("Cancel", ActionKind.Danger) { onAction(ScannerAction.CancelDownload(t, s)) }
                ModelState.Downloaded -> {
                    ModelActionButton("Load") { onAction(ScannerAction.LoadModel(t, s)) }
                    ModelActionButton("Delete", ActionKind.Danger) { onAction(ScannerAction.DeleteModel(t, s)) }
                }
                ModelState.Loaded -> {
                    ModelActionButton("Unload") { onAction(ScannerAction.UnloadModel(t, s)) }
                    ModelActionButton("Delete", ActionKind.Danger) { onAction(ScannerAction.DeleteModel(t, s)) }
                }
            }
        }
        if (state is ModelState.Downloading) {
            LinearProgressIndicator(
                progress = { state.progress },
                color = PX.Purple,
                trackColor = PX.Lilac,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(50)),
            )
        }
        HorizontalDivider(color = PX.Hairline, modifier = Modifier.padding(top = 10.dp))
    }
}

private fun modelSizeLabel(s: ModelSize) = if (s == ModelSize.Micro) "micro" else "large"

private fun statusFor(s: ModelState): Pair<String, BadgeTone> = when (s) {
    is ModelState.Downloading -> "Downloading ${(s.progress * 100).toInt()}%" to BadgeTone.Neutral
    ModelState.Loaded -> "Loaded" to BadgeTone.Success
    ModelState.Downloaded -> "Downloaded" to BadgeTone.Brand
    ModelState.Failed -> "Download failed" to BadgeTone.Danger
    ModelState.NotDownloaded -> "Not downloaded" to BadgeTone.Neutral
}

private enum class ActionKind { Primary, Outline, Danger }

@Composable
private fun ModelActionButton(label: String, kind: ActionKind = ActionKind.Outline, onClick: () -> Unit) {
    val fg = when (kind) {
        ActionKind.Primary -> Color.White
        ActionKind.Danger -> PX.RedText
        ActionKind.Outline -> PX.Purple
    }
    val bg = if (kind == ActionKind.Primary) PX.Purple else Color.Transparent
    val borderColor = if (kind == ActionKind.Danger) Color(0xFFF3A3A3) else PX.Purple
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(if (kind == ActionKind.Primary) Modifier else Modifier.border(1.dp, borderColor, RoundedCornerShape(10.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = montserrat(12.sp), color = fg)
    }
}

@Preview(showBackground = true)
@Composable
private fun ModelsSheetPreview() {
    VisionTheme {
        SheetScaffold(title = "On-device models", onClose = {}) {
            ModelsSheet(state = ScannerUiState(sheet = SheetKind.Models), onAction = {})
        }
    }
}
