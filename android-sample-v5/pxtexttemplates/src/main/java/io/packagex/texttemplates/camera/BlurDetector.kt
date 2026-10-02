package io.packagex.texttemplates.camera

import io.packagex.texttemplates.extraction.models.BoundingBox

internal class BlurDetector constructor() {

    companion object {
        private const val DOWNSCALE_WIDTH = 320
        private const val DOWNSCALE_HEIGHT = 240

        /** Default padding around an ROI rect: 15 % of the rect's own
         *  dimensions on each side. Includes the text/background edge that the
         *  Laplacian actually measures, without including so much surround
         *  that the metric turns back into a full-frame approximation.
         *  Mirrors iOS `BlurDetector.defaultRoiPaddingFraction`. */
        const val DEFAULT_ROI_PADDING_FRACTION = 0.15f

        /** Fall-open value ("treat as sharp") returned when the ROI is
         *  degenerate, so a transient ROI computation problem can't turn into
         *  a perma-reject. Mirrors iOS. */
        private const val FALL_OPEN_VARIANCE = 9999f
    }

    /**
     * Computes the Laplacian variance of a grayscale image as a blur metric.
     * Higher values = sharper image. Returns true if the image is sharp enough.
     */
    fun isSharp(yPlaneBytes: ByteArray, width: Int, height: Int, threshold: Float): Boolean {
        val variance = computeLaplacianVariance(yPlaneBytes, width, height)
        return variance >= threshold
    }

    fun computeLaplacianVariance(yPlaneBytes: ByteArray, width: Int, height: Int): Float {
        // Downscale for performance
        val scaleX = width.toFloat() / DOWNSCALE_WIDTH
        val scaleY = height.toFloat() / DOWNSCALE_HEIGHT
        val downscaled = ByteArray(DOWNSCALE_WIDTH * DOWNSCALE_HEIGHT)

        for (dy in 0 until DOWNSCALE_HEIGHT) {
            for (dx in 0 until DOWNSCALE_WIDTH) {
                val srcX = (dx * scaleX).toInt().coerceIn(0, width - 1)
                val srcY = (dy * scaleY).toInt().coerceIn(0, height - 1)
                downscaled[dy * DOWNSCALE_WIDTH + dx] = yPlaneBytes[srcY * width + srcX]
            }
        }

        // Apply 3x3 Laplacian kernel: [0, 1, 0; 1, -4, 1; 0, 1, 0]
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (y in 1 until DOWNSCALE_HEIGHT - 1) {
            for (x in 1 until DOWNSCALE_WIDTH - 1) {
                val center = downscaled[y * DOWNSCALE_WIDTH + x].toInt() and 0xFF
                val top = downscaled[(y - 1) * DOWNSCALE_WIDTH + x].toInt() and 0xFF
                val bottom = downscaled[(y + 1) * DOWNSCALE_WIDTH + x].toInt() and 0xFF
                val left = downscaled[y * DOWNSCALE_WIDTH + (x - 1)].toInt() and 0xFF
                val right = downscaled[y * DOWNSCALE_WIDTH + (x + 1)].toInt() and 0xFF

                val laplacian = (top + bottom + left + right - 4 * center).toDouble()
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }

        val mean = sum / count
        return ((sumSq / count) - mean * mean).toFloat()
    }

    /**
     * ROI-bounded Laplacian variance. [roi] is in the Y plane's pixel
     * coordinate system (origin top-left). [paddingFraction] adds that much of
     * the rect's own width/height on each side before clipping to plane
     * bounds. Samples at a stride that caps the ROI at ~[DOWNSCALE_WIDTH]
     * columns — same downsample-then-Laplacian math as the full-frame variant,
     * so the threshold scale is directly comparable between the two.
     *
     * Bounding the variance to where the text sat in the previous accepted
     * frame makes the metric content-aware: a sharp background can no longer
     * mask a blurry label, and a busy background can't fail a sharp one.
     */
    fun computeLaplacianVarianceInRoi(
        yPlaneBytes: ByteArray,
        width: Int,
        height: Int,
        roi: BoundingBox,
        paddingFraction: Float = DEFAULT_ROI_PADDING_FRACTION,
    ): Float {
        // Apply padding, then clip to plane bounds.
        val padX = (roi.width * paddingFraction).toInt()
        val padY = (roi.height * paddingFraction).toInt()
        val left = (roi.left - padX).coerceAtLeast(0)
        val top = (roi.top - padY).coerceAtLeast(0)
        val right = (roi.right + padX).coerceAtMost(width)
        val bottom = (roi.bottom + padY).coerceAtMost(height)
        val roiW = right - left
        val roiH = bottom - top
        if (roiW < 4 || roiH < 4) return FALL_OPEN_VARIANCE

        val step = (roiW / DOWNSCALE_WIDTH).coerceAtLeast(1)
        if ((roiW / step) * (roiH / step) < 100) return FALL_OPEN_VARIANCE

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        var y = top + step
        while (y < bottom - step) {
            var x = left + step
            while (x < right - step) {
                val center = yPlaneBytes[y * width + x].toInt() and 0xFF
                val up = yPlaneBytes[(y - step) * width + x].toInt() and 0xFF
                val down = yPlaneBytes[(y + step) * width + x].toInt() and 0xFF
                val lft = yPlaneBytes[y * width + (x - step)].toInt() and 0xFF
                val rgt = yPlaneBytes[y * width + (x + step)].toInt() and 0xFF

                val laplacian = (up + down + lft + rgt - 4 * center).toDouble()
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
                x += step
            }
            y += step
        }

        if (count == 0) return FALL_OPEN_VARIANCE
        val mean = sum / count
        return ((sumSq / count) - mean * mean).toFloat().coerceAtLeast(0f)
    }
}
