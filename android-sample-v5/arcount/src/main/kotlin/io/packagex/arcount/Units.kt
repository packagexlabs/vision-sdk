package io.packagex.arcount

import io.packagex.arcount.UnitState.AMBIGUOUS
import io.packagex.arcount.UnitState.COUNTED
import io.packagex.arcount.UnitState.MANUAL
import io.packagex.arcount.UnitState.TENTATIVE
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Why a read neither made nor updated a unit (spec 5.4, hard rules; 5.1, extent) */
enum class Drop { BORDER, OTHER_GTIN, OFF_PLANE, RAIL_BAND, OUTSIDE_EXTENT, DUPLICATE }

/** A section's count (spec 5.4): N = counted + manual, shown as the range [N, N + tentative + ambiguous] */
data class Counts(val counted: Int, val manual: Int, val tentative: Int, val ambiguous: Int) {
    val low get() = counted + manual
    val high get() = low + tentative + ambiguous
}

/** Where a section's units live, in the anchor frame: its plane, its label and its GTIN set (spec 5.1) */
class SectionFrame(
    var plane: SectionPlane,
    var gtins: Set<String>,
    /** The label's latest ray; null in an unlabelled section, which has no rail band */
    var labelRay: Ray? = null,
    /** The label point: the prior on its ray, or triangulated from its reads; the plane passes through it until a unit triangulates */
    var labelPoint: Vec3? = null,
) {
    /** The label's height on the plane, where its ray meets the plane: the centre of the rail band */
    val labelHeight: Double?
        get() {
            val ray = labelRay ?: return null
            return plane.height(plane.intersect(ray) ?: labelPoint ?: return null)
        }
}

/** One unit of the row (spec 5.4, unit record), in the anchor frame */
class CountUnit internal constructor(val id: Int, val gtin: String, state: UnitState, val createdNs: Long, maxRays: Int) {
    var state = state
        internal set

    /** X_a, its covariance, and its σ along the viewing ray: from the fit once a gate accepts a depth, else the plane prior */
    var point = Vec3.ZERO
        internal set
    var covariance = Mat3.ZERO
        internal set
    var sigmaZ = 0.0
        internal set

    /** The rule that gave the unit its depth; null while its point is the plane prior */
    var depthGate: DepthGate? = null
        internal set
    var lastRead: Read? = null
        internal set
    var lastRecord: PoseRecord? = null
        internal set
    var lastReadNs = createdNs
        internal set

    /** The camera centre at the last read (at creation for a unit added by hand), in the anchor frame */
    var lastCentre = Vec3.ZERO
        internal set
    var observations = 0
        internal set
    var lastEngineId = -1
        internal set

    /** The unit an AMBIGUOUS unit may be a second view of */
    var linkedTo: Int? = null
        internal set
    internal var mergeFrames = 0
    internal var segment = -1
    internal var segmentFirstNs = Long.MIN_VALUE
    internal val track = DepthTrack(maxRays)
    internal var lastRay: Ray? = null

    val rays get() = track.size
    val hasDepth get() = depthGate != null
}

/** What one frame's reads did: read to unit id for the gated ones, the units they made, the reads dropped and why */
class FrameOutcome(val matched: Map<Read, Int>, val created: List<CountUnit>, val dropped: List<Pair<Read, Drop>>, val merged: List<Int>, val accepted: Int)

/** A FROZEN section's view of a frame: the COUNTED units re-read inside their gates, and whether a read fell in an ambiguity band */
class ResumeCheck(val countedReread: Set<Int>, val inBand: Boolean)

/**
 * The unit table of one section and its per-frame association (spec 5.4): reads are filtered by the hard rules,
 * matched one-to-one per GTIN inside a quarter-pitch gate (Hungarian, engine id as a tie-break inside the matrix),
 * and an unmatched read becomes AMBIGUOUS inside the band of an unmatched unit, else TENTATIVE.
 */
class UnitTable(private val config: CountConfig, val frame: SectionFrame) {
    private val all = ArrayList<CountUnit>()
    private val log = ArrayDeque<String>()
    private val otherGtins = HashSet<String>()
    private var nextId = 1

    val units: List<CountUnit> get() = all

