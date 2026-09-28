package io.packagex.visiondemo.scanner

import android.graphics.Bitmap
import android.graphics.RectF
import io.packagex.visiondemo.data.ItemLabelFeedback
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.SheetKind

sealed interface ScannerAction {
    data class SetMode(val m: ScanMode) : ScannerAction
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

    // Result drawer
    data object ToggleExpanded : ScannerAction
    /** "Scan next" / "New Scan": closes the drawer (AR also resets its markers, Task 11). */
    data object ScanNext : ScannerAction
    /** Copies [text] (ScannerEffect.Copy) and toasts "Copied <label>". */
    data class Copy(val label: String, val text: String) : ScannerAction
    /** Item-label feedback for the shown OCR result, keyed by `OcrField.id`. */
    data class SendFeedback(val entries: Map<String, ItemLabelFeedback.Entry>, val comment: String = "") : ScannerAction
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
    /** Vision Scanner's Photos button: asks the UI to open the picker ([ScannerEffect.PickPhoto]). */
    data object PickPhoto : ScannerAction
    /** An image picked from Photos: extracted like a capture (Vision Scanner only). */
    data class ImportPhoto(val bitmap: Bitmap) : ScannerAction
}

sealed interface ScannerEffect {
    data class Toast(val text: String) : ScannerEffect
    data object Haptic : ScannerEffect
    /** Put [text] on the clipboard with `ClipDescription.EXTRA_IS_SENSITIVE = true` (scanned values can be personal data). */
    data class Copy(val text: String) : ScannerEffect
    /** Open the system photo picker; send [ScannerAction.ImportPhoto] with the picked image. */
    data object PickPhoto : ScannerEffect
}
