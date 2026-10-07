package io.packagex.visiondemo.ar

import io.packagex.arcount.CountConfig
import io.packagex.arcount.DepthGate
import io.packagex.arcount.Pose
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Drift plan Phase 4 (P2): each pin's point, refined from the ray of every read that claims it (§3.4 rules 1, 4 and 8),
 * in its anchor's frame, so an anchor correction carries the rays with the pin (I1, I4). Pure and allocation-free: the
 * rays, the prior and the scratch are DoubleArrays, so a claim leaves the GL thread no garbage.
 */

/** Rule 1: a read's ray noise, in pixels at its image's fx (the core's σray, CountConfig.sigmaRayPx) */
const val PIN_SIGMA_PX = 15.0

/** Rule 1: Huber's k, in σ, and the warm-started iterations of each solve */
const val PIN_HUBER_K = 2.0
const val PIN_IRLS_ITERATIONS = 3

/** Rule 1: the newest rays kept, and up to this many older keyrays whose origins lie more than [PIN_KEY_MIN_M] apart */
const val PIN_NEW_RAYS = 20
const val PIN_KEY_RAYS = 10
const val PIN_KEY_MIN_M = 0.01

/** Rule 1: the prior's σ along its ray: for the median of valid hits, the nominal width's (a fraction of its depth), and the default depth's */
const val PIN_PRIOR_HITS_M = 0.10
const val PIN_PRIOR_WIDTH = 0.30
const val PIN_PRIOR_DEFAULT_M = 0.25

/** Rule 4: a re-init's prior, at the current depth on the newest ray */
const val PIN_REINIT_SIGMA_M = 0.15

/** Rule 1: the gate test (a data-only fit) runs every this many claims, or once the baseline has grown this much */
const val PIN_GATE_CLAIMS = 5
const val PIN_GATE_BASELINE_M = 0.01

/** Rule 1's guard: a solve whose mean range leaves this band (the core's depth range), or that lies behind a ray, is not taken */
const val PIN_SOLVE_MIN_M = 0.08
const val PIN_SOLVE_MAX_M = 1.5

// ponytail: 2, from six Memor 35 traces of the test sheet (2026-10-07): where ARCore tracked a moving phone a verified
// pin's range stayed within 1.8x of what its size at verification implies; with the phone held still (14:07, camera
// within 4 cm) 13 of 15 verified pins slid to 2-5.4x (0.27 m to 1.46). Lower it if pins still slide; tilt alone moves it ~1.2x
/**
 * Rule 1's size guard: once verified, a solve whose range on the newest ray lies beyond this multiple (either way) of
 * the range its label's width at verification gives at the newest read's width in pixels (fx · [PinEstimator.widthM] /
 * px) is not taken
 */
const val PIN_SIZE_RATIO = 2.0

/** Rule 8: a pin whose point lies farther than this from its anchor gets a new anchor there */
const val PIN_REANCHOR_M = 0.05

/** Rule 6: a merge's survivor takes this many of the other pin's newest rays */
const val PIN_ABSORB_RAYS = 3

/** Rule 9: a pin's anchor poses kept, one per frame (as the frame records) */
const val PIN_ANCHOR_FRAMES = 32

/** A ray's range never counts as less than this in its weight, metres */
private const val MIN_RANGE_M = 0.05

/** Where a pin's prior came from (rule 1, and rule 4's re-init) */
enum class PriorSource {
    /** Not started */
    NONE,

    /** The median of at least two valid hits */
    HITS,

    /** The EAN/UPC nominal width's depth */
    WIDTH,

    /** [PIN_DEFAULT_DEPTH_M], or a pin's point when the Android rules first refine it */
    DEFAULT,

    /** Rule 4: the current depth on the newest of its bad claims' rays */
    REINIT,
}

/**
 * One pin's point in its anchor's frame ([x], [y], [z]) from the rays of the reads that claimed it (rule 1): Huber-weighted
 * Gauss-Newton on each ray's angle (k = [PIN_HUBER_K], [PIN_IRLS_ITERATIONS] iterations from the last point), σ =
 * [PIN_SIGMA_PX]/fx, so 1/(σ·range)² across a ray and nothing along it, over the newest [PIN_NEW_RAYS] rays and up to
 * [PIN_KEY_RAYS] keyrays, with a prior until a data-only
 * fit passes the core's WIDE or DENSE gate ([RayGate]); the pin is then [verified]. A solve that leaves
 * [PIN_SOLVE_MIN_M]..[PIN_SOLVE_MAX_M] or lands behind a ray is not taken. [PIN_REINIT_BAD] bad claims in a row restart
 * it from their rays (rule 4); [moveBy] carries it to a new anchor (rule 8). Rays are origin and unit direction.
 */
class PinEstimator {
    // The newest rays, a ring from `first`, and the keyrays (one spare slot while a new one is weighed): ox, oy, oz, dx, dy, dz
    private val rays = DoubleArray(PIN_NEW_RAYS * 6)
    private var first = 0
    private val keys = DoubleArray((PIN_KEY_RAYS + 1) * 6)
    private val bad = DoubleArray(PIN_REINIT_BAD * 6)

