package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.ItemCode
import io.packagex.arcount.ItemCount
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.Vec3
import kotlin.math.acos
import kotlin.math.hypot

/*
 * AR Item Count's pins: one persistent marker per physical barcode of a listed code, placed and removed exactly as the
 * iOS demo's AR scanner does (vision-sdk-ios main cabf9d9c, `Ported/ARBarcode/ScannerController.swift`), with its
 * constants. Pure: the GL thread's [ArPins] feeds it ARCore's hits and holds the anchors.
 */

/** A new pin needs this many agreeing sightings (iOS candidateConfirmCount) */
internal const val PIN_CONFIRM_COUNT = 3

/** A sighting joins a candidate whose last position lies this close to its ray, laterally (iOS candidateMatchRadius) */
internal const val PIN_CANDIDATE_RADIUS_M = 0.08

/** A candidate not seen for this long is dropped (iOS candidateTimeout, 1.5 s) */
internal const val PIN_CANDIDATE_TIMEOUT_NS = 1_500_000_000L

/** Sightings kept per candidate (iOS maxCandidateSamples) */
internal const val PIN_MAX_SAMPLES = 7

/** A sighting re-sights a pin of its code within this lateral distance of its ray, on top of the box check (iOS worldAssignRadius) */
internal const val PIN_ASSIGN_RADIUS_M = 0.12

/** Same-code pins this close are one barcode (a birth race): the most recently seen stays (iOS siblingMergeRadius) */
internal const val PIN_SIBLING_MERGE_M = 0.15

/** Hits farther than this from the capture camera are bad geometry (iOS worldMaxHitDistance) */
internal const val PIN_MAX_HIT_M = 3.0

/** A read batch older than this, against the newest frame, is dropped (iOS place(): 0.5 s) */
internal const val PIN_MAX_BATCH_AGE_NS = 500_000_000L

/** Tracked frames before pins are placed (iOS mapReadyMinFrames) */
internal const val PIN_WARM_UP_FRAMES = 60

/** Moving moderately: new pins OK (iOS cameraModerate, 0.40 m/s and 60 deg/s) */
internal const val PIN_MODERATE_MPS = 0.40
internal const val PIN_MODERATE_DPS = 60.0

/** The read's box is inflated by this fraction of its width and height on each side (iOS viewRect insetBy -0.3) */
internal const val PIN_BOX_INFLATE = 0.3

/** The match radius never drops below this many points / dp on screen (iOS matchRadius floor, 40) */
internal const val PIN_MATCH_FLOOR_DP = 40f

/**
 * iOS updateMotionEstimate and the map warm-up, on the GL thread, in the reads' clock (the frames'
 * `androidCameraTimestamp`). Every frame: speed and the forward vector's turn rate (over 0.1 ms to 0.5 s), and
 * [lastImmoderateNs], the last frame moving faster than moderate; tracked frames count toward [mapReady].
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
        if (tracking && !mapReady) warmFrames++
        val p = prev
        val dtS = (timestampNs - prevNs) / 1e9
        if (p != null && dtS > 1e-4 && dtS < 0.5) {
            speedMps = (camera.t - p.t).norm() / dtS
            // The camera looks down -Z of its own frame
            val dot = (camera.rotate(FORWARD) dot p.rotate(FORWARD)).coerceIn(-1.0, 1.0)
            rotationDps = Math.toDegrees(acos(dot)) / dtS
        }
        if (!cameraModerate) lastImmoderateNs = timestampNs
        prev = camera
        prevNs = timestampNs
    }

    /** iOS place(): a batch captured at [captureNs] is too stale at the frame [nowNs] */
    fun fresh(captureNs: Long, nowNs: Long): Boolean = nowNs - captureNs <= PIN_MAX_BATCH_AGE_NS

    /** iOS accumulateCandidate's gate: warmed up, moderate now, and moderate since before the capture */
    fun mayCreate(captureNs: Long): Boolean = mapReady && cameraModerate && lastImmoderateNs < captureNs

    private companion object {
        val FORWARD = Vec3(0.0, 0.0, -1.0)
    }
}

/**
 * One read of a listed code, hit-tested (iOS place()'s `Placed`): its [code] key ([ItemCode.key]), the world [hit]
 * (with the hit's [rotation]) of its centre [ray]; in pixels of its own (capture-time) stream image its [centreU],
 * [centreV], its corners' box inflated by [PIN_BOX_INFLATE] ([minU] .. [maxV]) and [matchRadiusPx]; and that image's
 * [camera] and [intrinsics], to project pins into it.
 */
data class Sighting(
    val code: String,
    val hit: Vec3,
    val rotation: Quat,
    val ray: Ray,
    val centreU: Double,
    val centreV: Double,
    val minU: Double,
    val minV: Double,
    val maxU: Double,
    val maxV: Double,
    val matchRadiusPx: Double,
    val camera: Pose,
    val intrinsics: Intrinsics,
)

