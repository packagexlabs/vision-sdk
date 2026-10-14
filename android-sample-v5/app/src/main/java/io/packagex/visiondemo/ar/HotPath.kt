package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.ItemCode
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.Vec3
import java.util.Formatter
import java.util.Locale
import kotlin.math.sqrt

/*
 * What the GL thread runs per read, per hit and per pin on every frame, without the garbage: [Pose.apply],
 * [Intrinsics.project] and the like as scalars handed to an inlined block, each the same expressions in the same order
 * as the core's (Java semantics: no fused multiply-add), so every result is bit-identical; a pose's inverse made once
 * for all its uses ([Inverses]); a read's code key made once per text ([ReadKeys]); and pin ids kept unboxed ([IntMap],
 * [IntSet]).
 */

/** (x, y, z) turned by [q] as [Quat.rotate] computes it (t = (u × v)·2, then v + t·w + u × t), handed to [f] */
internal inline fun <R> rotated(q: Quat, x: Double, y: Double, z: Double, f: (Double, Double, Double) -> R): R {
    val tx = 2 * (q.y * z - q.z * y)
    val ty = 2 * (q.z * x - q.x * z)
    val tz = 2 * (q.x * y - q.y * x)
    return f(x + q.w * tx + (q.y * tz - q.z * ty), y + q.w * ty + (q.z * tx - q.x * tz), z + q.w * tz + (q.x * ty - q.y * tx))
}

/** [pose] applied to (x, y, z) as [Pose.apply] computes it (q.rotate(p) + t), handed to [f] */
internal inline fun <R> applied(pose: Pose, x: Double, y: Double, z: Double, f: (Double, Double, Double) -> R): R {
    val t = pose.t
    return rotated(pose.q, x, y, z) { rx, ry, rz -> f(rx + t.x, ry + t.y, rz + t.z) }
}

/** The pixel of [k] that the camera-frame point (x, y, z) is seen at, as [Intrinsics.project], handed to [f]; [behind] when it is not in front */
internal inline fun <R> projected(k: Intrinsics, x: Double, y: Double, z: Double, behind: () -> R, f: (Double, Double) -> R): R =
    if (z >= -1e-6) behind() else f(k.cx + k.fx * x / -z, k.cy - k.fy * y / -z)

/** The length of (x, y, z), as [io.packagex.arcount.Vec3.norm] */
internal fun norm(x: Double, y: Double, z: Double): Double = sqrt(x * x + y * y + z * z)

/** [lateral] of the point (x, y, z), as it computes it */
internal fun lateral(ray: Ray, x: Double, y: Double, z: Double): Double {
    val o = ray.origin
    val d = ray.dir
    val vx = x - o.x
    val vy = y - o.y
    val vz = z - o.z
    val along = vx * d.x + vy * d.y + vz * d.z
    if (along <= 0.0) return Double.MAX_VALUE
    return norm(vx - d.x * along, vy - d.y * along, vz - d.z * along)
}

/**
 * [read]'s centre ray in the world from its frame [capture], as `read.ray(capture, null)` makes it (the camera's pose
 * turning [Intrinsics.rayInCamera]'s unit direction), with two objects instead of ten
 */
internal fun centreRay(read: Read, capture: PoseRecord): Ray {
    val k = capture.intrinsics
    val x = (read.centreU - k.cx) / k.fx
    val y = -(read.centreV - k.cy) / k.fy
    val z = -1.0
    val n = norm(x, y, z)
    require(n > 0.0) { "the zero vector has no direction" }
    val camera = capture.camera
    return rotated(camera.q, x / n, y / n, z / n) { dx, dy, dz -> Ray(camera.t, Vec3(dx, dy, dz)) }
}

/**
 * String.format for the GL thread's log lines, with one Formatter and one StringBuilder kept: the same text (a Formatter
 * over the default format locale, as String.format makes, made again when that changes), without a new pair per line.
 */
internal class LineFormat {
    private val sb = StringBuilder(256)
    private var locale: Locale? = null
    private var formatter: Formatter? = null