    /** The newest rays kept, and the keyrays */
    var rayCount = 0
        private set
    var keyCount = 0
        private set

    /** Rule 4: the bad claims in a row so far */
    var badInRow = 0
        private set

    private val prior = DoubleArray(3)
    private val info = DoubleArray(9)
    private var priorOn = false

    var priorSource = PriorSource.NONE
        private set

    private val point = DoubleArray(3)
    val x: Double get() = point[0]
    val y: Double get() = point[1]
    val z: Double get() = point[2]

    /** Whether a data-only fit passed the core's gate: the prior is dropped (rule 1) */
    var verified = false
        private set

    /**
     * The label's width, metres, at the first verification: the newest read's width in pixels there · range / fx (0
     * until then, or with no width). Kept through a re-init: the size guard ([PIN_SIZE_RATIO]) holds the depth to it.
     */
    var widthM = 0.0
        private set
    private var newestWidthPx = 0.0

    /** The point's σ along the newest ray, metres, from the last solve's normal equations; NaN before one */
    var sigmaZ = Double.NaN
        private set

    /** Whether a prior was set ([start]): until then nothing is solved */
    val started: Boolean get() = priorSource != PriorSource.NONE

    /** Counts every change of the point, for the pin's cached position */
    var version = 0
        private set

    private var sigma = PIN_SIGMA_PX / 2896.0
    private var sinceGate = 0
    private var baseline = 0.0
    private var baselineAtGate = 0.0

    // Scratch: every ray kept, the normal equations and their inverse, the rotation of a move
    private val all = DoubleArray((PIN_NEW_RAYS + PIN_KEY_RAYS) * 6)
    private val a = DoubleArray(9)
    private val b = DoubleArray(3)
    private val inv = DoubleArray(9)
    private val rot = DoubleArray(9)
    private val tmp = DoubleArray(9)
    private val gate = RayGate(PIN_NEW_RAYS + PIN_KEY_RAYS)

    /**
     * Starts over from a prior (rule 1): the point ([px], [py], [pz]) on a ray along ([dx], [dy], [dz]) at [range]
     * metres, [sigmaAlongM] along it and the ray noise across it, the noise being [PIN_SIGMA_PX] at [fx]. Every ray goes.
     */
    fun start(px: Double, py: Double, pz: Double, dx: Double, dy: Double, dz: Double, range: Double, sigmaAlongM: Double, fx: Double, source: PriorSource) {
        sigma = PIN_SIGMA_PX / fx
        prior[0] = px
        prior[1] = py
        prior[2] = pz
        // Its information: 1/across² across the ray, 1/σ² along it: ia·I + (il − ia)·ddᵀ
        val across = sigma * max(range, MIN_RANGE_M)
        val ia = 1.0 / (across * across)
        val il = 1.0 / (sigmaAlongM * sigmaAlongM)
        val k = il - ia
        info[0] = ia + k * dx * dx
        info[1] = k * dx * dy
        info[2] = k * dx * dz
        info[3] = info[1]
        info[4] = ia + k * dy * dy
        info[5] = k * dy * dz
        info[6] = info[2]
        info[7] = info[5]
        info[8] = ia + k * dz * dz
        priorOn = true
        priorSource = source
        point[0] = px
        point[1] = py
        point[2] = pz
        first = 0
        rayCount = 0
        keyCount = 0
        badInRow = 0
        verified = false
        sigmaZ = Double.NaN
        sinceGate = 0
        baseline = 0.0
        baselineAtGate = 0.0
        version++
    }

    /** A good claim's ray (rule 1), its read [widthPx] wide (0 unknown): kept, and the point solved again; a bad run ends */
    fun add(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, widthPx: Double = 0.0) {
        newestWidthPx = widthPx
        badInRow = 0
        push(ox, oy, oz, dx, dy, dz)
        solve()
    }

    /**
     * A counted bad claim's ray (rule 4): held, not used; the [PIN_REINIT_BAD]th in a row restarts the estimate from the
     * run's rays, with a prior at the current depth on the newest one ([PIN_REINIT_SIGMA_M]), no easing. True when it did.
     */
    fun bad(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, widthPx: Double = 0.0): Boolean {
        newestWidthPx = widthPx
        write(bad, badInRow, ox, oy, oz, dx, dy, dz)
        if (++badInRow < PIN_REINIT_BAD) return false
        val depth = max((point[0] - ox) * dx + (point[1] - oy) * dy + (point[2] - oz) * dz, PIN_MIN_RANGE_M)
        start(ox + dx * depth, oy + dy * depth, oz + dz * depth, dx, dy, dz, depth, PIN_REINIT_SIGMA_M, PIN_SIGMA_PX / sigma, PriorSource.REINIT)
        for (i in 0 until PIN_REINIT_BAD) {
            val o = 6 * i
            push(bad[o], bad[o + 1], bad[o + 2], bad[o + 3], bad[o + 4], bad[o + 5])
        }
        solve()
        return true
    }

