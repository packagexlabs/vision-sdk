package io.packagex.visiondemo.scanner

import android.graphics.RectF
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
    /** Gate card: re-run the entitlement check. */
    data object Authenticate : ScannerAction

    // Models
    data class DownloadModel(val t: DocType, val s: ModelSize, val thenLoad: Boolean = false) : ScannerAction
    data class LoadModel(val t: DocType, val s: ModelSize) : ScannerAction
    data class UnloadModel(val t: DocType, val s: ModelSize) : ScannerAction
    data class DeleteModel(val t: DocType, val s: ModelSize) : ScannerAction
    data class CancelDownload(val t: DocType, val s: ModelSize) : ScannerAction
    data object CheckUpdates : ScannerAction
}

sealed interface ScannerEffect {
    data class Toast(val text: String) : ScannerEffect
    data object Haptic : ScannerEffect
}