    fun format(pattern: String, vararg args: Any?): String {
        val l = Locale.getDefault(Locale.Category.FORMAT)
        var f = formatter
        if (f == null || l != locale) {
            f = Formatter(sb, l)
            formatter = f
            locale = l
        }
        sb.setLength(0)
        f.format(pattern, *args)
        return sb.toString()
    }
}

/**
 * The inverses of the newest [size] poses asked for, by identity (one thread): a batch's capture camera and the frame's
 * own are each inverted once, not once per read, hit and pin.
 */
internal class Inverses(private val size: Int = 2) {
    private val of = arrayOfNulls<Pose>(size)
    private val inverse = arrayOfNulls<Pose>(size)
    private var next = 0

    operator fun get(pose: Pose): Pose {
        for (i in 0 until size) if (of[i] === pose) return inverse[i]!!
        val inv = pose.inverse()
        of[next] = pose
        inverse[next] = inv
        next = (next + 1) % size
        return inv
    }
}

/**
 * [ItemCode.key] of reads, kept per text, and [nominalWidthM] per symbology (one thread): a GTIN's key takes ~20
 * objects to make, and the GL thread asks for it per read on every batch and every frame. A key does not depend on the
 * symbology but for 8 digits (UPC-E), which are made every time. At most [cap] texts are kept.
 */
internal class ReadKeys(private val cap: Int = 512) {
    private val byText = HashMap<String, String>()
    private val widths = HashMap<String, Double>()

    fun key(read: Read): String = key(read.text, read.symbology)

    fun key(text: String, symbology: String?): String {
        if (text.length == 8) return ItemCode.key(text, symbology)
        byText[text]?.let { return it }
        val key = ItemCode.key(text, symbology)
        if (byText.size >= cap) byText.clear()
        byText[text] = key
        return key
    }

    /** [nominalWidthM] of [symbology] */
    fun nominalWidth(symbology: String?): Double? {
        if (symbology == null) return null
        // Kept boxed: the width goes back as the map holds it, not boxed again
        val w: Double? = widths[symbology] ?: (nominalWidthM(symbology) ?: NO_WIDTH).also {
            if (widths.size >= cap) widths.clear()
            widths[symbology] = it
        }
        return if (w!!.isNaN()) null else w
    }

    private companion object {
        /** No nominal width, as kept */
        const val NO_WIDTH = Double.NaN
    }
}

/** Values by an unboxed int key (pin ids grow past the Integer cache with every re-birth); few keys, so a linear scan */
internal class IntMap<V : Any> {
    private var keys = IntArray(8)
    private var values = arrayOfNulls<Any>(8)
    var size = 0
        private set

    private fun indexOf(key: Int): Int {
        for (i in 0 until size) if (keys[i] == key) return i
        return -1
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Int): V? {
        val i = indexOf(key)
        return if (i < 0) null else values[i] as V
    }

    operator fun set(key: Int, value: V) {
        val i = indexOf(key)
        if (i >= 0) {
            values[i] = value
            return
        }
        if (size == keys.size) {
            keys = keys.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        keys[size] = key
        values[size++] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun remove(key: Int): V? {
        val i = indexOf(key)
        if (i < 0) return null
        val v = values[i] as V
        size--
        keys[i] = keys[size]
        values[i] = values[size]
        values[size] = null
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun valueAt(i: Int): V = values[i] as V

    fun keyAt(i: Int): Int = keys[i]

    fun clear() {
        values.fill(null, 0, size)
        size = 0
    }
}

/** A set of unboxed ints; few of them, so a linear scan */
internal class IntSet {
    private var items = IntArray(8)
    var size = 0
        private set

    operator fun contains(v: Int): Boolean {
        for (i in 0 until size) if (items[i] == v) return true
        return false
    }

    /** Whether [v] was not in it yet */
    fun add(v: Int): Boolean {
        if (contains(v)) return false
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = v
        return true
    }

    fun isNotEmpty() = size > 0

    fun clear() {
        size = 0
    }
}
