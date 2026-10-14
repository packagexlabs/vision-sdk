package io.packagex.arcount

import kotlin.math.sqrt

/**
 * Where a unit is expected in the current frame, and how sure that is (spec 5.4, prediction).
 *
 * @property u pixel column of p̂ in the unrotated image
 * @property v pixel row of p̂
 * @property z the unit's camera depth: its distance along the optical axis
 * @property sigmaPx σ_p̂ in pixels
 */
class Predicted(val u: Double, val v: Double, val z: Double, val sigmaPx: Double) {
    /** Whether p̂ lies inside the image */
    fun inImage(k: Intrinsics) = u >= 0 && v >= 0 && u < k.width && v < k.height
}

object Prediction {
    /** p̂ = π(T_ca · X_a) with T_ca = T_ac⁻¹; null when the point is not in front of the camera */
    fun pixel(xA: Vec3, tac: Pose, k: Intrinsics): Pair<Double, Double>? = k.project(tac.inverse().apply(xA))

    /** The camera depth of [xA]: its distance along the optical axis (the camera looks along -Z) */
    fun cameraDepth(xA: Vec3, tac: Pose): Double = -tac.inverse().apply(xA).z

    /** The camera's move from [from] to [to], perpendicular to the viewing ray from [to] to [xA] */
    fun perpendicularTravel(from: Vec3, to: Vec3, xA: Vec3): Double {
        val move = to - from
        val view = xA - to
        if (view.norm() < 1e-12) return move.norm()
        val r = view.unit()
        return (move - r * (move dot r)).norm()
    }

    /**
     * σ_p̂ = sqrt((f · t⊥ · σz / z²)² + σT² + σray²), σT = sigmaTPx + slope · travel: the depth error seen from a
     * camera that moved t⊥ across the ray since the unit's last read, the relative-pose error, and the ray noise.
     */
    fun sigmaPx(f: Double, tPerp: Double, sigmaZ: Double, z: Double, travel: Double, config: CountConfig): Double {
        val depthTerm = f * tPerp * sigmaZ / (z * z)
        val poseTerm = config.sigmaTPx + config.sigmaTSlopePxPerM * travel
        return sqrt(depthTerm * depthTerm + poseTerm * poseTerm + config.sigmaRayPx * config.sigmaRayPx)
    }

    /** The prediction of a unit at [xA] with depth σ [sigmaZ], last read from camera centre [lastCentre] (anchor frame) */
    fun of(xA: Vec3, sigmaZ: Double, lastCentre: Vec3, tac: Pose, k: Intrinsics, config: CountConfig): Predicted? {
        val (u, v) = pixel(xA, tac, k) ?: return null
        val z = cameraDepth(xA, tac)
        val tPerp = perpendicularTravel(lastCentre, tac.t, xA)
        val travel = (tac.t - lastCentre).norm()
        return Predicted(u, v, z, sigmaPx(k.fx, tPerp, sigmaZ, z, travel, config))
    }
}
