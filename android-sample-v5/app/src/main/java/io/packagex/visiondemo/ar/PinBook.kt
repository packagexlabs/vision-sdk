package io.packagex.visiondemo.ar

import io.packagex.arcount.ItemCode
import io.packagex.arcount.ItemCount
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.UnitPoint
import io.packagex.arcount.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

/*
 * AR Item Count's pins: one persistent marker per physical barcode of a listed code. Where the counting core has a
 * triangulated depth for the barcode's unit, the pin sits at the core's point ([PinBook.follow]); before that, a
 * provisional pin comes from hit tests under the old AR Barcode renderer's rules (ArBarcodeRenderer at ceff1a4^,
 * "markers without animation or smoothing"), with its constants, and only hits that pass [checkHit]. Pure: the GL
 * thread's [ArPins] feeds it and holds the anchors.
 */

/** A new pin needs this many agreeing sightings (old candidateConfirmCount) */
internal const val PIN_CONFIRM_COUNT = 3

/** A candidate not seen for this long is dropped (old candidateTimeoutNs) */
internal const val PIN_CANDIDATE_TIMEOUT_NS = 1_500_000_000L

/** Sightings kept per candidate (old maxSamples) */
internal const val PIN_MAX_SAMPLES = 7

/** A sighting joins a candidate whose last position lies this close to its ray (old candidateMatchRadiusM) */
internal const val PIN_CANDIDATE_RADIUS_M = 0.08

/** A sighting re-sights a pin of its payload within this lateral distance of its ray; also the sibling-merge radius (old assignRadiusLocalizedM) */
internal const val PIN_ASSIGN_RADIUS_M = 0.15

/** A hit is kept only this far in front of the capture camera (camera depth, metres): the floor behind a shelf is not */
internal const val PIN_MIN_DEPTH_M = 0.15
internal const val PIN_MAX_DEPTH_M = 1.5

/** A hit's reprojection into its read's own frame must land this close to the read's centre: pixels at 4K, or half the quad's width if more */
internal const val PIN_MAX_REPROJECTION_PX_4K = 25.0

/** A core-placed pin moves only when the core's point for its unit moved more than this (a better triangulation) */
internal const val PIN_CORE_MOVE_M = 0.03

/** A read batch older than this, against the newest frame, is dropped (old 500 ms batch staleness) */
internal const val PIN_MAX_BATCH_AGE_NS = 500_000_000L

/** Tracked frames before pins are placed (old WarmUpGate(minFrames = 60)) */
internal const val PIN_WARM_UP_FRAMES = 60

/** Moving moderately: new pins OK (old cameraModerate, 0.40 m/s and 60 deg/s; moderateMotionGate = true) */
internal const val PIN_MODERATE_MPS = 0.40
internal const val PIN_MODERATE_DPS = 60.0

/**
 * The old renderer's camera motion and warm-up gates, on the GL thread, in the clock of the reads (the frames'
 * `androidCameraTimestamp`). While the frame tracks: the warm-up counts, speed and rotation rate (full relative angle
 * of the quaternions, over 0.1 ms to 0.5 s) update, and [lastImmoderateNs] marks the last frame moving faster than
 * moderate; a frame that does not track marks it too, so no read captured before the loss is trusted after.
 */
class PinMotion {
    var warmFrames = 0
        private set
    val mapReady: Boolean get() = warmFrames >= PIN_WARM_UP_FRAMES
    var speedMps = 0.0
        private set
    var rotationDps = 0.0
        private set
    var lastImmoderateNs = 0L
        private set
    private var prev: Pose? = null
    private var prevNs = 0L

    val cameraModerate: Boolean get() = speedMps < PIN_MODERATE_MPS && rotationDps < PIN_MODERATE_DPS

