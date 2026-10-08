package io.packagex.visiondemo.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.packagex.visiondemo.ar.OverlayRules
import io.packagex.visiondemo.ar.PinRules
import io.packagex.visiondemo.designsystem.LinkLabel
import io.packagex.visiondemo.designsystem.PX
import io.packagex.visiondemo.designsystem.SectionLabel
import io.packagex.visiondemo.designsystem.Segmented
import io.packagex.visiondemo.designsystem.SheetScaffold
import io.packagex.visiondemo.designsystem.ToggleRow
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.designsystem.inter
import io.packagex.visiondemo.designsystem.montserrat
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.Processing
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visiondemo.scanner.ScannerAction
import io.packagex.visiondemo.scanner.ScannerUiState
import io.packagex.visiondemo.scanner.onDevice

/**
 * Ported from iOS `UI/Sheets.swift` `SettingsSheet`. Grouping follows iOS: `wildCard`/`parseRecipient`/
 * `parseSender` sit under "Vision Scanner" with auto capture (Sheets.swift:94-99); Processing is its own
 * group (the brief's explicit Settings row list; iOS itself only has this control in `DocTypeSheet`).
 * "Reset to defaults" is [ScannerAction.ResetSettings] (Sheets.swift:161-166). "Advanced" carries
 * "Detection enabled" and the AR trace switch. Rows not carried over: the static symbologies grid, the
 * other AR-debug toggles and the environment row. "On-device
 * models" content moves to its own sheet here (the "Models" row below), including "Check for updates".
 */
