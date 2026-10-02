package io.packagex.visiondemo.ar

import io.packagex.arcount.Gtin
import io.packagex.arcount.ItemCount
import io.packagex.arcount.Pose
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Vec3
import kotlin.math.abs
import kotlin.math.acos

/*
 * AR Item Count's pins: one persistent marker per physical barcode, independent of the counting core. The rules are the
 * old AR Barcode renderer's (ArBarcodeRenderer at ceff1a4^, "markers without animation or smoothing"), with its
 * constants; iOS ScannerController is its port. Pure: the GL thread's [ArPins] feeds it ARCore's hits and holds the
 * anchors.
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

/** Hits farther than this from the capture camera are bad geometry (old 3.0 m check) */
internal const val PIN_MAX_HIT_M = 3.0

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

/** One read, hit-tested: its [payload], the world [hit] (with the hit's [rotation]) of its capture-time centre [ray] */
data class Sighting(val payload: String, val symbology: String?, val hit: Vec3, val rotation: Quat, val ray: Ray)

/** A pin: its world [position] (the anchor's last tracked one), birth [pose], and when a sighting last claimed it */
class Pin(val id: Int, val payload: String, val symbology: String?, val pose: Pose) {
    var position: Vec3 = pose.t
    var lastSeenNs = 0L
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
 * The pins and the candidates of one session (old ArBarcodeRenderer's markers and candidates): exclusive one-to-one
 * assignment of a batch's sightings to same-payload pins by smallest lateral distance; the rest feed candidates, which
 * become pins after [PIN_CONFIRM_COUNT] agreeing sightings, born at the latest one. A pin is never moved by a sighting
 * (only ARCore moves its anchor) and never expires: it goes on [clear] (New Scan) or a sibling merge.
 */
class PinBook {
    private class Candidate(val payload: String, val symbology: String?) {
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
                if (pin.payload != s.payload) continue
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
        val cand = candidates.firstOrNull { it.payload == s.payload && lateral(s.ray, it.positions.last()) < PIN_CANDIDATE_RADIUS_M }
        if (cand == null) {
            candidates += Candidate(s.payload, s.symbology).also {
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
        val pin = Pin(nextId++, s.payload, s.symbology, Pose(s.hit, s.rotation)).also { it.lastSeenNs = nowNs }
        list += pin
        mergePending = true
        return pin
    }

    /**
     * Old mergeSiblingMarkers: same-payload pins at (nearly) the same spot, within [PIN_ASSIGN_RADIUS_M], are a birth
     * race; the most recently seen stays (a tie keeps the later one). Same-payload pins farther apart are different
     * physical copies and both stay. The removed pins go back, for their anchors.
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
                if (a.payload == b.payload && (a.position - b.position).norm() <= PIN_ASSIGN_RADIUS_M) {
                    if (a.lastSeenNs > b.lastSeenNs) {
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

/** How a pin is drawn: green dot (listed, counted), grey dot (listed, none counted yet), white outline (not listed) */
enum class PinColour { COUNTED, LISTED, UNLISTED }

/**
 * [payload]'s colour against the item list [listed] and the counter's [items]: GTINs compare as 14 digits, as the
 * counter does (and as the neutral rings did); counted when its code's [ItemCount.countLow] is above 0.
 */
fun pinColour(payload: String, symbology: String?, listed: Set<String>, items: List<ItemCount>): PinColour {
    val key = Gtin.normalize(payload, symbology)
    if (listed.none { Gtin.normalize(it) == key }) return PinColour.UNLISTED
    return if (items.any { it.countLow > 0 && Gtin.normalize(it.code) == key }) PinColour.COUNTED else PinColour.LISTED
}
