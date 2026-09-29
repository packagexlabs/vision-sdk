package io.packagex.visiondemo.data

import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visionsdk.modelmanagement.api.ModelManager
import io.packagex.visionsdk.ocr.ml.core.enums.ExecutionProvider
import io.packagex.visionsdk.ocr.ml.core.enums.ModelSize as SdkModelSize
import io.packagex.visionsdk.ocr.ml.core.enums.OCRModule
import io.packagex.visionsdk.ocr.ml.core.model_options.ShippingLabelOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rows shown in the Models sheet: every (DocType, ModelSize) combo the SDK ships an offline model
 * for (see [OCRModule.getOfflineModels] -- SL Micro/Large, BOL/IL Large only, DC Micro/Large).
 * Ported from iOS `DemoModel.modelRows`, restricted to combos Android actually supports.
 */
val modelRows: List<Pair<DocType, ModelSize>> = listOf(
    DocType.SL to ModelSize.Micro, DocType.SL to ModelSize.Large,
    DocType.BOL to ModelSize.Large,
    DocType.IL to ModelSize.Large,
    DocType.DC to ModelSize.Micro, DocType.DC to ModelSize.Large,
)

/** null for doc types with no offline model (VLM/Tire/IdCard/Plate are cloud-only VLM prompts).
 *  [slOptions] only matters for [DocType.SL] -- see [OnDeviceOCRManager.tryParseAddressesIfEnabled],
 *  which reads it off the `ocrModule` passed to `makePrediction` at prediction time. */
internal fun ocrModuleFor(type: DocType, size: ModelSize, slOptions: ShippingLabelOptions = ShippingLabelOptions()): OCRModule? {
    val sdkSize = when (size) {
        ModelSize.Micro -> SdkModelSize.Micro
        ModelSize.Large -> SdkModelSize.Large
    }
    return when (type) {
        DocType.SL -> OCRModule.ShippingLabel(sdkSize, slOptions)
        DocType.BOL -> OCRModule.BillOfLading(SdkModelSize.Large)
        DocType.IL -> OCRModule.ItemLabel(SdkModelSize.Large)
        DocType.DC -> OCRModule.DocumentClassification(sdkSize)
        else -> null
    }
}

/**
 * Only replaces [current] with [state] when [current] is still [ModelState.Downloading] --
 * guards the download-progress and download-failure transitions against a concurrent cancel()
 * having already moved the row to a terminal state. Pure (no SDK access) so it's directly
 * testable. Ported from iOS `DemoModel.swift:739` (`if case .downloading = self.models[key] { ... }`).
 */
internal fun ifStillDownloading(current: ModelState, state: ModelState): ModelState =
    if (current is ModelState.Downloading) state else current

/**
 * [refresh]'s per-row decision: a row mid-download keeps its progress and skips requerying the
 * SDK entirely -- [isLoaded]/[isDownloaded] are lambdas so a still-downloading row never invokes
 * them. Pure (the SDK calls are behind the caller-supplied lambdas) so it's directly testable.
 * Ported from iOS `DemoModel.swift:721` (`if case .downloading = models[key] { continue }`).
 */
internal suspend fun refreshedState(current: ModelState, isLoaded: suspend () -> Boolean, isDownloaded: suspend () -> Boolean): ModelState {
    if (current is ModelState.Downloading) return current
    return when {
        isLoaded() -> ModelState.Loaded
        isDownloaded() -> ModelState.Downloaded
        else -> ModelState.NotDownloaded
    }
}

/**
 * Folds [refresh]'s probe of one row into the row's state *now*: a download that started or finished
 * while the probe ran wins (the probe only saw [ModelState.Downloading] rows as skipped).
 */
internal fun mergeRefreshed(now: ModelState?, probed: ModelState): ModelState =
    if (now is ModelState.Downloading || probed is ModelState.Downloading) now ?: probed else probed

interface ModelRepository {
    val states: StateFlow<Map<Pair<DocType, ModelSize>, ModelState>>
    /** Version of each model on disk (`ModelInfo.version`); iOS `modelVersion` (DemoModel.swift:727). */
    val versions: StateFlow<Map<Pair<DocType, ModelSize>, String>>
    suspend fun refresh()
    suspend fun download(t: DocType, s: ModelSize, thenLoad: Boolean)
    suspend fun load(t: DocType, s: ModelSize)
    fun cancel(t: DocType, s: ModelSize)
    fun unload(t: DocType, s: ModelSize)
    suspend fun delete(t: DocType, s: ModelSize)
    suspend fun checkUpdates(): String
}

