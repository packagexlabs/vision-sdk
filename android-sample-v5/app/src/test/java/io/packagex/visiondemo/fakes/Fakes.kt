package io.packagex.visiondemo.fakes

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.packagex.docscanner.DocumentQuad
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.camera.DetectionGatedCamera
import io.packagex.visiondemo.camera.ScanEvent
import io.packagex.visiondemo.data.EntitlementRepository
import io.packagex.visiondemo.data.Extraction
import io.packagex.visiondemo.data.ExtractionRepository
import io.packagex.visiondemo.data.ItemCatalogRepository
import io.packagex.visiondemo.data.ModelRepository
import io.packagex.visiondemo.data.PreferencesRepository
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.data.ReportRepository
import io.packagex.visiondemo.data.TextTemplates
import io.packagex.visiondemo.data.TtPath
import io.packagex.visiondemo.data.TtState
import io.packagex.texttemplates.sdk.PXClient
import io.packagex.texttemplates.sdk.PXDetection
import io.packagex.texttemplates.sdk.PXField
import io.packagex.texttemplates.sdk.PXPredictionResult
import io.packagex.texttemplates.sdk.PXQuickResult
import io.packagex.texttemplates.sdk.PXRegionOfInterest
import io.packagex.visiondemo.document.CaptureStart
import io.packagex.visiondemo.document.DocumentCamera
import io.packagex.visiondemo.document.DocumentPage
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.OcrResult
import io.packagex.visiondemo.model.Processing
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.ScannerConfig
import io.packagex.visionsdk.core.ScanningMode
import io.packagex.visionsdk.dto.BarcodeSymbology
import io.packagex.visionsdk.dto.ScannedCodeResult
import io.packagex.visionsdk.ui.views.VisionCameraView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/** In-memory [PreferencesRepository]: no DataStore, no Context -- for ViewModel tests. */
class FakePreferences(initial: Prefs = Prefs()) : PreferencesRepository {
    private val _prefs = MutableStateFlow(initial)
    override val prefs: Flow<Prefs> = _prefs
    override suspend fun update(transform: (Prefs) -> Prefs) {
        _prefs.value = transform(_prefs.value)
    }
}

/** In-memory [ModelRepository]: state transitions without touching ModelManager/the SDK. */
class FakeModels(
    initial: Map<Pair<DocType, ModelSize>, ModelState> = emptyMap(),
    versions: Map<Pair<DocType, ModelSize>, String> = emptyMap(),
) : ModelRepository {
    private val _states = MutableStateFlow(initial)
    override val states: StateFlow<Map<Pair<DocType, ModelSize>, ModelState>> = _states
    override val versions = MutableStateFlow(versions)
    var updatesMessage: String = "All downloaded models are up to date"

    override suspend fun refresh() {}

    override suspend fun download(t: DocType, s: ModelSize, thenLoad: Boolean) {
        _states.value = _states.value + ((t to s) to (if (thenLoad) ModelState.Loaded else ModelState.Downloaded))
    }

    override suspend fun load(t: DocType, s: ModelSize) {
        _states.value = _states.value + ((t to s) to ModelState.Loaded)
    }

    override fun cancel(t: DocType, s: ModelSize) {
        _states.value = _states.value + ((t to s) to ModelState.NotDownloaded)
    }

    override fun unload(t: DocType, s: ModelSize) {
        _states.value = _states.value + ((t to s) to ModelState.Downloaded)
    }

    override suspend fun delete(t: DocType, s: ModelSize) {
        _states.value = _states.value + ((t to s) to ModelState.NotDownloaded)
    }

    override suspend fun checkUpdates(): String = updatesMessage
}

/** Returns [result] (optionally after [delayMs], for phase/loading tests) instead of calling the SDK.
 *  [routedType]: the type wild card "routed" to (defaults to the requested type). [error]: thrown instead. */
class FakeExtraction(
    private val result: String,
    private val delayMs: Long = 0,
    private val routedType: DocType? = null,
    private val error: Exception? = null,
) : ExtractionRepository {
    var lastRequestedType: DocType? = null
    var lastProcessing: Processing? = null
    var calls = 0

    override suspend fun extract(
        bitmap: Bitmap,
        codes: List<ScannedCodeResult>,
        type: DocType,
        processing: Processing,
        size: ModelSize,
        wildCard: Boolean,
    ): Extraction {
        lastRequestedType = type
        lastProcessing = processing
        calls++
        if (delayMs > 0) delay(delayMs)
        error?.let { throw it }
        return Extraction(if (wildCard) routedType ?: type else type, result)
    }
}

