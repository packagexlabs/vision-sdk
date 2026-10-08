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
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * AR Item Count's pins: one persistent marker per physical barcode of a listed code. [PinRules.IOS] places and removes
 * them exactly as the iOS demo's AR scanner does (vision-sdk-ios main cabf9d9c, `Ported/ARBarcode/ScannerController.swift`),
 * with its constants, and never moves them. [PinRules.ANDROID] runs the drift plan's §3.4: the identity rules (2, 3, 5, 6
 * and 7, Phase 3), identity coming from geometry, as labels cannot overlap and identical units sit a label apart (I5),
 * never from recency; and, with [PinBook.refine], each pin refined from the ray of every read that claims it,
 * re-initialised after three bad claims in a row (rules 1, 4 and 9, Phase 4, [PinEstimator]). Pure: the GL thread's
 * [ArPins] feeds it ARCore's hits and anchor poses, and holds the anchors.
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

// [PinRules.ANDROID] (drift plan §3.4)

/** Rule 2: a code's pitch, learned from two of its reads in one image, is never below this */
internal const val PIN_MIN_PITCH_M = 0.02

/** Rule 3: a claim farther than this many pixels of a 4K stream image (or half its quad's width) off its pin is bad */
internal const val PIN_BAD_PX = 60.0

/** Rule 3: a bad claim this soon after the pin's last good one, with no map correction since, is voided */
internal const val PIN_VOID_NS = 1_000_000_000L

/** Rule 4: this many counted bad claims in a row re-initialise a pin from their rays ([PinEstimator.bad]) */
internal const val PIN_REINIT_BAD = 3

/** Rule 5: a sighting joins a candidate whose median lies within half the label's width of its ray, and never less than this */
internal const val PIN_CANDIDATE_MIN_M = 0.01

/** Rule 6: same-code pins this close (or within half their label's width, if less) are one label */
internal const val PIN_MERGE_M = 0.015

/** Rule 7: a pin younger than this is never retired */
internal const val PIN_RETIRE_AGE_NS = 1_000_000_000L

/** Rule 7: a pin misses a batch only where it lies at least this fraction of the image inside its border ... */
internal const val PIN_RETIRE_MARGIN = 0.1

/** ... and within this camera depth */
internal const val PIN_RETIRE_MAX_M = 1.5

/** Rule 7: a pin that missed this many batches over [PIN_RETIRE_NS], with no claim between, is retired */
internal const val PIN_RETIRE_BATCHES = 6
internal const val PIN_RETIRE_NS = 2_000_000_000L

/**
 * Rule 7: a miss this long after its run's first restarts the run, so misses left from an earlier look at the pin
 * (UNIT-A's pin 3 on 2026-10-07, retired on re-entering the view 62 s after its last claim) no longer add up with new
 * ones. Three times [PIN_RETIRE_NS]: twice is enough for a ghost in plain view (a miss per batch reading its code, 3 or
 * more a second on the Memor), but a pin left by a 20 cm map shift is in view only part of each sweep and must still
 * go within 6 s (PinTwinTest; at 4 s it took 8.4 s)
 */
internal const val PIN_RETIRE_WINDOW_NS = 3 * PIN_RETIRE_NS

/**
 * Rule 7: with refinement on ([PinBook.refine], what the app runs), a pin with this many good claims or more is counted
 * (the counter counts a unit on its second read, Units.kt: TENTATIVE -> COUNTED) and is not retired as a ghost: a
 * counted item that vanished would leave the count and the pins disagreeing. Only after a map correction, once a newer
 * pin of its code has taken over its label ([PinBook] rule 7), does a counted pin go; a pin read fewer times, never
 * counted, can go as a ghost. With refinement off (frozen pins, the legacy setting) every pin keeps the old retirement:
 * a frozen pin cannot re-initialise, so its re-birth and retirement are its only way back onto its label
 */
internal const val PIN_COUNTED_CLAIMS = 2

// ponytail: 0.3 m, a map correction's reach (11:53: ARCore came back 16 cm off after a lost second). A ceiling: two
// units of one code over 0.3 m apart that no image shows together keep both pins only if no map correction comes
/** Rule 7: a single unit's pin left behind by a map correction goes for a newer pin of its code this near */
internal const val PIN_RELOCATE_M = 0.3

/** Rule 7: an engine box the batch's image did not decode, inflated by this fraction a side, shields at most one pin */
internal const val PIN_SHIELD_INFLATE = 0.1

// ponytail: from the 2026-10-07 traces. A scan-line decode's quad is far flatter than its code's other reads (15:03,
// UNIT-T: aspects 0.00-0.20 against 0.30-0.40, and 25 of 53 landed 1.1-1.6 cm off every unit, against 1 of 218 wider ones),
// but a long label is flat too: PX-0001-2026-ARCOUNT reads at 0.15-0.20 every time, and a fixed 0.2 bore it no pin at all
// (11:53). So a read is thin against its own code's reads: under [PIN_THIN_SHARE] of their median aspect, or, before
// [PIN_THIN_MIN_READS] of them, under [PIN_THIN_FLOOR]
/** Rule 5: a read whose quad's aspect (short side / long side) is under this share of its code's median feeds no candidate */
internal const val PIN_THIN_SHARE = 0.5

/** Rule 5: before this many reads of a code, a read is thin under [PIN_THIN_FLOOR] */
internal const val PIN_THIN_MIN_READS = 5

/** Rule 5: the aspect under which a read is thin while its code has fewer than [PIN_THIN_MIN_READS] reads */
internal const val PIN_THIN_FLOOR = 0.12

/** Rule 5: how many of a code's newest reads' aspects make its median */
internal const val PIN_THIN_HISTORY = 31

// ponytail: 0.5, from the 2026-10-07 traces: the ghost births of 15:03 lay 0.6-1.9 cm from a pin of their code (pitch
// 3.2 and 12 cm), real units' births at least 1.8 cm (UNIT-T, 3.2 cm pitch) from the nearest; 0.75 would have stopped two
// of those. A ceiling: a pin off its label by more than this still lets its label's pin be born beside it
/** Rule 5: once a code's pitch is known, no pin of it is born nearer than this share of the pitch to one of its pins */
internal const val PIN_BIRTH_GUARD = 0.5

// ponytail: 1 cm, under the 2 cm a corroborating depth point may lie off a plane; 2026-10-07 (second replay of 15:54's
// recording) ARCore moved the table from y -0.19 to -0.117 and the pins held at -0.19 landed 172-359 px off their units
/** A pin held on a plane follows it once ARCore's plane lies more than this off its point, metres ([Pin.followPlane]) */
internal const val PIN_PLANE_FOLLOW_M = 0.01

/** A plane hit's normal in its pose's frame (ARCore: the hit pose's y axis) */
private val UP = Vec3(0.0, 1.0, 0.0)

// ponytail: 10 s from the session's first tracked frame. 2026-10-07: the first plane came 4.6, 6.6, 8.9 and 5.2-7.3 s
// after tracking began in the runs that counted right (21.5 s at 14:46, 29.7 s at 12:34), so 5 s mostly gave up just
// before it; with none, every pin of 14:07 and 14:10 was born 2-4 s in, at the default depth or the nominal width.
// Raise it to wait longer for a surface ARCore is slow to find; lower it if a surface it never finds (glass, a plain
// white desk) keeps the user waiting
/** Rule 7's cover for a counted pin: the birth guard's radius ([PIN_BIRTH_GUARD] of [pitch]), its label's [widthM] before a pitch is known */
internal fun takeOverRadius(pitch: Double?, widthM: Double): Double = pitch?.let { PIN_BIRTH_GUARD * it } ?: widthM.takeIf { it > 0.0 } ?: PIN_MERGE_M

/**
 * Rule 7: whether [q] takes the counted [pin]'s place: a pin of its code born since [pin]'s last good claim, [lateralM] off
 * the ray its label's reads land on now, within [radius] ([takeOverRadius]); never a neighbouring unit's older pin
 */
internal fun takesOver(q: Pin, pin: Pin, lateralM: Double, radius: Double): Boolean =
    q.code == pin.code && q.bornNs > pin.lastGoodNs && lateralM <= radius

/** Rule 5's surface gate holds births this long after the first tracked frame while no plane tracks, then gives up */
internal const val GATE_TIMEOUT_NS = 10_000_000_000L