    /** The bad run ends without a claim (the rules changed) */
    fun clearBad() {
        badInRow = 0
    }

    /**
     * Rule 8: the rays, the prior and the point carried by [t] into a new anchor's frame (t maps the old frame's points
     * to the new one's: A_new⁻¹·A_old)
     */
    fun moveBy(t: Pose) {
        rotationOf(t, rot)
        val tx = t.t.x
        val ty = t.t.y
        val tz = t.t.z
        for (i in 0 until rayCount) moveRay(rays, 6 * ((first + i) % PIN_NEW_RAYS), tx, ty, tz)
        for (i in 0 until keyCount) moveRay(keys, 6 * i, tx, ty, tz)
        for (i in 0 until badInRow) moveRay(bad, 6 * i, tx, ty, tz)
        movePoint(prior, 0, tx, ty, tz)
        movePoint(point, 0, tx, ty, tz)
        // The prior's information turns with the frame: R · info · Rᵀ
        for (r in 0 until 3) for (c in 0 until 3) tmp[3 * r + c] = rot[3 * r] * info[c] + rot[3 * r + 1] * info[3 + c] + rot[3 * r + 2] * info[6 + c]
        for (r in 0 until 3) for (c in 0 until 3) info[3 * r + c] = tmp[3 * r] * rot[3 * c] + tmp[3 * r + 1] * rot[3 * c + 1] + tmp[3 * r + 2] * rot[3 * c + 2]
        version++
    }

    /**
     * Rule 6: [from]'s newest [n] rays, carried into this frame by [t] (from's frame to this one), join these, and the
     * point is solved again
     */
    fun absorbNewest(from: PinEstimator, n: Int, t: Pose) {
        if (!started) return
        rotationOf(t, rot)
        val k = minOf(n, from.rayCount)
        for (i in from.rayCount - k until from.rayCount) {
            val o = 6 * ((from.first + i) % PIN_NEW_RAYS)
            val r = from.rays
            val ox = rot[0] * r[o] + rot[1] * r[o + 1] + rot[2] * r[o + 2] + t.t.x
            val oy = rot[3] * r[o] + rot[4] * r[o + 1] + rot[5] * r[o + 2] + t.t.y
            val oz = rot[6] * r[o] + rot[7] * r[o + 1] + rot[8] * r[o + 2] + t.t.z
            val dx = rot[0] * r[o + 3] + rot[1] * r[o + 4] + rot[2] * r[o + 5]
            val dy = rot[3] * r[o + 3] + rot[4] * r[o + 4] + rot[5] * r[o + 5]
            val dz = rot[6] * r[o + 3] + rot[7] * r[o + 4] + rot[8] * r[o + 5]
            push(ox, oy, oz, dx, dy, dz)
        }
        if (k > 0) solve()
    }

    /** A ray joins the newest; the oldest of them, when full, is weighed as a keyray. The baseline seen grows. */
    private fun push(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double) {
        if (rayCount == PIN_NEW_RAYS) {
            offerKey(6 * first)
            first = (first + 1) % PIN_NEW_RAYS
            rayCount--
        }
        for (i in 0 until rayCount) baseline = max(baseline, distanceTo(rays, 6 * ((first + i) % PIN_NEW_RAYS), ox, oy, oz))
        for (i in 0 until keyCount) baseline = max(baseline, distanceTo(keys, 6 * i, ox, oy, oz))
        write(rays, (first + rayCount) % PIN_NEW_RAYS, ox, oy, oz, dx, dy, dz)
        rayCount++
        sinceGate++
    }

    /**
     * The ray at [from] in [rays] leaves the newest: kept as a keyray when its origin lies more than [PIN_KEY_MIN_M]
     * from every keyray's; past [PIN_KEY_RAYS], the keyray nearest another goes
     */
    private fun offerKey(from: Int) {
        for (i in 0 until keyCount) if (distanceTo(keys, 6 * i, rays[from], rays[from + 1], rays[from + 2]) <= PIN_KEY_MIN_M) return
        System.arraycopy(rays, from, keys, 6 * keyCount, 6)
        if (++keyCount <= PIN_KEY_RAYS) return
        var drop = 0
        var nearest = Double.MAX_VALUE
        for (i in 0 until keyCount) {
            for (j in 0 until keyCount) {
                if (i == j) continue
                val d = distanceTo(keys, 6 * i, keys[6 * j], keys[6 * j + 1], keys[6 * j + 2])
                if (d < nearest) {
                    nearest = d
                    drop = i
                }
            }
        }
        System.arraycopy(keys, 6 * (drop + 1), keys, 6 * drop, 6 * (keyCount - drop - 1))
        keyCount--
    }