/** Records the last report call; always succeeds unless [shouldFail] is set. */
class FakeReport(var shouldFail: Boolean = false) : ReportRepository {
    var lastMessage: String? = null
    var lastFields: Set<String>? = null
    var lastModelSize: ModelSize? = null

    override suspend fun report(r: OcrResult, fields: Set<String>, message: String, image: Bitmap?, modelSize: ModelSize): Result<Unit> {
        lastMessage = message
        lastFields = fields
        lastModelSize = modelSize
        return if (shouldFail) Result.failure(IllegalStateException("report failed")) else Result.success(Unit)
    }
}

/** Always allows or always denies, without a real [VisionCameraView], after [delayMs].
 *  [onDone] models the SDK's `enable*Mode` switching the view into the checked mode when it finishes;
 *  like the SDK's blocking license work, the wait and [onDone] run even if the caller was cancelled. */
class FakeEntitlement(
    private val allowed: Boolean = true,
    private val delayMs: Long = 0,
    var onDone: (ScanMode) -> Unit = {},
) : EntitlementRepository {
    override suspend fun check(view: VisionCameraView?, mode: ScanMode): Result<Unit> {
        withContext(NonCancellable) { if (delayMs > 0) delay(delayMs); onDone(mode) }
        return if (allowed) Result.success(Unit) else Result.failure(IllegalStateException("not entitled"))
    }
}

/** Records what the ViewModel asked of the camera; [emit] feeds SDK events (buffered until collected).
 *  Like the SDK, a rescan or facing switch clears the detection pause; [DetectionGatedCamera] re-applies it.
 *  Like the SDK too, a rescan starts a stopped camera ([running]); [pause] stops it as [CameraController] does. */
class FakeCamera : DetectionGatedCamera() {
    private val channel = Channel<ScanEvent>(Channel.UNLIMITED)
    override val events: Flow<ScanEvent> = channel.receiveAsFlow()
    val pausedFlow = MutableStateFlow(false)
    override val paused: StateFlow<Boolean> = pausedFlow
    override val view: VisionCameraView? = null

    var detectionPaused = false
    /** Starts as the scanner's, as after the permission grant, so tests needn't claim first. */
    var owner = CameraOwner.Scanner
    /** Whether the sensor is streaming. */
    var running = true
    var lastConfig: ScannerConfig? = null
    var lastScanning: ScanningMode? = null
    var torchOn = false
    var captures = 0
    var rescans = 0
    var userActiveCalls = 0
    var front = false
    var focusPoint: Pair<Float, Float>? = null
    var zoomRatio = 1f
    /** Critically hot: [resume] refuses, like [io.packagex.visiondemo.camera.PausePolicy.resume]. */
    var hot = false

    fun emit(event: ScanEvent) { channel.trySend(event) }

    /** [io.packagex.visiondemo.camera.PausePolicy] pauses (idle, heat, background): the camera stops. */
    fun pause() { pausedFlow.value = true; running = false }

    override val mayRun get() = owner == CameraOwner.Scanner && !pausedFlow.value
    override fun sdkStart() { running = true }
    override fun claim(owner: CameraOwner) {
        this.owner = owner
        if (owner != CameraOwner.Scanner) running = false else if (!pausedFlow.value) start()
    }
    override fun sdkApply(config: ScannerConfig, frame: RectF?, scanning: ScanningMode) { lastConfig = config; lastScanning = scanning }
    override fun sdkPauseDetection() { detectionPaused = true }
    override fun sdkResumeDetection() { detectionPaused = false }
    override fun capture() { captures++ }
    override fun sdkRescan() { rescans++; running = true; detectionPaused = false }
    override fun torch(on: Boolean) { torchOn = on }
    override fun zoom(ratio: Float) { zoomRatio = ratio }
    override fun sdkLens(front: Boolean) { this.front = front; detectionPaused = false }
    override fun focus(x: Float, y: Float) { focusPoint = x to y }
    override fun resume(): Boolean {
        if (hot) return false
        pausedFlow.value = false
        if (owner == CameraOwner.Scanner) start()
        return true
    }
    override fun userActive() { userActiveCalls++ }
    /** Every [setBusy] value, in order. */
    val busy = mutableListOf<Boolean>()
    override fun setBusy(busy: Boolean) { this.busy += busy }
}

/** Records what the ViewModel asked of Document Acquisition; [still] feeds a capture (null = failed), [seePage] the live quad. */
class FakeDocument : DocumentCamera {
    private val channel = Channel<Bitmap?>(Channel.UNLIMITED)
    override val quad = MutableStateFlow<DocumentQuad?>(null)
    override val stills: Flow<Bitmap?> = channel.receiveAsFlow()
    override var auto = false
    override var detecting = false
    var captures = 0
    var exports = 0
    /** What the next shutter press starts. */
    var captureStart = CaptureStart.Started
    var torchOn = false
    var front = false
    var focusPoint: Pair<Float, Float>? = null
    var exportFails = false

