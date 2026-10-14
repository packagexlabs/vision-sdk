package io.packagex.arcount

import kotlin.math.sqrt

/** A point or a direction, in metres */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)

    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun norm() = sqrt(this dot this)

    /** This direction with length 1; the zero vector has none */
    fun unit(): Vec3 {
        val n = norm()
        require(n > 0.0) { "the zero vector has no direction" }
        return Vec3(x / n, y / n, z / n)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

/** A rotation as a unit quaternion, in ARCore's order x, y, z, w */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    /** [v] turned by this rotation */
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2.0
        return v + t * w + (u cross t)
    }

    /** The opposite rotation */
    fun conj() = Quat(-x, -y, -z, w)

    /** This rotation after [o]: (this * o).rotate(v) == this.rotate(o.rotate(v)) */
    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    companion object {
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)

        /** A turn of [radians] about [axis] */
        fun axisAngle(axis: Vec3, radians: Double): Quat {
            val a = axis.unit()
            val s = kotlin.math.sin(radians / 2)
            return Quat(a.x * s, a.y * s, a.z * s, kotlin.math.cos(radians / 2))
        }
    }
}

/**
 * A rigid transform p -> q·p + t. An ARCore pose maps a point of its own frame (the camera's, an anchor's) to the
 * world; [inverse] maps the world back.
 */
data class Pose(val t: Vec3, val q: Quat) {
    fun apply(p: Vec3) = q.rotate(p) + t

    fun rotate(d: Vec3) = q.rotate(d)

    fun inverse(): Pose {
        val qi = q.conj()
        return Pose(-qi.rotate(t), qi)
    }

    /** This transform after [o]: (this * o).apply(p) == this.apply(o.apply(p)) */
    operator fun times(o: Pose) = Pose(q.rotate(o.t) + t, q * o.q)

    companion object {
        val IDENTITY = Pose(Vec3.ZERO, Quat.IDENTITY)
    }
}

/**
 * Pinhole intrinsics of the image the reads are in (the app's 4K stream), in its pixels, unrotated (sensor readout
 * order). ARCore gives them for its CPU image only; the app scales them by the width ratio.
 */
data class Intrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val width: Int, val height: Int) {
    /**
     * The direction, in the camera's frame, of the ray through pixel ([u], [v]). ARCore's camera frame has +X right,
     * +Y up and -Z forward relative to the image readout, so image down is camera -Y.
     */
    fun rayInCamera(u: Double, v: Double) = Vec3((u - cx) / fx, -(v - cy) / fy, -1.0).unit()

    /** The pixel that point [p] of the camera's frame is seen at; null when it is not in front of the camera */
    fun project(p: Vec3): Pair<Double, Double>? {
        if (p.z >= -1e-6) return null
        return Pair(cx + fx * p.x / -p.z, cy - fy * p.y / -p.z)
    }
}
