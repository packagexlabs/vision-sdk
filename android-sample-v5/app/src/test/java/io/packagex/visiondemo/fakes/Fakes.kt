package io.packagex.visiondemo.fakes

import android.graphics.Bitmap
import io.packagex.visiondemo.data.EntitlementRepository
import io.packagex.visiondemo.data.ExtractionRepository
import io.packagex.visiondemo.data.ModelRepository
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.data.PreferencesRepository
import io.packagex.visiondemo.data.ReportRepository
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.OcrResult
import io.packagex.visiondemo.model.Processing
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visionsdk.dto.ScannedCodeResult
import io.packagex.visionsdk.ui.views.VisionCameraView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [PreferencesRepository]: no DataStore, no Context -- for ViewModel tests. */
class FakePreferences(initial: Prefs = Prefs()) : PreferencesRepository {
    private val _prefs = MutableStateFlow(initial)
    override val prefs: Flow<Prefs> = _prefs
    override suspend fun update(transform: (Prefs) -> Prefs) {
        _prefs.value = transform(_prefs.value)
    }
}

/** In-memory [ModelRepository]: state transitions without touching ModelManager/the SDK. */
class FakeModels(initial: Map<Pair<DocType, ModelSize>, ModelState> = emptyMap()) : ModelRepository {
    private val _states = MutableStateFlow(initial)
    override val states: StateFlow<Map<Pair<DocType, ModelSize>, ModelState>> = _states
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

/** Returns [result] (optionally after [delayMs], for phase/loading tests) instead of calling the SDK. */
class FakeExtraction(private val result: String, private val delayMs: Long = 0) : ExtractionRepository {
    var lastRequestedType: DocType? = null

    override suspend fun extract(
        bitmap: Bitmap,
        codes: List<ScannedCodeResult>,
        type: DocType,
        processing: Processing,
        size: ModelSize,
        wildCard: Boolean,
    ): String {
        lastRequestedType = type
        if (delayMs > 0) delay(delayMs)
        return result
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
    override suspend fun check(view: VisionCameraView, mode: ScanMode): Result<Unit> =
        if (allowed) Result.success(Unit) else Result.failure(IllegalStateException("not entitled"))
}
