package io.packagex.visiondemo.scanner

import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.data.PriceTag
import io.packagex.visiondemo.designsystem.PXButtonKind
import io.packagex.visiondemo.model.DetectedCode
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.Feedback
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.ScanResult
import io.packagex.visiondemo.model.SheetKind

data class ScannerUiState(
    /** The active mode. Not persisted: the app always opens on Barcode, as iOS. */
    val mode: ScanMode = ScanMode.Barcode,
    val prefs: Prefs = Prefs(),
    val phase: Phase = Phase.Idle,
    val result: ScanResult? = null,
    /** Shutter-row thumbnail; reopened by [ScannerAction.ReopenLast] only in the same mode. */
    val lastResult: Pair<ScanMode, ScanResult>? = null,
    val sheet: SheetKind? = null,
    /** Next sheet to present once the current one has finished hiding ([ScannerAction.SheetDismissed]). */
    val pendingSheet: SheetKind? = null,
    val codeInFrame: Boolean = false,
    val boxes: List<DetectedCode> = emptyList(),
    val paused: Boolean = false,
    /** Default-deny: a gated mode stays behind the gate card until the entitlement check passes. */
    val gated: Boolean = false,
    val entitlementChecking: Boolean = false,
    val models: Map<Pair<DocType, ModelSize>, ModelState> = emptyMap(),
    val alert: Alert? = null,
    val torch: Boolean = false,
    val permissionDenied: Boolean = false,
    val missingKey: String? = null,
    /** Frame flash: Success during the 380 ms before a result shows, Error for 1.2 s after a failure. */
    val feedback: Feedback? = null,
    /** Price tag: unique tags read since entering the mode or the last close (hint "N tags found"). */
    val tags: List<PriceTag> = emptyList(),
    /** Item retrieval: codes reported in view within the last second. */
    val codesInView: List<String> = emptyList(),
    /** Item retrieval list: the codes to find (iOS `items`). */
    val items: List<String> = emptyList(),
    /** Result drawer expanded (reset whenever a result is presented or closed). */
    val resultExpanded: Boolean = false,
    /** Zoom preset in use (reset to 1 on mode switch). */
    val zoom: Float = 1f,
)

data class Alert(val title: String, val message: String, val actions: List<AlertAction>)

/** Tapping it sends [action]; the ViewModel dismisses the alert first. */
data class AlertAction(val label: String, val kind: PXButtonKind = PXButtonKind.Primary, val action: ScannerAction)
