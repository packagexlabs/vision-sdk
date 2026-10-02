package io.packagex.arcount

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The downscaled luma copy the app sends with each frame (spec 5.8), rendered from the simulator's true camera: the
 * shelf plane (at the first symbol's depth) with a smooth random texture, each symbol as a white box with vertical
 * bars of random widths (the same bars for the same text, as identical products have), box-filtered over each luma
 * pixel's footprint; then a horizontal box blur of speed · exposure · f / z / scale px from the camera's motion
 * since the last frame, and Gaussian noise.
 *
 * At [scale] 12 a 4K stream gives 320 × 180 px, the same picture in luma pixels as a 1280 × 720 stream at ¼ scale.
 */
class LumaScene(
    private val symbols: () -> List<Symbol>,
    val scale: Double = 12.0,
    seed: Int = 1,
    private val k: Intrinsics = K4K,
    private val exposureS: Double = 0.033,
    private val noise: Double = 2.0,
    textureAmplitude: Double = 60.0,
) {
    val width = (k.width / scale).toInt()
    val height = (k.height / scale).toInt()
    private val rnd = Random(seed)
    private val texture = DoubleArray(TEX_N * TEX_N) { 140.0 + textureAmplitude * (rnd.nextDouble() - 0.5) }
    private val bars = HashMap<String, DoubleArray>()
    private var prev: Pair<Long, Pose>? = null

    /** Bar edges of [text]'s symbol, as fractions 0..1 of its width: dark from edge 0 to 1, 2 to 3, ... */
    private fun barsOf(text: String, symbolWidth: Double): DoubleArray = bars.getOrPut(text) {
        val r = Random(text.hashCode())
        val edges = ArrayList<Double>()
        var x = 0.0
        while (x < symbolWidth) {
            edges += x / symbolWidth
            x += (1.2 + 3.6 * r.nextDouble()) / 1000
        }
        if (edges.size % 2 == 1) edges += 1.0
        edges.toDoubleArray()
    }

    /** Dark length (as a fraction of the width) of the bars between fractions a and b */
    private fun dark(edges: DoubleArray, a: Double, b: Double): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < edges.size) {
            val lo = max(a, edges[i])
            val hi = min(b, edges[i + 1])
            if (hi > lo) sum += hi - lo
            i += 2
        }
        return sum
    }

    private fun tex(x: Double, y: Double): Double {
        val gx = ((x + 1.0) / TEX_STEP).coerceIn(0.0, TEX_N - 1.001)
        val gy = ((y + 1.0) / TEX_STEP).coerceIn(0.0, TEX_N - 1.001)
        val i = gx.toInt()
        val j = gy.toInt()
        val fx = gx - i
        val fy = gy - j
        val a = texture[j * TEX_N + i] * (1 - fx) + texture[j * TEX_N + i + 1] * fx
        val b = texture[(j + 1) * TEX_N + i] * (1 - fx) + texture[(j + 1) * TEX_N + i + 1] * fx
        return a * (1 - fy) + b * fy
    }

    /** The luma copy of the frame at [ts] seen by the true camera [camera] */
    fun render(ts: Long, camera: Pose): LumaImage {
        val syms = symbols()
        val z0 = syms.firstOrNull()?.centre?.z ?: -0.3
        val c = camera.t
        val img = DoubleArray(width * height)
        for (j in 0 until height) {
            for (i in 0 until width) {
                val u = i * scale + (scale - 1) / 2
                val v = j * scale + (scale - 1) / 2
                val d = camera.rotate(Vec3((u - k.cx) / k.fx, -(v - k.cy) / k.fy, -1.0))
                val t = (z0 - c.z) / d.z
                if (t <= 0) {
                    img[j * width + i] = 128.0
                    continue
                }
                val x = c.x + d.x * t
                val y = c.y + d.y * t
                val hw = 0.5 * scale * t / k.fx
                val hh = 0.5 * scale * t / k.fy
                var value = tex(x, y)
                for (s in syms) {
                    val sx0 = s.centre.x - s.width / 2
                    val sy0 = s.centre.y - s.height / 2
                    val qx = QUIET
                    val ox = min(x + hw, sx0 + s.width + qx) - max(x - hw, sx0 - qx)
                    val oy = min(y + hh, sy0 + s.height) - max(y - hh, sy0)
                    if (ox <= 0 || oy <= 0) continue
                    val area = 4 * hw * hh
                    val fl = ox * oy / area
                    val edges = barsOf(s.text, s.width)
                    val a = (x - hw - sx0) / s.width
                    val b = (x + hw - sx0) / s.width
                    val fd = dark(edges, a, b) * s.width * oy / area
                    value = value * (1 - fl) + WHITE * (fl - fd) + DARK * fd
                }
                img[j * width + i] = value
            }
        }
        val p = prev
        prev = ts to camera
        if (p != null && ts > p.first) {
            val speed = (camera.t - p.second.t).norm() / ((ts - p.first) / 1e9)
            val blur = speed * exposureS * k.fx / abs(z0 - c.z) / scale
            if (blur > 0.05) blurRows(img, blur)
        }
        val data = ByteArray(width * height) { (img[it] + noise * rnd.gauss()).roundToInt().coerceIn(0, 255).toByte() }
        return LumaImage(width, height, data)
    }

    /** A horizontal box blur of [length] px (fractional), the row's ends repeated: the mean over [x − L/2, x + L/2] */
    private fun blurRows(img: DoubleArray, length: Double) {
        val row = DoubleArray(width)
        val cum = DoubleArray(width + 1)
        for (j in 0 until height) {
            for (i in 0 until width) row[i] = img[j * width + i]
            for (i in 0 until width) cum[i + 1] = cum[i] + row[i]
            // the integral of the row as a step function over pixel i = [i - 0.5, i + 0.5), clamped at the ends
            fun integral(x: Double): Double {
                val e = x + 0.5
                if (e <= 0) return e * row[0]
                if (e >= width) return cum[width] + (e - width) * row[width - 1]
                val n = e.toInt()
                return cum[n] + (e - n) * row[n]
            }
            for (i in 0 until width) img[j * width + i] = (integral(i + length / 2) - integral(i - length / 2)) / length
        }
    }

    private companion object {
        const val TEX_N = 400
        const val TEX_STEP = 0.005
        const val QUIET = 0.003
        const val WHITE = 225.0
        const val DARK = 35.0
    }
}

/** Feeds [core] the luma copy of every frame [scene] renders, before the frame's reads and pose record */
fun Sim.feedLuma(core: CountingCore, scene: LumaScene) {
    luma = { ts, camera -> core.onLuma(ts, scene.render(ts, camera), scene.scale) }
}
