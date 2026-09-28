package io.packagex.visiondemo.document

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Per-page capture checks. Two cheap signals, both measured on the geometry-
 * corrected page *before* enhancement — the enhancer deliberately drives paper
 * to near-white, so glare measured after it is meaningless. Port of iOS
 * DocumentQuality; thresholds are shared, so keep the working width the same.
 */
class DocumentQuality(
    /** Fraction of pixels effectively clipped to pure white — highlights no post-processing recovers. */
    val glareFraction: Double,
    /** Variance of the 4-neighbour Laplacian at the working width. Higher is sharper. */
    val sharpness: Double,
) {
    companion object {
        /** Above this share of blown pixels a page is worth retaking at an angle. */
        const val GLARE_LIMIT = 0.02

        /** Below this small print will be unreadable. Calibrated on the synthetic harness. */
        const val SHARPNESS_FLOOR = 40.0
    }

    val isGlared: Boolean get() = glareFraction > GLARE_LIMIT
    val isSoft: Boolean get() = sharpness < SHARPNESS_FLOOR

    val warning: String?
        get() =
            when {
                isGlared && isSoft -> "Glare and soft focus. Retake at a slight angle, closer and steadier."
                isGlared -> "Glare on this page. Text under the bright spot cannot be recovered — retake at a slight angle."
                isSoft -> "This page looks soft. Retake a little closer and hold steadier."
                else -> null
            }
}

object DocumentQualityChecker {
    /** Working width both analysers measure at, so a 12 MP capture and a cropped receipt compare. */
    const val WORKING_WIDTH = 1024

    /** [gray] is 8-bit luminance, `width * height` entries. */
    fun analyze(
        gray: IntArray,
        width: Int,
        height: Int,
    ): DocumentQuality {
        if (width <= 2 || height <= 2 || gray.size < width * height) return DocumentQuality(0.0, Double.MAX_VALUE)
        var blown = 0
        for (i in 0 until width * height) if (gray[i] >= 252) blown++
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val lap = 4.0 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width]
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        val mean = sum / n
        return DocumentQuality(blown.toDouble() / (width * height), max(0.0, sumSq / n - mean * mean))
    }

    fun analyze(page: Bitmap): DocumentQuality {
        val w = minOf(WORKING_WIDTH, page.width)
        val h = max(1, (page.height.toDouble() * w / page.width).roundToInt())
        val small = Bitmap.createScaledBitmap(page, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== page) small.recycle()
        for (i in px.indices) {
            val p = px[i]
            px[i] = ((p ushr 16 and 0xff) * 299 + (p ushr 8 and 0xff) * 587 + (p and 0xff) * 114) / 1000
        }
        return analyze(px, w, h)
    }
}