@Singleton
class SdkModelRepository @Inject constructor(
    private val secrets: Secrets,
) : ModelRepository {
    // Computed each call (not cached at construction) since ModelManager.initialize() runs in
    // App.onCreate(), which may not have run yet when Hilt constructs this singleton.
    private val manager get() = ModelManager.getInstance()

    private val _states = MutableStateFlow<Map<Pair<DocType, ModelSize>, ModelState>>(
        modelRows.associateWith { ModelState.NotDownloaded },
    )
    override val states: StateFlow<Map<Pair<DocType, ModelSize>, ModelState>> = _states

    private val _versions = MutableStateFlow<Map<Pair<DocType, ModelSize>, String>>(emptyMap())
    override val versions: StateFlow<Map<Pair<DocType, ModelSize>, String>> = _versions

    override suspend fun refresh() {
        val updated = withContext(Dispatchers.IO) {
            modelRows.associateWith { (t, s) ->
                val module = ocrModuleFor(t, s) ?: return@associateWith ModelState.NotDownloaded
                refreshedState(
                    current = _states.value.getValue(t to s),
                    isLoaded = { manager.isModelLoaded(module) },
                    isDownloaded = { manager.findDownloadedModel(module) != null },
                )
            }
        }
        // Atomic merge: the SDK's progress callback updates rows on its own thread meanwhile.
        _states.update { now -> now + updated.mapValues { (k, probed) -> mergeRefreshed(now[k], probed) } }
        // Rebuilt from scratch, so a row that is no longer on disk loses its version.
        val versions = withContext(Dispatchers.IO) {
            updated.filterValues { it == ModelState.Loaded || it == ModelState.Downloaded }.keys.mapNotNull { key ->
                val module = ocrModuleFor(key.first, key.second) ?: return@mapNotNull null
                manager.findDownloadedModel(module)?.let { key to it.version }
            }.toMap()
        }
        _versions.update { versions }
    }

    override suspend fun download(t: DocType, s: ModelSize, thenLoad: Boolean) {
        val module = ocrModuleFor(t, s) ?: return
        setState(t, s, ModelState.Downloading(0f))
        try {
            manager.downloadModel(module = module, apiKey = secrets.apiKey) { progress ->
                setIfStillDownloading(t, s, ModelState.Downloading(progress.progress))
            }
            setState(t, s, ModelState.Downloaded)
            refreshVersion(t, s, module)
            if (thenLoad) load(t, s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setIfStillDownloading(t, s, ModelState.Failed)
            throw e
        }
    }

    override suspend fun load(t: DocType, s: ModelSize) {
        val module = ocrModuleFor(t, s) ?: return
        try {
            manager.loadModel(module = module, apiKey = secrets.apiKey, executionProvider = ExecutionProvider.CPU)
            setState(t, s, ModelState.Loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setState(t, s, ModelState.Failed)
            throw e
        }
    }

    override fun cancel(t: DocType, s: ModelSize) {
        val module = ocrModuleFor(t, s) ?: return
        manager.cancelDownload(module)
        setState(t, s, ModelState.NotDownloaded)
    }

    override fun unload(t: DocType, s: ModelSize) {
        val module = ocrModuleFor(t, s) ?: return
        manager.unloadModel(module)
        setState(t, s, ModelState.Downloaded)
    }

    override suspend fun delete(t: DocType, s: ModelSize) {
        val module = ocrModuleFor(t, s) ?: return
        manager.deleteModel(module)
        setState(t, s, ModelState.NotDownloaded)
        _versions.update { it - (t to s) }
    }

    override suspend fun checkUpdates(): String {
        val downloaded = modelRows.filter { _states.value[it] is ModelState.Downloaded || _states.value[it] is ModelState.Loaded }
        if (downloaded.isEmpty()) return "No downloaded models to check"
        var failures = 0
        downloaded.forEach { (t, s) ->
            val module = ocrModuleFor(t, s) ?: return@forEach
            try {
                manager.checkModelUpdates(module = module, apiKey = secrets.apiKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
            }
        }
        refresh()
        return if (failures == 0) "All downloaded models are up to date" else "$failures update check${if (failures == 1) "" else "s"} failed"
    }

    private suspend fun refreshVersion(t: DocType, s: ModelSize, module: OCRModule) {
        val v = withContext(Dispatchers.IO) { manager.findDownloadedModel(module)?.version }
        _versions.update { if (v == null) it - (t to s) else it + ((t to s) to v) }
    }

    private fun setState(t: DocType, s: ModelSize, state: ModelState) {
        _states.update { it + ((t to s) to state) }
    }

    /** Atomic: the check and the write can't interleave with a cancel() on the main thread. */
    private fun setIfStillDownloading(t: DocType, s: ModelSize, state: ModelState) {
        _states.update { it + ((t to s) to ifStillDownloading(it.getValue(t to s), state)) }
    }
}
