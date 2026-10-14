package io.packagex.arcount

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** The test stream: 4K, f = 2896 px */
val K4K = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)

/** An EAN-13 product code, and the same code as the core keys it */
const val GTIN = "4006381333931"
const val GTIN14 = "04006381333931"
const val OTHER_GTIN = "5000112637922"
const val LABEL = "LABEL-1"
const val FRAME_NS = 33_333_333L
const val EXPOSURE_NS = 10_000_000L

/**
 * A barcode on a vertical shelf facing +Z (the camera looks along -Z), in the world. Upright symbols read left to
 * right (+X); an upside-down one is turned 180° in the shelf's plane, so its tl corner is at its bottom right.
 */
data class Symbol(
    val text: String,
    val centre: Vec3,
    val engineId: Int,
    val width: Double = 0.031,
    val height: Double = 0.02,
    val upsideDown: Boolean = false,
) {
    /** tl, tr, br, bl in the world, in the decoder's order */
    fun corners(): List<Vec3> {
        val hw = width / 2
        val hh = height / 2
        val tl = Vec3(-hw, hh, 0.0)
        val tr = Vec3(hw, hh, 0.0)
        val br = Vec3(hw, -hh, 0.0)
        val bl = Vec3(-hw, -hh, 0.0)
        return (if (upsideDown) listOf(br, bl, tl, tr) else listOf(tl, tr, br, bl)).map { centre + it }
    }
}

/** Gaussian noise */
fun Random.gauss(): Double = sqrt(-2 * ln(nextDouble(1e-12, 1.0))) * cos(2 * PI * nextDouble())

/**
 * What the decoder reads of [symbols] from a camera at [camera]: each symbol whose centre projects inside the image,
 * its four corners projected and moved together by Gaussian noise of [noisePx] per axis; a quad within 2 px of the
 * border (or beyond it) is marked as touching it.
 */
fun shoot(camera: Pose, symbols: List<Symbol>, ts: Long, noisePx: Double = 0.0, rnd: Random = Random(0), k: Intrinsics = K4K): List<Read> =
    symbols.mapNotNull { s ->
        val pts = s.corners().map { k.project(camera.inverse().apply(it)) ?: return@mapNotNull null }
        val centre = k.project(camera.inverse().apply(s.centre)) ?: return@mapNotNull null
        if (centre.first < 0 || centre.second < 0 || centre.first >= k.width || centre.second >= k.height) return@mapNotNull null
        val du = if (noisePx > 0) rnd.gauss() * noisePx else 0.0
        val dv = if (noisePx > 0) rnd.gauss() * noisePx else 0.0
        val corners = pts.flatMap { listOf(it.first + du, it.second + dv) }
        val touches = pts.any { (u, v) -> u < 2 || v < 2 || u > k.width - 3 || v > k.height - 3 }
        Read(ts, s.text, corners, s.engineId, touchesBorder = touches)
    }

/** A camera at [x], [y], [z] looking along -Z (no rotation) */
fun cameraAt(x: Double, y: Double = 0.0, z: Double = 0.0) = Pose(Vec3(x, y, z), Quat.IDENTITY)