    /** Logged decisions, newest last */
    val events: List<String> get() = log.toList()

    /** The metric pitch p */
    var pitch = config.defaultPitch
        private set

    fun counts() = Counts(all.count { it.state == COUNTED }, all.count { it.state == MANUAL }, all.count { it.state == TENTATIVE }, all.count { it.state == AMBIGUOUS })

    /** The unit's prediction in [record]'s frame, whose anchor is the section's */
    fun predict(unit: CountUnit, record: PoseRecord): Predicted? =
        Prediction.of(unit.point, unit.sigmaZ, unit.lastCentre, record.cameraInAnchor(), record.intrinsics, config)

    fun pitchPx(f: Double, z: Double) = Pitch.px(f, pitch, z)

    /** Associates one frame's reads (labels already taken out); [segment] numbers the unbroken tracking segment */
    fun associate(record: PoseRecord, reads: List<Read>, segment: Int): FrameOutcome {
        val dropped = ArrayList<Pair<Read, Drop>>()
        val reads1 = prepare(record, reads, dropped)
        val before = all.toList()
        val matches = match(record, reads1, before.filter { it.state != MANUAL })
        val matchedIds = matches.values.map { it.id }.toSet()
        val tac = record.cameraInAnchor()
        val matched = LinkedHashMap<Read, Int>()
        for ((i, u) in matches) {
            take(u, reads1[i], record, tac)
            matched[reads1[i].read] = u.id
            if (u.state == TENTATIVE && u.segment == segment && record.timestampNs > u.segmentFirstNs) {
                u.state = COUNTED
                note("unit ${u.id}: COUNTED on its second read")
            }
            if (u.segment != segment) {
                u.segment = segment
                u.segmentFirstNs = record.timestampNs
            }
        }
        val readOf = matches.entries.associate { (i, u) -> u.id to reads1[i] }
        for (u in matches.values.filter { it.state == AMBIGUOUS }) {
            val others = alongside(u, readOf, record, before) ?: continue
            note("unit ${u.id}: COUNTED, read in one frame with units $others")
            u.state = COUNTED
            u.linkedTo = null
        }
        val free = before.filter { it.id !in matchedIds }
        val created = ArrayList<CountUnit>()
        for ((i, c) in reads1.withIndex()) {
            if (i in matches) continue
            val link = bandOf(c, record, free)
            val u = CountUnit(nextId++, c.gtin, if (link == null) TENTATIVE else AMBIGUOUS, record.timestampNs, config.maxRays)
            u.linkedTo = link?.id
            u.segment = segment
            u.segmentFirstNs = record.timestampNs
            take(u, c, record, tac)
            all += u
            created += u
            note("unit ${u.id}: ${u.state}" + (link?.let { " (in the band of unit ${it.id})" } ?: ""))
        }
        val merged = settleAmbiguous(record, reads1, matchedIds + created.map { it.id })
        replane()
        pitch = Pitch.metric(all.filter { it.state == COUNTED }.map { frame.plane.along(it.point) }, config)
        return FrameOutcome(matched, created, dropped, merged, reads1.size)
    }

    /** For a FROZEN section: which COUNTED units [reads] re-read inside their gates, and whether one fell in a band; changes no unit */
    fun check(record: PoseRecord, reads: List<Read>): ResumeCheck {
        val reads1 = prepare(record, reads, ArrayList())
        val matches = match(record, reads1, all.filter { it.state != MANUAL })
        val matchedIds = matches.values.map { it.id }.toSet()
        val free = all.filter { it.id !in matchedIds }
        val inBand = reads1.indices.any { it !in matches && bandOf(reads1[it], record, free) != null }
        return ResumeCheck(matches.values.filter { it.state == COUNTED }.map { it.id }.toSet(), inBand)
    }

    /** A unit added by hand at [point] on the plane, seen from camera centre [cameraCentre] */
    fun addManual(point: Vec3, cameraCentre: Vec3, nowNs: Long): CountUnit {
        val u = CountUnit(nextId++, frame.gtins.minOrNull() ?: "", MANUAL, nowNs, config.maxRays)
        u.point = point
        u.sigmaZ = config.priorSigmaAlongRay
        u.lastCentre = cameraCentre
        all += u
        note("unit ${u.id}: MANUAL")
        return u
    }

