package io.packagex.arcount

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** The motion cues of spec 5.4 and 5.6: predicted blur ("Slow down") and a missing depth ("Slide a little") */
object Blur {
    /** The camera's rotation rate in rad/s between two poses [dtS] seconds apart */
    fun rotationRate(a: Pose, b: Pose, dtS: Double): Double {
        val r = b.q * a.q.conj()
        return 2 * atan2(sqrt(r.x * r.x + r.y * r.y + r.z * r.z), abs(r.w)) / dtS
    }

    /** The camera centre's speed in m/s between two poses [dtS] seconds apart */
    fun speed(a: Pose, b: Pose, dtS: Double) = (b.t - a.t).norm() / dtS

    /** Predicted blur in modules: (ω · t · f + v · t · f / z) / module_px */
    fun modules(omega: Double, speed: Double, exposureS: Double, f: Double, z: Double, modulePx: Double) =
        (omega * exposureS * f + speed * exposureS * f / z) / modulePx

    /** Blur predicted for [cur] from its motion since [prev]; null without an exposure or a time step */
    fun of(prev: PoseRecord, cur: PoseRecord, z: Double, modulePx: Double): Double? {
        val dt = (cur.timestampNs - prev.timestampNs) / 1e9
        if (cur.exposureNs <= 0 || dt <= 0 || z <= 0 || modulePx <= 0) return null
        return modules(rotationRate(prev.camera, cur.camera, dt), speed(prev.camera, cur.camera, dt), cur.exposureNs / 1e9, cur.intrinsics.fx, z, modulePx)
    }

    /** "Slide a little": one of [inView] (units predicted in the image) has had no depth for [CountConfig.noDepthPromptNs] */
    fun slideALittle(inView: List<CountUnit>, nowNs: Long, config: CountConfig) =
        inView.any { it.state != UnitState.MANUAL && !it.hasDepth && nowNs - it.createdNs >= config.noDepthPromptNs }
}

/** "Slow down" once predicted blur has stayed above [CountConfig.blurMaxModules] for more than [CountConfig.blurHoldNs] */
class BlurWatch(private val config: CountConfig) {
    private var aboveSince: Long? = null

    /** Feeds the frame at [nowNs] with its predicted blur (null: none known); returns whether "Slow down" shows */
    fun update(nowNs: Long, modules: Double?): Boolean {
        if (modules == null || modules <= config.blurMaxModules) {
            aboveSince = null
            return false
        }
        val since = aboveSince ?: nowNs.also { aboveSince = it }
        return nowNs - since > config.blurHoldNs
    }
}