    /**
     * Rule 1: Huber-weighted Gauss-Newton on each ray's angular residual, from the last point, with the prior while it
     * holds; the gate test when due ([PIN_GATE_CLAIMS], [PIN_GATE_BASELINE_M]); then the guard. Across a ray this is the
     * plan's 1/(σ·range)² weighting; along it a ray holds nothing, so rays from one spot (a phone that only turns) leave
     * the depth to the prior instead of pulling the point toward the camera, as a point-to-line fit's would.
     */
    private fun solve() {
        val m = gather()
        var x0 = point[0]
        var x1 = point[1]
        var x2 = point[2]
        var solved = false
        for (iteration in 0 until PIN_IRLS_ITERATIONS) {
            // The normal equations [a]·step = [b]: the prior's information, and its gradient info·(x − prior)
            if (priorOn) {
                System.arraycopy(info, 0, a, 0, 9)
                val px = x0 - prior[0]
                val py = x1 - prior[1]
                val pz = x2 - prior[2]
                b[0] = info[0] * px + info[1] * py + info[2] * pz
                b[1] = info[3] * px + info[4] * py + info[5] * pz
                b[2] = info[6] * px + info[7] * py + info[8] * pz
            } else {
                a.fill(0.0)
                b.fill(0.0)
            }
            for (i in 0 until m) accumulateAngle(a, b, all, 6 * i, x0, x1, x2, sigma)
            if (!inverse3(a, inv)) break
            x0 -= inv[0] * b[0] + inv[1] * b[1] + inv[2] * b[2]
            x1 -= inv[3] * b[0] + inv[4] * b[1] + inv[5] * b[2]
            x2 -= inv[6] * b[0] + inv[7] * b[1] + inv[8] * b[2]
            solved = true
        }
        if (solved && m > 0) {
            // σ along the newest ray: dᵀ · A⁻¹ · d
            val o = 6 * (m - 1)
            val dx = all[o + 3]
            val dy = all[o + 4]
            val dz = all[o + 5]
            sigmaZ = sqrt(max(dx * (inv[0] * dx + inv[1] * dy + inv[2] * dz) + dy * (inv[3] * dx + inv[4] * dy + inv[5] * dz) + dz * (inv[6] * dx + inv[7] * dy + inv[8] * dz), 0.0))
        }
        if (priorOn && m >= GATE_MIN_RAYS && (sinceGate >= PIN_GATE_CLAIMS || baseline >= baselineAtGate + PIN_GATE_BASELINE_M)) {
            sinceGate = 0
            baselineAtGate = baseline
            if (gate.fit(all, m, sigma) != null) {
                priorOn = false
                verified = true
                x0 = gate.x[0]
                x1 = gate.x[1]
                x2 = gate.x[2]
                if (widthM == 0.0 && newestWidthPx > 0.0) widthM = newestWidthPx * rangeOnNewest(m, x0, x1, x2) * sigma / PIN_SIGMA_PX
            }
        }
        if (m > 0 && !acceptable(m, x0, x1, x2)) return
        point[0] = x0
        point[1] = x1
        point[2] = x2
        version++
    }

    /** Rule 1's guard: [m] rays' mean range from (x0, x1, x2) within [PIN_SOLVE_MIN_M]..[PIN_SOLVE_MAX_M], and in front of each */
    private fun acceptable(m: Int, x0: Double, x1: Double, x2: Double): Boolean {
        if (!(x0.isFinite() && x1.isFinite() && x2.isFinite())) return false
        var sum = 0.0
        for (i in 0 until m) {
            val o = 6 * i
            val vx = x0 - all[o]
            val vy = x1 - all[o + 1]
            val vz = x2 - all[o + 2]
            if (vx * all[o + 3] + vy * all[o + 4] + vz * all[o + 5] <= 0.0) return false
            sum += sqrt(vx * vx + vy * vy + vz * vz)
        }
        val range = sum / m
        if (range < PIN_SOLVE_MIN_M || range > PIN_SOLVE_MAX_M) return false
        if (widthM <= 0.0 || newestWidthPx <= 0.0) return true
        // The size guard: fx · widthM / px, fx being PIN_SIGMA_PX / sigma
        val sized = PIN_SIGMA_PX / sigma * widthM / newestWidthPx
        val newest = rangeOnNewest(m, x0, x1, x2)
        return newest <= sized * PIN_SIZE_RATIO && newest >= sized / PIN_SIZE_RATIO
    }

    /** The distance from the newest of [m] gathered rays' origin to (x0, x1, x2) */
    private fun rangeOnNewest(m: Int, x0: Double, x1: Double, x2: Double): Double {
        val o = 6 * (m - 1)
        return sqrt(sq(x0 - all[o]) + sq(x1 - all[o + 1]) + sq(x2 - all[o + 2]))
    }

    /** The keyrays then the newest rays, oldest first, into [all]; their count */
    private fun gather(): Int {
        System.arraycopy(keys, 0, all, 0, 6 * keyCount)
        for (i in 0 until rayCount) System.arraycopy(rays, 6 * ((first + i) % PIN_NEW_RAYS), all, 6 * (keyCount + i), 6)
        return keyCount + rayCount
    }

