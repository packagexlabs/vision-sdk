package io.packagex.visiondemo.data

import android.content.Context
import io.packagex.visionsdk.modelmanagement.api.ModelManager
import io.packagex.visionsdk.modelmanagement.model.BundledModel
import io.packagex.visionsdk.ocr.ml.core.enums.ModelSize as SdkModelSize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One entry of `assets/bundled_models/manifest.json`, written at build time by `app/bundled-models.gradle.kts`. */
@Serializable
internal data class BundledModelEntry(
    val modelClass: String,
    val modelSize: String,
    val version: String,
    val modelId: String,
    val modelVersionId: String,
    val key: String,
    val file: String,
)

/**
 * Local Models build (`BuildConfig.LOCAL_MODELS`): installs every model the APK ships where the SDK keeps
 * downloaded ones, so they load with no download. Versions already installed are skipped, so after the first
 * launch this only reads the manifest (and puts back a model the SDK has since deleted).
 */
internal suspend fun installBundledModels(context: Context, manager: ModelManager) {
    val manifest = context.assets.open("bundled_models/manifest.json").use { it.readBytes().decodeToString() }
    Json.decodeFromString<List<BundledModelEntry>>(manifest).forEach { e ->
        val size = SdkModelSize.entries.first { it.value == e.modelSize }
        manager.installBundledModel(BundledModel(e.modelClass, size, e.version, e.modelId, e.modelVersionId, e.key)) {
            context.assets.open("bundled_models/${e.file}")
        }
    }
}
