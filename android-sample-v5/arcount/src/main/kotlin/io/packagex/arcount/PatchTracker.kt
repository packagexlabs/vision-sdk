package io.packagex.arcount

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * An 8-bit luma plane; pixel (x, y) is data[y * rowStride + x], read as unsigned. [free] hands [data] to [onFree] (its
 * maker's pool) once, when the last holder lets the image go: nothing reads it after.
 */
class LumaImage(
    val width: Int,
    val height: Int,
    val data: ByteArray,
    val rowStride: Int = width,
    private val onFree: ((ByteArray) -> Unit)? = null,
) {
    private var freed = false

    init {
        require(width > 0 && height > 0 && rowStride >= width) { "bad plane: ${width}x$height, row stride $rowStride" }
        require(data.size >= (height - 1).toLong() * rowStride + width) {
            "${data.size} bytes are too few for a ${width}x$height plane of row stride $rowStride"
        }
    }

    fun free() {
        if (freed) return
        freed = true
        onFree?.invoke(data)
    }
}

/**
 * A barcode's pixels at a decode, to find again in later frames
 *
 * @property mean of the level 0 template, and [norm] the root of its summed squares about that mean: what the final
 *   correlation needs
 */
class Patch internal constructor(
    internal val fine: PatchLevel,
    internal val coarse: PatchLevel,
    internal val mean: Double,
    internal val norm: Double,
    internal val oneDimensional: Boolean,
)

/**
 * Where a patch was found, in the pixels of the image it was searched in. [oneDimensional]: the patch has structure in
 * one image direction only (a barcode's bars say nothing along themselves), so only that direction was measured and
 * the coordinate along the bars is the seed's.
 */
data class Tracked(val x: Double, val y: Double, val ncc: Double, val oneDimensional: Boolean)

/** One direction a level may step along (a unit vector) and 1 / the template's curvature along it */
internal class Axis(val x: Double, val y: Double, val inverse: Double)

/**
 * A patch at one pyramid level: the template, its gradient (0 on the outer ring, which has no neighbour on both sides)
 * and the directions it may step along. A patch of half h is a square of side 2h+1.
 */
internal class PatchLevel(
    val half: Int,
    val template: FloatArray,
    val gx: FloatArray,
    val gy: FloatArray,
    val axes: Array<Axis>,
) {
    val side = 2 * half + 1
}

/**
 * Follows a barcode's pixels between decodes: translation-only Lucas-Kanade in the inverse compositional form (Baker
 * and Matthews, "Lucas-Kanade 20 Years On", 2004), coarse to fine on a 2-level pyramid.
 *
 * Positions are in pixels, pixel (i, j) being centred on (i, j). Level 1 is the 2x2 box average of level 0, so its
 * pixel (i, j) is centred on level 0 position (2i + 0.5, 2j + 0.5); it is read from level 0 where needed, never
 * built. The template's gradient and 2x2 Hessian are computed once per level in [capture]; [track] only samples the
 * image.
 *
 * Gradient descent reaches about as far as the pattern's own scale. For a barcode that is a bar width or so at level 0
 * and about twice that with level 1: on random bars of 2..8 px a shift of 4.5 px is found nine times in ten, one of
 * 9 px hardly ever. The seed must be that close.
 */
object PatchTracker {
    private const val MAX_ITERATIONS = 20

    /** A step shorter than this many pixels of its level is converged */
    private const val STEP_EPS = 0.02

    /** Aperture rule: below this share of the larger Hessian eigenvalue, the smaller one's direction is not used */
    private const val WEAK_RATIO = 0.01

    /**
     * A patch is flat when its root-mean-square gradient is under this many grey levels per pixel, about 1% of the
     * 0..255 range. The Hessian is a sum of squared gradients over the n pixels that have one, so the test is the
     * larger eigenvalue below n * FLAT_GRADIENT^2: the patch size sets the scale. Noise of s grey levels gives
     * s / sqrt(2) rms (central differences), so this rejects noise up to about 3.5 levels. Bars of contrast c with an
     * edge every l pixels give c^2 / (2 l): 100 for c = 40 and l = 8, so even faint bars sit 4 times above the limit
     * in gradient, 16 times in eigenvalue.
     */
    private const val FLAT_GRADIENT = 2.5

    /**
     * The square patch of side 2·half+1 centred on (cx, cy); null when it does not fit in the image or has no texture.
     * half is at least 4. Level 1 is a window of half (half - 2) / 2 around the same centre, a little smaller than
     * level 0's area so that it fits wherever that does.
     */
    fun capture(img: LumaImage, cx: Double, cy: Double, half: Int): Patch? {
        require(half >= 4) { "half is $half, but level 1 needs a patch of at least 9x9" }
        val half1 = (half - 2) / 2
        val c1x = toCoarse(cx)
        val c1y = toCoarse(cy)
        if (!fits(img, 0, cx, cy, half) || !fits(img, 1, c1x, c1y, half1)) return null
        val n0 = 2 * half + 1
        val n1 = 2 * half1 + 1
        val t0 = FloatArray(n0 * n0)
        val t1 = FloatArray(n1 * n1)
        window0(img, cx - half, cy - half, n0, t0)
        window1(img, c1x - half1, c1y - half1, n1, t1, FloatArray((n1 + 1) * (n1 + 1)))
        val g0 = gradient(t0, n0)
        val g1 = gradient(t1, n1)
        val axes0 = axesOf(g0, n0, null)
        if (axes0.isEmpty()) return null
        val oneDimensional = axes0.size == 1
        // A patch with one direction at full resolution steps along that one at level 1 too, so the coordinate along
        // the bars never leaves the seed, however level 1's own gradient tilts
        val axes1 = axesOf(g1, n1, if (oneDimensional) axes0[0] else null)
        var sum = 0.0
        for (v in t0) sum += v
        val mean = sum / t0.size
        var spread = 0.0
        for (v in t0) spread += (v - mean) * (v - mean)
        return Patch(
            PatchLevel(half, t0, g0.gx, g0.gy, axes0),
            PatchLevel(half1, t1, g1.gx, g1.gy, axes1),
            mean,
            sqrt(spread),
            oneDimensional,
        )
    }

    /**
     * Where the patch is in img, searched from (seedX, seedY); null when it is lost: the window leaves the image at
     * either level, the zero-mean normalized cross-correlation of the template with the image there is below minNcc,
     * or the result is not finite.
     *
     * Aperture rule: where the template's smaller Hessian eigenvalue is below 1% of the larger, the patch has
     * structure in one direction only and a step along the other would be noise. Then only the strong eigenvector is
     * stepped along, the coordinate along the weak one stays the seed's, and the result is oneDimensional.
     */
    fun track(patch: Patch, img: LumaImage, seedX: Double, seedY: Double, minNcc: Double = 0.6): Tracked? {
        val d = DoubleArray(2)
        if (!descend(patch.coarse, img, 1, toCoarse(seedX), toCoarse(seedY), d)) return null
        d[0] *= 2
        d[1] *= 2
        if (!descend(patch.fine, img, 0, seedX, seedY, d)) return null
        val x = seedX + d[0]
        val y = seedY + d[1]
        if (!x.isFinite() || !y.isFinite() || !fits(img, 0, x, y, patch.fine.half)) return null
        val ncc = correlation(patch, img, x, y)
        if (!ncc.isFinite() || ncc < minNcc) return null
        return Tracked(x, y, ncc, patch.oneDimensional)
    }

    /**
     * Inverse compositional steps at one level, starting [d] pixels (of that level) off the seed (sx, sy) and leaving
     * the result in d. With the error image e = I(x + p) - T(x), the step dp solves H dp = sum(grad T * e) over the
     * axes, and p becomes p - dp. False when the window leaves the image or d is not finite.
     */
    private fun descend(lv: PatchLevel, img: LumaImage, depth: Int, sx: Double, sy: Double, d: DoubleArray): Boolean {
        val n = lv.side
        val template = lv.template
        val gx = lv.gx
        val gy = lv.gy
        val win = FloatArray(n * n)
        val block = FloatArray(if (depth == 0) 0 else (n + 1) * (n + 1))
        var iteration = 0
        while (iteration++ < MAX_ITERATIONS) {
            val x = sx + d[0]
            val y = sy + d[1]
            if (!fits(img, depth, x, y, lv.half)) return false
            val left = x - lv.half
            val top = y - lv.half
            if (depth == 0) window0(img, left, top, n, win) else window1(img, left, top, n, win, block)
            var bx = 0.0
            var by = 0.0
            for (k in win.indices) {
                val e = (win[k] - template[k]).toDouble()
                bx += gx[k] * e
                by += gy[k] * e
            }
            var stepX = 0.0
            var stepY = 0.0
            for (a in lv.axes) {
                val c = (bx * a.x + by * a.y) * a.inverse
                stepX += c * a.x
                stepY += c * a.y
            }
            d[0] -= stepX
            d[1] -= stepY
            if (!d[0].isFinite() || !d[1].isFinite()) return false
            if (stepX * stepX + stepY * stepY < STEP_EPS * STEP_EPS) break
        }
        return true
    }

    /** Zero-mean normalized cross-correlation of the level 0 template with the image at (x, y); NaN if that is flat */
    private fun correlation(patch: Patch, img: LumaImage, x: Double, y: Double): Double {
        val lv = patch.fine
        val template = lv.template
        val win = FloatArray(template.size)
        window0(img, x - lv.half, y - lv.half, lv.side, win)
        var sum = 0.0
        var sumSq = 0.0
        var cross = 0.0
        for (k in win.indices) {
            val v = win[k].toDouble()
            sum += v
            sumSq += v * v
            cross += v * template[k]
        }
        val count = win.size
        val imageMean = sum / count
        val spread = sumSq - count * imageMean * imageMean
        if (spread < 1e-6) return Double.NaN
        return (cross - count * patch.mean * imageMean) / (patch.norm * sqrt(spread))
    }

    /** Level 1 position of a level 0 position: its pixel i is centred on 2i + 0.5 */
    private fun toCoarse(v: Double) = (v - 0.5) / 2

    /**
     * Whether the (2·half+1)^2 window centred on (x, y) lies in the pixels of level [depth], with a pixel to spare for
     * the bilinear read; false for NaN. Level 1 has width / 2 by height / 2 pixels: an odd last row or column is
     * unused.
     */
    private fun fits(img: LumaImage, depth: Int, x: Double, y: Double, half: Int): Boolean {
        val w = img.width shr depth
        val h = img.height shr depth
        return 2 * half + 2 <= min(w, h) && x - half >= 0 && x + half <= w - 1 && y - half >= 0 && y + half <= h - 1
    }

    /** The n x n window of level 0 whose top left sample is at (left, top), read bilinearly; it must fit */
    private fun window0(img: LumaImage, left: Double, top: Double, n: Int, out: FloatArray) {
        val data = img.data
        val stride = img.rowStride
        // The clamp only acts when the window ends on the last pixel: the fraction is then 1, not 0 with a pixel past
        // the edge
        val ix = min(floor(left).toInt(), img.width - n - 1)
        val iy = min(floor(top).toInt(), img.height - n - 1)
        val fx = (left - ix).toFloat()
        val fy = (top - iy).toFloat()
        val w00 = (1 - fx) * (1 - fy)
        val w10 = fx * (1 - fy)
        val w01 = (1 - fx) * fy
        val w11 = fx * fy
        var k = 0
        for (j in 0 until n) {
            var p = (iy + j) * stride + ix
            for (i in 0 until n) {
                val upper = w00 * (data[p].toInt() and 0xFF) + w10 * (data[p + 1].toInt() and 0xFF)
                val lower = w01 * (data[p + stride].toInt() and 0xFF) + w11 * (data[p + stride + 1].toInt() and 0xFF)
                out[k++] = upper + lower
                p++
            }
        }
    }

    /** The same at level 1; [block] is scratch for the (n+1)^2 level 1 pixels the bilinear read touches */
    private fun window1(img: LumaImage, left: Double, top: Double, n: Int, out: FloatArray, block: FloatArray) {
        val data = img.data
        val stride = img.rowStride
        val ix = min(floor(left).toInt(), (img.width shr 1) - n - 1)
        val iy = min(floor(top).toInt(), (img.height shr 1) - n - 1)
        val fx = (left - ix).toFloat()
        val fy = (top - iy).toFloat()
        val w00 = (1 - fx) * (1 - fy)
        val w10 = fx * (1 - fy)
        val w01 = (1 - fx) * fy
        val w11 = fx * fy
        val bs = n + 1
        for (j in 0 until bs) {
            var p = 2 * (iy + j) * stride + 2 * ix
            for (i in 0 until bs) {
                val upper = (data[p].toInt() and 0xFF) + (data[p + 1].toInt() and 0xFF)
                val lower = (data[p + stride].toInt() and 0xFF) + (data[p + stride + 1].toInt() and 0xFF)
                block[j * bs + i] = (upper + lower) * 0.25f
                p += 2
            }
        }
        for (j in 0 until n) {
            for (i in 0 until n) {
                val q = j * bs + i
                out[j * n + i] = w00 * block[q] + w10 * block[q + 1] + w01 * block[q + bs] + w11 * block[q + bs + 1]
            }
        }
    }

    private class Gradient(val gx: FloatArray, val gy: FloatArray, val hxx: Double, val hxy: Double, val hyy: Double)

    /** Central differences of the n x n window t, and the Hessian sum(g g^T) over the (n-2)^2 pixels that have them */
    private fun gradient(t: FloatArray, n: Int): Gradient {
        val gx = FloatArray(n * n)
        val gy = FloatArray(n * n)
        var hxx = 0.0
        var hxy = 0.0
        var hyy = 0.0
        for (j in 1 until n - 1) {
            for (i in 1 until n - 1) {
                val k = j * n + i
                val x = (t[k + 1] - t[k - 1]) * 0.5f
                val y = (t[k + n] - t[k - n]) * 0.5f
                gx[k] = x
                gy[k] = y
                hxx += x.toDouble() * x
                hxy += x.toDouble() * y
                hyy += y.toDouble() * y
            }
        }
        return Gradient(gx, gy, hxx, hxy, hyy)
    }

    /**
     * The directions a level steps along, each with 1 / curvature: the inverse Hessian over the eigenvectors that
     * carry signal. None when the larger eigenvalue is below the flat limit. One, the strong eigenvector, when the
     * smaller is below [WEAK_RATIO] of the larger. Two otherwise. With [lock], the one direction is that one, whatever
     * this level's own eigenvectors are.
     */
    private fun axesOf(g: Gradient, n: Int, lock: Axis?): Array<Axis> {
        val flat = FLAT_GRADIENT * FLAT_GRADIENT * (n - 2) * (n - 2)
        if (lock != null) {
            val curvature = lock.x * lock.x * g.hxx + 2 * lock.x * lock.y * g.hxy + lock.y * lock.y * g.hyy
            return if (curvature >= flat) arrayOf(Axis(lock.x, lock.y, 1 / curvature)) else emptyArray()
        }
        val e = eigen(g.hxx, g.hxy, g.hyy)
        if (e.l1 < flat) return emptyArray()
        val strong = Axis(e.vx, e.vy, 1 / e.l1)
        return if (e.l2 < WEAK_RATIO * e.l1) arrayOf(strong) else arrayOf(strong, Axis(-e.vy, e.vx, 1 / e.l2))
    }

    private class Eigen(val l1: Double, val l2: Double, val vx: Double, val vy: Double)

    /** Eigenvalues l1 >= l2 >= 0 of [[a, b], [b, c]] and the unit eigenvector of l1 */
    private fun eigen(a: Double, b: Double, c: Double): Eigen {
        val mid = (a + c) / 2
        val radius = hypot((a - c) / 2, b)
        val l1 = mid + radius
        val l2 = max(mid - radius, 0.0)
        // (b, l1 - a) and (l1 - c, b) both solve (H - l1 I) v = 0; the longer is the one without cancellation. A
        // diagonal H gives exactly (1, 0) or (0, 1), which keeps an image without structure along y exactly on its seed
        val ux = b
        val uy = l1 - a
        val wx = l1 - c
        val wy = b
        val useU = ux * ux + uy * uy >= wx * wx + wy * wy
        val vx = if (useU) ux else wx
        val vy = if (useU) uy else wy
        val length = hypot(vx, vy)
        return if (length > 0.0) Eigen(l1, l2, vx / length, vy / length) else Eigen(l1, l2, 1.0, 0.0)
    }
}