    fun onFrame(timestampNs: Long, camera: Pose, tracking: Boolean) {
        if (!tracking) {
            lastImmoderateNs = timestampNs
            return
        }
        if (!mapReady) warmFrames++
        val p = prev
        val dtS = (timestampNs - prevNs) / 1e9
        if (p != null && dtS in 1e-4..0.5) {
            speedMps = (camera.t - p.t).norm() / dtS
            val dot = abs(camera.q.x * p.q.x + camera.q.y * p.q.y + camera.q.z * p.q.z + camera.q.w * p.q.w).coerceIn(0.0, 1.0)
            rotationDps = Math.toDegrees(2.0 * acos(dot)) / dtS
        }
        prev = camera
        prevNs = timestampNs
        if (!cameraModerate) lastImmoderateNs = timestampNs
    }

    /** Whether reads captured at [captureNs] are trusted at the frame [nowNs]: fresh enough, and no immoderate motion or tracking loss since. */
    fun accepts(captureNs: Long, nowNs: Long): Boolean = nowNs - captureNs <= PIN_MAX_BATCH_AGE_NS && lastImmoderateNs < captureNs
}

/** One read of a listed code, hit-tested: its [code] key ([ItemCode.key]), the world [hit] (with the hit's [rotation]) of its capture-time centre [ray] */
data class Sighting(val code: String, val hit: Vec3, val rotation: Quat, val ray: Ray)

/** How a hit relates to its read: [errorPx] from the read's centre once reprojected into the read's frame, [depthM] in that camera */
data class HitCheck(val errorPx: Double, val depthM: Double, val accepted: Boolean)

/**
 * The hit [hit] (world) of [read]'s centre ray, reprojected into the read's own frame ([capture]'s pose and intrinsics):
 * accepted when it lands within max([PIN_MAX_REPROJECTION_PX_4K] at 4K, half the quad's width) of the read's centre and
 * lies [PIN_MIN_DEPTH_M]..[PIN_MAX_DEPTH_M] in front of the camera. A hit behind the camera has no pixel: infinite error.
 */
fun checkHit(hit: Vec3, read: Read, capture: PoseRecord): HitCheck {
    val inCamera = capture.camera.inverse().apply(hit)
    val depth = -inCamera.z
    val k = capture.intrinsics
    val px = k.project(inCamera)
    val error = px?.let { (u, v) -> hypot(u - read.centreU, v - read.centreV) } ?: Double.POSITIVE_INFINITY
    val limit = max(PIN_MAX_REPROJECTION_PX_4K * k.width / 3840.0, read.widthPx / 2)
    return HitCheck(error, depth, error <= limit && depth in PIN_MIN_DEPTH_M..PIN_MAX_DEPTH_M)
}

/**
 * A pin of [code]: its anchor's [pose], its world [position] (the anchor's last tracked one), when a sighting last
 * claimed it; [unitKey] the core unit it follows and [corePoint] where the core put that unit when the pin was last
 * placed (null: a provisional pin from hit tests).
 */
class Pin(val id: Int, val code: String, pose: Pose) {
    var pose: Pose = pose
        internal set
    var position: Vec3 = pose.t
    var lastSeenNs = 0L
    var unitKey: String? = null
        internal set
    var corePoint: Vec3? = null
        internal set
    val fromCore: Boolean get() = corePoint != null
}

/**
 * Perpendicular distance from [p] to [ray], depth-independent so noisy hit depth can't make the same barcode look like
 * a new copy; [Double.MAX_VALUE] for a point behind the ray's origin (old lateralToRay).
 */
fun lateral(ray: Ray, p: Vec3): Double {
    val v = p - ray.origin
    val along = v dot ray.dir
    if (along <= 0.0) return Double.MAX_VALUE
    return (v - ray.dir * along).norm()
}

/**
 * The pins and the candidates of one session. Hit tests (old ArBarcodeRenderer's markers and candidates): exclusive
 * one-to-one assignment of a batch's sightings to same-code pins by smallest lateral distance; the rest feed
 * candidates, which become provisional pins after [PIN_CONFIRM_COUNT] agreeing sightings, born at the latest one. The
 * core ([follow]): each unit point takes its pin, snapping a provisional one to the core's point. A sighting never moves
 * a pin; only ARCore (its anchor) and the core do. A pin never expires: it goes on [clear] (New Scan) or a merge.
 */
class PinBook {
    private class Candidate(val code: String) {
        val positions = ArrayDeque<Vec3>()
        var lastSeenNs = 0L
    }

