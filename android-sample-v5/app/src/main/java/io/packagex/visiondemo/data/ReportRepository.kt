package io.packagex.visiondemo.data

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.OcrResult
import io.packagex.visionsdk.ApiManager
import io.packagex.visionsdk.core.ReportResult
import io.packagex.visionsdk.ocr.ml.core.enums.PlatformType
import io.packagex.visionsdk.service.dto.BOLModelToReport
import io.packagex.visionsdk.service.dto.DCModelToReport
import io.packagex.visionsdk.service.dto.ILModelToReport
import io.packagex.visionsdk.service.dto.ModelToReport
import io.packagex.visionsdk.service.dto.SLModelToReport
import javax.inject.Inject
import javax.inject.Singleton

interface ReportRepository {
    suspend fun report(r: OcrResult, fields: Set<String>, message: String, image: Bitmap?): Result<Unit>
}

@Singleton
class SdkReportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: Secrets,
) : ReportRepository {

    override suspend fun report(r: OcrResult, fields: Set<String>, message: String, image: Bitmap?): Result<Unit> {
        val model = modelToReport(r.docType, fields)
            ?: return Result.failure(IllegalArgumentException("Reporting is not supported for ${r.docType}"))
        val combined = if (fields.isEmpty()) message else "$message · ${fields.joinToString(", ")}"
        val result = ApiManager().reportIssueSuspend(
            context = context,
            apiKey = secrets.apiKey,
            token = null,
            platformType = PlatformType.Native,
            modelToReport = model,
            report = combined,
            customData = null,
            image = image?.scaledTo(1000),
        )
        return when (result) {
            ReportResult.Successful, ReportResult.SavedForLater -> Result.success(Unit)
            is ReportResult.Failed -> Result.failure(result.exception)
        }
    }

    /** Report-field flags per doc type, matching the SDK report models' keys. Ported from iOS `ReportBuilder`. */
    private fun modelToReport(docType: DocType, keys: Set<String>): ModelToReport? = when (docType) {
        DocType.SL -> SLModelToReport(
            modelSize = null,
            trackingNo = "tracking_no" in keys,
            courierName = "courier_name" in keys,
            weight = "weight" in keys,
            dimensions = "dimensions" in keys,
            receiverName = "receiver_name" in keys,
            receiverAddress = "receiver_address" in keys,
            senderName = "sender_name" in keys,
            senderAddress = "sender_address" in keys,
        )
        DocType.BOL -> BOLModelToReport(
            modelSize = null,
            referenceNo = "referenceNo" in keys,
            loadNumber = "loadNumber" in keys,
            purchaseOrderNumber = "purchaseOrderNumber" in keys,
            invoiceNumber = "invoiceNumber" in keys,
            customerPurchaseOrderNumber = "customerPurchaseOrderNumber" in keys,
            orderNumber = "orderNumber" in keys,
            billOfLading = "billOfLading" in keys,
            masterBillOfLading = "masterBillOfLading" in keys,
            lineBillOfLading = "lineBillOfLading" in keys,
            houseBillOfLading = "houseBillOfLading" in keys,
            shippingId = "shippingId" in keys,
            shippingDate = "shippingDate" in keys,
            date = "date" in keys,
        )
        DocType.IL -> ILModelToReport(
            modelSize = null,
            supplierName = "supplier_name" in keys,
            itemName = "item_name" in keys,
            itemSKU = "item_sku" in keys,
            weight = "weight" in keys,
            quantity = "quantity" in keys,
            dimensions = "dimensions" in keys,
            productionDate = "production_date" in keys,
            supplierAddress = "supplier_address" in keys,
        )
        DocType.DC -> DCModelToReport(modelSize = null, documentClass = "document_class" in keys)
        else -> null
    }
}

/** Downscales before the report multipart upload (the SDK's own reportIssueSuspend resizes too; this
 *  just avoids holding a full-resolution copy in memory a moment longer than needed). */
private fun Bitmap.scaledTo(maxDimension: Int): Bitmap {
    val scale = maxDimension.toFloat() / maxOf(width, height)
    if (scale >= 1f) return this
    return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
}