    /**
     * Takes back the last unit added by hand. An AMBIGUOUS unit linked to it stays AMBIGUOUS (review I2: as TENTATIVE
     * its next read would count it, a duplicate when its code belongs to a counted unit): it is linked again to the
     * nearest unit whose band held its last read, at that read's pose, or to none; it then counts only by ruling R2.
     */
    fun removeLastManual(): Boolean {
        val m = all.lastOrNull { it.state == MANUAL } ?: return false
        all.remove(m)
        for (u in all) {
            if (u.linkedTo != m.id) continue
            u.linkedTo = relink(u)
            note("unit ${u.id}: stays AMBIGUOUS, linked to unit ${u.linkedTo}")
        }
        note("unit ${m.id}: MANUAL removed")
        return true
    }

    /** The nearest unit whose band holds [u]'s last read, at that read's pose */
    private fun relink(u: CountUnit): Int? {
        val read = u.lastRead ?: return null
        val record = u.lastRecord ?: return null
        val ray = u.lastRay ?: return null
        return bandOf(Candidate(read, u.gtin, ray, 0.0), record, all.filter { it !== u })?.id
    }

    /**
     * The plane's depth: the median triangulated unit depth once one exists, else the label point's. Units still on
     * the prior are re-intersected with it.
     */
    fun replane() {
        val depths = all.filter { it.hasDepth }.map { frame.plane.normal dot it.point }.sorted()
        val label = frame.labelPoint
        val offset = when {
            depths.isNotEmpty() -> (depths[(depths.size - 1) / 2] + depths[depths.size / 2]) / 2
            label != null -> frame.plane.normal dot label
            else -> return
        }
        if (offset == frame.plane.offset) return
        frame.plane = frame.plane.copy(offset = offset)
        for (u in all) if (!u.hasDepth && u.state != MANUAL) placeOnPlane(u)
    }

    private class Candidate(val read: Read, val gtin: String, val ray: Ray, val z: Double)

    /** The hard rules: border, GTIN set, plane, rail band, extent, then one code decoded twice */
    private fun prepare(record: PoseRecord, reads: List<Read>, dropped: MutableList<Pair<Read, Drop>>): List<Candidate> {
        val tac = record.cameraInAnchor()
        val f = record.intrinsics.fx
        val labelHeight = frame.labelHeight
        val kept = ArrayList<Candidate>()
        for (r in reads) {
            val gtin = Gtin.normalize(r.text, r.symbology)
            val ray = r.ray(record)
            val p = frame.plane.intersect(ray)
            val drop = when {
                r.touchesBorder -> Drop.BORDER
                gtin !in frame.gtins -> Drop.OTHER_GTIN
                p == null -> Drop.OFF_PLANE
                labelHeight != null && abs(frame.plane.height(p) - labelHeight) <= config.railHalfHeight -> Drop.RAIL_BAND
                abs(frame.plane.along(p)) > config.maxExtentFromAnchor -> Drop.OUTSIDE_EXTENT
                else -> null
            }
            if (drop == Drop.OTHER_GTIN && otherGtins.add(gtin)) note("$gtin: outside the section's GTIN set, not counted")
            if (drop != null || p == null) {
                dropped += r to (drop ?: Drop.OFF_PLANE)
                continue
            }
            val z = Prediction.cameraDepth(p, tac).coerceIn(config.minDepth, config.maxDepth)
            val twin = kept.firstOrNull {
                it.gtin == gtin && hypot(it.read.centreU - r.centreU, it.read.centreV - r.centreV) < config.duplicateFraction * pitchPx(f, min(it.z, z))
            }
            if (twin != null) {
                dropped += r to Drop.DUPLICATE
                note("$gtin: decoded twice in one frame, one unit")
                continue
            }
            for (o in kept) if (o.gtin == gtin && opposite(o.read, r)) note("$gtin: two reads with opposite reading directions in one frame (two units)")
            kept += Candidate(r, gtin, ray, z)
        }
        return kept
    }

