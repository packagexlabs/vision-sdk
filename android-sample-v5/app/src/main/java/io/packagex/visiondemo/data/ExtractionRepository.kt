package io.packagex.visiondemo.data

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.Processing
import io.packagex.visionsdk.ApiManager
import io.packagex.visionsdk.dto.ScannedCodeResult
import io.packagex.visionsdk.ocr.ml.core.OnDeviceOCRManager
import io.packagex.visionsdk.ocr.ml.core.model_options.ShippingLabelOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Extraction output: the JSON and the document type it was read as (the routed type for wild card). */
data class Extraction(val type: DocType, val json: String)

interface ExtractionRepository {
    /**
     * [wildCard]: classify on-device first (document classification · micro, always -- regardless
     * of [processing]/[size]), then dispatch to the reported module: bill of lading -> cloud,
     * item/shipping label -> on-device large (downloading and loading each model first if it
     * isn't already). An unrecognized/missing classification throws [UnsupportedDocumentException].
     */
    suspend fun extract(
        bitmap: Bitmap,
        codes: List<ScannedCodeResult>,
        type: DocType,
        processing: Processing,
        size: ModelSize,
        wildCard: Boolean = false,
    ): Extraction
}

/**
 * Thrown by wild-card extraction when the on-device classifier's result doesn't map to a
 * supported document type. Surfaced via [ScanError.from] as "Unsupported document".
 * Ported from iOS `DemoModel.swift:636-637`.
 */
class UnsupportedDocumentException(documentClass: String?) : Exception(
    if (documentClass == null) "Document Type extraction failed!" else "We do not support extraction of this document type yet!",
)

/**
 * Wild-card dispatch: what to extract next, given the on-device document-classification result.
 * Pure (no SDK access) so it's directly testable. Ported from iOS `DemoModel.swift:594-640`.
 */
internal data class WildCardRoute(val type: DocType, val processing: Processing, val size: ModelSize)

internal fun wildCardRoute(documentClass: String?): WildCardRoute? = when (documentClass) {
    "bill_of_lading" -> WildCardRoute(DocType.BOL, Processing.Cloud, ModelSize.Large)
    "item_label" -> WildCardRoute(DocType.IL, Processing.Device, ModelSize.Large)
    "shipping_label" -> WildCardRoute(DocType.SL, Processing.Device, ModelSize.Large)
    else -> null
}

@Singleton
class SdkExtractionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val secrets: Secrets,
    private val preferences: PreferencesRepository,
    private val models: ModelRepository,
) : ExtractionRepository {

    override suspend fun extract(
        bitmap: Bitmap,
        codes: List<ScannedCodeResult>,
        type: DocType,
        processing: Processing,
        size: ModelSize,
        wildCard: Boolean,
    ): Extraction = withContext(Dispatchers.Default) {
        if (!wildCard) return@withContext Extraction(type, extractOne(bitmap, codes, type, processing, size))

        // Wild card, as the original demo: classify on-device (DC · micro, always), then dispatch
        // to the reported module -- BOL to the cloud, SL/IL on-device large (after ensuring that
        // model is downloaded+loaded, like iOS's prepareModel).
        ensureLoaded(DocType.DC, ModelSize.Micro)
        val classification = extractOne(bitmap, codes, DocType.DC, Processing.Device, ModelSize.Micro)
        val documentClass = OcrParser.documentClass(classification)
        val route = wildCardRoute(documentClass) ?: throw UnsupportedDocumentException(documentClass)
        if (route.processing == Processing.Device) ensureLoaded(route.type, route.size)
        Extraction(route.type, extractOne(bitmap, codes, route.type, route.processing, route.size))
    }

    /** Download+load, like iOS's `prepareModel`, unless the row is already loaded. */
    private suspend fun ensureLoaded(t: DocType, s: ModelSize) {
        if (models.states.value[t to s] is ModelState.Loaded) return
        models.download(t, s, thenLoad = true)
    }

    private suspend fun extractOne(
        bitmap: Bitmap,
        codes: List<ScannedCodeResult>,
        type: DocType,
        processing: Processing,
        size: ModelSize,
    ): String {
        val api = ApiManager()
        return when (type) {
            DocType.VLM -> api.vlmApiCallSync(apiKey = secrets.apiKey, bitmap = bitmap, prompt = null)
            DocType.Tire -> api.vlmApiCallSync(apiKey = secrets.apiKey, bitmap = bitmap, prompt = VlmPrompts.vehicleTire)
            DocType.IdCard -> api.vlmApiCallSync(apiKey = secrets.apiKey, bitmap = bitmap, prompt = VlmPrompts.identityDocument)
            DocType.Plate -> api.vlmApiCallSync(apiKey = secrets.apiKey, bitmap = bitmap, prompt = VlmPrompts.licensePlate)
            DocType.SL, DocType.BOL, DocType.IL, DocType.DC ->
                if (processing == Processing.Device) {
                    // iOS activeModel: document classification is always micro, regardless of the
                    // modelSize pref.
                    val effectiveSize = if (type == DocType.DC) ModelSize.Micro else size
                    val slOptions = if (type == DocType.SL) {
                        val p = preferences.prefs.first()
                        ShippingLabelOptions(p.parseRecipient, p.parseSender)
                    } else {
                        ShippingLabelOptions()
                    }
                    val module = ocrModuleFor(type, effectiveSize, slOptions) ?: error("No on-device model for $type")
                    OnDeviceOCRManager(context, module).makePrediction(module, bitmap, codes, secrets.apiKey, null)
                } else {
                    val barcodes = codes.map { it.scannedCode }
                    when (type) {
                        DocType.SL -> api.shippingLabelApiCallSync(
                            apiKey = secrets.apiKey,
                            bitmap = bitmap,
                            barcodeList = barcodes,
                            locationId = null,
                            options = cloudShippingLabelOptions,
                        )
                        DocType.BOL -> api.billOfLadingApiCallSync(apiKey = secrets.apiKey, bitmap = bitmap, barcodeList = barcodes)
                        DocType.IL -> api.itemLabelApiCallSync(apiKey = secrets.apiKey, bitmap = bitmap)
                        else -> api.documentClassificationApiCallSync(apiKey = secrets.apiKey, bitmap = bitmap)
                    }
                }
        }
    }

    companion object {
        /** iOS `DemoModel.swift:646-648`'s cloud shipping-label options: recipient/location match,
         *  no unique-hash postprocessing, outbound tracker without reusing an existing tracking number. */
        private val cloudShippingLabelOptions: Map<String, Any> = mapOf(
            "match" to mapOf("search" to listOf("recipients"), "location" to true),
            "postprocess" to mapOf("require_unique_hash" to false),
            "transform" to mapOf("tracker" to "outbound", "use_existing_tracking_number" to false),
        )
    }
}
