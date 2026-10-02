package io.packagex.arcount

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** The reads of one frame with the pose of their capture; [interpolated] when ARCore skipped that capture */
data class Paired(val record: PoseRecord, val reads: List<Read>, val interpolated: Boolean = false)

/**
 * Pairs every read with the PoseRecord of its capture (spec 5.2, 5.8). A read pairs with the record whose timestamp
 * equals its own; until that record comes the read waits. Records come in order, so once a later record is here the
 * capture's own record will never come (ARCore skipped it): the read then gets the pose interpolated between its two
 * neighbours, never across a jump or a tracking loss, and only when the later neighbour came within
 * [CountConfig.pairWaitNs] of the capture (record time, not wall time). Anything else is dropped and counted.
 */
class Pairing(private val config: CountConfig = CountConfig()) {
    private val records = ArrayDeque<PoseRecord>()
    private val waiting = ArrayList<Pair<Long, List<Read>>>()

    /** Reads dropped because no pose could be found for them */
    var droppedReads = 0
        private set

    /** Frames paired at once, late (after waiting), and by interpolation */
    var exact = 0
        private set
    var late = 0
        private set
    var interpolated = 0
        private set

    /** The newest record */
    val newest: PoseRecord? get() = records.lastOrNull()

    /** Keeps [record]; returns the waiting reads it settles, oldest first; drops those it shows can never pair */
    fun addRecord(record: PoseRecord): List<Paired> {
        val last = records.lastOrNull()
        if (last != null && record.timestampNs <= last.timestampNs) return emptyList()
        records.addLast(record)
        while (records.first().timestampNs < record.timestampNs - config.poseRingNs) records.removeFirst()
        val out = ArrayList<Paired>()
        val still = ArrayList<Pair<Long, List<Read>>>()
        for (w in waiting.sortedBy { it.first }) {
            when {
                w.first == record.timestampNs -> {
                    late++
                    out += Paired(record, w.second)
                }
                w.first < record.timestampNs -> settleSkipped(w.first, w.second)?.let { out += it }
                else -> still += w
            }
        }
        waiting.clear()
        waiting += still
        return out
    }

    /** The reads of one capture: paired now when their record is here, else null while they wait or when dropped */
    fun addReads(timestampNs: Long, reads: List<Read>): Paired? {
        val newest = records.lastOrNull()
        if (newest == null || timestampNs > newest.timestampNs) {
            waiting += timestampNs to reads
            if (waiting.size > MAX_WAITING) droppedReads += waiting.removeAt(0).second.size
            return null
        }
        records.firstOrNull { it.timestampNs == timestampNs }?.let {
            exact++
            return Paired(it, reads)
        }
        return settleSkipped(timestampNs, reads)
    }

    /** A capture older than the newest record without a record of its own: interpolate, or drop */
    private fun settleSkipped(ts: Long, reads: List<Read>): Paired? {
        val before = records.lastOrNull { it.timestampNs < ts }
        val after = records.firstOrNull { it.timestampNs > ts }
        val pose = if (before != null && after != null && after.timestampNs - ts <= config.pairWaitNs) between(before, after, ts) else null
        if (pose == null) {
            droppedReads += reads.size
            return null
        }
        interpolated++
        return Paired(pose, reads, interpolated = true)
    }

    /** The record at [ts] between [a] and [b]; null across a tracking loss, an anchor change or a jump */
    private fun between(a: PoseRecord, b: PoseRecord, ts: Long): PoseRecord? {
        if (a.frameTracking != Tracking.TRACKING || b.frameTracking != Tracking.TRACKING) return null
        if ((a.anchor == null) != (b.anchor == null)) return null
        if (a.anchor != null && (a.anchorTracking != Tracking.TRACKING || b.anchorTracking != Tracking.TRACKING)) return null
        val stepM = (cameraIn(b).t - cameraIn(a).t).norm()
        val dtS = (b.timestampNs - a.timestampNs) / 1e9
        if (stepM > config.jumpStep || stepM > max(config.jumpSpeed * dtS, config.jumpMinStep)) return null
        val f = (ts - a.timestampNs).toDouble() / (b.timestampNs - a.timestampNs)
        val anchor = if (a.anchor != null && b.anchor != null) lerp(a.anchor, b.anchor, f) else null
        return a.copy(timestampNs = ts, camera = lerp(a.camera, b.camera, f), anchor = anchor)
    }

    private fun cameraIn(r: PoseRecord) = (r.anchor?.inverse() ?: Pose.IDENTITY) * r.camera

    private fun lerp(a: Pose, b: Pose, f: Double) = Pose(a.t + (b.t - a.t) * f, slerp(a.q, b.q, f))

    private fun slerp(a: Quat, b0: Quat, f: Double): Quat {
        var d = a.x * b0.x + a.y * b0.y + a.z * b0.z + a.w * b0.w
        val b = if (d < 0) Quat(-b0.x, -b0.y, -b0.z, -b0.w) else b0
        if (d < 0) d = -d
        val (wa, wb) = if (d > 0.9995) {
            (1 - f) to f
        } else {
            val th = acos(d)
            sin((1 - f) * th) / sin(th) to sin(f * th) / sin(th)
        }
        val q = Quat(wa * a.x + wb * b.x, wa * a.y + wb * b.y, wa * a.z + wb * b.z, wa * a.w + wb * b.w)
        val n = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
        return Quat(q.x / n, q.y / n, q.z / n, q.w / n)
    }

    private companion object {
        const val MAX_WAITING = 64
    }
}