    private fun opposite(a: Read, b: Read) =
        (a.corners[2] - a.corners[0]) * (b.corners[2] - b.corners[0]) + (a.corners[3] - a.corners[1]) * (b.corners[3] - b.corners[1]) < 0

    /**
     * One-to-one, per GTIN, inside the gate: cost |p − p̂| / pitch_px, +0.1 inside the matrix for another engine id.
     * Only a unit whose σ_p̂ is within [CountConfig.gateMaxSigmaFraction] of its pitch_px takes a read by gate; its
     * gate radius is max(gateCost · pitch_px, gateSigmas · σ_p̂), at most gateMaxCost · pitch_px (ruling R1).
     */
    private fun match(record: PoseRecord, reads: List<Candidate>, units: List<CountUnit>): Map<Int, CountUnit> {
        if (reads.isEmpty() || units.isEmpty()) return emptyMap()
        val f = record.intrinsics.fx
        val preds = units.map { predict(it, record) }
        val cost = Array(reads.size) { i ->
            DoubleArray(units.size) { j ->
                val p = preds[j]
                if (p == null || units[j].gtin != reads[i].gtin) return@DoubleArray Double.POSITIVE_INFINITY
                val px = pitchPx(f, p.z)
                if (p.sigmaPx > config.gateMaxSigmaFraction * px) return@DoubleArray Double.POSITIVE_INFINITY
                val radius = minOf(maxOf(config.gateCost * px, config.gateSigmas * p.sigmaPx), config.gateMaxCost * px)
                val d = hypot(reads[i].read.centreU - p.u, reads[i].read.centreV - p.v)
                if (d > radius) Double.POSITIVE_INFINITY else d / px + if (reads[i].read.engineId != units[j].lastEngineId) config.engineIdPenalty else 0.0
            }
        }
        return Hungarian.assign(cost).withIndex().filter { it.value >= 0 }.associate { it.index to units[it.value] }
    }

    /** The nearest unmatched COUNTED, TENTATIVE or MANUAL unit whose band 0.75 pitch_px + 2 σ_p̂ holds [c] */
    private fun bandOf(c: Candidate, record: PoseRecord, free: List<CountUnit>): CountUnit? =
        free.filter { it.state != AMBIGUOUS }.mapNotNull { u -> inBand(c, u, record)?.let { u to it } }.minByOrNull { it.second }?.first

    /** How far [c] lies from [u]'s prediction in pitches, when it lies inside [u]'s band (a unit added by hand bands every GTIN) */
    private fun inBand(c: Candidate, u: CountUnit, record: PoseRecord): Double? {
        if (u.gtin != c.gtin && u.state != MANUAL) return null
        val p = predict(u, record) ?: return null
        val px = pitchPx(record.intrinsics.fx, p.z)
        val d = hypot(c.read.centreU - p.u, c.read.centreV - p.v)
        return if (d <= config.ambiguityPitchFraction * px + config.ambiguitySigmas * p.sigmaPx) d / px else null
    }

    /**
     * Ruling R2: AMBIGUOUS [u] counts when this frame also read its linked unit and every unit whose band holds
     * [u]'s read, all those reads pairwise half a pitch_px apart or more. Returns those units' ids, or null.
     */
    private fun alongside(u: CountUnit, readOf: Map<Int, Candidate>, record: PoseRecord, units: List<CountUnit>): List<Int>? {
        val own = readOf[u.id] ?: return null
        val candidates = units.filter { it !== u && (it.id == u.linkedTo || inBand(own, it, record) != null) }
        val reads = listOf(u to own) + candidates.map { it to (readOf[it.id] ?: return null) }
        val f = record.intrinsics.fx
        for (a in reads.indices) {
            for (b in a + 1 until reads.size) {
                val za = predict(reads[a].first, record)?.z ?: return null
                val zb = predict(reads[b].first, record)?.z ?: return null
                val half = config.duplicateFraction * maxOf(pitchPx(f, za), pitchPx(f, zb))
                val ra = reads[a].second.read
                val rb = reads[b].second.read
                if (hypot(ra.centreU - rb.centreU, ra.centreV - rb.centreV) < half) return null
            }
        }
        return candidates.map { it.id }
    }