    private fun moveRay(r: DoubleArray, o: Int, tx: Double, ty: Double, tz: Double) {
        movePoint(r, o, tx, ty, tz)
        val dx = r[o + 3]
        val dy = r[o + 4]
        val dz = r[o + 5]
        r[o + 3] = rot[0] * dx + rot[1] * dy + rot[2] * dz
        r[o + 4] = rot[3] * dx + rot[4] * dy + rot[5] * dz
        r[o + 5] = rot[6] * dx + rot[7] * dy + rot[8] * dz
    }

    private fun movePoint(p: DoubleArray, o: Int, tx: Double, ty: Double, tz: Double) {
        val x = p[o]
        val y = p[o + 1]
        val z = p[o + 2]
        p[o] = rot[0] * x + rot[1] * y + rot[2] * z + tx
        p[o + 1] = rot[3] * x + rot[4] * y + rot[5] * z + ty
        p[o + 2] = rot[6] * x + rot[7] * y + rot[8] * z + tz
    }

    private companion object {
        /** The gate test needs as many rays as the WIDE gate's inliers */
        val GATE_MIN_RAYS = CountConfig().wideMinInliers
    }
}

/**
 * Triangulation.fit (Depth.kt) on [count] rays of weight 1 (ox, oy, oz, dx, dy, dz each in the array given), without
 * allocating: least squares with the ray of the largest residual dropped while it exceeds [CountConfig.rejectSigmas] σ,
 * then spec 5.4's WIDE or DENSE gate within the depth range. [x] and [sigmaZ] are the fit's.
 */
internal class RayGate(capacity: Int, private val config: CountConfig = CountConfig()) {
    val x = DoubleArray(3)

    var sigmaZ = Double.NaN
        private set

    private val inlier = BooleanArray(capacity)
    private val a = DoubleArray(9)
    private val b = DoubleArray(3)
    private val cov = DoubleArray(9)
    private val x0 = DoubleArray(3)
    private var n = 0
    private var sigma = 0.0

    /** The gate [count] rays of [rays] pass at ray noise [sigmaRad]; null for none (or a degenerate fit) */
    fun fit(rays: DoubleArray, count: Int, sigmaRad: Double): DepthGate? {
        sigma = sigmaRad
        sigmaZ = Double.NaN
        for (i in 0 until count) inlier[i] = true
        n = count
        if (!leastSquares(rays, count)) return null
        while (n > 2) {
            var worst = -1
            var worstR = -1.0
            for (i in 0 until count) {
                if (!inlier[i]) continue
                val r = angle(rays, 6 * i, x[0], x[1], x[2])
                if (r > worstR) {
                    worstR = r
                    worst = i
                }
            }
            if (worstR <= config.rejectSigmas * sigma) break
            inlier[worst] = false
            n--
            if (!leastSquares(rays, count)) return null
        }
        var ss = 0.0
        var range = 0.0
        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        var last = -1
        var inFront = true
        for (i in 0 until count) {
            if (!inlier[i]) continue
            val o = 6 * i
            val r = angle(rays, o, x[0], x[1], x[2])
            ss += r * r
            val vx = x[0] - rays[o]
            val vy = x[1] - rays[o + 1]
            val vz = x[2] - rays[o + 2]
            range += sqrt(vx * vx + vy * vy + vz * vz)
            if (rays[o + 3] * vx + rays[o + 4] * vy + rays[o + 5] * vz <= 0.0) inFront = false
            mx += rays[o]
            my += rays[o + 1]
            mz += rays[o + 2]
            last = i
        }
        val dof = 2 * n - 3
        val s2 = if (dof > 0) ss / (sigma * sigma) / dof else 1.0
        val scale = if (s2 > 1.0) s2 else 1.0
        range /= n
        var vx = x[0] - mx / n
        var vy = x[1] - my / n
        var vz = x[2] - mz / n
        val vn = sqrt(vx * vx + vy * vy + vz * vz)
        if (vn > 1e-9) {
            vx /= vn
            vy /= vn
            vz /= vn
        } else {
            vx = rays[6 * last + 3]
            vy = rays[6 * last + 4]
            vz = rays[6 * last + 5]
        }
        val q = vx * (cov[0] * vx + cov[1] * vy + cov[2] * vz) + vy * (cov[3] * vx + cov[4] * vy + cov[5] * vz) + vz * (cov[6] * vx + cov[7] * vy + cov[8] * vz)
        sigmaZ = sqrt(max(q * scale, 0.0))
        var span = 0.0
        var baseline = 0.0
        for (i in 0 until count) {
            if (!inlier[i]) continue
            for (j in i + 1 until count) {
                if (!inlier[j]) continue
                span = max(span, angleBetween(rays, 6 * i, 6 * j))
                baseline = max(baseline, distanceTo(rays, 6 * i, rays[6 * j], rays[6 * j + 1], rays[6 * j + 2]))
            }
        }
        val rms = sqrt(ss / n)
        return when {
            !inFront || range < config.minDepth || range > config.maxDepth -> null
            rms > config.maxRmsResidualSigmas * sigma -> null
            n >= config.wideMinInliers && span >= rad(config.wideSpanDeg) &&
                baseline >= 2 * range * tan(rad(config.wideBaselineHalfAngleDeg)) && sigmaZ <= config.wideMaxSigmaZ -> DepthGate.WIDE
            n >= config.denseMinInliers && span >= rad(config.denseSpanDeg) && sigmaZ <= config.denseMaxSigmaZ -> DepthGate.DENSE
            else -> null
        }
    }

