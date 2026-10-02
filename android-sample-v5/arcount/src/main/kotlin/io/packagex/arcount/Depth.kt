package io.packagex.arcount

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Which rule of spec 5.4 accepted a depth: (a) WIDE, ≥ 5 inliers over ≥ 3° with the baseline and σz ≤ 2 cm (ruling R3);
 * (b) DENSE, ≥ 8 over ≥ 2° with σz ≤ 2 cm. Neither accepts rays whose RMS residual exceeds 1.5 σray (ruling R3).
 */
enum class DepthGate { WIDE, DENSE }

/**
 * A least-squares point over the inlier rays.
 *
 * @property covariance from the normal equations with every ray weighted by 1 / (σray · range)², scaled up by the
 *   fit's own residual variance when that is larger than σray says (never down)
 * @property sigmaZ the standard deviation along the mean viewing direction
 * @property spanRad the largest angle between two inlier rays
 * @property baseline the largest distance between two inlier camera centres
 * @property range the mean distance from the camera centres to the point
 * @property rmsResidual the RMS angular residual of the inlier rays, radians
 * @property gate the rule that accepts this depth; null when none does
 */
class RayFit(
    val point: Vec3,
    val covariance: Mat3,
    val inliers: Int,
    val sigmaZ: Double,
    val spanRad: Double,
    val baseline: Double,
    val range: Double,
    val rmsResidual: Double,
    val gate: DepthGate?,
)

/** Triangulation of one unit from its rays (spec 5.4, depth) */
object Triangulation {
    /** The closed-form least-squares point of [rays] (3×3 normal equations) and its covariance; null when degenerate */
    fun leastSquares(rays: List<Ray>, sigmaRad: Double): Pair<Vec3, Mat3>? {
        if (rays.size < 2) return null
        val first = solve(rays) { 1.0 } ?: return null
        return solve(rays) { r -> 1.0 / (sigmaRad * max((first.first - r.origin).norm(), 1e-3)).pow(2) }
    }

    private fun solve(rays: List<Ray>, weight: (Ray) -> Double): Pair<Vec3, Mat3>? {
        var a = Mat3.ZERO
        var b = Vec3.ZERO
        for (r in rays) {
            val w = weight(r)
            val p = Mat3.across(r.dir) * w
            a += p
            b += p * r.origin
        }
        val inv = a.inverse() ?: return null
        return (inv * b) to inv
    }

    /** The angle between [ray] and the direction from its origin to [p] */
    fun residual(ray: Ray, p: Vec3): Double {
        val v = p - ray.origin
        return atan2((ray.dir cross v).norm(), ray.dir dot v)
    }

    /**
     * Least squares with iterative rejection: the ray with the largest angular residual goes while it exceeds
     * rejectSigmas · σray; then the gates of spec 5.4 and the 8 cm – 1.5 m depth range decide whether the depth holds.
     */
    fun fit(rays: List<Ray>, sigmaRad: Double, config: CountConfig): RayFit? {
        val inliers = rays.toMutableList()
        var solution = leastSquares(inliers, sigmaRad) ?: return null
        while (inliers.size > 2) {
            val worst = inliers.indices.maxBy { residual(inliers[it], solution.first) }
            if (residual(inliers[worst], solution.first) <= config.rejectSigmas * sigmaRad) break
            inliers.removeAt(worst)
            solution = leastSquares(inliers, sigmaRad) ?: return null
        }
        val (x, cov0) = solution
        val dof = 2 * inliers.size - 3
        val s2 = if (dof > 0) inliers.sumOf { (residual(it, x) / sigmaRad).pow(2) } / dof else 1.0
        val cov = if (s2 > 1.0) cov0 * s2 else cov0
        val range = inliers.map { (x - it.origin).norm() }.average()
        val meanOrigin = inliers.fold(Vec3.ZERO) { s, r -> s + r.origin } * (1.0 / inliers.size)
        val toPoint = x - meanOrigin
        val view = if (toPoint.norm() > 1e-9) toPoint.unit() else inliers.last().dir
        val sigmaZ = sqrt(max(cov.quadratic(view), 0.0))
        var span = 0.0
        var baseline = 0.0
        for (i in inliers.indices) {
            for (j in i + 1 until inliers.size) {
                span = max(span, atan2((inliers[i].dir cross inliers[j].dir).norm(), inliers[i].dir dot inliers[j].dir))
                baseline = max(baseline, (inliers[i].origin - inliers[j].origin).norm())
            }
        }
        val inFront = inliers.all { (it.dir dot (x - it.origin)) > 0 }
        val rms = sqrt(inliers.sumOf { residual(it, x).pow(2) } / inliers.size)
        val gate = when {
            !inFront || range < config.minDepth || range > config.maxDepth -> null
            rms > config.maxRmsResidualSigmas * sigmaRad -> null
            inliers.size >= config.wideMinInliers && span >= rad(config.wideSpanDeg) &&
                baseline >= 2 * range * tan(rad(config.wideBaselineHalfAngleDeg)) && sigmaZ <= config.wideMaxSigmaZ -> DepthGate.WIDE
            inliers.size >= config.denseMinInliers && span >= rad(config.denseSpanDeg) && sigmaZ <= config.denseMaxSigmaZ -> DepthGate.DENSE
            else -> null
        }
        return RayFit(x, cov, inliers.size, sigmaZ, span, baseline, range, rms, gate)
    }