    private val list = ArrayList<Pin>()
    private val candidates = ArrayList<Candidate>()
    private var nextId = 1

    /** A pin was created or re-sighted: the sibling merge runs on the next frame (old mergePending) */
    var mergePending = false
        private set

    val pins: List<Pin> get() = list
    val candidateCount: Int get() = candidates.size

    /** Old drainDetections' first step, every frame */
    fun expireCandidates(nowNs: Long) {
        candidates.removeAll { nowNs - it.lastSeenNs > PIN_CANDIDATE_TIMEOUT_NS }
    }

    /**
     * One batch (old assignAndPlace): pairs within [PIN_ASSIGN_RADIUS_M] claimed greedily, smallest lateral distance
     * first, each pin and each sighting at most once; a claimed pin's lastSeen is [nowNs]. Unclaimed sightings feed the
     * candidates when [mayCreate] (map warmed up and the camera moderate now). The pins born go back, for anchors.
     */
    fun place(sightings: List<Sighting>, nowNs: Long, mayCreate: Boolean): List<Pin> {
        if (sightings.isEmpty()) return emptyList()
        data class Pairing(val s: Int, val pin: Pin, val lateral: Double)
        val pairings = ArrayList<Pairing>()
        sightings.forEachIndexed { i, s ->
            for (pin in list) {
                if (pin.code != s.code) continue
                val d = lateral(s.ray, pin.position)
                if (d <= PIN_ASSIGN_RADIUS_M) pairings += Pairing(i, pin, d)
            }
        }
        pairings.sortBy { it.lateral }
        val claimed = HashSet<Int>()
        val assigned = HashSet<Int>()
        for (p in pairings) {
            if (p.pin.id in claimed || p.s in assigned) continue
            claimed += p.pin.id
            assigned += p.s
            p.pin.lastSeenNs = nowNs
            mergePending = true
        }
        val born = ArrayList<Pin>()
        sightings.forEachIndexed { i, s ->
            if (i in assigned || !mayCreate) return@forEachIndexed
            accumulate(s, nowNs)?.let { born += it }
        }
        return born
    }

    private fun accumulate(s: Sighting, nowNs: Long): Pin? {
        val cand = candidates.firstOrNull { it.code == s.code && lateral(s.ray, it.positions.last()) < PIN_CANDIDATE_RADIUS_M }
        if (cand == null) {
            candidates += Candidate(s.code).also {
                it.positions.addLast(s.hit)
                it.lastSeenNs = nowNs
            }
            return null
        }
        cand.positions.addLast(s.hit)
        if (cand.positions.size > PIN_MAX_SAMPLES) cand.positions.removeFirst()
        cand.lastSeenNs = nowNs
        if (cand.positions.size < PIN_CONFIRM_COUNT) return null
        candidates.remove(cand)
        // Born at the raw latest agreeing hit, not an average (old AR_MARKER_SMOOTHING = false), as a world anchor
        val pin = Pin(nextId++, s.code, Pose(s.hit, s.rotation)).also { it.lastSeenNs = nowNs }
        list += pin
        mergePending = true
        return pin
    }

