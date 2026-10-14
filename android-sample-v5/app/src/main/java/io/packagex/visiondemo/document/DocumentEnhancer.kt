package io.packagex.visiondemo.document

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device document clean-up: flat-fields uneven lighting and specular glare,
 * then lifts text contrast. No model, pure Kotlin on packed ARGB. Port of the
 * iOS DocumentEnhancer (Core Image gaussian-blur ÷ divide-blend ÷ colorControls).
 *
 * Divides the page by its own heavily blurred illumination estimate: paper
 * flattens to uniform white, lighting gradients and glare falloff cancel out,
 * and ink keeps its ratio to the paper around it. This recovers *gradients*, not
 * clipped data — a highlight already saturated to pure white has no text left.
 */
object DocumentEnhancer {
    /** Illumination field is estimated at about this width; it has no fine detail. */
    private const val FIELD_WIDTH = 160

    /** Blur radius as a fraction of the long edge: wide enough to lose the text. */
    private const val BLUR_FRACTION = 1.0 / 24.0

    // Natural look (CamScanner-like), same values as vision-native document_enhance.cpp:
    // flat-field lighting, paper to white, ink kept with a mild linear stretch. The
    // earlier tuning (black cap 0.85, gamma 1.5, ink erosion, sharpen 0.6) stretched
    // light pages up to 10x and filled in small text.
    private const val SATURATION = 1.0f
    // No black-point stretch (it left grey and thin text near white): v / white point, then t^2.5.
    private const val WHITE_POINT = 0.96f

    /** Unsharp mask strength: out = v + k·(v − box3×3(v)). */
    private const val SHARPEN_K = 0.3f

    /** NEON path (vision-native) when available; the Kotlin loop below is the reference and fallback. */
    /** [consume]: the caller hands [src] over, so the native path enhances it in place instead of copying it. */
    fun enhance(
        src: IntArray,
        width: Int,
        height: Int,
        consume: Boolean = false,
    ): IntArray {
        if (width < 2 || height < 2 || src.size < width * height) return src
        if (useNative) {
            val copy = if (consume) src else src.copyOf()
            val t = System.nanoTime()
            if (io.packagex.visionsdk.native.DocumentEnhanceNative
                    .enhanceInPlace(copy, width, height)
            ) {
                lastPath = "neon %dms".format((System.nanoTime() - t) / 1_000_000)
                return copy
            }
        }
        lastPath = "kotlin"
        return enhanceKotlin(src, width, height)
    }

    /** Off in JVM unit tests (no native lib); on by default on device. */
    var useNative = true

    /** Which implementation the last enhance() used — for the per-page log line. */
    @Volatile var lastPath = "none"

