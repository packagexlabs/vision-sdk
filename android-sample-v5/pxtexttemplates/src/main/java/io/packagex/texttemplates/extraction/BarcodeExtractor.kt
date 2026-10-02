package io.packagex.texttemplates.extraction

import android.util.Log
import io.packagex.texttemplates.extraction.models.BarcodeFrameResult
import io.packagex.texttemplates.extraction.models.BoundingBox
import io.packagex.texttemplates.extraction.models.DetectedBarcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

internal class BarcodeExtractor constructor() {

    companion object {
        private const val TAG = "[BarcodeExtractor]"
    }

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_CODABAR,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_ITF,
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_PDF417,
            Barcode.FORMAT_DATA_MATRIX,
        )
        .build()

    private val scanner = BarcodeScanning.getClient(options)

    suspend fun extract(inputImage: InputImage): BarcodeFrameResult {
        return try {
            val mlBarcodes = scanner.process(inputImage).await()

            // ML Kit coordinate-space quirk (same as OcrExtractor): when the
            // InputImage carries a rotation, `boundingBox` comes back
            // rotation-compensated (upright space) but `cornerPoints` stay in
            // the raw sensor frame. Normalise the corners sensor→upright here
            // so they share one space with `bounds`; otherwise the prediction
            // engine's corner-driven deskew would fight the already-upright
            // bounds.
            val rotation = inputImage.rotationDegrees
            val sensorW = inputImage.width
            val sensorH = inputImage.height

            val barcodes = mlBarcodes.mapNotNull { barcode ->
                val rawData = barcode.rawValue ?: barcode.displayValue ?: return@mapNotNull null
                val rect = barcode.boundingBox
                val bounds = rect?.let {
                    BoundingBox(it.left, it.top, it.width(), it.height())
                }
                // ML Kit returns corners TL→clockwise; keep only the well-formed
                // 4-point case and rotate each point into upright space, then
                // snap the quad's orientation to the authoritative upright
                // `bounds` (rotationDegrees-based normalisation alone left the
                // quad ~90° off on some capture paths — a barcode drawn
                // perpendicular to its bars).
                val corners = barcode.cornerPoints
                    ?.takeIf { it.size == 4 }
                    ?.map { rotatePointToUpright(it.x, it.y, rotation, sensorW, sensorH) }
                    ?.let { if (bounds != null) alignCornersToBounds(it, bounds) else it }
                val (format, data) = canonicalize(barcode.format, rawData)
                DetectedBarcode(
                    data = data,
                    format = format,
                    bounds = bounds,
                    corners = corners,
                )
            }

            Log.d(TAG, "Detected ${barcodes.size} barcodes")
            BarcodeFrameResult(barcodes = barcodes)
        } catch (e: Exception) {
            Log.e(TAG, "Barcode extraction failed", e)
            BarcodeFrameResult(barcodes = emptyList())
        }
    }

    /**
     * Rotate a raw sensor-frame point into upright (rotation-compensated)
     * space. Mirrors `OcrExtractor.rotatePointToUpright` and
     * `Geometry.rotateCornerPoints` — all three must agree on the convention.
     * Kept local so the extraction layer needn't import the prediction package.
     */
    private fun rotatePointToUpright(
        x: Int,
        y: Int,
        rotationDegrees: Int,
        width: Int,
        height: Int,
    ): Pair<Int, Int> = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> Pair(height - y, x)
        180 -> Pair(width - x, height - y)
        270 -> Pair(y, width - x)
        else -> Pair(x, y)
    }

    /**
     * Snap an oriented corner quad to the orientation of the authoritative
     * upright [bounds] box. ML Kit returns `boundingBox` rotation-compensated
     * (upright) but `cornerPoints` in the raw sensor frame; the sensor→upright
     * step in [rotatePointToUpright] depends on `inputImage.rotationDegrees`,
     * which on some capture paths doesn't match what's needed and leaves the
     * quad rotated ~90° (visible as a barcode drawn perpendicular to its bars,
     * and as an inflated/mislocated box to the matcher). `bounds` is always
     * correct, so pick the k·90° rotation of the quad — about its own centroid,
     * recentred on the bounds centre — whose axis-aligned hull best overlaps
     * `bounds`. When the quad is already upright, k=0 wins and the result is
     * unchanged (sub-pixel). Self-correcting: needs no rotationDegrees.
     */
    private fun alignCornersToBounds(
        corners: List<Pair<Int, Int>>,
        bounds: BoundingBox,
    ): List<Pair<Int, Int>> {
        val bcx = bounds.left + bounds.width / 2.0
        val bcy = bounds.top + bounds.height / 2.0
        val ccx = corners.map { it.first }.average()
        val ccy = corners.map { it.second }.average()
        val bAabb = doubleArrayOf(
            bounds.left.toDouble(), bounds.top.toDouble(),
            (bounds.left + bounds.width).toDouble(), (bounds.top + bounds.height).toDouble(),
        )
        var best = corners
        var bestIou = -1.0
        for (k in 0..3) {
            val rot = corners.map { (x, y) ->
                val dx = x - ccx
                val dy = y - ccy
                val rxy = when (k) {
                    0 -> Pair(dx, dy)
                    1 -> Pair(-dy, dx)
                    2 -> Pair(-dx, -dy)
                    else -> Pair(dy, -dx)
                }
                Pair(bcx + rxy.first, bcy + rxy.second)
            }
            val xs = rot.map { it.first }
            val ys = rot.map { it.second }
            val aabb = doubleArrayOf(
                xs.minOrNull() ?: bcx, ys.minOrNull() ?: bcy,
                xs.maxOrNull() ?: bcx, ys.maxOrNull() ?: bcy,
            )
            val iou = aabbIou(aabb, bAabb)
            if (iou > bestIou) {
                bestIou = iou
                best = rot.map { Pair(Math.round(it.first).toInt(), Math.round(it.second).toInt()) }
            }
        }
        return best
    }

    /** IoU of two axis-aligned boxes `[x1,y1,x2,y2]`; 0 when disjoint/degenerate. */
    private fun aabbIou(a: DoubleArray, b: DoubleArray): Double {
        val iw = minOf(a[2], b[2]) - maxOf(a[0], b[0])
        val ih = minOf(a[3], b[3]) - maxOf(a[1], b[1])
        if (iw <= 0.0 || ih <= 0.0) return 0.0
        val inter = iw * ih
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        val union = areaA + areaB - inter
        return if (union > 0.0) inter / union else 0.0
    }

    /**
     * Map an ML Kit (format, data) pair to the canonical symbology + data used
     * by templates and the iOS pipeline.
     *
     * ML Kit reports UPC-A as its own [Barcode.FORMAT_UPC_A] with 12-digit data,
     * but iOS Vision (and therefore every saved template) reports the identical
     * symbol as EAN-13 — Vision doesn't expose a distinct UPC-A symbology, it
     * surfaces UPC-A as a 13-digit EAN-13 with a leading "0". UPC-A *is* EAN-13
     * with a leading zero, so we fold it here. Without this the matcher's
     * strict per-base-type pairing in `matchBarcode` drops the whole EAN13
     * group (template has 1 EAN13, device detects 0), starving fields like
     * Product Number of their barcode geometric signal.
     */
    private fun canonicalize(format: Int, data: String): Pair<String, String> {
        if (format == Barcode.FORMAT_UPC_A) {
            val ean13Data = if (data.length == 12) "0$data" else data
            return "EAN13" to ean13Data
        }
        return formatName(format) to data
    }

    /**
     * Canonical symbology names — must match `_CANONICAL_TYPES` in
     * `api/barcode.py`. Templates are saved server-side with canonical names
     * (e.g. "CODE128"); on-device matcher pairs detected barcodes against the
     * template's `barcode_types` by base-type string equality, so any
     * underscore variant here would silently mismatch and drop barcode
     * signals from the matcher.
     */
    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_CODE_128 -> "CODE128"
        Barcode.FORMAT_CODE_39 -> "CODE39"
        Barcode.FORMAT_CODE_93 -> "CODE93"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_EAN_13 -> "EAN13"
        Barcode.FORMAT_EAN_8 -> "EAN8"
        Barcode.FORMAT_ITF -> "I25"
        Barcode.FORMAT_QR_CODE -> "QRCODE"
        Barcode.FORMAT_UPC_A -> "UPCA"
        Barcode.FORMAT_UPC_E -> "UPCE"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_DATA_MATRIX -> "DATAMATRIX"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        else -> "UNKNOWN"
    }
}