    /** Triangulation.leastSquares over the inliers: unweighted, then weighted by 1/(σ·range)² from that first point */
    private fun leastSquares(rays: DoubleArray, count: Int): Boolean {
        if (n < 2) return false
        if (!normal(rays, count, false)) return false
        x.copyInto(x0)
        return normal(rays, count, true)
    }

    private fun normal(rays: DoubleArray, count: Int, weighted: Boolean): Boolean {
        a.fill(0.0)
        b.fill(0.0)
        for (i in 0 until count) {
            if (!inlier[i]) continue
            val o = 6 * i
            val w = if (weighted) 1.0 / sq(sigma * max(distanceTo(rays, o, x0[0], x0[1], x0[2]), 1e-3)) else 1.0
            accumulate(a, b, rays, o, w)
        }
        if (!inverse3(a, cov)) return false
        x[0] = cov[0] * b[0] + cov[1] * b[1] + cov[2] * b[2]
        x[1] = cov[3] * b[0] + cov[4] * b[1] + cov[5] * b[2]
        x[2] = cov[6] * b[0] + cov[7] * b[1] + cov[8] * b[2]
        return true
    }

    private fun rad(deg: Double) = deg * PI / 180
}

/**
 * A pin's anchor pose on its newest frames, from its birth (rule 9): a claim is taken into the anchor's frame as it stood
 * at the claim's capture, and one captured before the oldest pose kept (the birth rays, early claims delayed by the 4K
 * lag) as at the oldest. Poses as tx, ty, tz, qx, qy, qz, qw; allocation-free but for [pose].
 */
class AnchorRing(private val size: Int = PIN_ANCHOR_FRAMES) {
    private val ts = LongArray(size)
    private val poses = DoubleArray(size * 7)
    private var oldest = 0

    var count = 0
        private set

    /** Counts every change, for the pin's cached position */
    var version = 0
        private set

    /** The slot of the newest pose */
    val newest: Int get() = (oldest + count - 1) % size

    fun add(timestampNs: Long, pose: Pose) = add(timestampNs, pose.t.x, pose.t.y, pose.t.z, pose.q.x, pose.q.y, pose.q.z, pose.q.w)

    /** The anchor's pose on the frame at [timestampNs] (after every pose kept) */
    fun add(timestampNs: Long, tx: Double, ty: Double, tz: Double, qx: Double, qy: Double, qz: Double, qw: Double) {
        val s = if (count < size) (oldest + count++) % size else oldest.also { oldest = (oldest + 1) % size }
        ts[s] = timestampNs
        val o = 7 * s
        poses[o] = tx
        poses[o + 1] = ty
        poses[o + 2] = tz
        poses[o + 3] = qx
        poses[o + 4] = qy
        poses[o + 5] = qz
        poses[o + 6] = qw
        version++
    }

    /** The slot for a capture at [timestampNs]: its frame's, else the newest before it, else the oldest kept */
    fun slotAt(timestampNs: Long): Int {
        for (i in count - 1 downTo 0) {
            val s = (oldest + i) % size
            if (ts[s] <= timestampNs) return s
        }
        return oldest
    }

    fun timestampAt(slot: Int): Long = ts[slot]

    fun pose(slot: Int): Pose {
        val o = 7 * slot
        return Pose(Vec3(poses[o], poses[o + 1], poses[o + 2]), Quat(poses[o + 3], poses[o + 4], poses[o + 5], poses[o + 6]))
    }

    /** [slot]'s pose applied to the point ([x], [y], [z]) of the anchor's frame, into [out] */
    fun apply(slot: Int, x: Double, y: Double, z: Double, out: DoubleArray) {
        val o = 7 * slot
        rotate(poses[o + 3], poses[o + 4], poses[o + 5], poses[o + 6], x, y, z, out, 0)
        out[0] += poses[o]
        out[1] += poses[o + 1]
        out[2] += poses[o + 2]
    }

