package io.packagex.visiondemo.data

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.Processing
import io.packagex.visionsdk.ApiManager
import io.packagex.visionsdk.dto.ScannedCodeResult
import io.packagex.visionsdk.ocr.ml.core.OnDeviceOCRManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface ExtractionRepository {
    /** [wildCard]: classify on-device first (DC · micro), then extract with the reported module. */
    suspend fun extract(
        bitmap: Bitmap,
        codes: List<ScannedCodeResult>,
        type: DocType,
        processing: Processing,
        size: ModelSize,
        wildCard: Boolean = false,
    ): String
}

@Singleton
class SdkExtractionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: Secrets,
) : ExtractionRepository {

    override suspend fun extract(
        bitmap: Bitmap,
        codes: List<ScannedCodeResult>,
        type: DocType,
        processing: Processing,
        size: ModelSize,
        wildCard: Boolean,
    ): String = withContext(Dispatchers.Default) {
        if (!wildCard) return@withContext extractOne(bitmap, codes, type, processing, size)

        // Wild card, as the original demo: classify first, then dispatch to the reported module.
        val classification = extractOne(bitmap, codes, DocType.DC, processing, ModelSize.Micro)
        val mapped = when (OcrParser.documentClass(classification)) {
            "bill_of_lading" -> DocType.BOL
            "item_label" -> DocType.IL
            "shipping_label" -> DocType.SL
            else -> return@withContext classification
        }
        extractOne(bitmap, codes, mapped, processing, size)
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
                    val module = ocrModuleFor(type, size) ?: error("No on-device model for $type")
                    OnDeviceOCRManager(context, module).makePrediction(module, bitmap, codes, secrets.apiKey, null)
                } else {
                    val barcodes = codes.map { it.scannedCode }
                    when (type) {
                        DocType.SL -> api.shippingLabelApiCallSync(apiKey = secrets.apiKey, bitmap = bitmap, barcodeList = barcodes, locationId = null)
                        DocType.BOL -> api.billOfLadingApiCallSync(apiKey = secrets.apiKey, bitmap = bitmap, barcodeList = barcodes)
                        DocType.IL -> api.itemLabelApiCallSync(apiKey = secrets.apiKey, bitmap = bitmap)
                        else -> api.documentClassificationApiCallSync(apiKey = secrets.apiKey, bitmap = bitmap)
                    }
                }
        }
    }
}
