package io.packagex.visiondemo.document

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Normalised sampling coordinates on a coarse grid, in the convention UVDoc
 * emits: -1…1 across the source image, with -1 and +1 at the centres of the
 * outermost pixels. [x] is the horizontal coordinate, [y] the vertical; both are
 * row-major with `width * height` entries. For each point of the flat output it
 * says where to read from in the photograph (a backward map).
 *
 * Pure Kotlin, no Android types: the geometry is unit-tested on the JVM.
 * Port of DocumentDewarp.swift from the iOS demo.
 */
class BackwardMap(
    val width: Int,
    val height: Int,
    val x: FloatArray,
    val y: FloatArray,
) {
    init {
        require(width > 1 && height > 1) { "grid must be at least 2x2" }
        require(x.size == width * height && y.size == width * height) { "grid size mismatch" }
    }

    /**
     * How far the map departs from reading straight through, as a fraction of the
     * image. A flat page predicts close to the identity and is best left alone
     * rather than resampled for nothing.
     */
    val deviationFromIdentity: Double
        get() {
            var worst = 0.0
            for (row in 0 until height) {
                val identityY = row.toDouble() / (height - 1) * 2 - 1
                for (col in 0 until width) {
                    val identityX = col.toDouble() / (width - 1) * 2 - 1
                    worst = max(worst, abs(x[row * width + col] - identityX))
                    worst = max(worst, abs(y[row * width + col] - identityY))
                }
            }
            return worst / 2 // -1…1 spans the whole image, so halve it
        }
}

object DocumentResampler {
    /** Below this the page is treated as flat and passed through untouched. */
    const val MINIMUM_DEVIATION = 0.005

    /**
     * Columns between recomputations of the sampling position. The map comes from
     * a 31 × 45 grid, so it varies far too slowly to be worth evaluating per
     * pixel; positions in between are interpolated.
     */
    private const val SAMPLE_STRIDE = 8

    /** Off in JVM unit tests (no native lib); on by default on device. */
    var useNative = true

    /** Which implementation the last resample() used — for the per-page log line. */
    @Volatile var lastPath = "none"

    /**
     * Reads packed ARGB_8888 [src] through [map], bilinear in both the map and
     * the pixels. Output has the same size as the input. NEON (vision-native)
     * when available; [resampleKotlin] is the bit-identical reference/fallback.
     */
    fun resample(
        src: IntArray,
        width: Int,
        height: Int,
        map: BackwardMap,
    ): IntArray {
        if (useNative && width >= 2 && height >= 2 && src.size >= width * height) {
            val out = IntArray(width * height)
            val t = System.nanoTime()
            if (io.packagex.visionsdk.native.DocumentResampleNative
                    .resample(src, width, height, map.x, map.y, map.width, map.height, out)
            ) {
                lastPath = "neon %dms".format((System.nanoTime() - t) / 1_000_000)
                return out
            }
        }
        lastPath = "kotlin"
        return resampleKotlin(src, width, height, map)
    }

    fun resampleKotlin(
        src: IntArray,
        width: Int,
        height: Int,
        map: BackwardMap,
    ): IntArray {
        val out = IntArray(width * height)
        if (width < 2 || height < 2 || src.size < width * height) return out

        val lastCol = (width - 1).toDouble()
        val lastRow = (height - 1).toDouble()
        val sampleCols = IntArray(width / SAMPLE_STRIDE + 2)
        var nSamples = 0
        var c = 0
        while (c < width - 1) {
            sampleCols[nSamples++] = c
            c += SAMPLE_STRIDE
        }
        sampleCols[nSamples++] = width - 1

        val srcX = DoubleArray(width)
        val srcY = DoubleArray(width)
        val gw = map.width
        val gx = map.x
        val gy = map.y

        for (row in 0 until height) {
            // Weights for the two grid rows straddling this output row.
            val fy = row / lastRow * (map.height - 1)
            val gy0 = min(fy.toInt(), map.height - 1)
            val gy1 = min(gy0 + 1, map.height - 1)
            val ty = fy - gy0
            val iy = 1 - ty
            val top = gy0 * gw
            val bottom = gy1 * gw

            for (s in 0 until nSamples) {
                val col = sampleCols[s]
                val fx = col / lastCol * (gw - 1)
                val gx0 = min(fx.toInt(), gw - 1)
                val gx1 = min(gx0 + 1, gw - 1)
                val tx = fx - gx0
                val ix = 1 - tx
                val nx = (gx[top + gx0] * ix + gx[top + gx1] * tx) * iy + (gx[bottom + gx0] * ix + gx[bottom + gx1] * tx) * ty
                val ny = (gy[top + gx0] * ix + gy[top + gx1] * tx) * iy + (gy[bottom + gx0] * ix + gy[bottom + gx1] * tx) * ty
                // -1…1 back to pixels, clamped to the image.
                val px = ((nx + 1) * 0.5 * lastCol).coerceIn(0.0, lastCol)
                val py = ((ny + 1) * 0.5 * lastRow).coerceIn(0.0, lastRow)
                srcX[col] = px
                srcY[col] = py
                if (s == 0) continue
                val prev = sampleCols[s - 1]
                val span = col - prev
                if (span <= 1) continue
                val x0 = srcX[prev]
                val y0 = srcY[prev]
                for (step in 1 until span) {
                    val t = step.toDouble() / span
                    srcX[prev + step] = x0 + (px - x0) * t
                    srcY[prev + step] = y0 + (py - y0) * t
                }
            }

            val outRow = row * width
            for (col in 0 until width) {
                val sx = srcX[col]
                val sy = srcY[col]
                val x0 = sx.toInt()
                val y0 = sy.toInt()
                val x1 = min(x0 + 1, width - 1)
                val y1 = min(y0 + 1, height - 1)
                // 8-bit fixed-point weights.
                val tx = ((sx - x0) * 256 + 0.5).toInt()
                val ty2 = ((sy - y0) * 256 + 0.5).toInt()
                val ix = 256 - tx
                val iy2 = 256 - ty2
                val p00 = src[y0 * width + x0]
                val p01 = src[y0 * width + x1]
                val p10 = src[y1 * width + x0]
                val p11 = src[y1 * width + x1]
                var packed = 0
                var shift = 24
                while (shift >= 0) {
                    val t = ((p00 ushr shift and 0xff) * ix + (p01 ushr shift and 0xff) * tx) shr 8
                    val b = ((p10 ushr shift and 0xff) * ix + (p11 ushr shift and 0xff) * tx) shr 8
                    packed = packed or (((t * iy2 + b * ty2) shr 8) shl shift)
                    shift -= 8
                }
                out[outRow + col] = packed
            }
        }
        return out
    }
}