    /** World [ray] in the anchor's frame of [slot]: origin and direction into [out] (6) */
    fun toFrame(slot: Int, ray: Ray, out: DoubleArray) {
        val o = 7 * slot
        val qx = -poses[o + 3]
        val qy = -poses[o + 4]
        val qz = -poses[o + 5]
        val qw = poses[o + 6]
        rotate(qx, qy, qz, qw, ray.origin.x - poses[o], ray.origin.y - poses[o + 1], ray.origin.z - poses[o + 2], out, 0)
        rotate(qx, qy, qz, qw, ray.dir.x, ray.dir.y, ray.dir.z, out, 3)
    }

    /** How far the anchor moved between the two newest poses, metres; NaN with one */
    fun lastStep(): Double {
        if (count < 2) return Double.NaN
        val a = 7 * newest
        val b = 7 * ((oldest + count - 2) % size)
        return sqrt(sq(poses[a] - poses[b]) + sq(poses[a + 1] - poses[b + 1]) + sq(poses[a + 2] - poses[b + 2]))
    }

    /** Every pose p becomes p · [by] (rule 8: by = A_old⁻¹·A_new, so each frame's pose is the new anchor's there) */
    fun compose(by: Pose) {
        for (i in 0 until count) {
            val o = 7 * ((oldest + i) % size)
            val ax = poses[o + 3]
            val ay = poses[o + 4]
            val az = poses[o + 5]
            val aw = poses[o + 6]
            val bx = by.q.x
            val by_ = by.q.y
            val bz = by.q.z
            val bw = by.q.w
            val tx = poses[o]
            val ty = poses[o + 1]
            val tz = poses[o + 2]
            rotate(ax, ay, az, aw, by.t.x, by.t.y, by.t.z, poses, o)
            poses[o] += tx
            poses[o + 1] += ty
            poses[o + 2] += tz
            poses[o + 3] = aw * bx + ax * bw + ay * bz - az * by_
            poses[o + 4] = aw * by_ - ax * bz + ay * bw + az * bx
            poses[o + 5] = aw * bz + ax * by_ - ay * bx + az * bw
            poses[o + 6] = aw * bw - ax * bx - ay * by_ - az * bz
        }
        version++
    }
}

/** (vx, vy, vz) turned by the unit quaternion (qx, qy, qz, qw) into [out] from [o], as [io.packagex.arcount.Quat.rotate] */
internal fun rotate(qx: Double, qy: Double, qz: Double, qw: Double, vx: Double, vy: Double, vz: Double, out: DoubleArray, o: Int) {
    val tx = 2 * (qy * vz - qz * vy)
    val ty = 2 * (qz * vx - qx * vz)
    val tz = 2 * (qx * vy - qy * vx)
    out[o] = vx + qw * tx + (qy * tz - qz * ty)
    out[o + 1] = vy + qw * ty + (qz * tx - qx * tz)
    out[o + 2] = vz + qw * tz + (qx * ty - qy * tx)
}

/** [t]'s rotation as a row-major 3×3 matrix, into [out] */
private fun rotationOf(t: Pose, out: DoubleArray) {
    val (x, y, z, w) = t.q
    out[0] = 1 - 2 * (y * y + z * z)
    out[1] = 2 * (x * y - z * w)
    out[2] = 2 * (x * z + y * w)
    out[3] = 2 * (x * y + z * w)
    out[4] = 1 - 2 * (x * x + z * z)
    out[5] = 2 * (y * z - x * w)
    out[6] = 2 * (x * z - y * w)
    out[7] = 2 * (y * z + x * w)
    out[8] = 1 - 2 * (x * x + y * y)
}

/**
 * One Gauss-Newton term of the ray at [o] in [r] (origin, unit d) at the point (x0, x1, x2), noise [sigma] radians: its
 * residual e = P·u, with u the unit vector from the origin to the point, P = I − ddᵀ, and range ρ (at least
 * [MIN_RANGE_M]); its Jacobian J = P·(I − uuᵀ)/ρ, which has nothing along u. With c = u·d and w = d − c·u,
 * JᵀJ = (I − uuᵀ − wwᵀ)/ρ² and Jᵀe = −c·w/ρ. Huber-weighted (k = [PIN_HUBER_K]) on the ray's angle: [a] += h·JᵀJ/σ²,
 * [g] += h·Jᵀe/σ². A point on the ray's origin adds nothing.
 */
private fun accumulateAngle(a: DoubleArray, g: DoubleArray, r: DoubleArray, o: Int, x0: Double, x1: Double, x2: Double, sigma: Double) {
    val vx = x0 - r[o]
    val vy = x1 - r[o + 1]
    val vz = x2 - r[o + 2]
    val n = sqrt(vx * vx + vy * vy + vz * vz)
    if (n < 1e-9) return
    val ux = vx / n
    val uy = vy / n
    val uz = vz / n
    val dx = r[o + 3]
    val dy = r[o + 4]
    val dz = r[o + 5]
    val c = ux * dx + uy * dy + uz * dz
    val wx = dx - c * ux
    val wy = dy - c * uy
    val wz = dz - c * uz
    val z = angle(r, o, x0, x1, x2) / sigma
    val h = (if (z <= PIN_HUBER_K) 1.0 else PIN_HUBER_K / z) / (sigma * sigma)
    val range = max(n, MIN_RANGE_M)
    val s = h / (range * range)
    a[0] += s * (1 - ux * ux - wx * wx)
    a[1] += s * (-ux * uy - wx * wy)
    a[2] += s * (-ux * uz - wx * wz)
    a[3] += s * (-uy * ux - wy * wx)
    a[4] += s * (1 - uy * uy - wy * wy)
    a[5] += s * (-uy * uz - wy * wz)
    a[6] += s * (-uz * ux - wz * wx)
    a[7] += s * (-uz * uy - wz * wy)
    a[8] += s * (1 - uz * uz - wz * wz)
    val t = -h * c / range
    g[0] += t * wx
    g[1] += t * wy
    g[2] += t * wz
}

