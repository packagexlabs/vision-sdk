package io.packagex.visiondemo.scanner

import android.graphics.RectF
import io.packagex.visiondemo.data.ItemLabelFeedback
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.data.TtPath
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.SheetKind
import java.io.File

sealed interface ScannerAction {
    /** Opens [m]'s camera (a home module card), or switches mode. */
    data class SetMode(val m: ScanMode) : ScannerAction
    /** The camera's back arrow (and system Back): leaves the mode, releases the camera, shows the module cards. */
    data object GoHome : ScannerAction
    data object Shutter : ScannerAction
    data object CloseResult : ScannerAction
    data object ReopenLast : ScannerAction
    data class OpenSheet(val k: SheetKind) : ScannerAction
    /** The sheet was asked to close (swipe, scrim, close button); its hide animation follows. */
    data object DismissSheet : ScannerAction
    /** The sheet's hide animation finished: presents [ScannerUiState.pendingSheet] or resumes detection. */
    data object SheetDismissed : ScannerAction
    /** Tap on the paused camera. */
    data object Resume : ScannerAction
    data object UserActive : ScannerAction
    /** Equality is by lambda reference, which is what alert-action matching needs. */
    data class UpdatePrefs(val t: (Prefs) -> Prefs) : ScannerAction
    data object ToggleTorch : ScannerAction
    data object ToggleAuto : ScannerAction
    data class Report(val fields: Set<String>, val message: String) : ScannerAction
    data object CancelProcessing : ScannerAction
    data object DismissAlert : ScannerAction
    data class PermissionResult(val granted: Boolean) : ScannerAction
    /** The viewfinder's rect in camera-view px (single Barcode/QR decode only inside it). */
    data class FrameChanged(val rect: RectF) : ScannerAction
    /** Re-runs the failed step behind the current alert's "Try again". */
    data object Retry : ScannerAction
    /** "Turn on torch and retry" on a no-code alert. */
    data object TorchRetry : ScannerAction
    /** Gate card: re-run the entitlement check. */
    data object Authenticate : ScannerAction

    // Models
    data class DownloadModel(val t: DocType, val s: ModelSize, val thenLoad: Boolean = false) : ScannerAction
    data class LoadModel(val t: DocType, val s: ModelSize) : ScannerAction
    data class UnloadModel(val t: DocType, val s: ModelSize) : ScannerAction
    data class DeleteModel(val t: DocType, val s: ModelSize) : ScannerAction
    data class CancelDownload(val t: DocType, val s: ModelSize) : ScannerAction
    data object CheckUpdates : ScannerAction

    // Item retrieval list (Items sheet)
    /** Adds one code (typed or picked) to the list. */
    data class AddItem(val sku: String) : ScannerAction
    /** The Items sheet's "Add Item": adds every code currently in view (iOS `addItemsInView`). */
    data object AddItemsInView : ScannerAction
    data class RemoveItem(val sku: String) : ScannerAction
    /** The Items sheet's "Delete" (all). */
    data object ClearItems : ScannerAction

    // Result screen
    /** "Scan next" / "New Scan": closes the drawer (AR Item Count also starts a fresh count, keeping the list). */
    data object ScanNext : ScannerAction
    /** Copies [text] (ScannerEffect.Copy) and toasts "Copied <label>". */
    data class Copy(val label: String, val text: String) : ScannerAction
    /** Item-label feedback for the shown OCR result, keyed by `OcrField.id`. */
    data class SendFeedback(val entries: Map<String, ItemLabelFeedback.Entry>, val comment: String = "") : ScannerAction
    /** Document drawer's Retake (drops the last page) and "Add page" (keeps them); both return to the camera (iOS `rescanDocument`). */
    data class RescanDocument(val dropLast: Boolean) : ScannerAction
    /** Document drawer's "Export PDF", from the enhanced or the original pages. */
    data class ExportPdf(val enhanced: Boolean) : ScannerAction
    /** Price drawer's "Clear tags". */
    data object ClearTags : ScannerAction

    /** Retrieval drawer's "Open item list": closes the result without rescanning, then opens Items. */
    data object OpenItemList : ScannerAction

    // Settings
    /** Settings › Advanced "Detection enabled". */
    data class SetDetectionEnabled(val on: Boolean) : ScannerAction
    /** Settings "Reset to defaults". */
    data object ResetSettings : ScannerAction

    // Camera chrome
    data class Zoom(val ratio: Float) : ScannerAction
    /** Front / back camera. */
    data object FlipCamera : ScannerAction
    /** Tap on the camera, in view-normalized (0..1) coordinates. */
    data class Focus(val x: Float, val y: Float) : ScannerAction

    // AR Item Count
    /** Outcome of [ScannerEffect.InstallArCore]. */
    data class ArInstallResult(val result: ArInstall) : ScannerAction

    // Text Templates
    /** Account email from the setup card or the Text Templates sheet; [fromSetup] closes the setup card. */
    data class TtSetEmail(val email: String, val fromSetup: Boolean) : ScannerAction
    /** "Sign out": clears the email and goes straight to the setup card, as on first use. */
    data object TtSignOut : ScannerAction
    data object TtSync : ScannerAction
    /** "Load templates" (syncs first when nothing is cached), also the no-templates alert's action. */
    data object TtLoad : ScannerAction
    data object TtUnload : ScannerAction
    data object TtClearScans : ScannerAction
    data object TtClearTemplateCache : ScannerAction
    /** One-Shot (the SDK scanner's still) or Stream (`PXScannerView`'s own camera). */
    data class TtSetPath(val path: TtPath) : ScannerAction
    /** Result screen "Re-predict as…": the retained scan against another loaded template. */
    data class TtRepredict(val templateId: String) : ScannerAction
}

enum class ArInstall { Installed, Declined, Unsupported }

sealed interface ScannerEffect {
    data class Toast(val text: String) : ScannerEffect
    data object Haptic : ScannerEffect
    /** Put [text] on the clipboard with `ClipDescription.EXTRA_IS_SENSITIVE = true` (scanned values can be personal data). */
    data class Copy(val text: String) : ScannerEffect
    /** AR isn't installed yet: `ArCoreApk.requestInstall` from the Activity, then send [ScannerAction.ArInstallResult]. */
    data object InstallArCore : ScannerEffect
    /** Share the exported document PDF (through the app's FileProvider). */
    data class SharePdf(val file: File) : ScannerEffect
}
