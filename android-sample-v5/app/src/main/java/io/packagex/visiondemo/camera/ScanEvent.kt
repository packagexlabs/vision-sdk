package io.packagex.visiondemo.camera

import android.graphics.Bitmap
import android.graphics.Rect
import io.packagex.visionsdk.core.pricetag.PriceTagData
import io.packagex.visionsdk.dto.ScannedCodeResult
import io.packagex.visionsdk.exceptions.VisionSDKException

/** Every callback the SDK can deliver through [CameraController.events], as one flow. */
sealed interface ScanEvent {
    data class Codes(val codes: List<ScannedCodeResult>) : ScanEvent
    data class Boxes(val barcodes: List<ScannedCodeResult>, val qr: List<ScannedCodeResult>, val doc: Rect?) : ScanEvent
    data class Indications(val barcode: Boolean, val qr: Boolean, val text: Boolean, val document: Boolean) : ScanEvent
    data class Captured(val bitmap: Bitmap, val codes: List<ScannedCodeResult>, val sharpness: Float) : ScanEvent
    data class PriceTag(val data: PriceTagData) : ScanEvent
    data class Failure(val e: VisionSDKException) : ScanEvent
    data object Started : ScanEvent
}