/** A ray's normal equations, weight [w]: [a] += w·(I − ddᵀ), [b] += w·(I − ddᵀ)·o, for the ray at [o] in [r] */
private fun accumulate(a: DoubleArray, b: DoubleArray, r: DoubleArray, o: Int, w: Double) {
    val dx = r[o + 3]
    val dy = r[o + 4]
    val dz = r[o + 5]
    val p0 = 1 - dx * dx
    val p1 = -dx * dy
    val p2 = -dx * dz
    val p4 = 1 - dy * dy
    val p5 = -dy * dz
    val p8 = 1 - dz * dz
    a[0] += w * p0
    a[1] += w * p1
    a[2] += w * p2
    a[3] += w * p1
    a[4] += w * p4
    a[5] += w * p5
    a[6] += w * p2
    a[7] += w * p5
    a[8] += w * p8
    val ox = r[o]
    val oy = r[o + 1]
    val oz = r[o + 2]
    b[0] += w * (p0 * ox + p1 * oy + p2 * oz)
    b[1] += w * (p1 * ox + p4 * oy + p5 * oz)
    b[2] += w * (p2 * ox + p5 * oy + p8 * oz)
}

/** [m]'s inverse into [out] (row-major 3×3), as [io.packagex.arcount.Mat3.inverse]; false when singular to working precision */
internal fun inverse3(m: DoubleArray, out: DoubleArray): Boolean {
    val d = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
    var scale = 0.0
    for (i in 0 until 9) scale = max(scale, abs(m[i]))
    if (scale == 0.0 || !d.isFinite() || abs(d) <= 1e-14 * scale * scale * scale) return false
    out[0] = (m[4] * m[8] - m[5] * m[7]) / d
    out[1] = (m[2] * m[7] - m[1] * m[8]) / d
    out[2] = (m[1] * m[5] - m[2] * m[4]) / d
    out[3] = (m[5] * m[6] - m[3] * m[8]) / d
    out[4] = (m[0] * m[8] - m[2] * m[6]) / d
    out[5] = (m[2] * m[3] - m[0] * m[5]) / d
    out[6] = (m[3] * m[7] - m[4] * m[6]) / d
    out[7] = (m[1] * m[6] - m[0] * m[7]) / d
    out[8] = (m[0] * m[4] - m[1] * m[3]) / d
    return true
}

/** The angle between the ray at [o] in [r] and the direction from its origin to (x, y, z), as Triangulation.residual */
private fun angle(r: DoubleArray, o: Int, x: Double, y: Double, z: Double): Double {
    val vx = x - r[o]
    val vy = y - r[o + 1]
    val vz = z - r[o + 2]
    val dx = r[o + 3]
    val dy = r[o + 4]
    val dz = r[o + 5]
    val cx = dy * vz - dz * vy
    val cy = dz * vx - dx * vz
    val cz = dx * vy - dy * vx
    return atan2(sqrt(cx * cx + cy * cy + cz * cz), dx * vx + dy * vy + dz * vz)
}

/** The angle between the directions of the rays at [i] and [j] in [r] */
private fun angleBetween(r: DoubleArray, i: Int, j: Int): Double {
    val ax = r[i + 3]
    val ay = r[i + 4]
    val az = r[i + 5]
    val bx = r[j + 3]
    val by = r[j + 4]
    val bz = r[j + 5]
    val cx = ay * bz - az * by
    val cy = az * bx - ax * bz
    val cz = ax * by - ay * bx
    return atan2(sqrt(cx * cx + cy * cy + cz * cz), ax * bx + ay * by + az * bz)
}

/** The distance from the origin of the ray at [o] in [r] to (x, y, z) */
private fun distanceTo(r: DoubleArray, o: Int, x: Double, y: Double, z: Double) = sqrt(sq(r[o] - x) + sq(r[o + 1] - y) + sq(r[o + 2] - z))

private fun write(r: DoubleArray, slot: Int, ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double) {
    val o = 6 * slot
    r[o] = ox
    r[o + 1] = oy
    r[o + 2] = oz
    r[o + 3] = dx
    r[o + 4] = dy
    r[o + 5] = dz
}

private fun sq(v: Double) = v * v
