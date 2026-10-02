package io.packagex.arcount

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * The patch tracker's state per unit and its policy (spec 5.9): every gated read re-captures the unit's patch; while
 * the camera moves, each unit in view with a live patch is followed from its previous position carried by the
 * relative pose; a position the checks accept gives the unit a weighted ray and its marker; one they reject drops
 * the track until the next decode.
 */
class PatchTracks(private val config: CountConfig) {
    private class UnitTrack {
        var patch: Patch? = null

        /** The read the patch was captured at */
        var from: Read? = null

        /** The latest position, decoded or tracked, in stream pixels of [record]'s image */
        var x = 0.0
        var y = 0.0
        var record: PoseRecord? = null

        /** The frame of the latest accepted track; before [decodeNs] when none came since the decode */
        var trackedNs = Long.MIN_VALUE
        var decodeNs = Long.MIN_VALUE
        var oneDimensional = false
        val nccs = ArrayDeque<Double>()

        /** The camera centre (anchor frame) at the capture, and the tracked rays held until the parallax since then counts */
        var captureCentre = Vec3.ZERO
        val held = ArrayList<Ray>()
        var parallaxReached = false
    }

    private class Found(val unit: CountUnit, val track: UnitTrack, val u: Double, val v: Double, val ncc: Double, val oneDimensional: Boolean, val z: Double)

    private val byUnit = HashMap<Int, UnitTrack>()
    private var table: UnitTable? = null

    /** Calls of [PatchTracker.track], and the rays taken from them */
    var trackCalls = 0L
        private set
    var trackedRays = 0L
        private set

    /** Drops every track: the section changed, or it is not COUNTING */
    fun clear() {
        byUnit.clear()
        table = null
    }

    private fun tracksOf(t: UnitTable) {
        if (t === table) return
        byUnit.clear()
        table = t
    }

    /**
     * Re-captures the patch of every unit whose last read is newer than its patch, from the luma frame of that read's
     * capture, at the quad's centre, half-size [CountConfig.patchHalfFraction] of the quad's larger side (clamped).
     * The decode wins: the position becomes the read's centre. When that frame is gone, the unit has no patch.
     */
    fun capture(t: UnitTable, lumas: LumaRing) {
        tracksOf(t)
        byUnit.keys.retainAll(t.units.map { it.id }.toSet())
        for (u in t.units) {
            if (u.state == UnitState.MANUAL) continue
            val read = u.lastRead ?: continue
            val record = u.lastRecord ?: continue
            val tr = byUnit.getOrPut(u.id) { UnitTrack() }
            if (tr.from === read) continue
            tr.patch = null
            tr.x = read.centreU
            tr.y = read.centreV
            tr.record = record
            tr.decodeNs = u.lastReadNs
            tr.trackedNs = Long.MIN_VALUE
            tr.captureCentre = u.lastCentre
            tr.held.clear()
            tr.parallaxReached = false
            val f = lumas.at(read.timestampNs)
            if (f == null) {
                if (!lumas.mayStillCome(read.timestampNs)) tr.from = read
                continue
            }
            tr.from = read
            val xs = (0 until 4).map { read.corners[2 * it] }
            val ys = (0 until 4).map { read.corners[2 * it + 1] }
            val side = max(xs.max() - xs.min(), ys.max() - ys.min())
            val cx = f.toLuma(read.centreU)
            val cy = f.toLuma(read.centreV)
            // Near the border the patch shrinks to what the image holds, never below patchMinHalf
            val room = floor(minOf(cx, cy, f.img.width - 1 - cx, f.img.height - 1 - cy)).toInt()
            val half = minOf(ceil(config.patchHalfFraction * side / f.scale).toInt().coerceIn(config.patchMinHalf, config.patchMaxHalf), room)
            if (half >= config.patchMinHalf) tr.patch = PatchTracker.capture(f.img, cx, cy, half)
        }
    }