    private fun rad(deg: Double) = deg * PI / 180
}

/** A unit's last [maxRays] rays, in the anchor frame */
class DepthTrack(private val maxRays: Int) {
    private val window = ArrayDeque<Ray>()

    val rays: List<Ray> get() = window.toList()

    val size get() = window.size

    fun add(ray: Ray) {
        window.addLast(ray)
        while (window.size > maxRays) window.removeFirst()
    }

    fun fit(sigmaRad: Double, config: CountConfig) = Triangulation.fit(window.toList(), sigmaRad, config)
}

/**
 * The section plane (spec 5.1), in the anchor frame: vertical, its [normal] the horizontal part of the optical axis
 * at OPEN (pointing from the camera into the shelf), the points p with normal · p = [offset].
 */
data class SectionPlane(val normal: Vec3, val offset: Double, val up: Vec3) {
    /** The horizontal direction in the plane: right, seen from the camera that opened the section */
    val shelfAxis: Vec3 get() = (normal cross up).unit()

    /** Where [ray] meets the plane in front of its origin; null when it runs parallel to it or away from the shelf */
    fun intersect(ray: Ray): Vec3? {
        val den = normal dot ray.dir
        if (den <= 1e-9) return null
        val s = (offset - (normal dot ray.origin)) / den
        return if (s > 0) ray.at(s) else null
    }

    /** Position of [p] along the shelf axis */
    fun along(p: Vec3) = shelfAxis dot p

    /** Height of [p] */
    fun height(p: Vec3) = up dot p

    /** The same plane moved to pass through [p] */
    fun through(p: Vec3) = copy(offset = normal dot p)

    /**
     * The plane prior (spec 5.4): a unit's point before it has a depth is where its ray meets this plane, σ
     * [sigmaAlong] along the ray and the ray noise across it.
     */
    fun prior(ray: Ray, sigmaAlong: Double, sigmaRad: Double): PlanePoint? {
        val p = intersect(ray) ?: return null
        val across = sigmaRad * (p - ray.origin).norm()
        val cov = Mat3.IDENTITY * (across * across) + Mat3.outer(ray.dir, ray.dir) * (sigmaAlong * sigmaAlong - across * across)
        return PlanePoint(p, cov, sigmaAlong)
    }

    companion object {
        /** The vertical plane through [through] facing [viewDir]'s horizontal part, [up] being the world's up */
        fun vertical(viewDir: Vec3, up: Vec3, through: Vec3): SectionPlane {
            val u = up.unit()
            var h = viewDir - u * (viewDir dot u)
            if (h.norm() < 1e-6) h = u cross Vec3(1.0, 0.0, 0.0)
            if (h.norm() < 1e-6) h = u cross Vec3(0.0, 0.0, 1.0)
            val n = h.unit()
            return SectionPlane(n, n dot through, u)
        }
    }
}

/** A point on the section plane with its covariance and its σ along the ray that found it */
class PlanePoint(val point: Vec3, val covariance: Mat3, val sigmaZ: Double)
