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

    override suspend fun report(r: OcrResult, fields: Set<String>, message: String, image: Bitmap?): Result<Unit> {
        lastMessage = message
        lastFields = fields
        return if (shouldFail) Result.failure(IllegalStateException("report failed")) else Result.success(Unit)
    }
}

/** Always allows or always denies, without a real [VisionCameraView]. */
class FakeEntitlement(private val allowed: Boolean = true) : EntitlementRepository {
    override suspend fun check(view: VisionCameraView?, mode: ScanMode): Result<Unit> =
        if (allowed) Result.success(Unit) else Result.failure(IllegalStateException("not entitled"))
}

/** Records what the ViewModel asked of the camera; [emit] feeds SDK events (buffered until collected).
 *  Like the SDK, a rescan or facing switch clears the detection pause; [DetectionGatedCamera] re-applies it. */
class FakeCamera : DetectionGatedCamera() {
    private val channel = Channel<ScanEvent>(Channel.UNLIMITED)
    override val events: Flow<ScanEvent> = channel.receiveAsFlow()
    val pausedFlow = MutableStateFlow(false)
    override val paused: StateFlow<Boolean> = pausedFlow
    override val view: VisionCameraView? = null

    var detectionPaused = false
    var owner = CameraOwner.None
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

    override fun claim(owner: CameraOwner) { this.owner = owner }
    override fun sdkApply(config: ScannerConfig, frame: RectF?, scanning: ScanningMode) { lastConfig = config; lastScanning = scanning }
    override fun sdkPauseDetection() { detectionPaused = true }
    override fun sdkResumeDetection() { detectionPaused = false }
    override fun capture() { captures++ }
    override fun sdkRescan() { rescans++; detectionPaused = false }
    override fun torch(on: Boolean) { torchOn = on }
    override fun zoom(ratio: Float) { zoomRatio = ratio }
    override fun sdkLens(front: Boolean) { this.front = front; detectionPaused = false }
    override fun focus(x: Float, y: Float) { focusPoint = x to y }
    override fun resume(): Boolean {
        if (hot) return false
        pausedFlow.value = false
        return true
    }
    override fun userActive() { userActiveCalls++ }
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
    /** The shutter finds a page (the controller refuses without one). */
    var pageInView = true
    var exportFails = false

    fun still(bitmap: Bitmap? = fakeBitmap()) { channel.trySend(bitmap) }
    fun seePage(seen: Boolean) {
        quad.value = if (seen) DocumentQuad(emptyList(), true, 1f, 100, 100, 0, true, 0f, 0f, 0f, 0f, 0f, false, false) else null
    }

    override fun capture(): Boolean { if (pageInView) captures++; return pageInView }
    var zoomRatio = 1f
    override fun zoom(ratio: Float) { zoomRatio = ratio }
    override suspend fun process(original: Bitmap, index: Int) = DocumentPage(original, index)
    override suspend fun exportPdf(pages: List<DocumentPage>, enhanced: Boolean): File? {
        exports++
        return if (exportFails) null else File("Document.pdf")
    }
}

/** In-memory [ItemCatalogRepository]. */
class FakeCatalog(initial: Map<String, String> = emptyMap(), items: List<String> = emptyList()) : ItemCatalogRepository {
    override val names = MutableStateFlow(initial)
    override suspend fun name(sku: String, name: String) { names.value = names.value + (sku to name) }
    override suspend fun remove(sku: String) { names.value = names.value - sku }
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