    /**
     * Follows every unit in view with a live patch into [r], whose luma copy is [luma]; only while [moving]. Each
     * accepted position not flagged one-dimensional gives the unit a ray of weight (σray / σtrack)².
     */
    fun track(t: UnitTable, r: PoseRecord, luma: LumaFrame, moving: Boolean) {
        tracksOf(t)
        if (!moving || r.anchor == null) return
        val k = r.intrinsics
        val tac = r.cameraInAnchor()
        val found = ArrayList<Found>()
        for (u in t.units) {
            if (u.state == UnitState.MANUAL) continue
            val tr = byUnit[u.id] ?: continue
            val patch = tr.patch ?: continue
            val prev = tr.record ?: continue
            if (prev.timestampNs >= r.timestampNs) continue
            if (r.timestampNs - tr.decodeNs > config.trackMaxAgeNs) {
                drop(tr)
                continue
            }
            val p = t.predict(u, r) ?: continue
            if (!p.inImage(k)) continue
            val seed = carry(u, tr, prev, tac, k)
            if (seed == null) {
                drop(tr)
                continue
            }
            val sx = luma.toLuma(seed.first)
            val sy = luma.toLuma(seed.second)
            // A patch at the image's edge is out of view, not lost: it is followed again once it is back inside
            val reach = patch.fine.half + EDGE_MARGIN
            if (sx < reach || sy < reach || sx > luma.img.width - 1 - reach || sy > luma.img.height - 1 - reach) continue
            trackCalls++
            val hit = PatchTracker.track(patch, luma.img, sx, sy, config.minNcc)
            val su = hit?.let { luma.toStream(it.x) }
            val sv = hit?.let { luma.toStream(it.y) }
            val ok = hit != null && su != null && sv != null &&
                (tr.nccs.isEmpty() || hit.ncc >= config.nccDropFraction * tr.nccs.average()) &&
                hypot(su - p.u, sv - p.v) / luma.scale <= config.trackGateSigmas * p.sigmaPx / luma.scale + config.trackGateLumaPx
            if (!ok) {
                drop(tr)
                continue
            }
            found += Found(u, tr, su!!, sv!!, hit!!.ncc, hit.oneDimensional, p.z)
        }
        // Two tracked positions of one GTIN within half a pitch_px: one of them is on the other's pixels, so neither holds
        val conflicted = HashSet<Found>()
        for (i in found.indices) {
            for (j in i + 1 until found.size) {
                val a = found[i]
                val b = found[j]
                if (a.unit.gtin != b.unit.gtin) continue
                val half = config.duplicateFraction * max(t.pitchPx(k.fx, a.z), t.pitchPx(k.fx, b.z))
                if (hypot(a.u - b.u, a.v - b.v) < half) conflicted += listOf(a, b)
            }
        }
        val weight = (config.sigmaRayPx / config.sigmaTrackPx).let { it * it }
        var rays = 0
        for (fd in found) {
            val tr = fd.track
            if (fd in conflicted) {
                drop(tr)
                continue
            }
            tr.x = fd.u
            tr.y = fd.v
            tr.record = r
            tr.trackedNs = r.timestampNs
            tr.oneDimensional = fd.oneDimensional
            tr.nccs.addLast(fd.ncc)
            while (tr.nccs.size > config.nccHistory) tr.nccs.removeFirst()
            // A one-dimensional track measured the bar normal only: no ray in this version (spec 5.9's line constraint)
            if (fd.oneDimensional) continue
            tr.held += r.ray(fd.u, fd.v)
            // Every tracked ray carries the capture's decode error as a shared offset, which only parallax well above
            // σray makes harmless: rays are held until the camera has moved that far across the unit's ray
            if (!tr.parallaxReached) {
                val across = Prediction.perpendicularTravel(tr.captureCentre, tac.t, fd.unit.point)
                tr.parallaxReached = k.fx * across / fd.z >= config.trackMinParallaxSigmas * config.sigmaRayPx
            }
            if (!tr.parallaxReached) continue
            for (ray in tr.held) t.addTrackedRay(fd.unit, ray, weight, k.fx)
            rays += tr.held.size
            tr.held.clear()
        }
        trackedRays += rays
        if (rays > 0) t.replane()
    }

    /** The track is lost until the unit's next decode, with the rays it held */
    private fun drop(tr: UnitTrack) {
        tr.patch = null
        tr.held.clear()
    }

    /** The unit's previous position moved by the relative pose from its frame to this one, at the unit's depth */
    private fun carry(u: CountUnit, tr: UnitTrack, prev: PoseRecord, tac: Pose, k: Intrinsics): Pair<Double, Double>? {
        if (prev.anchor == null) return null
        val at = pointAt(u, tr, prev) ?: return null
        return Prediction.pixel(at, tac, k)
    }

    /** The point on the ray through the unit's position in [rec] at the unit's camera depth in that frame */
    private fun pointAt(u: CountUnit, tr: UnitTrack, rec: PoseRecord): Vec3? {
        val ptac = rec.cameraInAnchor()
        val z = Prediction.cameraDepth(u.point, ptac)
        if (z <= 0) return null
        val d = rec.intrinsics.rayInCamera(tr.x, tr.y)
        return ptac.apply(d * (z / -d.z))
    }

    private companion object {
        /** Luma pixels kept between a patch's window and the image edge, for the search to move in */
        const val EDGE_MARGIN = 2
    }

    /** The unit's tracked position in the frame at [timestampNs], in stream pixels; null when it was not tracked there */
    fun trackedAt(unitId: Int, timestampNs: Long): Pair<Double, Double>? {
        val tr = byUnit[unitId] ?: return null
        if (tr.patch == null || tr.trackedNs != timestampNs || tr.trackedNs <= tr.decodeNs) return null
        return tr.x to tr.y
    }

    /** The point on the unit's latest tracked ray at its current depth estimate; null when none came since its decode */
    fun anchorPoint(u: CountUnit): Vec3? {
        val tr = byUnit[u.id] ?: return null
        val rec = tr.record ?: return null
        if (tr.patch == null || tr.trackedNs <= tr.decodeNs) return null
        return pointAt(u, tr, rec)
    }

    /** Whether the unit holds a live patch whose latest position is a tracked one */
    fun isTracked(unitId: Int): Boolean {
        val tr = byUnit[unitId] ?: return false
        return tr.patch != null && tr.trackedNs > tr.decodeNs
    }

    /** Whether the unit holds a patch: captured at its last read and not dropped since */
    fun hasPatch(unitId: Int) = byUnit[unitId]?.patch != null

    /** The unit's last NCCs, oldest first */
    fun nccs(unitId: Int): List<Double> = byUnit[unitId]?.nccs?.toList() ?: emptyList()
}
