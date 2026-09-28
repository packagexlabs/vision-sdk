package io.packagex.visiondemo.data

import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visionsdk.modelmanagement.api.ModelManager
import io.packagex.visionsdk.ocr.ml.core.enums.ExecutionProvider
import io.packagex.visionsdk.ocr.ml.core.enums.ModelSize as SdkModelSize
import io.packagex.visionsdk.ocr.ml.core.enums.OCRModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** null for doc types with no offline model (VLM/Tire/IdCard/Plate are cloud-only VLM prompts). */
internal fun ocrModuleFor(type: DocType, size: ModelSize): OCRModule? {
    val sdkSize = when (size) {
        ModelSize.Micro -> SdkModelSize.Micro
        ModelSize.Large -> SdkModelSize.Large
    }
    return when (type) {
        DocType.SL -> OCRModule.ShippingLabel(sdkSize)
        DocType.BOL -> OCRModule.BillOfLading(SdkModelSize.Large)
        DocType.IL -> OCRModule.ItemLabel(SdkModelSize.Large)
        DocType.DC -> OCRModule.DocumentClassification(sdkSize)
        else -> null
    }
}

interface ModelRepository {
    val states: StateFlow<Map<Pair<DocType, ModelSize>, ModelState>>
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

    override suspend fun refresh() {
        _states.value = modelRows.associateWith { (t, s) ->
            val module = ocrModuleFor(t, s) ?: return@associateWith ModelState.NotDownloaded
            when {
                manager.isModelLoaded(module) -> ModelState.Loaded
                manager.findDownloadedModel(module) != null -> ModelState.Downloaded
                else -> ModelState.NotDownloaded
            }
        }
    }

    override suspend fun download(t: DocType, s: ModelSize, thenLoad: Boolean) {
        val module = ocrModuleFor(t, s) ?: return
        setState(t, s, ModelState.Downloading(0f))
        try {
            manager.downloadModel(module = module, apiKey = secrets.apiKey) { progress ->
                setState(t, s, ModelState.Downloading(progress.progress))
            }
            setState(t, s, ModelState.Downloaded)
            if (thenLoad) load(t, s)
        } catch (e: Exception) {
            setState(t, s, ModelState.Failed)
        }
    }

    override suspend fun load(t: DocType, s: ModelSize) {
        val module = ocrModuleFor(t, s) ?: return
        try {
            manager.loadModel(module = module, apiKey = secrets.apiKey, executionProvider = ExecutionProvider.CPU)
            setState(t, s, ModelState.Loaded)
        } catch (e: Exception) {
            setState(t, s, ModelState.Failed)
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
    }

    override suspend fun checkUpdates(): String {
        val downloaded = modelRows.filter { _states.value[it] is ModelState.Downloaded || _states.value[it] is ModelState.Loaded }
        if (downloaded.isEmpty()) return "No downloaded models to check"
        var failures = 0
        downloaded.forEach { (t, s) ->
            val module = ocrModuleFor(t, s) ?: return@forEach
            runCatching { manager.checkModelUpdates(module = module, apiKey = secrets.apiKey) }.onFailure { failures++ }
        }
        refresh()
        return if (failures == 0) "All downloaded models are up to date" else "$failures update check${if (failures == 1) "" else "s"} failed"
    }

    private fun setState(t: DocType, s: ModelSize, state: ModelState) {
        _states.value = _states.value + ((t to s) to state)
    }
}