/**
 * Rule 5's surface gate for a session: births need a real surface ([needsSurface]) once a plane tracks, and before that
 * until the session has tracked for [GATE_TIMEOUT_NS]; then, with still no plane, the old births return (the nominal
 * width, the default depth), so a surface ARCore never finds still counts. A plane found later closes it again.
 */
class BirthGate {
    enum class State(val trace: String) { HOLDING("holding"), SURFACE("surface"), FALLBACK("fallback") }

    private var trackedSinceNs = Long.MIN_VALUE

    /** Whether a plane has tracked in this session */
    var planeSeen = false
        private set

    var state = State.HOLDING
        private set

    /** One frame at [timestampNs]: whether it tracks, and whether a plane tracks now ([planeTracks], sticky) */
    fun frame(timestampNs: Long, tracking: Boolean, planeTracks: Boolean) {
        if (tracking && trackedSinceNs == Long.MIN_VALUE) trackedSinceNs = timestampNs
        if (planeTracks) planeSeen = true
        state = when {
            planeSeen -> State.SURFACE
            trackedSinceNs != Long.MIN_VALUE && timestampNs - trackedSinceNs >= GATE_TIMEOUT_NS -> State.FALLBACK
            else -> State.HOLDING
        }
    }

    val needsSurface: Boolean get() = state != State.FALLBACK

    /** A new session */
    fun reset() {
        trackedSinceNs = Long.MIN_VALUE
        planeSeen = false
        state = State.HOLDING
    }
}

/** A pin or candidate never counts as nearer than this to the camera, metres (the label width and pitch at its range, a re-init's depth) */
internal const val PIN_MIN_RANGE_M = 0.1

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
            speedMps = norm(camera.t.x - p.t.x, camera.t.y - p.t.y, camera.t.z - p.t.z) / dtS
            // The camera looks down -Z of its own frame (every frame: as scalars, the same arithmetic as rotate and dot)
            val f = FORWARD
            val dot = rotated(camera.q, f.x, f.y, f.z) { ax, ay, az ->
                rotated(p.q, f.x, f.y, f.z) { bx, by, bz -> ax * bx + ay * by + az * bz }
            }.coerceIn(-1.0, 1.0)
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
 * [centreV], its corners' box inflated by [PIN_BOX_INFLATE] ([minU] .. [maxV]) and [matchRadiusPx]; that image's
 * [camera] and [intrinsics], to project pins into it; whether the hit lies on a plane ([onPlane]) and where its point
 * came from ([source]: a valid hit, the nominal width, the nearest hit, or no hit at all, [guessed]); its quad's width
 * ([Read.widthPx]), its next side ([heightPx]) and its symbology's nominal width ([nominalWidthM], metres; 0 none).
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
    val onPlane: Boolean = false,
    val source: HitSource = HitSource.HIT,
    val widthPx: Double = 0.0,
    val nominalM: Double = 0.0,
    val heightPx: Double = 0.0,
) {
    /** No hit at all: [defaultPick]'s point */
    val guessed: Boolean get() = source == HitSource.DEFAULT

    /** Its quad's short side over its long side; NaN with no size */
    val aspect: Double get() = if (widthPx > 0.0 && heightPx > 0.0) minOf(widthPx, heightPx) / maxOf(widthPx, heightPx) else Double.NaN
}

/**
 * The [Sighting] of [read] in its frame [capture], hit at [hit] ([onPlane]: on a plane; [source]: where the point came
 * from): the box of its corners, inflated by [PIN_BOX_INFLATE] of its size on each side, and a match radius of the
 * inflated box's larger side, at least [floorPx] (iOS's 40 points, in pixels of the stream image). [code] and
 * [nominalM] are the read's key and its symbology's nominal width, for a caller that has them already.
 */
fun sightingOf(
    read: Read,
    capture: PoseRecord,
    hit: Vec3,
    rotation: Quat,
    ray: Ray,
    floorPx: Double,
    onPlane: Boolean = false,
    source: HitSource = HitSource.HIT,
    code: String = ItemCode.key(read),
    nominalM: Double = nominalWidthM(read.symbology) ?: 0.0,
): Sighting {
    // Each axis's min and max folded from the first corner, as Iterable<Double>.min()/max() fold them (Math.min/max)
    val c = read.corners
    var minX = c[0]
    var maxX = minX
    var minY = c[1]
    var maxY = minY
    for (i in 1 until 4) {
        val x = c[2 * i]
        val y = c[2 * i + 1]
        minX = min(minX, x)
        maxX = max(maxX, x)
        minY = min(minY, y)
        maxY = max(maxY, y)
    }
    val w = maxX - minX
    val h = maxY - minY
    val minU = minX - w * PIN_BOX_INFLATE
    val maxU = maxX + w * PIN_BOX_INFLATE
    val minV = minY - h * PIN_BOX_INFLATE
    val maxV = maxY + h * PIN_BOX_INFLATE
    val radius = maxOf(maxU - minU, maxV - minV, floorPx)
    return Sighting(
        code, hit, rotation, ray, read.centreU, read.centreV, minU, minV, maxU, maxV, radius, capture.camera, capture.intrinsics,
        onPlane, source, read.widthPx, nominalM, hypot(c[4] - c[2], c[5] - c[3]),
    )
}

/** A depth point hit needs at least this raw depth confidence (0-255) where it lies: depth-from-motion is noisy on plain, flat or dim surfaces */
internal const val MIN_DEPTH_CONFIDENCE = 128

/**
 * Where [hit] (world) lies in the image of the frame [camera] and [intrinsics] are of, normalized 0..1 of that image
 * (ARCore's IMAGE_NORMALIZED once mapped to the CPU image); null when it is behind the camera. [toCamera] is
 * [camera]'s inverse, for a caller that has it.
 */
fun imageNormalized(hit: Vec3, camera: Pose, intrinsics: Intrinsics, toCamera: Pose = camera.inverse()): Pair<Double, Double>? =
    applied(toCamera, hit.x, hit.y, hit.z) { x, y, z ->
        projected(intrinsics, x, y, z, { null }) { u, v -> u / intrinsics.width to v / intrinsics.height }
    }

/** The pixel of a [width] x [height] depth image at texture-normalized ([texU], [texV]); null outside it */
fun depthPixel(texU: Float, texV: Float, width: Int, height: Int): Pair<Int, Int>? {
    if (!(texU >= 0f && texU < 1f && texV >= 0f && texV < 1f)) return null
    return (texU * width).toInt().coerceIn(0, width - 1) to (texV * height).toInt().coerceIn(0, height - 1)
}

/**
 * Whether a depth point hit passes the gate (drift plan Phase 2): no confidence known (no depth image, outside it) fails,
 * as a depth from motion nothing vouches for. [PinRules.IOS] still lets an unknown one pass.
 */
fun depthConfident(confidence: Int?): Boolean = confidence != null && confidence >= MIN_DEPTH_CONFIDENCE

/** How far a hit lies from its read once reprojected into the read's own frame, in pixels, and its depth there (diagnostics) */
data class HitCheck(val errorPx: Double, val depthM: Double)

/** [toCamera]: the inverse of [capture]'s camera, for a caller that has it */
fun checkHit(hit: Vec3, read: Read, capture: PoseRecord, toCamera: Pose = capture.camera.inverse()): HitCheck =
    applied(toCamera, hit.x, hit.y, hit.z) { x, y, z ->
        val error = projected(capture.intrinsics, x, y, z, { Double.POSITIVE_INFINITY }) { u, v -> hypot(u - read.centreU, v - read.centreV) }
        HitCheck(error, -z)
    }

/**
 * A pin of [code]: its anchor's birth [pose]; whether most of the sightings it was born from hit a plane ([bornOnPlane])
 * or hit nothing ([bornGuessed]: its depth is a guess); the capture time of its birth ([bornNs]) and its label's width
 * there ([widthM], metres; 0 unknown); its anchor's poses since ([anchors], rule 9) and its point in the anchor's frame
 * ([est], refined under [PinRules.ANDROID] with [PinBook.refine]; the anchor itself until then), so its world
 * [position] is the newest anchor pose applied to that point;
 * when a sighting last claimed it, how many claims it took ([claims]), the capture time of its last good one
 * ([lastGoodNs], its birth at first) and the bad ones since ([badInRow], rule 4); for rule 7, the batches since its last
 * good claim that read its code in view but not by it ([misses], the first at [firstMissNs]), its good claims so far
 * ([goodClaims]), the ray of its newest claim that missed it ([landedRay], at [landedNs]: where its label's reads land
 * once it left them) and the last batch that
 * read its code at all ([lastSightedNs]); and the frame time the
 * read-rate boost ran for it ([boostNs], P2c).
 */