/**
 * The [Sighting] of [read] in its frame [capture], hit at [hit]: the box of its corners, inflated by [PIN_BOX_INFLATE]
 * of its size on each side, and a match radius of the inflated box's larger side, at least [floorPx] (iOS's 40 points,
 * in pixels of the stream image).
 */
fun sightingOf(read: Read, capture: PoseRecord, hit: Vec3, rotation: Quat, ray: Ray, floorPx: Double): Sighting {
    val xs = (0 until 4).map { read.corners[2 * it] }
    val ys = (0 until 4).map { read.corners[2 * it + 1] }
    val w = xs.max() - xs.min()
    val h = ys.max() - ys.min()
    val minU = xs.min() - w * PIN_BOX_INFLATE
    val maxU = xs.max() + w * PIN_BOX_INFLATE
    val minV = ys.min() - h * PIN_BOX_INFLATE
    val maxV = ys.max() + h * PIN_BOX_INFLATE
    val radius = maxOf(maxU - minU, maxV - minV, floorPx)
    return Sighting(ItemCode.key(read), hit, rotation, ray, read.centreU, read.centreV, minU, minV, maxU, maxV, radius, capture.camera, capture.intrinsics)
}

/** A depth point hit needs at least this raw depth confidence (0-255) where it lies: depth-from-motion is noisy on plain, flat or dim surfaces */
internal const val MIN_DEPTH_CONFIDENCE = 128

/**
 * Where [hit] (world) lies in the image of the frame [camera] and [intrinsics] are of, normalized 0..1 of that image
 * (ARCore's IMAGE_NORMALIZED once mapped to the CPU image); null when it is behind the camera.
 */
fun imageNormalized(hit: Vec3, camera: Pose, intrinsics: Intrinsics): Pair<Double, Double>? =
    intrinsics.project(camera.inverse().apply(hit))?.let { (u, v) -> u / intrinsics.width to v / intrinsics.height }

/** The pixel of a [width] x [height] depth image at texture-normalized ([texU], [texV]); null outside it */
fun depthPixel(texU: Float, texV: Float, width: Int, height: Int): Pair<Int, Int>? {
    if (!(texU >= 0f && texU < 1f && texV >= 0f && texV < 1f)) return null
    return (texU * width).toInt().coerceIn(0, width - 1) to (texV * height).toInt().coerceIn(0, height - 1)
}

/** Whether a depth point hit passes the gate: no confidence known (no depth image, outside it) passes, as no gate */
fun depthConfident(confidence: Int?): Boolean = confidence == null || confidence >= MIN_DEPTH_CONFIDENCE

/** How far a hit lies from its read once reprojected into the read's own frame, in pixels, and its depth there (diagnostics) */
data class HitCheck(val errorPx: Double, val depthM: Double)

fun checkHit(hit: Vec3, read: Read, capture: PoseRecord): HitCheck {
    val inCamera = capture.camera.inverse().apply(hit)
    val px = capture.intrinsics.project(inCamera)
    val error = px?.let { (u, v) -> hypot(u - read.centreU, v - read.centreV) } ?: Double.POSITIVE_INFINITY
    return HitCheck(error, -inCamera.z)
}

/** A pin of [code]: its anchor's birth [pose], its world [position] (the anchor's last tracked one), and when a sighting last claimed it */
class Pin(val id: Int, val code: String, val pose: Pose) {
    var position: Vec3 = pose.t
    var lastSeenNs = 0L
}

/**
 * Perpendicular distance from [p] to [ray], depth-independent so noisy hit depth can't make the same barcode look like
 * a new copy; [Double.MAX_VALUE] for a point behind the ray's origin (iOS lateralDistance).
 */
fun lateral(ray: Ray, p: Vec3): Double {
    val v = p - ray.origin
    val along = v dot ray.dir
    if (along <= 0.0) return Double.MAX_VALUE
    return (v - ray.dir * along).norm()
}

/**
 * The pins and the candidates of one session (iOS ScannerController's markers and candidates). A pin is never moved by a
 * sighting (only ARCore moves its anchor) and is removed only by [mergeSiblings] and [clear], as on iOS.
 */
class PinBook {
    private class Candidate(val code: String) {
        val positions = ArrayList<Vec3>()
        var lastRotation = Quat.IDENTITY
        var lastSeenNs = 0L
    }

    private val list = ArrayList<Pin>()
    private val candidates = ArrayList<Candidate>()
    private var nextId = 1

    val pins: List<Pin> get() = list
    val candidateCount: Int get() = candidates.size

