package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.hypot

/**
 * A device run's trace (ArTrace ndjson, kept in test resources as `traces/<HHMMSS>.ndjson.gz`) replayed through the
 * real [PinBook], fed as [ArPins] feeds it: every frame drives [PinMotion] and [BirthGate]; every batch the run placed
 * (its `hit` lines, grouped by capture and by the frame that placed it) is placed again on that frame, each listed read
 * a [Sighting] on its recorded point: the hit ARCore gave ([HitSource.HIT]: its distance along the read's centre ray,
 * on a plane when its kind was one), the nominal width's or the default depth's. What ARCore did cannot be replayed:
 * - the anchors stay where the pins were born: a trace keeps none of ARCore's moves of them (carrying them as the
 *   section anchor moved made 4 of the runs worse, not better: that anchor's moves are not the pins'), so a run whose
 *   map drifts far ([drift]) is not replayed faithfully after it;
 * - [PinBook.mapMovedNs] comes from frames that did not track and from steps of 2-20 cm of the section anchor;
 * - the engine's undecoded boxes (rule 7's shield) are not in a trace: none shield;
 * - ARCore's tracked upward planes: the run's table (the median height of its plane hits), from its first plane hit, and
 *   any other surface its reads met (a floor), from the first read that met it ([seenPlanes]);
 * - a read the app gave a verified pin's depth ([HitSource.PIN]) had no hit test: when the replay has no verified pin
 *   there, it takes the run's table (the median height of its plane hits) under the read, once a plane was hit, else
 *   the nominal width's or the default depth ([fallbacks]);
 * - the surface gate sees a plane from the first plane hit on (ARCore may track one before any read hits it).
 */
class TraceReplay(private val name: String, stream: InputStream) {
    private class Frame(val record: PoseRecord, val anchor: Pose?)
    private class Hit(
        val ts: Long, val now: Long, val code: String, val id: Int, val text: String, val outcome: String, val kind: String?, val distM: Double?,
        /** ARCore's hits on the read's ray as traced: kind, distance along it, in its polygon (planes), the Phase 2 verdict */
        val seen: List<Seen>,
    )
    private class Seen(val kind: String, val distM: Double, val inPolygon: Boolean?, val verdict: String?)
    class PinEvent(val ev: String, val pin: Int, val code: String, val nowNs: Long, val at: Vec3?)

    private val frames = ArrayList<Frame>()
    private val reads = HashMap<Long, MutableList<Read>>()
    private val hits = ArrayList<Hit>()
    val events = ArrayList<PinEvent>()
    var rules = PinRules.ANDROID
        private set
    var refine = true
        private set

    /** The first frame a read's ray met a plane on, and the run's table height (the median of its plane hits' y) */
    private var planeNs = Long.MAX_VALUE
    private var tableY: Double? = null

    /**
     * The upward surfaces the run's reads met: its table (the median height of its plane hits) from [planeNs], and every
     * other surface more than [OTHER_SURFACE_M] from it (a floor under the table) from the first frame a read met it.
     * The points of plane hits, and of planes a depth point agreed with or verified pins vouched for (each lies on a plane
     * ARCore tracked), are clustered by height within [TABLE_M], a cluster at its median; with no plane hit, every
     * cluster is a surface. ARCore's tracked planes as far as the trace shows them: a cluster near the table is the table
     * as the map moved it (12:03, 2026-10-07: 3 of them, 6.7 cm apart), not a surface of its own.
     */
    private class SeenPlane(val y: Double, val fromNs: Long)
    private val seenPlanes = ArrayList<SeenPlane>()