class Pin(
    val id: Int,
    val code: String,
    val pose: Pose,
    val bornOnPlane: Boolean = false,
    val bornGuessed: Boolean = false,
    val bornNs: Long = 0L,
    val widthM: Double = 0.0,
) {
    val anchors = AnchorRing().also { it.add(bornNs, pose) }
    val est = PinEstimator()
    var lastSeenNs = 0L
    var claims = 0
    var lastGoodNs = bornNs
    var misses = 0
    var firstMissNs = 0L
    var goodClaims = 0
    var landedRay: Ray? = null
    var landedNs = Long.MIN_VALUE
    var lastSightedNs = 0L
    var boostNs = 0L

    /**
     * Rule 4's count while pins are frozen ([PinBook.refine] off, Phase 3), where no estimator runs, and the run's reads
     * before its [PIN_REINIT_BAD]th, with their capture times
     */
    internal var frozenBadInRow = 0
    internal val frozenRun = ArrayList<Pair<Sighting, Long>>(PIN_REINIT_BAD)

    /** Rule 4: its counted bad claims in a row, its estimator's or, frozen, [frozenBadInRow] (the other is 0) */
    val badInRow: Int get() = est.badInRow + frozenBadInRow

    /** A data-only fit passed the core's gate (rule 1): with refined pins a dot, else a ring ([drawnAsRing]) */
    val verified: Boolean get() = est.verified

    private val scratch = DoubleArray(3)
    private var cached: Vec3? = null
    private var cachedAnchors = -1
    private var cachedEst = -1

    /** Its newest anchor pose applied to its point */
    val position: Vec3
        get() {
            val c = cached
            if (c != null && cachedAnchors == anchors.version && cachedEst == est.version) return c
            return at(anchors.newest).also {
                cached = it
                cachedAnchors = anchors.version
                cachedEst = est.version
            }
        }

    /** Where it stood at a capture at [captureNs]: its anchor's pose then (rule 9) applied to its point now (§3.3 step 2.1) */
    fun positionAt(captureNs: Long): Vec3 = at(anchors.slotAt(captureNs))

    /** Its point's distance from its anchor, metres (rule 8) */
    val localNorm: Double get() = sqrt(est.x * est.x + est.y * est.y + est.z * est.z)

    /** [ArPins], each frame its anchor tracks: the anchor's pose then; how far it moved since the last pose kept, metres */
    fun anchored(timestampNs: Long, tx: Double, ty: Double, tz: Double, qx: Double, qy: Double, qz: Double, qw: Double): Double {
        anchors.add(timestampNs, tx, ty, tz, qx, qy, qz, qw)
        return anchors.lastStep()
    }

    /**
     * Rule 8: its anchor is now [to] (world, made where the old one put its point): the rays, the prior and the point
     * move into the new anchor's frame by A_new⁻¹·A_old, and every pose kept becomes the new anchor's on its frame
     */
    fun reanchor(to: Pose) {
        val old = anchors.pose(anchors.newest)
        est.moveBy(to.inverse() * old)
        anchors.compose(old.inverse() * to)
    }

    /**
     * Its plane is now the one through [at] with unit [normal] (world; ARCore re-estimated it): when its point lies more than
     * [PIN_PLANE_FOLLOW_M] off it, the point is held on the new plane from now on ([PinEstimator.lockToPlane]), sliding
     * along its newest ray, so it keeps its place in that image. True when it moved. Only a pin held on a plane.
     */
    fun followPlane(at: Vec3, normal: Vec3): Boolean {
        if (!est.onPlane) return false
        val w = position
        if (abs((w.x - at.x) * normal.x + (w.y - at.y) * normal.y + (w.z - at.z) * normal.z) <= PIN_PLANE_FOLLOW_M) return false
        val toAnchor = anchors.pose(anchors.newest).inverse()
        val c = toAnchor.apply(at)
        val n = toAnchor.rotate(normal)
        est.lockToPlane(c.x, c.y, c.z, n.x, n.y, n.z)
        return true
    }

    /** Rule 6: [other]'s newest rays, carried from its anchor's frame into this one's (their newest poses), refine this point */
    fun absorb(other: Pin) {
        if (!est.started || !other.est.started) return
        est.absorbNewest(other.est, PIN_ABSORB_RAYS, anchors.pose(anchors.newest).inverse() * other.anchors.pose(other.anchors.newest))
    }

    private fun at(slot: Int): Vec3 {
        anchors.apply(slot, est.x, est.y, est.z, scratch)
        return Vec3(scratch[0], scratch[1], scratch[2])
    }
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
 * Whether world point [p] lies in front of [camera], within [PIN_RETIRE_MAX_M], and [PIN_RETIRE_MARGIN] of its image
 * ([k]) inside the border; [toCamera] is [camera]'s inverse, for a caller that has it
 */
fun wellInside(p: Vec3, camera: Pose, k: Intrinsics, toCamera: Pose = camera.inverse()): Boolean =
    applied(toCamera, p.x, p.y, p.z) { x, y, z ->
        if (-z > PIN_RETIRE_MAX_M) {
            false
        } else {
            projected(k, x, y, z, { false }) { u, v ->
                val mu = PIN_RETIRE_MARGIN * k.width
                val mv = PIN_RETIRE_MARGIN * k.height
                u >= mu && u <= k.width - mu && v >= mv && v <= k.height - mv
            }
        }
    }

/**
 * A box the engine has in a batch's image but did not decode there, its corners' box inflated by [PIN_SHIELD_INFLATE]
 * a side, in that image's pixels: tracked with the text of [code] (a key), or, [code] null, not read at all (a detector
 * or localizer box, any code). Rule 7 counts the one pin in it nearest its centre ([centreU], [centreV]) as seen.
 */
class TrackedBox(val code: String?, val minU: Double, val minV: Double, val maxU: Double, val maxV: Double) {
    val centreU get() = (minU + maxU) / 2
    val centreV get() = (minV + maxV) / 2
}

/** The [TrackedBox] of [read] (one of [trackedOf]'s), whose key is [code] (null: no text) */
fun trackedBoxOf(read: Read, code: String?): TrackedBox {
    val c = read.corners
    val minU = minOf(c[0], c[2], c[4], c[6])
    val maxU = maxOf(c[0], c[2], c[4], c[6])
    val minV = minOf(c[1], c[3], c[5], c[7])
    val maxV = maxOf(c[1], c[3], c[5], c[7])
    val w = (maxU - minU) * PIN_SHIELD_INFLATE
    val h = (maxV - minV) * PIN_SHIELD_INFLATE
    return TrackedBox(code, minU - w, minV - h, maxU + w, maxV + h)
}

/** [sighting] re-sighted [pin] (a claim) */
class Claim(val sighting: Sighting, val pin: Pin)

/**
 * What one [PinBook.place] did: the pins its merges [removed] and the ones it [retired] (their anchors go), the [claims]
 * it applied, for the metrics, the ones it [voided] (rule 3: their reads went to the candidates instead), and the pins
 * their third bad claim in a row re-initialised ([reinits], rule 4)
 */
class Placed(
    val removed: List<Pin>,
    val claims: List<Claim>,
    val retired: List<Pin> = emptyList(),
    val voided: List<Claim> = emptyList(),
    val reinits: List<Pin> = emptyList(),
) {
    companion object {
        val NONE = Placed(emptyList(), emptyList())
    }
}

/**
 * The pins and the candidates of one session (iOS ScannerController's markers and candidates). Under [PinRules.IOS] a
 * pin is never moved by a sighting (only ARCore moves its anchor) and is removed only by [mergeSiblings] and [clear], as
 * on iOS; under [PinRules.ANDROID] it also goes by retirement (rule 7), and with [refine] every claim refines it
 * ([PinEstimator]). [rules] and [refine] may change between batches; the rule 4 and 7 counters then start again.
 */
class PinBook {
    private class Candidate(val code: String) {
        val positions = ArrayList<Vec3>()

        /** Whether each of [positions] was a plane hit, whether it was no hit at all, and whether a valid hit */
        val onPlane = ArrayList<Boolean>()
        val guessed = ArrayList<Boolean>()
        val valid = ArrayList<Boolean>()

        /** Each sighting's world ray, for the pin's estimator (rule 9) */
        val rays = ArrayList<Ray>()
        var lastRotation = Quat.IDENTITY

        /** The newest plane hit's rotation (its y axis the plane's normal), for a pin born on the plane */
        var planeRotation: Quat? = null
        var lastSeenNs = 0L

        /** The per-axis median of [positions] (rule 5), kept until they change */
        var median: Vec3? = null
    }

    private val list = ArrayList<Pin>()
    private val candidates = ArrayList<Candidate>()

    /** Each code's pitch between identical units, metres, from the newest image that read it twice (rule 2) */
    private val pitches = HashMap<String, Double>()

    /** Each code's newest reads' aspects ([Sighting.aspect]), a ring of [PIN_THIN_HISTORY], for [thin] */
    private class Aspects {
        val ring = DoubleArray(PIN_THIN_HISTORY)
        var count = 0
        var next = 0
    }
    private val aspects = HashMap<String, Aspects>()
    private val aspectSort = DoubleArray(PIN_THIN_HISTORY)

    /** Which of one place()'s sightings are thin ([thin]), by index, and the others ([place]'s scratch) */
    private var thinOf = BooleanArray(8)
    private val kept = ArrayList<Sighting>()
    private var nextId = 1

    /** A claim's ray in its pin's anchor frame (scratch) */
    private val ray6 = DoubleArray(6)

    /** Frozen pins' bad runs' earlier reads, with their capture times, to seed the candidates before the batch's own ([frozenBad]) */
    private val reseed = ArrayList<Pair<Sighting, Long>>()

    // Scratch of one place() (one thread; nothing is kept between batches): each capture camera's inverse, the pins
    // where they stood, the pairings (sighting index, pin index, lateral distance, pixels off) in the order found, the
    // pins claimed, the sightings owned and the pins well claimed; one axis of a median
    private val inverses = Inverses()
    private val at = ArrayList<Vec3>()
    private var pairSighting = IntArray(8)
    private var pairPin = IntArray(8)
    private var pairLateral = DoubleArray(8)
    private var pairOff = DoubleArray(8)
    private var pairs = 0
    private val claimed = IntSet()
    private val owned = IntSet()
    private val good = IntSet()
    private val shielded = IntSet()
    private var shieldU = DoubleArray(8)
    private var shieldV = DoubleArray(8)
    private val axis = DoubleArray(PIN_MAX_SAMPLES + 1)

    /** Which rules place and remove the pins ([ArPins] sets it each frame) */
    var rules = PinRules.ANDROID
        set(value) {
            if (value != field) restartRuns()
            field = value
        }

    /**
     * Under [PinRules.ANDROID], whether claims refine the pins (drift plan Phase 4, its own switch so it can be A/B tested
     * against Phase 3 in one session; [ArPins] sets it each frame). Off, the pins are frozen as in Phase 3: each stays
     * where it was born, judged where it is now, and from its [PIN_REINIT_BAD]th bad claim in a row its claims are voided
     * and their reads seed a candidate, standing in for the re-init a frozen pin cannot do.
     */
    var refine = true
        set(value) {
            if (value != field) restartRuns()
            field = value
        }

    /** The rules changed: the rule 4 and 7 counters start again, as the other rules did not keep them */
    private fun restartRuns() {
        for (pin in list) {
            pin.est.clearBad()
            pin.frozenBadInRow = 0
            pin.frozenRun.clear()
            pin.misses = 0
        }
    }

    /**
     * The newest frame on which the map moved (an anchor stepped, or tracking was lost; [ArPins] sets it each frame):
     * a claim voids only while there has been no such frame since its pin's last good claim (rule 3)
     */
    var mapMovedNs = Long.MIN_VALUE

    /**
     * Rule 5's surface gate ([BirthGate.needsSurface], [ArPins] sets it each frame): under [PinRules.ANDROID] only a read
     * hitting a real surface or a verified pin's depth feeds a candidate; the others are [held] (they still claim)
     */
    var birthsNeedSurface = false

    /** The reads the surface gate kept from the candidates so far, the newest captured at [lastHeldNs] */
    var held = 0
        private set
    var lastHeldNs = Long.MIN_VALUE
        private set

    val pins: List<Pin> get() = list
    val candidateCount: Int get() = candidates.size

    /** [code]'s pitch (rule 2), metres; null until one image read it twice under [PinRules.ANDROID] */
    fun pitchOf(code: String): Double? = pitches[code]

    /**
     * One batch captured at [captureNs], placed at the frame [nowNs] (iOS place()): every (sighting, pin) pairing of
     * one code whose pin, projected into the sighting's own frame, lies in its inflated box or within its match radius
     * of its centre, and within [PIN_ASSIGN_RADIUS_M] of its ray; claimed greedily, smallest lateral distance first,
     * each pin and each sighting at most once; a claimed pin was seen at [nowNs], and the siblings merge. Each unclaimed
     * sighting feeds the candidates when [mayCreate]; a confirmed one becomes a pin once [anchor] made its anchor, and
     * the siblings merge. The pins removed go back, for their anchors, and the claims, for the metrics.
     *
     * [PinRules.ANDROID] adds (§3.4): each pin is judged where it stood at the capture, its anchor then ([Pin.positionAt]);
     * once a code's pitch is known, a pin is claimed only within half of it, in the image and laterally, the box no
     * longer enough (rule 2); a claim more than [PIN_BAD_PX] (or half the quad's width) off a pin that had a good one
     * under [PIN_VOID_NS] ago, with no map correction since ([mapMovedNs]), is voided and its read feeds the candidates,
     * as most likely a pinless identical neighbour's (rule 3); a good claim refines its pin (rule 1), and the
     * [PIN_REINIT_BAD]th counted bad claim in a row re-initialises it on their rays, which counts as good (rule 4);
     * candidates join on their median, and a newborn pin starts from rule 1's prior and its sightings' rays (rule 5);
     * the merge is the physical one (rule 6); and pins that keep missing reads of their code in view, a bad claim being
     * no claim, retire (rule 7), unless the engine still tracks their code's box where they stand ([tracked]). With [refine] off (Phase 3) nothing is refined: a pin is judged where it is now, and
     * from its [PIN_REINIT_BAD]th counted bad claim in a row each one is voided; that run's reads seed a candidate, and
     * the stale pin retires.
     */
    fun place(
        batch: List<Sighting>,
        captureNs: Long,
        nowNs: Long,
        mayCreate: Boolean,
        tracked: List<TrackedBox> = emptyList(),
        anchor: (Pin) -> Boolean,
    ): Placed {
        if (batch.isEmpty()) return Placed.NONE
        val android = rules == PinRules.ANDROID
        val refining = android && refine
        if (thinOf.size < batch.size) thinOf = BooleanArray(batch.size * 2)
        var sightings = batch
        if (android) {
            var thins = 0
            for (i in batch.indices) {
                thinOf[i] = thin(batch[i]) // against the reads before this batch
                if (thinOf[i]) thins++
            }
            for (i in batch.indices) remember(batch[i])
            if (thins > 0) { // rule 5: a scan-line decode neither claims nor births, nor teaches a pitch
                kept.clear()
                for (i in batch.indices) if (!thinOf[i]) kept += batch[i]
                sightings = kept
                for (i in sightings.indices) thinOf[i] = false
                if (sightings.isEmpty()) return Placed.NONE
            }
            learnPitches(sightings)
        }
        val removed = ArrayList<Pin>()
        val claims = ArrayList<Claim>()
        val voided = ArrayList<Claim>(0)
        var reinits: ArrayList<Pin>? = null
        at.clear()
        for (j in list.indices) at += if (refining) list[j].positionAt(captureNs) else list[j].position
        pairs = 0
        for (i in sightings.indices) {
            val s = sightings[i]
            val pitch = if (android) pitches[s.code] else null
            val toCamera = inverses[s.camera]
            for (j in list.indices) {
                if (list[j].code != s.code) continue
                val p = at[j]
                applied(toCamera, p.x, p.y, p.z) { cx, cy, cz ->
                    projected(s.intrinsics, cx, cy, cz, {}) { u, v ->
                        val off = hypot(u - s.centreU, v - s.centreV)
                        val d = lateral(s.ray, p.x, p.y, p.z)
                        val near = if (pitch == null) {
                            val inBox = u in s.minU..s.maxU && v in s.minV..s.maxV
                            (inBox || off <= s.matchRadiusPx) && d <= PIN_ASSIGN_RADIUS_M
                        } else {
                            val o = s.ray.origin
                            val range = maxOf(norm(p.x - o.x, p.y - o.y, p.z - o.z), PIN_MIN_RANGE_M)
                            off <= minOf(s.matchRadiusPx, pitch / 2 * s.intrinsics.fx / range) && d <= minOf(PIN_ASSIGN_RADIUS_M, pitch / 2)
                        }
                        if (near) addPairing(i, j, d, off)
                    }
                }
            }
        }
        sortPairings()
        claimed.clear()
        owned.clear()
        good.clear()
        for (k in 0 until pairs) {
            val pin = list[pairPin[k]] // no pin goes before the claims are applied
            val si = pairSighting[k]
            if (pin.id in claimed || si in owned) continue
            claimed.add(pin.id)
            val s = sightings[si]
            var ok = pairOff[k] <= badPx(s)
            if (!ok) {
                pin.landedRay = s.ray
                pin.landedNs = captureNs
            }
            if (android) {
                if (!ok && captureNs - pin.lastGoodNs < PIN_VOID_NS && mapMovedNs <= pin.lastGoodNs) { // rule 3
                    voided += Claim(s, pin)
                    continue
                }
                if (!refining && !ok && frozenBad(pin, s, captureNs)) { // rule 4's stand-in: it left its label
                    voided += Claim(s, pin)
                    continue
                }
                if (refining && refine(pin, s, captureNs, ok)) { // rule 4: it now lies on this read
                    ok = true
                    (reinits ?: ArrayList<Pin>(1).also { reinits = it }) += pin
                }
            }
            owned.add(si)
            pin.lastSeenNs = nowNs
            pin.claims++
            if (ok) {
                pin.goodClaims++
                pin.lastGoodNs = captureNs
                pin.frozenBadInRow = 0
                pin.frozenRun.clear()
                good.add(pin.id)
            }
            claims += Claim(s, pin)
        }
        if (claimed.isNotEmpty()) removed += mergeSiblings()
        // Frozen: a run's earlier reads, applied as claims, seed the candidates with its voided third, so the label's
        // new pin is born on it
        for (r in reseed.indices) {
            val (run, runNs) = reseed[r]
            if (accumulate(run, runNs, nowNs, mayCreate, android, android && thin(run), anchor)) removed += mergeSiblings()
        }
        reseed.clear()
        for (i in sightings.indices) {
            if (i in owned) continue
            if (accumulate(sightings[i], captureNs, nowNs, mayCreate, android, android && thinOf[i], anchor)) removed += mergeSiblings()
        }
        val retired = if (android) retire(sightings, good, captureNs, tracked) else emptyList()
        return Placed(removed, claims, retired, voided, reinits ?: emptyList())
    }

    private fun addPairing(sighting: Int, pin: Int, lateral: Double, offPx: Double) {
        if (pairs == pairPin.size) {
            pairSighting = pairSighting.copyOf(pairs * 2)
            pairPin = pairPin.copyOf(pairs * 2)
            pairLateral = pairLateral.copyOf(pairs * 2)
            pairOff = pairOff.copyOf(pairs * 2)
        }
        pairSighting[pairs] = sighting
        pairPin[pairs] = pin
        pairLateral[pairs] = lateral
        pairOff[pairs] = offPx
        pairs++
    }

    /** The pairings by lateral distance, ties in the order found: a stable sort, as sortBy's, so the same order */
    private fun sortPairings() {
        for (i in 1 until pairs) {
            val s = pairSighting[i]
            val p = pairPin[i]
            val d = pairLateral[i]
            val off = pairOff[i]
            var j = i - 1
            while (j >= 0 && pairLateral[j].compareTo(d) > 0) {
                pairSighting[j + 1] = pairSighting[j]
                pairPin[j + 1] = pairPin[j]
                pairLateral[j + 1] = pairLateral[j]
                pairOff[j + 1] = pairOff[j]
                j--
            }
            pairSighting[j + 1] = s
            pairPin[j + 1] = p
            pairLateral[j + 1] = d
            pairOff[j + 1] = off
        }
    }

    /**
     * Rule 4's stand-in while pins are frozen: [s], captured at [captureNs], is [pin]'s next counted bad claim in a row.
     * Before the [PIN_REINIT_BAD]th it is applied and kept with the run; from it on the pin no longer lies on its label
     * and the claim is voided (true), the run's earlier reads joining [reseed] at the [PIN_REINIT_BAD]th.
     */
    private fun frozenBad(pin: Pin, s: Sighting, captureNs: Long): Boolean {
        val n = ++pin.frozenBadInRow
        if (n < PIN_REINIT_BAD) {
            pin.frozenRun += s to captureNs
            return false
        }
        if (n == PIN_REINIT_BAD) reseed += pin.frozenRun
        pin.frozenRun.clear()
        return true
    }

    /**
     * [s] captured at [captureNs] claimed [pin] ([good] or a counted bad claim): its ray, taken into the pin's anchor
     * frame as at the capture (rule 9), refines the pin (rule 1) or joins its bad run (rule 4). A pin never refined
     * (born under [PinRules.IOS] or frozen) first takes its point as its prior, at the default σ. True when it re-initialised.
     */
    private fun refine(pin: Pin, s: Sighting, captureNs: Long, good: Boolean): Boolean {
        val e = pin.est
        pin.anchors.toFrame(pin.anchors.slotAt(captureNs), s.ray, ray6)
        if (!e.started) {
            val range = sqrt(sq(e.x - ray6[0]) + sq(e.y - ray6[1]) + sq(e.z - ray6[2]))
            e.start(e.x, e.y, e.z, ray6[3], ray6[4], ray6[5], range, PIN_PRIOR_DEFAULT_M, s.intrinsics.fx, PriorSource.DEFAULT)
        }
        if (good) {
            e.add(ray6[0], ray6[1], ray6[2], ray6[3], ray6[4], ray6[5], s.widthPx)
            return false
        }
        return e.bad(ray6[0], ray6[1], ray6[2], ray6[3], ray6[4], ray6[5], s.widthPx)
    }

    /**
     * Rule 1's prior for [pin], newborn from candidate [c] ([s] its newest sighting), then its sightings' rays, all in
     * the anchor's frame at its birth (rule 9). A depth on the newest ray, so it never pulls the point off that ray:
     * the median of at least two valid hits' ([PIN_PRIOR_HITS_M] along it), else the EAN/UPC nominal width's
     * ([PIN_PRIOR_WIDTH] of it), else [PIN_DEFAULT_DEPTH_M] ([PIN_PRIOR_DEFAULT_M]).
     */
    private fun startEstimate(pin: Pin, c: Candidate, s: Sighting) {
        val hits = c.valid.count { it }
        val widthDepth = if (s.nominalM > 0.0 && s.widthPx > 0.0) (s.intrinsics.fx * s.nominalM / s.widthPx).coerceIn(PIN_MIN_DEPTH_M, PIN_MAX_DEPTH_M) else Double.NaN
        val prior: Vec3
        val sigma: Double
        val source: PriorSource
        when {
            hits >= 2 -> {
                prior = s.ray.at(maxOf((median(c.positions, c.valid) - s.ray.origin) dot s.ray.dir, PIN_MIN_RANGE_M))
                sigma = PIN_PRIOR_HITS_M
                source = PriorSource.HITS
            }
            widthDepth.isFinite() -> {
                prior = atDepth(s, widthDepth)
                sigma = PIN_PRIOR_WIDTH * widthDepth
                source = PriorSource.WIDTH
            }
            else -> {
                prior = atDepth(s, PIN_DEFAULT_DEPTH_M)
                sigma = PIN_PRIOR_DEFAULT_M
                source = PriorSource.DEFAULT
            }
        }
        val toAnchor = pin.pose.inverse()
        val p = toAnchor.apply(prior)
        val o = toAnchor.apply(s.ray.origin)
        val d = toAnchor.rotate(s.ray.dir)
        pin.est.start(p.x, p.y, p.z, d.x, d.y, d.z, maxOf((p - o) dot d, PIN_MIN_RANGE_M), sigma, s.intrinsics.fx, source)
        val birth = pin.anchors.newest
        for (r in c.rays) {
            pin.anchors.toFrame(birth, r, ray6)
            pin.est.add(ray6[0], ray6[1], ray6[2], ray6[3], ray6[4], ray6[5])
        }
    }

    /** The world point on [s]'s centre ray at camera depth [z] in its image */
    private fun atDepth(s: Sighting, z: Double): Vec3 {
        val k = s.intrinsics
        return s.camera.apply(Vec3((s.centreU - k.cx) / k.fx * z, -(s.centreV - k.cy) / k.fy * z, -z))
    }

    private fun sq(v: Double) = v * v

    /** Rule 3: how far off its pin, in pixels of [s]'s image, a claim may be and still be good */
    private fun badPx(s: Sighting) = maxOf(PIN_BAD_PX * s.intrinsics.width / 3840.0, s.widthPx / 2)

    /**
     * Rule 2: each code read twice or more in one image learns its pitch, the smallest angle between two of its rays
     * times their range (the mean of its reads' points, within [PIN_MIN_DEPTH_M]..[PIN_MAX_DEPTH_M]), at least
     * [PIN_MIN_PITCH_M]. Thin reads ([thin]) teach nothing: their centre is not their label's (15:03 and 12:34,
     * UNIT-T: every image that learned under 70 % of the 3.2 cm pitch, 12 and 13 of them, had one).
     */
    private fun learnPitches(sightings: List<Sighting>) {
        if (sightings.size < 2) return
        // Each code once, at its first sighting, its sightings in batch order (as grouping them would give them)
        for (first in sightings.indices) {
            if (thinOf[first]) continue
            val code = sightings[first].code
            var seen = false
            for (i in 0 until first) if (sightings[i].code == code && !thinOf[i]) seen = true
            if (seen) continue
            var count = 0
            for (i in first until sightings.size) if (sightings[i].code == code && !thinOf[i]) count++
            if (count < 2) continue
            var angle = Double.MAX_VALUE
            var sum = 0.0
            for (i in first until sightings.size) {
                val a = sightings[i]
                if (a.code != code || thinOf[i]) continue
                for (j in i + 1 until sightings.size) {
                    val b = sightings[j]
                    if (b.code == code && !thinOf[j]) angle = minOf(angle, acos((a.ray.dir dot b.ray.dir).coerceIn(-1.0, 1.0)))
                }
                sum += norm(a.hit.x - a.ray.origin.x, a.hit.y - a.ray.origin.y, a.hit.z - a.ray.origin.z)
            }
            val range = (sum / count).coerceIn(PIN_MIN_DEPTH_M, PIN_MAX_DEPTH_M)
            pitches[code] = maxOf(angle * range, PIN_MIN_PITCH_M)
        }
    }

    /**
     * iOS accumulateCandidate; true when a pin was born. Android (rule 5) adds: a [thin] read feeds no
     * candidate, nor, while [birthsNeedSurface], one whose point is no real surface's ([HitSource.HIT]) or a verified
     * pin's ([HitSource.PIN]): it is [held]; with [refine], a read seen near a pin of its code in its own image
     * ([nearAPinInItsImage]) feeds none, a candidate confirmed within [PIN_BIRTH_GUARD] of its code's pitch of one of its
     * pins is dropped, no pin born, and a pin born on a plane is held on it ([PinEstimator.lockToPlane]).
     */
    private fun accumulate(s: Sighting, captureNs: Long, nowNs: Long, mayCreate: Boolean, android: Boolean, thin: Boolean, anchor: (Pin) -> Boolean): Boolean {
        if (!mayCreate || thin) return false
        if (android && birthsNeedSurface && s.source != HitSource.HIT && s.source != HitSource.PIN) {
            held++
            lastHeldNs = captureNs
            return false
        }
        if (android && refine && nearAPinInItsImage(s, captureNs)) return false // frozen, a re-birth is how a pin gets back on its label
        // The stale go, the others keeping their order (no predicate lambda per read)
        var stale = candidates.size - 1
        while (stale >= 0) {
            if (captureNs - candidates[stale].lastSeenNs > PIN_CANDIDATE_TIMEOUT_NS) candidates.removeAt(stale)
            stale--
        }
        val cand = candidates.firstOrNull { it.code == s.code && joins(s, it, android) }
            ?: Candidate(s.code).also { candidates += it }
        cand.positions += s.hit
        cand.onPlane += s.onPlane
        cand.guessed += s.guessed
        cand.valid += s.source == HitSource.HIT || s.source == HitSource.PIN
        cand.rays += s.ray
        if (cand.positions.size > PIN_MAX_SAMPLES) {
            cand.positions.removeAt(0)
            cand.onPlane.removeAt(0)
            cand.guessed.removeAt(0)
            cand.valid.removeAt(0)
            cand.rays.removeAt(0)
        }
        cand.median = null
        cand.lastRotation = s.rotation
        if (s.onPlane) cand.planeRotation = s.rotation
        cand.lastSeenNs = captureNs
        if (cand.positions.size < PIN_CONFIRM_COUNT) return false
        candidates.remove(cand)
        // Born at the per-axis median of the agreeing sightings, turned as the last hit (iOS birthTransform); on a
        // plane when most of them were, as the median then lies on it, and a guess when most of them hit nothing
        val at = medianOf(cand)
        if (android && refine && tooNear(s.code, at)) return false // frozen, a re-birth is how a pin gets back on its label
        val n = cand.positions.size
        val pin = Pin(
            nextId++, s.code, Pose(at, cand.lastRotation),
            bornOnPlane = 2 * cand.onPlane.count { it } > n,
            bornGuessed = 2 * cand.guessed.count { it } > n,
            bornNs = captureNs,
            widthM = labelWidthM(s, at),
        ).also { it.lastSeenNs = nowNs }
        if (android && refine) {
            startEstimate(pin, cand, s)
            val q = cand.planeRotation
            if (pin.bornOnPlane && q != null) {
                val toAnchor = pin.pose.inverse()
                val c = toAnchor.apply(at)
                val normal = toAnchor.rotate(q.rotate(UP))
                pin.est.lockToPlane(c.x, c.y, c.z, normal.x, normal.y, normal.z)
            }
        }
        if (!anchor(pin)) return false
        list += pin
        return true
    }

    /**
     * Rule 5: whether [s] is a scan-line decode, its centre not its label's: its quad's [Sighting.aspect] under
     * [PIN_THIN_SHARE] of its code's median (the [PIN_THIN_HISTORY] newest reads [remember] kept), or, before
     * [PIN_THIN_MIN_READS] of them, under [PIN_THIN_FLOOR]. A quad with no size is not thin.
     */
    private fun thin(s: Sighting): Boolean {
        val a = s.aspect
        if (a.isNaN()) return false
        val seen = aspects[s.code]
        if (seen == null || seen.count < PIN_THIN_MIN_READS) return a < PIN_THIN_FLOOR
        System.arraycopy(seen.ring, 0, aspectSort, 0, seen.count)
        Arrays.sort(aspectSort, 0, seen.count)
        val n = seen.count
        val median = if (n % 2 == 1) aspectSort[n / 2] else (aspectSort[n / 2 - 1] + aspectSort[n / 2]) / 2
        return a < PIN_THIN_SHARE * median
    }

    /** [s]'s aspect into its code's ring, for [thin] */
    private fun remember(s: Sighting) {
        val a = s.aspect
        if (a.isNaN()) return
        val seen = aspects.getOrPut(s.code) { Aspects() }
        seen.ring[seen.next] = a
        seen.next = (seen.next + 1) % PIN_THIN_HISTORY
        if (seen.count < PIN_THIN_HISTORY) seen.count++
    }

    /**
     * Rule 5's guard: whether a pin of [code] lies within [PIN_BIRTH_GUARD] of its pitch of [at] (none until the pitch is
     * known). On 2026-10-07 (15:03) a read of UNIT-C voided as its pin's neighbour (rule 3), and thin UNIT-T reads,
     * were born 0.6-1.9 cm from their units' pins and stayed as counted ghosts.
     */
    private fun tooNear(code: String, at: Vec3): Boolean {
        val pitch = pitches[code] ?: return false
        for (pin in list) if (pin.code == code && apart(pin.position, at) < PIN_BIRTH_GUARD * pitch) return true
        return false
    }

    /**
     * Rule 5's guard in [s]'s own image, whatever the depths: whether a pin of its code, where it stood at the capture,
     * is seen inside [s]'s quad, or within [PIN_BIRTH_GUARD] of its code's pitch (its label's width before a pitch is
     * known) of the read's centre, in pixels at that pin's depth. [tooNear] measures in the world, where a read of a
     * labelled unit whose hit ARCore put on the table re-estimated 6 cm lower is not near that unit's pin (device
     * replays of 15:54's recording, 2026-10-07: the table at 2-4 heights in one replay, 23 and 24 pins for 17).
     */
    private fun nearAPinInItsImage(s: Sighting, captureNs: Long): Boolean {
        val toCamera = inverses[s.camera]
        val pitch = pitches[s.code]
        // The quad's own box: the sighting's is inflated by PIN_BOX_INFLATE a side
        val padU = PIN_BOX_INFLATE * (s.maxU - s.minU) / (1 + 2 * PIN_BOX_INFLATE)
        val padV = PIN_BOX_INFLATE * (s.maxV - s.minV) / (1 + 2 * PIN_BOX_INFLATE)
        for (pin in list) {
            if (pin.code != s.code) continue
            val p = pin.positionAt(captureNs)
            val near = applied(toCamera, p.x, p.y, p.z) { cx, cy, cz ->
                projected(s.intrinsics, cx, cy, cz, { false }) { u, v ->
                    val inQuad = u >= s.minU + padU && u <= s.maxU - padU && v >= s.minV + padV && v <= s.maxV - padV
                    inQuad || hypot(u - s.centreU, v - s.centreV) <= PIN_BIRTH_GUARD * (pitch ?: pin.widthM) * s.intrinsics.fx / -cz
                }
            }
            if (near) return true
        }
        return false
    }

    /**
     * Whether [s] joins candidate [c]. iOS: [c]'s last position lies within [PIN_CANDIDATE_RADIUS_M] of its ray. Android
     * (rule 5): [c]'s median does, within half the label's width there and at least [PIN_CANDIDATE_MIN_M], so one
     * noisy point cannot chain a candidate across to a neighbour.
     */
    private fun joins(s: Sighting, c: Candidate, android: Boolean): Boolean {
        if (!android) return lateral(s.ray, c.positions.last()) < PIN_CANDIDATE_RADIUS_M
        val m = medianOf(c)
        return lateral(s.ray, m) < maxOf(PIN_CANDIDATE_MIN_M, labelWidthM(s, m) / 2) && sameDepth(s, m)
    }

    /**
     * Rule 5: whether [s]'s point and the candidate's median [m] lie at depths along [s]'s ray within [PIN_SIZE_RATIO]
     * of each other. On one line of sight the lateral test cannot tell the table from the floor behind it: 2026-10-08
     * 12:17, a read of a UNIT-T on the table at 0.24 m joined two of its earlier reads' points on the floor at 1.07 m,
     * and the median of the three bore the pin on the floor.
     */
    private fun sameDepth(s: Sighting, m: Vec3): Boolean {
        val o = s.ray.origin
        val d = s.ray.dir
        val a = (s.hit.x - o.x) * d.x + (s.hit.y - o.y) * d.y + (s.hit.z - o.z) * d.z
        val b = (m.x - o.x) * d.x + (m.y - o.y) * d.y + (m.z - o.z) * d.z
        return a > 0.0 && b > 0.0 && a <= b * PIN_SIZE_RATIO && b <= a * PIN_SIZE_RATIO
    }

    /** [c]'s per-axis median, made once per change of its positions */
    private fun medianOf(c: Candidate): Vec3 = c.median ?: median(c.positions).also { c.median = it }

    /** [s]'s label width in metres were it at [p]: its quad's width at [p]'s range along its ray */
    private fun labelWidthM(s: Sighting, p: Vec3) = s.widthPx * maxOf((p - s.ray.origin) dot s.ray.dir, PIN_MIN_RANGE_M) / s.intrinsics.fx

    /**
     * Same-code pins that are one barcode merge. iOS (mergeSiblingMarkers): within [PIN_SIBLING_MERGE_M] of each other
     * they are a birth race, and the most recently seen stays (a tie keeps the later one); copies farther apart each
     * keep their own pin. Android (rule 6): only pins within [PIN_MERGE_M], or half their label's width if less, are one
     * label, since identical units sit a label apart; the one with more claims stays (a tie keeps the older) and, with
     * [refine], takes the other's newest rays ([PIN_ABSORB_RAYS]).
     */
    fun mergeSiblings(): List<Pin> {
        if (list.size < 2) return emptyList()
        val android = rules == PinRules.ANDROID
        val absorb = android && refine
        var removed: ArrayList<Pin>? = null
        var i = 0
        while (i < list.size) {
            var j = i + 1
            while (j < list.size) {
                val a = list[i]
                val b = list[j]
                val radius = if (android) mergeRadius(a, b) else PIN_SIBLING_MERGE_M
                if (a.code == b.code && apart(a.position, b.position) <= radius) {
                    if (removed == null) removed = ArrayList()
                    if (if (android) a.claims >= b.claims else a.lastSeenNs > b.lastSeenNs) {
                        if (absorb) a.absorb(b)
                        removed += b
                        list.removeAt(j)
                    } else {
                        if (absorb) b.absorb(a)
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
        return removed ?: emptyList()
    }

    /** (a - b).norm(), as scalars */
    private fun apart(a: Vec3, b: Vec3) = norm(a.x - b.x, a.y - b.y, a.z - b.z)

    /** Rule 6's radius for [a] and [b]: [PIN_MERGE_M], or half the narrower known label width if less */
    private fun mergeRadius(a: Pin, b: Pin): Double {
        val w = if (a.widthM <= 0.0) b.widthM else if (b.widthM <= 0.0) a.widthM else minOf(a.widthM, b.widthM)
        return if (w > 0.0) minOf(PIN_MERGE_M, w / 2) else PIN_MERGE_M
    }

    /**
     * Rule 7: a pin at least [PIN_RETIRE_AGE_NS] old that lies [wellInside] the image of a batch reading its code
     * ([sightings]), where it stood at the capture (where it is now, with pins frozen), but took no good claim from any
     * of them ([good]) missed it; one that missed [PIN_RETIRE_BATCHES] batches over [PIN_RETIRE_NS] with no good claim
     * between is a ghost, and goes. A bad or voided claim is no claim: it is either a neighbour's read or a sign the pin
     * left its label, which the third bad claim in a row corrects (rule 4) or, frozen, leaves to a re-birth. A pin an
     * engine box undecoded in that image shields ([shield]) is seen: the engine re-reads only some of the known codes
     * per image, and boxes small tight codes it cannot read yet, so a batch reading 1 of 4 identical units says nothing
     * against the other 3. Misses count within [PIN_RETIRE_WINDOW_NS] of their run's first, and a run restarts once its
     * code went unread for over [PIN_RETIRE_NS] (the camera looked away). A counted pin ([PIN_COUNTED_CLAIMS] good
     * claims) misses only after a map correction since its last good claim, and retires only once a newer pin of its
     * code covers its label ([takenOver]), so the item keeps one pin and none vanishes in normal use: on 2026-10-07
     * (14:23) four counted units retired after the camera came back and the engine boxed 14 of 17 units. With
     * refinement off every pin keeps the old retirement (counted ones too, and no restart after the code went unread):
     * frozen, it is their only way back onto their label.
     */
    private fun retire(sightings: List<Sighting>, good: IntSet, captureNs: Long, tracked: List<TrackedBox>): List<Pin> {
        shield(sightings[0], captureNs, tracked)
        var out: ArrayList<Pin>? = null
        var i = 0
        while (i < list.size) {
            val pin = list[i]
            val counted = refine && pin.goodClaims >= PIN_COUNTED_CLAIMS
            if (pin.id in good || captureNs - pin.bornNs < PIN_RETIRE_AGE_NS || (counted && mapMovedNs <= pin.lastGoodNs)) {
                pin.misses = 0
                i++
                continue
            }
            if (refine && relocated(pin) && !wellInside(if (refine) pin.positionAt(captureNs) else pin.position, sightings[0].camera, sightings[0].intrinsics, inverses[sightings[0].camera])) {
                list.removeAt(i)
                (out ?: ArrayList<Pin>().also { out = it }) += pin
                continue
            }
            val s = firstOfCode(sightings, pin.code)
            if (s != null) {
                if (refine && captureNs - pin.lastSightedNs > PIN_RETIRE_NS) pin.misses = 0
                pin.lastSightedNs = captureNs
            }
            val p = if (refine) pin.positionAt(captureNs) else pin.position
            if (s == null || !wellInside(p, s.camera, s.intrinsics, inverses[s.camera])) {
                i++
                continue
            }
            if (pin.id in shielded) {
                pin.misses = 0
                i++
                continue
            }
            if (pin.misses == 0 || captureNs - pin.firstMissNs > PIN_RETIRE_WINDOW_NS) {
                pin.misses = 0
                pin.firstMissNs = captureNs
            }
            pin.misses++
            if (pin.misses >= PIN_RETIRE_BATCHES && captureNs - pin.firstMissNs >= PIN_RETIRE_NS && (!counted || takenOver(pin, captureNs))) {
                list.removeAt(i)
                (out ?: ArrayList<Pin>().also { out = it }) += pin
                continue
            }
            i++
        }
        return out ?: emptyList()
    }

    /**
     * Rule 7 for a counted [pin]: whether a pin of its code born since its last good claim lies within the birth guard's
     * radius ([PIN_BIRTH_GUARD] of its pitch; its label's width before a pitch is known) of [Pin.landedRay], a claim that
     * missed it since: its label's reads land there now, and that pin was born on them. A neighbouring unit's pin is
     * neither: 2026-10-07 15:39, EAN pin 6 (86 claims) went at 28.5 s for pin 7, its neighbour one pitch (6 cm) away born
     * 0.2 s after it, and EAN ended 2 of 3; UNIT-T pins 9, 10 and 15 went for pins 18-20, born at 26.3 s after their last
     * claims, 1.0-1.3 cm off
     */
    private fun takenOver(pin: Pin, captureNs: Long): Boolean {
        val ray = pin.landedRay ?: return false
        if (pin.landedNs <= pin.lastGoodNs) return false
        val radius = takeOverRadius(pitches[pin.code], pin.widthM)
        for (q in list) {
            if (q === pin) continue
            val p = if (refine) q.positionAt(captureNs) else q.position
            if (takesOver(q, pin, lateral(ray, p.x, p.y, p.z), radius)) return true
        }
        return false
    }

    /**
     * Rule 7: whether [pin] is a code's one unit (no image read it twice: no pitch) that a map correction since its last
     * good claim left behind: a newer pin of its code, born since then within [PIN_RELOCATE_M], is its label's now. It
     * goes while out of the batch's view, where it may stay for good; in view and unread it stays, as a unit the engine
     * cannot read would (the counted rule). 2026-10-07 11:53: tracking was lost for 1.1 s and ARCore came back 16 cm off;
     * PX-0001's counted pin stayed 22 cm from its label, out of view, and its new pin stood there.
     */
    private fun relocated(pin: Pin): Boolean {
        if (pitches[pin.code] != null || mapMovedNs <= pin.lastGoodNs) return false
        for (q in list) {
            if (q !== pin && q.code == pin.code && q.bornNs > pin.lastGoodNs && apart(q.position, pin.position) <= PIN_RELOCATE_M) return true
        }
        return false
    }

    /**
     * The pins the [tracked] boxes shield into [shielded]: each box the one pin of its code (any code, for a box with
     * none) that lies in it at the capture, in [s]'s image (a batch's sightings share their image), nearest its centre,
     * a counted one ([PIN_COUNTED_CLAIMS]) before any other. One box, one pin: a ghost between two tight identical units
     * is not shielded by its neighbours' boxes, nor by its own unit's while that unit's counted pin is in it (15:03: a
     * never-claimed UNIT-T ghost 1.3 cm off a counted unit's pin met rule 7 from 34.2 s and still outlived the run).
     */
    private fun shield(s: Sighting, captureNs: Long, tracked: List<TrackedBox>) {
        shielded.clear()
        if (tracked.isEmpty()) return
        if (shieldU.size < list.size) {
            shieldU = DoubleArray(list.size * 2)
            shieldV = DoubleArray(list.size * 2)
        }
        val toCamera = inverses[s.camera]
        for (j in list.indices) {
            val p = if (refine) list[j].positionAt(captureNs) else list[j].position
            shieldU[j] = applied(toCamera, p.x, p.y, p.z) { x, y, z ->
                projected(s.intrinsics, x, y, z, { Double.NaN }) { u, v ->
                    shieldV[j] = v
                    u
                }
            }
        }
        for (b in tracked.indices) {
            val box = tracked[b]
            var best = -1
            var bestPx = Double.POSITIVE_INFINITY
            var bestCounted = -1
            var bestCountedPx = Double.POSITIVE_INFINITY
            for (j in list.indices) {
                if (box.code != null && list[j].code != box.code) continue
                val u = shieldU[j]
                val v = shieldV[j]
                if (u.isNaN() || u !in box.minU..box.maxU || v !in box.minV..box.maxV) continue
                val off = hypot(u - box.centreU, v - box.centreV)
                if (off < bestPx) {
                    best = j
                    bestPx = off
                }
                if (list[j].goodClaims >= PIN_COUNTED_CLAIMS && off < bestCountedPx) {
                    bestCounted = j
                    bestCountedPx = off
                }
            }
            if (bestCounted >= 0) best = bestCounted
            if (best >= 0) shielded.add(list[best].id)
        }
    }

    /** The first of [sightings] of [code], as firstOrNull finds it, without an iterator per pin and batch */
    private fun firstOfCode(sightings: List<Sighting>, code: String): Sighting? {
        for (i in sightings.indices) if (sightings[i].code == code) return sightings[i]
        return null
    }

    /** New Scan, or leaving the mode */
    fun clear() {
        list.clear()
        candidates.clear()
        pitches.clear()
        aspects.clear()
    }

    /** The per-axis median of [ps], or of those [valid] marks */
    private fun median(ps: List<Vec3>, valid: List<Boolean>? = null): Vec3 =
        Vec3(axisMedian(ps, valid, 0), axisMedian(ps, valid, 1), axisMedian(ps, valid, 2))

    /**
     * One axis's median, sorted in a primitive array: Arrays.sort(double[]) orders as Double.compareTo, which
     * List<Double>.sorted() sorts by, so the same values in the same places
     */
    private fun axisMedian(ps: List<Vec3>, valid: List<Boolean>?, which: Int): Double {
        val a = if (ps.size <= axis.size) axis else DoubleArray(ps.size)
        var n = 0
        for (i in ps.indices) {
            if (valid != null && !valid[i]) continue
            val p = ps[i]
            a[n++] = when (which) {
                0 -> p.x
                1 -> p.y
                else -> p.z
            }
        }
        Arrays.sort(a, 0, n)
        return if (n % 2 == 1) a[n / 2] else (a[n / 2 - 1] + a[n / 2]) / 2
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

/** The keys of the [items] counted so far ([ItemCount.countLow] above 0): [pinColour] against them needs no key per frame */
fun countedKeys(items: List<ItemCount>): Set<String> = items.filter { it.countLow > 0 }.mapTo(HashSet()) { ItemCode.key(it.code) }

/** [pinColour] with the item list's [counted] keys ([countedKeys]): the same colour, as a code is counted exactly when its key is among them */
fun pinColour(code: String, keys: Set<String>, counted: Set<String>): PinColour? {
    if (code !in keys) return null
    return if (code in counted) PinColour.COUNTED else PinColour.LISTED
}