    fun still(bitmap: Bitmap? = fakeBitmap()) { channel.trySend(bitmap) }
    fun seePage(seen: Boolean) {
        quad.value = if (seen) DocumentQuad(emptyList(), true, 1f, 100, 100, 0, true, 0f, 0f, 0f, 0f, 0f, false, false) else null
    }

    override fun capture(): CaptureStart { if (captureStart == CaptureStart.Started) captures++; return captureStart }
    override fun torch(on: Boolean) { torchOn = on }
    override fun lens(front: Boolean) { this.front = front }
    override fun focus(x: Float, y: Float) { focusPoint = x to y }
    /** The camera owner seen by each release, in order ([ownerProbe] reads it). */
    val releasedUnder = mutableListOf<CameraOwner?>()
    var ownerProbe: () -> CameraOwner? = { null }
    override fun release() { releasedUnder += ownerProbe() }
    var zoomRatio = 1f
    override fun zoom(ratio: Float) { zoomRatio = ratio }
    override suspend fun process(original: Bitmap, index: Int) = DocumentPage(original, index)
    var exportedPages: List<DocumentPage> = emptyList()
    var exportDelayMs = 0L
    override suspend fun exportPdf(pages: List<DocumentPage>, enhanced: Boolean): File? {
        exports++
        exportedPages = pages
        if (exportDelayMs > 0) delay(exportDelayMs)
        return if (exportFails) null else File("Document.pdf")
    }
}

/** In-memory [ItemCatalogRepository]. */
class FakeCatalog(items: List<String> = emptyList()) : ItemCatalogRepository {
    override val items = MutableStateFlow(items)
    override suspend fun setItems(items: List<String>) { this.items.value = items }
}

/** Swaps Dispatchers.Main for a [StandardTestDispatcher] so viewModelScope runs on runTest's virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Needs Robolectric (real android.graphics). */
fun fakeBitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

/** A code128 [ScannedCodeResult] at [box]; needs Robolectric. */
fun code(value: String, box: Rect = Rect(0, 0, 10, 10)) =
    ScannedCodeResult(value, box, BarcodeSymbology.code128, null, RectF(), 0f, null)

/** Text Templates without the SDK: [state] is set directly; [predict] returns [prediction] after [delayMs]. */
class FakeTextTemplates(initial: TtState = TtState()) : TextTemplates {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<TtState> = flow
    override val client: PXClient? = null
    var delayMs = 0L
    var predictions = 0
    var prediction = ttPrediction()
    override fun setEmail(raw: String): Boolean {
        if (!TtState.isValidEmail(raw)) return false
        flow.value = flow.value.copy(email = raw.trim().lowercase()); return true
    }
    override fun signOut() { flow.value = flow.value.copy(email = "") }
    override fun setPath(p: TtPath) { flow.value = flow.value.copy(path = p) }
    override suspend fun refresh() {}
    override suspend fun sync() = "synced"
    override suspend fun load(): String { flow.value = flow.value.copy(loadedIds = flow.value.cached.map { it.id }); return "Loaded" }
    override suspend fun unload(): String { flow.value = flow.value.copy(loadedIds = emptyList()); return "Unloaded" }
    override suspend fun clearScans() = "Scan cache cleared"
    override suspend fun clearTemplateCache() = "Template cache cleared"
    override suspend fun predict(bitmap: Bitmap): PXPredictionResult { predictions++; delay(delayMs); return prediction }
    override suspend fun repredict(scanId: String, templateId: String) =
        PXQuickResult(templateId, "Other", mapOf("sku" to PXField("B2", 0.8f, null, null)), imageWidth = 100, imageHeight = 100)
    override suspend fun report(scanId: String, image: Bitmap, reason: String) {}
}

fun ttPrediction(name: String? = "Shipping label") = PXPredictionResult(
    templateId = "t1",
    templateName = name,
    predictions = mapOf("sku" to PXField("A1", 0.95f, null, null)),
    detection = PXDetection(chosenId = "t1", ambiguous = false, candidates = emptyList()),
    resolvedRegionOfInterest = PXRegionOfInterest(listOf(listOf(0f, 0f), listOf(1f, 1f)), PXRegionOfInterest.Source.DEFAULT, 100, 100),
    imageWidth = 100,
    imageHeight = 100,
    scanId = "s1",
)