    init {
        val planeYs = ArrayList<Double>()
        val onPlanes = ArrayList<Pair<Long, Double>>()
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            for (line in lines) {
                val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                when (o.str("t")) {
                    "flags" -> {
                        rules = if (o.str("pinRules") == "IOS") PinRules.IOS else PinRules.ANDROID
                        refine = o["pinRefine"]?.jsonPrimitive?.content != "false"
                    }
                    "frame" -> {
                        val i = o["intr"]!!.jsonObject
                        val k = Intrinsics(i.dbl("fx"), i.dbl("fy"), i.dbl("cx"), i.dbl("cy"), i.dbl("w").toInt(), i.dbl("h").toInt())
                        val tracking = runCatching { Tracking.valueOf(o.str("tracking")!!) }.getOrDefault(Tracking.PAUSED)
                        val camera = pose(o["cam"]!!.jsonArray)
                        val anchor = (o["anchor"] as? JsonArray)?.let(::pose)
                        frames += Frame(PoseRecord(o["ts"]!!.jsonPrimitive.long, camera, null, tracking, null, k), anchor)
                    }
                    "read" -> {
                        val ts = o["ts"]!!.jsonPrimitive.long
                        val corners = o["raw"]!!.jsonArray.map { it.jsonPrimitive.double }
                        reads.getOrPut(ts) { ArrayList() } += Read(
                            ts, o.str("rawText")!!, corners, o["id"]!!.jsonPrimitive.content.toInt(), o.str("symbology"),
                            o["border"]?.jsonPrimitive?.content == "true",
                        )
                    }
                    "hit" -> {
                        val hitsSeen = o["hits"] as? JsonArray
                        val seen = hitsSeen.orEmpty().mapNotNull { e ->
                            val a = e as? JsonArray ?: return@mapNotNull null
                            val d = a.getOrNull(1)?.takeIf { it !is JsonNull }?.jsonPrimitive?.double ?: return@mapNotNull null
                            Seen(
                                a[0].jsonPrimitive.content, d, a.getOrNull(2)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toBoolean(),
                                a.getOrNull(3)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                            )
                        }
                        val h = Hit(
                            o["ts"]!!.jsonPrimitive.long, o["now"]!!.jsonPrimitive.long, o.str("code")!!, o["id"]!!.jsonPrimitive.content.toInt(),
                            o.str("text")!!, o.str("outcome")!!, o.str("kind"), o["distM"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double, seen,
                        )
                        hits += h
                        if (hitsSeen != null && hitsSeen.any { (it as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.content == "Plane" }) planeNs = minOf(planeNs, h.now)
                    }
                    "pin" -> {
                        val at = (o["at"] as? JsonArray)?.let { Vec3(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double, it[2].jsonPrimitive.double) }
                        val now = o["now"]?.jsonPrimitive?.long ?: o["ts"]!!.jsonPrimitive.long
                        events += PinEvent(o.str("ev")!!, o["pin"]!!.jsonPrimitive.content.toInt(), o.str("code")!!, now, at)
                    }
                }
            }
        }
        frames.sortBy { it.record.timestampNs }
        // The table: plane hits' points on their reads' centre rays
        val byTs = frames.associateBy { it.record.timestampNs }
        for (h in hits) {
            if (h.distM == null) continue
            val onPlane = (h.outcome == "ok" && (h.kind == "Plane" || h.kind == null)) || h.outcome == "vouched"
            if (!onPlane) continue
            val capture = byTs[h.ts]?.record ?: continue
            val read = readOf(h) ?: continue
            val y = centreRay(read, capture).at(h.distM).y
            if (h.outcome == "ok" && h.kind == "Plane") planeYs += y
            onPlanes += h.now to y
        }
        tableY = planeYs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        // Heights within TABLE_M of a cluster's first are that plane's; each counts from the first frame it was met on
        val clusters = ArrayList<MutableList<Pair<Long, Double>>>()
        for (p in onPlanes.sortedBy { it.second }) {
            val last = clusters.lastOrNull()
            if (last != null && p.second - last[0].second <= TABLE_M) last += p else clusters += mutableListOf(p)
        }
        val table = tableY
        if (table != null) seenPlanes += SeenPlane(table, planeNs)
        for (c in clusters) {
            val y = c[c.size / 2].second
            if (table == null || kotlin.math.abs(y - table) > OTHER_SURFACE_M) seenPlanes += SeenPlane(y, c.minOf { it.first })
        }
    }

    /** The upward surfaces met by [ts] ([seenPlanes]) */
    private fun planesAt(ts: Long): List<PlaneRef> =
        seenPlanes.filter { it.fromNs <= ts }.map { PlaneRef(Vec3(0.0, it.y, 0.0), Vec3(0.0, 1.0, 0.0)) }

    private fun readOf(h: Hit): Read? = reads[h.ts]?.firstOrNull { it.engineId == h.id && it.text == h.text }

    /** What one replay did */
    class Result(
        val name: String,
        /** Live pins per code at the end */
        val pins: Map<String, Int>,
        val births: Int,
        /** Retired pins, counted ones ([PIN_COUNTED_CLAIMS] good claims) apart */
        val retired: Int,
        val retiredCounted: Int,
        /**
         * Counted pins retired with no pin of their code born after them standing in for them at the end: within half
         * the code's pitch, or [PIN_RELOCATE_M] for a code of one unit
         */
        val countedLost: Int,
        val merged: Int,
        /** Pairs of same-code pins alive at the end closer than half their code's pitch on the sheet */
        val duplicates: Int,
        /** Claims' pins, as they stood at the capture before the batch, against the read, 4K px: median and p90 */
        val m1MedianPx: Double,
        val m1P90Px: Double,
        /** Reads that took a stand-in for a verified pin's depth the replay did not have, of [placedReads] */
        val fallbacks: Int,
        val placedReads: Int,
        val bornAtS: List<Pair<String, Double>>,
        val retiredAtS: List<Pair<String, Double>>,
    )

    /** The run replayed under the current rules (its own [rules] and [refine]); [pitchM]: each code's pitch on the sheet */
    fun replay(pitchM: Map<String, Double>, endS: Double = Double.MAX_VALUE, log: ((String) -> Unit)? = null): Result {
        val book = PinBook().also {
            it.rules = rules
            it.refine = refine
        }
        val motion = PinMotion()
        val gate = BirthGate()
        val breaks = MapBreaks()
        val placedOn = hits.filter { it.outcome in PLACED }.groupBy { it.now }
        val t0 = frames.firstOrNull()?.record?.timestampNs ?: 0L
        val byTs = frames.associateBy { it.record.timestampNs }
        var births = 0
        var retired = 0
        var retiredCounted = 0
        var merged = 0
        var fallbacks = 0
        var placedReads = 0
        val m1 = ArrayList<Double>()
        val bornAt = ArrayList<Pair<String, Double>>()
        val retiredAt = ArrayList<Pair<String, Double>>()
        val lostCounted = ArrayList<Pin>()
        var lastAnchor: Pose? = null
        for (f in frames) {
            val rec = f.record
            val ts = rec.timestampNs
            if ((ts - t0) / 1e9 > endS) break
            val tracking = rec.frameTracking == Tracking.TRACKING
            gate.frame(ts, tracking, tracking && ts >= planeNs)
            book.birthsNeedSurface = gate.needsSurface
            motion.onFrame(ts, rec.camera, tracking)
            breaks.frame(ts, tracking)
            val a = f.anchor
            val prev = lastAnchor
            if (a != null && prev != null) {
                val step = (a.t - prev.t).norm()
                if (step <= HANDOFF_M) breaks.anchorMoved(ts, step) // a bigger jump is a new section's anchor
            }
            if (a != null) lastAnchor = a
            if (!tracking) continue
            book.mapMovedNs = breaks.newestNs
            val batches = placedOn[ts] ?: continue
            for ((captureNs, batch) in batches.groupBy { it.ts }.toSortedMap()) {
                val capture = byTs[captureNs]?.record ?: continue
                val sightings = ArrayList<Sighting>()
                val vouchers = if (rules == PinRules.ANDROID && refine) book.pins.filter { it.verified }.map { it.positionAt(captureNs) } else emptyList()
                for (h in batch) {
                    val read = readOf(h) ?: continue
                    if (h.outcome == "offView") continue
                    val ray = centreRay(read, capture)
                    val width = nominalWidthM(read.symbology)
                    val known = if (rules == PinRules.ANDROID && refine) verifiedRange(book, h.code, read, capture, ray) else Double.NaN
                    val s = when {
                        known.isFinite() -> sightingOf(read, capture, ray.at(known), Quat.IDENTITY, ray, FLOOR_PX, source = HitSource.PIN, code = h.code, nominalM = width ?: 0.0)
                        h.outcome == "pin" -> {
                            fallbacks++
                            val y = tableY
                            val along = if (y != null && ts >= planeNs && ray.dir.y < -1e-3) (y - ray.origin.y) / ray.dir.y else Double.NaN
                            if (along.isFinite() && along > 0.0) {
                                sightingOf(read, capture, ray.at(along), Quat.IDENTITY, ray, FLOOR_PX, true, HitSource.HIT, h.code, width ?: 0.0)
                            } else {
                                val pick = width?.let { w -> nominalPoint(read, capture, w)?.let { HitPick(it, -1, HitSource.WIDTH, false) } } ?: defaultPick(read, capture)
                                sightingOf(read, capture, pick.point, Quat.IDENTITY, ray, FLOOR_PX, false, pick.source, h.code, width ?: 0.0)
                            }
                        }
                        h.outcome in SOURCES -> {
                            // The current hit rules on the hits the run traced ([pickHit], else [defaultPick])
                            val rayHits = h.seen.map { rayHit(it, ray) }
                            val planes = planesAt(ts)
                            val label = if (width == null && rules == PinRules.ANDROID && refine) learnedLabelWidthM(book.pins, h.code) else null
                            val pick = if (rules == PinRules.ANDROID) pickHit(rayHits, read, capture, ray, planes = planes, vouchers = vouchers, labelM = label) ?: defaultPick(read, capture) else null
                            if (pick != null) {
                                sightingOf(read, capture, pick.point, Quat.IDENTITY, ray, FLOOR_PX, pick.onPlane, pick.source, h.code, width ?: 0.0)
                            } else {
                                val d = h.distM ?: continue
                                sightingOf(read, capture, ray.at(d), Quat.IDENTITY, ray, FLOOR_PX, h.kind == "Plane", SOURCES[h.outcome]!!, h.code, width ?: 0.0)
                            }
                        }
                        else -> continue
                    }
                    sightings += s
                }
                if (sightings.isEmpty()) continue
                placedReads += sightings.size
                // M1: each claim's pin as it stood at the capture, before this batch moved it
                val before = HashMap<Int, Vec3>()
                for (pin in book.pins) before[pin.id] = if (refine) pin.positionAt(captureNs) else pin.position
                val placed = book.place(sightings, captureNs, ts, motion.mayCreate(captureNs)) { pin ->
                    births++
                    bornAt += pin.code to (ts - t0) / 1e9
                    log?.invoke("%.2f s birth %d %s at %s onPlane %s gate %s pitch %s".format((ts - t0) / 1e9, pin.id, pin.code, pin.pose.t.fmt(), pin.bornOnPlane, gate.state, book.pitchOf(pin.code)?.let { "%.3f".format(it) }))
                    true
                }
                val toCamera = capture.camera.inverse()
                for (c in placed.claims) {
                    val p = before[c.pin.id] ?: continue
                    val px = capture.intrinsics.project(toCamera.apply(p)) ?: continue
                    m1 += hypot(px.first - c.sighting.centreU, px.second - c.sighting.centreV)
                }
                merged += placed.removed.size
                for (pin in placed.reinits) log?.invoke("%.2f s reinit %d %s at %s".format((ts - t0) / 1e9, pin.id, pin.code, pin.position.fmt()))
                if (log != null) for (c in placed.claims) {
                    val p = before[c.pin.id] ?: continue
                    val px = capture.intrinsics.project(toCamera.apply(p)) ?: continue
                    val off = hypot(px.first - c.sighting.centreU, px.second - c.sighting.centreV)
                    log("%.2f s claim %d %s off %.0f px aspect %.2f w %.0f now %s".format((ts - t0) / 1e9, c.pin.id, c.pin.code, off, c.sighting.aspect, c.sighting.widthPx, c.pin.position.fmt()))
                }
                for (pin in placed.removed) log?.invoke("%.2f s merge %d %s at %s".format((ts - t0) / 1e9, pin.id, pin.code, pin.position.fmt()))
                for (pin in placed.retired) log?.invoke("%.2f s retire %d %s at %s good %d misses %d".format((ts - t0) / 1e9, pin.id, pin.code, pin.position.fmt(), pin.goodClaims, pin.misses))
                for (pin in placed.retired) {
                    retired++
                    retiredAt += pin.code to (ts - t0) / 1e9
                    if (pin.goodClaims >= PIN_COUNTED_CLAIMS) {
                        retiredCounted++
                        lostCounted += pin
                    }
                }
            }
        }
        val live = book.pins
        for (pin in live) log?.invoke("end pin %d %s at %s good %d verified %s".format(pin.id, pin.code, pin.position.fmt(), pin.goodClaims, pin.verified))
        val countedLost = lostCounted.count { gone ->
            val reach = pitchM[gone.code]?.let { 0.5 * it } ?: PIN_RELOCATE_M
            live.none { it.code == gone.code && it.bornNs > gone.bornNs && (it.position - gone.position).norm() <= reach }
        }
        var duplicates = 0
        for (i in live.indices) for (j in i + 1 until live.size) {
            val a = live[i]
            val b = live[j]
            if (a.code != b.code) continue
            if ((a.position - b.position).norm() < 0.5 * (pitchM[a.code] ?: continue)) duplicates++
        }
        m1.sort()
        fun q(p: Double) = if (m1.isEmpty()) Double.NaN else m1[minOf(m1.size - 1, (p * m1.size).toInt())]
        return Result(
            name, live.groupingBy { it.code }.eachCount().toSortedMap(), births, retired, retiredCounted, countedLost, merged,
            duplicates, q(0.5), q(0.9), fallbacks, placedReads, bornAt, retiredAt,
        )
    }

    /**
     * A traced hit as [pickHit] judges it: its point on the read's [ray]; a plane's normal up when it lies on one of the
     * run's surfaces (within [TABLE_M] of its height, [seenPlanes]), else facing the camera, or away when its verdict was "backOfPlane"; a
     * depth point confident unless its verdict was "lowConfidence" (unknown when there was none)
     */
    private fun rayHit(h: Seen, ray: io.packagex.arcount.Ray): RayHit {
        val p = ray.at(h.distM)
        return when (h.kind) {
            "Plane" -> {
                val normal = when {
                    h.verdict == "backOfPlane" -> ray.dir
                    seenPlanes.any { kotlin.math.abs(p.y - it.y) < TABLE_M } -> Vec3(0.0, 1.0, 0.0)
                    else -> ray.dir * -1.0
                }
                RayHit(HitKind.PLANE, p, normal, h.inPolygon == true)
            }
            "Point" -> RayHit(HitKind.POINT, p)
            "DepthPoint" -> RayHit(HitKind.DEPTH_POINT, p, confidence = when (h.verdict) { null -> null; "lowConfidence" -> 0; else -> 255 })
            else -> RayHit(HitKind.OTHER, p)
        }
    }

    /** [ArPins]' verifiedRange: the distance along [ray] of a verified pin of [code] in [read]'s quad at the capture; NaN: none */
    private fun verifiedRange(book: PinBook, code: String, read: Read, capture: PoseRecord, ray: io.packagex.arcount.Ray): Double {
        val c = read.corners
        val minU = minOf(c[0], c[2], c[4], c[6])
        val maxU = maxOf(c[0], c[2], c[4], c[6])
        val minV = minOf(c[1], c[3], c[5], c[7])
        val maxV = maxOf(c[1], c[3], c[5], c[7])
        val toCamera = capture.camera.inverse()
        for (pin in book.pins) {
            if (pin.code != code || !pin.verified) continue
            val p = pin.positionAt(capture.timestampNs)
            val uv = capture.intrinsics.project(toCamera.apply(p)) ?: continue
            if (uv.first !in minU..maxU || uv.second !in minV..maxV) continue
            return maxOf((p - ray.origin) dot ray.dir, PIN_MIN_RANGE_M)
        }
        return Double.NaN
    }

    /** The trace's own pins: live per code at its end, births and retirements (for the replay's fidelity) */
    fun recorded(): Triple<Map<String, Int>, Int, Int> {
        val live = HashMap<Int, String>()
        var births = 0
        var retired = 0
        for (e in events) {
            when (e.ev) {
                "birth" -> {
                    births++
                    live[e.pin] = e.code
                }
                "retire" -> {
                    retired++
                    live.remove(e.pin)
                }
                "merge", "clear" -> live.remove(e.pin)
            }
        }
        return Triple(live.values.groupingBy { it }.eachCount().toSortedMap(), births, retired)
    }

    private companion object {
        /** The outcomes of reads a placed batch held (the others' batches were not placed) */
        val PLACED = setOf("ok", "vouched", "width", "nearest", "default", "pin", "offView")
        val SOURCES = mapOf("ok" to HitSource.HIT, "vouched" to HitSource.HIT, "width" to HitSource.WIDTH, "nearest" to HitSource.NEAREST, "default" to HitSource.DEFAULT)

        /** The 40 dp match floor at the Memor's density, in 4K stream px ([TwinSim.FLOOR_PX]) */
        const val FLOOR_PX = 180.0

        /** A plane hit this near the run's table height is the table's (normal up) */
        const val TABLE_M = 0.03

        /** A surface this far from the table is another (the floor 0.7-0.8 m below it in 2026-10-07 16:31, 10-08 12:17) */
        const val OTHER_SURFACE_M = 0.10

        /** A section anchor jumping farther than this in one frame is a new section's, not a map step */
        const val HANDOFF_M = 0.20

        fun Vec3.fmt() = "(%.3f, %.3f, %.3f)".format(x, y, z)
        fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        fun JsonObject.dbl(key: String): Double = this[key]!!.jsonPrimitive.double
        fun pose(a: JsonArray): Pose {
            val v = a.map { it.jsonPrimitive.double }
            return Pose(Vec3(v[0], v[1], v[2]), Quat(v[3], v[4], v[5], v[6]))
        }
    }
}