    /** A read updates the unit's quad, pose, observations, rays and point */
    private fun take(u: CountUnit, c: Candidate, record: PoseRecord, tac: Pose) {
        u.track.add(c.ray)
        u.lastRay = c.ray
        u.lastRead = c.read
        u.lastRecord = record
        u.lastReadNs = record.timestampNs
        u.lastCentre = tac.t
        u.observations++
        u.lastEngineId = c.read.engineId
        val fit = u.track.fit(config.sigmaRayPx / record.intrinsics.fx, config)
        if (fit?.gate != null) {
            if (u.depthGate == null) note("unit ${u.id}: depth ${"%.3f".format(fit.range)} m by ${fit.gate} over ${fit.inliers} rays")
            u.point = fit.point
            u.covariance = fit.covariance
            u.sigmaZ = fit.sigmaZ
            u.depthGate = fit.gate
        } else if (u.depthGate == null) {
            placeOnPlane(u)
        }
    }

    /**
     * A tracked position's ray (spec 5.9), of weight (σray / σtrack)² in the unit's fit; its read, pose and
     * observations stay the last decode's. A depth a gate accepts is taken; otherwise the unit keeps what it had.
     */
    fun addTrackedRay(u: CountUnit, ray: Ray, weight: Double, f: Double) {
        u.track.add(ray, weight)
        val fit = u.track.fit(config.sigmaRayPx / f, config) ?: return
        if (fit.gate == null) return
        if (u.depthGate == null) note("unit ${u.id}: depth ${"%.3f".format(fit.range)} m by ${fit.gate} over ${fit.inliers} rays, tracked")
        u.point = fit.point
        u.covariance = fit.covariance
        u.sigmaZ = fit.sigmaZ
        u.depthGate = fit.gate
    }

    private fun placeOnPlane(u: CountUnit) {
        val ray = u.lastRay ?: return
        val f = u.lastRecord?.intrinsics?.fx ?: return
        val prior = frame.plane.prior(ray, config.priorSigmaAlongRay, config.sigmaRayPx / f) ?: return
        u.point = prior.point
        u.covariance = prior.covariance
        u.sigmaZ = prior.sigmaZ
    }

    /**
     * AMBIGUOUS unit U merges into its linked unit V after [CountConfig.mergeFrames] frames of evidence. A frame is
     * evidence only when U and V are both predicted inside the image, V and every other unit of that GTIN predicted
     * inside the image were read in it (units added by hand are never read and do not count), and no read lies in
     * U's gate. Absence of a read is evidence only when the engine had decode budget to spare for that GTIN in that
     * frame (the spec's "only one read of that GTIN" holds in every frame of an engine decoding one code a frame).
     *
     * @param read the ids of the units with a read in this frame: matched or created by it
     */
    private fun settleAmbiguous(record: PoseRecord, reads: List<Candidate>, read: Set<Int>): List<Int> {
        val merged = ArrayList<Int>()
        val k = record.intrinsics
        for (a in all.filter { it.state == AMBIGUOUS && it.id !in read }) {
            val linked = all.firstOrNull { it.id == a.linkedTo } ?: continue
            if (linked.id !in read) continue
            val pa = predict(a, record) ?: continue
            val pl = predict(linked, record) ?: continue
            if (!pa.inImage(k) || !pl.inImage(k)) continue
            val gate = config.gateCost * pitchPx(k.fx, pa.z)
            if (reads.any { it.gtin == a.gtin && hypot(it.read.centreU - pa.u, it.read.centreV - pa.v) <= gate }) continue
            val unread = all.any { it !== a && it.gtin == a.gtin && it.state != MANUAL && it.id !in read && predict(it, record)?.inImage(k) == true }
            if (unread) continue
            a.mergeFrames++
            if (a.mergeFrames < config.mergeFrames) continue
            all.remove(a)
            linked.observations += a.observations
            merged += a.id
            note("unit ${a.id}: merged into unit ${linked.id}")
        }
        return merged
    }

    private fun note(s: String) {
        log.addLast(s)
        if (log.size > MAX_EVENTS) log.removeFirst()
    }

    private companion object {
        const val MAX_EVENTS = 5000
    }
}
