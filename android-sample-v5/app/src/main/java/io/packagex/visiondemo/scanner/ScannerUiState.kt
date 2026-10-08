package io.packagex.visiondemo.scanner

import androidx.compose.runtime.Immutable
import io.packagex.arcount.CountView
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.data.PriceTag
import io.packagex.visiondemo.data.TtState
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

/** A snapshot of the camera screen. [Immutable] for Compose: every list and map in it is a fresh read-only
 *  collection per `copy`, never mutated after it is published on [ScannerViewModel.state]. */
@Immutable
data class ScannerUiState(
    /** v6 entry point: the module cards are shown and no camera runs. A card opens its mode ([ScannerAction.SetMode]);
     *  the camera's back arrow returns here ([ScannerAction.GoHome]). */
    val home: Boolean = true,
    /** The active mode (the last one opened while [home]). Not persisted. */
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
    /** Gated modes whose last entitlement check failed: their home cards show "Locked". */
    val notEntitled: Set<ScanMode> = emptySet(),
    val models: Map<Pair<DocType, ModelSize>, ModelState> = emptyMap(),
    val alert: Alert? = null,
    val torch: Boolean = false,
    val permissionDenied: Boolean = false,
    val missingKey: String? = null,
    /** Frame flash: Success during the 380 ms before a result shows, Error for 1.2 s after a failure. */
    val feedback: Feedback? = null,
    /** Price tag: unique tags read so far; cleared only by ClearTags (iOS). The Price drawer renders these. */
    val tags: List<PriceTag> = emptyList(),
    /** AR Item Count: the codes the AR session read within the last second ([io.packagex.visiondemo.ar.ArCount.codesInView]). */
    val codesInView: List<String> = emptyList(),
    /** AR Item Count: listed reads wait for ARCore to find a surface ([io.packagex.visiondemo.ar.ArCount.findingSurface]). */
    val findingSurface: Boolean = false,
    /** AR Item Count: every code the AR session read since it started or New Scan, the most recently first read first,
     *  at most 30 (the item list's "Seen" section). */
    val seen: List<String> = emptyList(),
    /** Item retrieval list: the codes to find (iOS `items`). */
    val items: List<String> = emptyList(),
    /** The items' names, SKU to name (iOS `catalogLookup`); a code may have none. */
    val itemNames: Map<String, String> = emptyMap(),
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
    /** AR Item Count: what Compose shows of the counter's newest view (prompt, per-item counts), without the
     *  per-frame geometry the GL thread draws ([io.packagex.visiondemo.ar.forUi]). */
    val arCount: CountView = CountView.EMPTY,
    /** AR Item Count: past the entitlement gate with ARCore installed, so the AR session's view is shown and runs. */
    val arOn: Boolean = false,
    /** Text Templates: account, templates and integration mode (iOS `model.tt`). */
    val tt: TtState = TtState(),
    /** Text Templates Stream: the session's latest guidance or failure, for the hint (iOS `guidance`). */
    val ttGuidance: String? = null,
    /** Text Templates Stream: the SDK camera has let go of the sensor, so `PXScannerView` may mount (iOS `altCameraReady`). */
    val ttCameraReady: Boolean = false,
)

/** Single-code Barcode/QR results show over the camera as a code card (v6), not on the result screen. */
val ScannerUiState.codeHud: DetectedCode?
    get() = (result as? ScanResult.Codes)?.codes?.singleOrNull()?.takeIf { mode == ScanMode.Barcode || mode == ScanMode.QR }

/** This state minus [ScannerUiState.boxes], which change on every analysed frame while codes are in view:
 *  what everything but the boxes overlay renders from. Unchanged (same instance) when there are no boxes. */
fun ScannerUiState.withoutBoxes(): ScannerUiState = if (boxes.isEmpty()) this else copy(boxes = emptyList())

/** Tap-to-focus point in view-normalized (0..1) coordinates. */
data class FocusTap(val x: Float, val y: Float, val id: Int)

@Immutable
data class Alert(val title: String, val message: String, val actions: List<AlertAction>)

/** Tapping it sends [action]; the ViewModel dismisses the alert first. */
data class AlertAction(val label: String, val kind: PXButtonKind = PXButtonKind.Primary, val action: ScannerAction)
