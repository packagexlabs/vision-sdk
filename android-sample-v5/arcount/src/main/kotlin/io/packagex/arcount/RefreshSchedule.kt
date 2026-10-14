package io.packagex.arcount

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

/**
 * The camera's motion between consecutive pose records (spec 5.3, 5.9): it moves while its centre is faster than
 * [CountConfig.stopSpeed] or it turns faster than [CountConfig.stopRotationDegPerS]; it settles on the
 * [CountConfig.stillFramesToSettle]th frame below both after it moved.
 */
class Motion(private val config: CountConfig) {
    private var prev: PoseRecord? = null
    private var wasMoving = false
    private var still = 0

    /** Whether the camera moved into the latest frame */
    var moving = false
        private set

    /** Whether the latest frame is the one the camera settled on */
    var settled = false
        private set

    fun update(r: PoseRecord) {
        val p = prev
        prev = r
        settled = false
        val dt = if (p == null) 0.0 else (r.timestampNs - p.timestampNs) / 1e9
        if (p == null || dt <= 0) return
        moving = Blur.speed(p.camera, r.camera, dt) > config.stopSpeed ||
            Blur.rotationRate(p.camera, r.camera, dt) > config.stopRotationDegPerS * PI / 180
        if (moving) {
            wasMoving = true
            still = 0
            return
        }
        still++
        if (wasMoving && still >= config.stillFramesToSettle) {
            settled = true
            wasMoving = false
        }
    }
}

/**
 * The engine's refresh for its next frames (spec 5.3, refresh schedule): 0 until the tracker is fed and the open
 * section has a unit, and for [CountConfig.burstFrames] engine frames after the camera settles or a read creates a
 * unit; [CountConfig.refreshMovingMs] while the camera moves; when still, that value until
 * [CountConfig.backoffAfterNs] has passed without a new unit or a settle, then ×[CountConfig.backoffFactor] for every
 * second begun since, up to [CountConfig.refreshMaxMs].
 */
class RefreshSchedule(private val config: CountConfig) {
    val motion = Motion(config)
    private var burstLeft = 0
    private var noveltyNs = Long.MIN_VALUE
    private var table: UnitTable? = null
    private var newestUnit = 0

    /** Every pose record, in order */
    fun onFrame(r: PoseRecord) {
        motion.update(r)
        if (motion.settled) burst(r.timestampNs)
    }

    /** Every frame the engine finished (an onReads call), before its reads are associated */
    fun onEngineFrame() {
        if (burstLeft > 0) burstLeft--
    }

    /** The open section's units after an association: a unit a read created starts a burst */
    fun onUnits(t: UnitTable?, nowNs: Long) {
        if (t !== table) {
            table = t
            newestUnit = 0
            burstLeft = 0
            noveltyNs = Long.MIN_VALUE
        }
        val newest = t?.units?.filter { it.state != UnitState.MANUAL }?.maxOfOrNull { it.id } ?: return
        if (newest <= newestUnit) return
        newestUnit = newest
        burst(nowNs)
    }

    private fun burst(nowNs: Long) {
        burstLeft = config.burstFrames
        noveltyNs = nowNs
    }

    /** The refresh in ms at [nowNs]; [live] when the tracker is fed and a COUNTING section has a unit */
    fun desiredMs(live: Boolean, nowNs: Long): Int {
        if (!live || burstLeft > 0 || noveltyNs == Long.MIN_VALUE) return 0
        if (motion.moving) return config.refreshMovingMs
        val quiet = nowNs - noveltyNs
        if (quiet < config.backoffAfterNs) return config.refreshMovingMs
        val steps = 1 + floor((quiet - config.backoffAfterNs) / 1e9)
        return min(config.refreshMaxMs.toDouble(), config.refreshMovingMs * config.backoffFactor.pow(steps)).toInt()
    }
}
