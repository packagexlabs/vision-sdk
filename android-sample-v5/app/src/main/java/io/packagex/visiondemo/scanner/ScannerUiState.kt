package io.packagex.visiondemo.scanner

import io.packagex.visiondemo.ar.PayloadCount
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
    /** SDK `ScanEvent.Indications` (Vision Scanner) / Document Acquisition's live boundary: a document
     *  is detected in frame, used by the hint text (iOS `seesDocument`). Independent of [codeInFrame],
     *  which tracks barcode/QR box presence and is the wrong signal for the document hint. */
    val seesDocument: Boolean = false,
    /** SDK `ScanEvent.Indications`: text detected in frame (iOS `seesText`). */
    val seesText: Boolean = false,
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
    /** Price tag: unique tags read so far; cleared only by ClearTags (iOS). The Price drawer renders these. */
    val tags: List<PriceTag> = emptyList(),
    /** Item retrieval: codes reported in view within the last second. */
    val codesInView: List<String> = emptyList(),
    /** Item retrieval list: the codes to find (iOS `items`). */
    val items: List<String> = emptyList(),
    /** Result drawer expanded (reset whenever a result is presented or closed). */
    val resultExpanded: Boolean = false,
    /** Zoom preset in use (reset to 1 on mode switch). */
    val zoom: Float = 1f,
    /** Settings › Advanced "Detection enabled"; not persisted (iOS `detectionEnabled`). */
    val detectionEnabled: Boolean = true,
    /** Version of each downloaded model, for the Models sheet rows. */
    val modelVersions: Map<Pair<DocType, ModelSize>, String> = emptyMap(),
    /** 150 ms white capture flash (iOS `flash`). */
    val flash: Boolean = false,
    /** The front lens is in use (iOS `frontCamera`). */
    val frontCamera: Boolean = false,
    /** Last tap-to-focus point, for the focus ring; a new [FocusTap.id] restarts the ring. */
    val focus: FocusTap? = null,
    /** AR Barcode: marked instances per payload, most first (the chip, hint, shutter and Items sheet). */
    val arCounts: List<PayloadCount> = emptyList(),
    /** AR item catalog, SKU -> name, newest first (iOS `ItemCatalog`). */
    val itemNames: Map<String, String> = emptyMap(),
)

/** Tap-to-focus point in view-normalized (0..1) coordinates. */
data class FocusTap(val x: Float, val y: Float, val id: Int)

data class Alert(val title: String, val message: String, val actions: List<AlertAction>)

/** Tapping it sends [action]; the ViewModel dismisses the alert first. */
data class AlertAction(val label: String, val kind: PXButtonKind = PXButtonKind.Primary, val action: ScannerAction)