    /**
     * The core's unit points ([io.packagex.arcount.CountView.unitPoints]) take their pins: the pin already following a
     * unit; else, nearest first, a free pin of its code (a provisional one within [PIN_ASSIGN_RADIUS_M], or one that
     * followed a unit no longer reported, e.g. of a closed section, within [PIN_CORE_MOVE_M]); else a new pin. A pin
     * is placed at the core's point when it was provisional or the point moved more than [PIN_CORE_MOVE_M]; the pins
     * placed (new or moved) go back, for new anchors. Pins of units no longer reported stay where they are.
     */
    fun follow(points: List<UnitPoint>, nowNs: Long): List<Pin> {
        if (points.isEmpty()) return emptyList()
        val placed = ArrayList<Pin>()
        val reported = points.mapTo(HashSet()) { it.key }
        val taken = HashSet<Int>()
        val open = ArrayList<UnitPoint>()
        for (u in points) {
            val pin = list.firstOrNull { it.unitKey == u.key }
            if (pin == null) open += u else {
                taken += pin.id
                if (moveTo(pin, u)) placed += pin
            }
        }
        data class Pairing(val u: UnitPoint, val pin: Pin, val d: Double)
        val pairings = ArrayList<Pairing>()
        for (u in open) {
            for (pin in list) {
                if (pin.code != u.code || pin.id in taken || pin.unitKey in reported) continue
                val d = (pin.position - u.world).norm()
                if (d <= if (pin.fromCore) PIN_CORE_MOVE_M else PIN_ASSIGN_RADIUS_M) pairings += Pairing(u, pin, d)
            }
        }
        pairings.sortBy { it.d }
        val linked = HashSet<String>()
        for (p in pairings) {
            if (p.pin.id in taken || p.u.key in linked) continue
            taken += p.pin.id
            linked += p.u.key
            p.pin.unitKey = p.u.key
            if (moveTo(p.pin, p.u)) placed += p.pin
        }
        for (u in open) {
            if (u.key in linked) continue
            val pin = Pin(nextId++, u.code, Pose(u.world, Quat.IDENTITY)).also {
                it.unitKey = u.key
                it.corePoint = u.world
                it.lastSeenNs = nowNs
            }
            list += pin
            placed += pin
            mergePending = true
        }
        return placed
    }

    private fun moveTo(pin: Pin, u: UnitPoint): Boolean {
        val c = pin.corePoint
        if (c != null && (c - u.world).norm() <= PIN_CORE_MOVE_M) return false
        pin.corePoint = u.world
        pin.pose = Pose(u.world, Quat.IDENTITY)
        pin.position = u.world
        return true
    }

    /** The pin's anchor could not be (re)made at the core's point: it is placed again on the next [follow] */
    fun unplace(id: Int) {
        list.firstOrNull { it.id == id }?.corePoint = null
    }

    /**
     * Old mergeSiblingMarkers, for provisional pins: a provisional pin within [PIN_ASSIGN_RADIUS_M] of another pin of
     * its code is a birth race or the core's pin of the same barcode; it goes (two provisional ones: the most recently
     * seen stays, a tie keeps the later one). Two pins at the core's points are never merged: the core tells identical
     * units a pitch apart. The removed pins go back, for their anchors.
     */
    fun mergeSiblings(): List<Pin> {
        mergePending = false
        if (list.size < 2) return emptyList()
        val removed = ArrayList<Pin>()
        var i = 0
        while (i < list.size) {
            var j = i + 1
            while (j < list.size) {
                val a = list[i]
                val b = list[j]
                if (a.code == b.code && !(a.fromCore && b.fromCore) && (a.position - b.position).norm() <= PIN_ASSIGN_RADIUS_M) {
                    val keepA = if (a.fromCore != b.fromCore) a.fromCore else a.lastSeenNs > b.lastSeenNs
                    if (keepA) {
                        removed += b
                    } else {
                        removed += a
                        list[i] = b
                    }
                    list.removeAt(j)
                    continue
                }
                j++
            }
            i++
        }
        return removed
    }

    /** A pin whose anchor could not be made */
    fun remove(id: Int) {
        list.removeAll { it.id == id }
    }

    /** New Scan */
    fun clear() {
        list.clear()
        candidates.clear()
        mergePending = false
    }
}

/** How a pin is drawn: green dot (its code counted), grey dot (listed, none counted yet) */
enum class PinColour { COUNTED, LISTED }

/** The code keys of the item list, as the core keys its units ([ItemCode.key]) */
fun listedKeys(listed: Set<String>): Set<String> = listed.mapTo(HashSet()) { ItemCode.key(it) }

/**
 * The colour of a pin of [code] (a key) against the item list's [keys] and the counter's [items]: counted when its
 * code's [ItemCount.countLow] is above 0; null when the code is not listed (not drawn).
 */
fun pinColour(code: String, keys: Set<String>, items: List<ItemCount>): PinColour? {
    if (code !in keys) return null
    return if (items.any { it.countLow > 0 && ItemCode.key(it.code) == code }) PinColour.COUNTED else PinColour.LISTED
}