    fun enhanceKotlin(
        source: IntArray,
        width: Int,
        height: Int,
    ): IntArray {
        if (width < 2 || height < 2 || source.size < width * height) return source
        // No ink erosion (it closed the counters of small type); see the constants.
        val src = source
        // 1. Illumination: box-downsample, three box blurs (≈ gaussian), per channel.
        val f = max(1, width / FIELD_WIDTH)
        val fw = width / f
        val fh = height / f
        val field = Array(3) { FloatArray(fw * fh) }
        val inv = 1f / (f * f)
        for (fy in 0 until fh) {
            for (fx in 0 until fw) {
                var r = 0
                var g = 0
                var b = 0
                for (y in fy * f until fy * f + f) {
                    var i = y * width + fx * f
                    for (x in 0 until f) {
                        val p = src[i++]
                        r += p ushr 16 and 0xff
                        g += p ushr 8 and 0xff
                        b += p and 0xff
                    }
                }
                val o = fy * fw + fx
                field[0][o] = r * inv
                field[1][o] = g * inv
                field[2][o] = b * inv
            }
        }
        val radius = max(1, (max(width, height) * BLUR_FRACTION / f / 1.7).roundToInt()) // 3 boxes of r ≈ gaussian σ≈1.7r
        val tmp = FloatArray(fw * fh)
        for (c in 0 until 3) repeat(3) { boxBlur(field[c], tmp, fw, fh, radius) }

        // 2. Field lookup per pixel (bilinear), paper-normalised value per channel.
        val sx = (fw - 1).toFloat() / (width - 1)
        val sy = (fh - 1).toFloat() / (height - 1)
        val norm = FloatArray(width * height * 3)
        for (y in 0 until height) {
            val gy = y * sy
            val y0 = min(gy.toInt(), fh - 1)
            val y1 = min(y0 + 1, fh - 1)
            val ty = gy - y0
            for (x in 0 until width) {
                val gx = x * sx
                val x0 = min(gx.toInt(), fw - 1)
                val x1 = min(x0 + 1, fw - 1)
                val tx = gx - x0
                val p = src[y * width + x]
                for (c in 0 until 3) {
                    val fld = field[c]
                    val ill =
                        (fld[y0 * fw + x0] * (1 - tx) + fld[y0 * fw + x1] * tx) * (1 - ty) +
                            (fld[y1 * fw + x0] * (1 - tx) + fld[y1 * fw + x1] * tx) * ty
                    norm[(y * width + x) * 3 + c] = (p ushr (16 - 8 * c) and 0xff) / max(ill, 1f)
                }
            }
        }

        // 3. Saturation + tone (no black-point stretch, see WHITE_POINT).
        val black = 0f
        val invRange = 1f / WHITE_POINT

        val out = IntArray(width * height)
        for (i in 0 until width * height) {
            var rf = norm[i * 3]
            var gf = norm[i * 3 + 1]
            var bf = norm[i * 3 + 2]
            val lum = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf
            rf = lum + (rf - lum) * SATURATION
            gf = lum + (gf - lum) * SATURATION
            bf = lum + (bf - lum) * SATURATION
            out[i] = (src[i] and 0xff000000.toInt()) or (tone(rf, black, invRange) shl 16) or (tone(gf, black, invRange) shl 8) or tone(bf, black, invRange)
        }

        // 4. Crisper glyph edges.
        unsharp(out, width, height)
        return out
    }

    private fun tone(
        v: Float,
        black: Float,
        invRange: Float,
    ): Int {
        val t = ((v - black) * invRange).coerceIn(0f, 1f)
        return (t * t * sqrt(t) * 255f).roundToInt().coerceIn(0, 255)
    }

    /** 3×3 unsharp mask over the packed channels, in place. */
    private fun unsharp(
        px: IntArray,
        width: Int,
        height: Int,
    ) {
        val src = px.copyOf()
        for (y in 0 until height) {
            val ym = max(y - 1, 0)
            val yp = min(y + 1, height - 1)
            for (x in 0 until width) {
                val xm = max(x - 1, 0)
                val xp = min(x + 1, width - 1)
                var packed = src[y * width + x] and 0xff000000.toInt()
                var shift = 16
                while (shift >= 0) {
                    var sum = 0
                    for (row in intArrayOf(ym, y, yp)) for (col in intArrayOf(xm, x, xp)) sum += src[row * width + col] ushr shift and 0xff
                    val v = src[y * width + x] ushr shift and 0xff
                    val o = (v + SHARPEN_K * (v - sum / 9f)).roundToInt().coerceIn(0, 255)
                    packed = packed or (o shl shift)
                    shift -= 8
                }
                px[y * width + x] = packed
            }
        }
    }

    /** Separable box blur with edge clamping, in place via [tmp]. */
    private fun boxBlur(
        a: FloatArray,
        tmp: FloatArray,
        w: Int,
        h: Int,
        r: Int,
    ) {
        val n = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += a[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / n
                sum += a[row + (x + r + 1).coerceIn(0, w - 1)] - a[row + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                a[y * w + x] = sum / n
                sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
    }
}