    /**
     * One batch captured at [captureNs], placed at the frame [nowNs] (iOS place()): every (sighting, pin) pairing of
     * one code whose pin, projected into the sighting's own frame, lies in its inflated box or within its match radius
     * of its centre, and within [PIN_ASSIGN_RADIUS_M] of its ray; claimed greedily, smallest lateral distance first,
     * each pin and each sighting at most once; a claimed pin was seen at [nowNs], and the siblings merge. Each unclaimed
     * sighting feeds the candidates when [mayCreate]; a confirmed one becomes a pin once [anchor] made its anchor, and
     * the siblings merge. The pins removed by the merges go back, for their anchors.
     */
    fun place(sightings: List<Sighting>, captureNs: Long, nowNs: Long, mayCreate: Boolean, anchor: (Pin) -> Boolean): List<Pin> {
        if (sightings.isEmpty()) return emptyList()
        val removed = ArrayList<Pin>()
        data class Pairing(val s: Int, val pin: Pin, val lateral: Double)
        val pairings = ArrayList<Pairing>()
        sightings.forEachIndexed { i, s ->
            for (pin in list) {
                if (pin.code != s.code) continue
                val (u, v) = s.intrinsics.project(s.camera.inverse().apply(pin.position)) ?: continue
                val inBox = u in s.minU..s.maxU && v in s.minV..s.maxV
                if (!inBox && hypot(u - s.centreU, v - s.centreV) > s.matchRadiusPx) continue
                val d = lateral(s.ray, pin.position)
                if (d <= PIN_ASSIGN_RADIUS_M) pairings += Pairing(i, pin, d)
            }
        }
        pairings.sortBy { it.lateral }
        val claimed = HashSet<Int>()
        val owned = HashSet<Int>()
        for (p in pairings) {
            if (p.pin.id in claimed || p.s in owned) continue
            claimed += p.pin.id
            owned += p.s
            p.pin.lastSeenNs = nowNs
        }
        if (claimed.isNotEmpty()) removed += mergeSiblings()
        sightings.forEachIndexed { i, s ->
            if (i in owned) return@forEachIndexed
            if (accumulate(s, captureNs, nowNs, mayCreate, anchor)) removed += mergeSiblings()
        }
        return removed
    }

    /** iOS accumulateCandidate; true when a pin was born */
    private fun accumulate(s: Sighting, captureNs: Long, nowNs: Long, mayCreate: Boolean, anchor: (Pin) -> Boolean): Boolean {
        if (!mayCreate) return false
        candidates.removeAll { captureNs - it.lastSeenNs > PIN_CANDIDATE_TIMEOUT_NS }
        val cand = candidates.firstOrNull { it.code == s.code && lateral(s.ray, it.positions.last()) < PIN_CANDIDATE_RADIUS_M }
        if (cand == null) {
            candidates += Candidate(s.code).also {
                it.positions += s.hit
                it.lastRotation = s.rotation
                it.lastSeenNs = captureNs
            }
            return false
        }
        cand.positions += s.hit
        if (cand.positions.size > PIN_MAX_SAMPLES) cand.positions.removeAt(0)
        cand.lastRotation = s.rotation
        cand.lastSeenNs = captureNs
        if (cand.positions.size < PIN_CONFIRM_COUNT) return false
        candidates.remove(cand)
        // Born at the per-axis median of the agreeing sightings, turned as the last hit (iOS birthTransform)
        val pin = Pin(nextId++, s.code, Pose(median(cand.positions), cand.lastRotation)).also { it.lastSeenNs = nowNs }
        if (!anchor(pin)) return false
        list += pin
        return true
    }

    /**
     * iOS mergeSiblingMarkers: same-code pins within [PIN_SIBLING_MERGE_M] of each other are a birth race; the most
     * recently seen stays (a tie keeps the later one). Copies farther apart each keep their own pin.
     */
    fun mergeSiblings(): List<Pin> {
        if (list.size < 2) return emptyList()
        val removed = ArrayList<Pin>()
        var i = 0
        while (i < list.size) {
            var j = i + 1
            while (j < list.size) {
                val a = list[i]
                val b = list[j]
                if (a.code == b.code && (a.position - b.position).norm() <= PIN_SIBLING_MERGE_M) {
                    if (a.lastSeenNs > b.lastSeenNs) {
                        removed += b
                        list.removeAt(j)
                    } else {
                        removed += a
                        list.removeAt(i)
                        j = i + 1
                    }
                    continue
                }
                j++
            }
            i++
        }
        return removed
    }

    /** New Scan, or leaving the mode */
    fun clear() {
        list.clear()
        candidates.clear()
    }

    private fun median(ps: List<Vec3>): Vec3 {
        fun m(v: List<Double>): Double {
            val s = v.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        }
        return Vec3(m(ps.map { it.x }), m(ps.map { it.y }), m(ps.map { it.z }))
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