@Composable
fun SettingsSheet(state: ScannerUiState, onAction: (ScannerAction) -> Unit) {
    val p = state.prefs

    Group("Scanning") {
        ToggleRow(
            title = "Multiple scan",
            desc = "Capture every code in view at once, not just one.",
            checked = p.multi,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(multi = checked) }) },
        )
        ToggleRow(
            title = "Outline detected codes",
            desc = "Outlines codes in multiple scan and on labels in Vision Scanner.",
            checked = p.showBoxes,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(showBoxes = checked) }) },
        )
        ToggleRow(
            title = "Show detection hints",
            desc = "Shows a line of guidance above the camera.",
            checked = p.showHints,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(showHints = checked) }) },
        )
    }

    Group("Processing") {
        Segmented(
            items = listOf("Cloud" to Processing.Cloud, "On-device" to Processing.Device),
            selection = p.processing,
            onSelect = { onAction(ScannerAction.UpdatePrefs { prefs -> prefs.copy(processing = it) }) },
            disabled = if (p.docType.onDevice) emptySet() else setOf(Processing.Device),
        )
    }

    Group("Scan feedback") {
        ToggleRow(
            title = "Sound",
            desc = "Plays a sound and haptic on a successful scan.",
            checked = p.sound,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(sound = checked) }) },
        )
    }

    Column {
        SectionLabel("On-device model size")
        Segmented(
            items = listOf("micro" to ModelSize.Micro, "large" to ModelSize.Large),
            selection = p.modelSize,
            onSelect = { onAction(ScannerAction.UpdatePrefs { prefs -> prefs.copy(modelSize = it) }) },
            mono = true,
        )
        Text(
            "Used for on-device OCR. Document classification always runs the micro model.",
            style = inter(13.sp),
            color = PX.Muted,
        )
    }

    Group("Vision Scanner") {
        ToggleRow(
            title = "Auto capture",
            desc = "Captures automatically once the frame has been steady, without pressing the shutter.",
            checked = p.autoCapture,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(autoCapture = checked) }) },
        )
        ToggleRow(
            title = "Wild card scan",
            desc = "Classifies on-device, then extracts shipping and item labels on-device and bills of lading in the cloud.",
            checked = p.wildCard,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(wildCard = checked) }) },
        )
        ToggleRow(
            title = "Clean up recipient address",
            desc = "Runs an extra cloud check on shipping-label recipient addresses.",
            checked = p.parseRecipient,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(parseRecipient = checked) }) },
        )
        ToggleRow(
            title = "Clean up sender address",
            desc = "Runs the same check on sender addresses.",
            checked = p.parseSender,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(parseSender = checked) }) },
        )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        LinkLabel("Reset to defaults") { onAction(ScannerAction.ResetSettings) }
    }

    Group("Advanced") {
        ToggleRow(
            title = "Detection enabled",
            desc = "Pause or resume detection on the active scanner.",
            checked = state.detectionEnabled,
            onCheckedChange = { onAction(ScannerAction.SetDetectionEnabled(it)) },
        )
        ToggleRow(
            title = "AR traces",
            desc = "Records AR Item Count's frames, reads, pins and timings to Android/data/<app>/files/ar-traces, for replay.",
            checked = p.arTrace,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(arTrace = checked) }) },
        )
        ToggleRow(
            title = "AR blur pre-skip",
            desc = "Keeps images predicted too blurred from AR Item Count's decoder. Turn off for measurement runs.",
            checked = p.arBlurSkip,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(arBlurSkip = checked) }) },
        )
        ToggleRow(
            title = "AR outlines fixed to the world",
            desc = "Moves each unlisted code's outline with the phone, by ARCore's pose. Off draws it where it was read.",
            checked = p.arOverlayRules == OverlayRules.ANDROID,
            onCheckedChange = { checked ->
                onAction(ScannerAction.UpdatePrefs { it.copy(arOverlayRules = if (checked) OverlayRules.ANDROID else OverlayRules.IOS) })
            },
        )
        ToggleRow(
            title = "AR far-safe outline depth",
            desc = "Carries outlines of codes other than EAN/UPC as if 0.8 m away, not by rotation only.",
            checked = p.arOutlineFarSafe,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(arOutlineFarSafe = checked) }) },
        )
        ToggleRow(
            title = "AR pins: Android rules",
            desc = "Places each pin on the nearest surface its label can be on, keeps one pin per identical unit, retires pins " +
                "whose code is read beside them, and rings a pin while its depth is unsure. Off: the iOS rules.",
            checked = p.arPinRules == PinRules.ANDROID,
            onCheckedChange = { checked ->
                onAction(ScannerAction.UpdatePrefs { it.copy(arPinRules = if (checked) PinRules.ANDROID else PinRules.IOS) })
            },
        )
        ToggleRow(
            title = "AR pins refined by every read",
            desc = "With the Android pin rules, moves each pin onto its label with every read and rings it until its depth is " +
                "verified. Off: pins stay where they were born, ringed only when born on a plane or with no hit, and a counted " +
                "pin can be removed (re-born on its label is then its only way back).",
            checked = p.arPinRefine,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(arPinRefine = checked) }) },
        )
        ToggleRow(
            title = "AR read-rate boost",
            desc = "With refined Android pins, reads every code in every frame while a ringed pin is in view (2 s at most per " +
                "pin) or a listed code has no pin yet. Off keeps the counter's slower schedule.",
            checked = p.arReadBoost,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(arReadBoost = checked) }) },
        )
        ToggleRow(
            title = "AR record session",
            desc = "From the next start of AR Item Count, records each run (ARCore's video and motion with every read) to " +
                "Android/data/<app>/files/ar-recordings, to replay pin rules on it. About 170 MB a minute.",
            checked = p.arRecord,
            onCheckedChange = { checked -> onAction(ScannerAction.UpdatePrefs { it.copy(arRecord = checked) }) },
        )
    }

    ModelsRow(onAction)
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        SectionLabel(title)
        content()
    }
}

@Composable
private fun ModelsRow(onAction: (ScannerAction) -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable { onAction(ScannerAction.OpenSheet(SheetKind.Models)) },
        ) {
            Text("Models", style = montserrat(14.sp), color = PX.Ink)
            Text("On-device downloads", style = inter(12.sp, FontWeight.Medium), color = PX.Muted)
        }
        HorizontalDivider(color = PX.Hairline)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsSheetPreview() {
    VisionTheme {
        SheetScaffold(title = "Settings", onClose = {}) {
            SettingsSheet(state = ScannerUiState(sheet = SheetKind.Settings), onAction = {})
        }
    }
}
