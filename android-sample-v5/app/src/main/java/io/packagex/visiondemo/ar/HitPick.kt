package io.packagex.visiondemo.ar

import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.Vec3
import kotlin.math.abs

/*
 * Drift plan Phase 2 (P1): which of ARCore's hits on a listed read's centre ray seeds its pin. The iOS rule takes the
 * first plane in its polygon before any nearer point, so a label's pin lands on the table, shelf or wall behind it and
 * shows parallax once the phone moves (root cause 2). Here the nearest hit that could be the label wins. Pure, for the
 * JVM tests: ArPins turns ARCore's hits into [RayHit]s.
 */

/** How AR Item Count places its pins (drift plan §4's `PinRules`, Settings › Advanced) */
enum class PinRules {
    /** As the iOS demo's AR scanner (ported in 966149c): a plane in its polygon first, else the nearest point */
    IOS,

    /**
     * The nearest valid hit ([pickHit]), else a prior that needs no hit ([defaultPick]); the identity rules of the drift
     * plan's §3.4 ([PinBook]); and, behind their own switches, each pin refined from every claim ([PinEstimator],
     * [PinBook.refine]) and drawn as a ring until verified ([drawnAsRing]), and the read-rate boost while that helps
     * ([ReadBoost])
     */
    ANDROID,
}

/** The camera depths a pin's hit may lie at, metres (Phase 2) */
const val PIN_MIN_DEPTH_M = 0.15
const val PIN_MAX_DEPTH_M = 1.5

/**
 * An EAN/UPC hit's implied width (widthPx · depth / fx) must lie within these multiples of the nominal width: 80%
 * magnification × cos 60° yaw, up to 200% magnification
 */
const val PIN_MIN_WIDTH_RATIO = 0.4
const val PIN_MAX_WIDTH_RATIO = 2.0

/** A valid plane at most this far behind the pick along the ray is taken instead: the label lies on it */
const val PIN_PLANE_BEHIND_M = 0.03

/** A plane's normal (its hit pose's +Y) with at least this upward component faces up (cos 25°) */
const val PIN_UP_MIN = 0.9

// ponytail: 2 cm, from 2026-10-07 12:34 (UNIT-Q: half its reads' depth points within it of the table, UNIT-A 363 of
// 418) and 12:03 (UNIT-A: 43 of 1182, median 5.8 cm off). Lower it if pins land on a wrong plane; a ceiling: a label on
// a box beyond the mapped table takes the table only when its depth point agrees with the table
/** A plane counts for a read with no valid hit when a depth point on its ray lies within this of it, metres */
const val PIN_CORROBORATE_M = 0.02

// ponytail: 2 pins within 25 cm, a few mm off the plane, from 2026-10-07 16:31 (the second replay of 15:54's recording):
// ARCore's table polygon never reached UNIT-T's row, whose reads met the table 9-11 cm (p50-p90) from 2-3 verified pins
// of the rows above; the same reads met the floor 72 cm below in its polygon, where one UNIT-T pin was born. Trusting any
// plane off its polygon took those floor hits too (a replay went to UNIT-Q 12 / UNIT-T 10): only the plane verified pins
// lie on vouches for itself. Raise PIN_VOUCH_PINS if a stray verified pin makes a wrong plane count; R bounds how far a
// surface is assumed to run on beyond the labels seen on it
/** A plane met off its polygon counts where at least this many verified pins lie on it ([vouched]) */
const val PIN_VOUCH_PINS = 2

/** ... within this of the ray's point on it, metres */
const val PIN_VOUCH_M = 0.25

/** A pin lies on a plane when it is at most this far from it along its normal, metres */
const val PIN_VOUCH_PLANE_M = 0.01

// ponytail: one verified pin within 15 cm, from 2026-10-08 12:19 (the QR row: two of its units 5.7 and 11.3 cm from the
// third's verified pin had no hit but the tracked table, and stayed unborn for good) and 12:17 (UNIT-T's third unit 5.3 cm
// from its first's). Lower it if a stray verified pin makes a wrong plane count
/** ... or one verified pin within this of the ray's point, metres */
const val PIN_VOUCH_ONE_M = 0.15

/**
 * Whether the upward plane through [q] with unit normal [n] runs on to [q] as far as the labels tell: at least
 * [PIN_VOUCH_PINS] of the verified pins' world points [pins] lie on it (within [PIN_VOUCH_PLANE_M]) within [PIN_VOUCH_M] of [q],
 * or one within [PIN_VOUCH_ONE_M]
 */
fun vouched(q: Vec3, n: Vec3, pins: List<Vec3>): Boolean {
    if (n.y < PIN_UP_MIN) return false
    var count = 0
    for (i in pins.indices) {
        val p = pins[i]
        val dx = p.x - q.x
        val dy = p.y - q.y
        val dz = p.z - q.z
        if (abs(dx * n.x + dy * n.y + dz * n.z) > PIN_VOUCH_PLANE_M) continue
        val d = norm(dx, dy, dz)
        if (d > PIN_VOUCH_M) continue
        if (d <= PIN_VOUCH_ONE_M || ++count >= PIN_VOUCH_PINS) return true
    }
    return false
}

// ponytail: 25 cm and PIN_SIZE_RATIO (2x), from 2026-10-08 12:17 (UNIT-T: a depth point agreed with the floor 1.06-1.08 m
// away, under a table the verified EAN pins 19-25 cm aside put at 0.23-0.24 m, 4.5x; two pins were born on the floor) and
// the third replay of 2026-10-07 15:54 (floor 75-85 cm below a sheet at 0.3 m, 3.6x); every hit the 24 replayed runs took
// lay within 0.67-1.5x of that depth. A ceiling: a label on a box whose top is nearer the camera than half the table's
// depth, within 25 cm of a verified label on the table, gets no hit there
/**
 * Labels on one sheet lie on one surface: within this of a verified pin, on its plane, a hit whose camera depth is more
 * than [PIN_SIZE_RATIO] times that surface's along the ray, or less than its inverse, is no hit, metres
 */
const val PIN_NEIGHBOUR_M = 0.25

/**
 * The camera depth in the capture ([toCamera]) where [ray] meets the surface of the verified pin nearest the ray there:
 * the plane through that pin ([vouchers]) with the normal of the upward plane of [planes] it lies on (within
 * [PIN_CORROBORATE_M]), met from above within [PIN_NEIGHBOUR_M] of the pin; null when no verified pin is that near.
 */
fun neighbourDepth(ray: Ray, toCamera: Pose, planes: List<PlaneRef>, vouchers: List<Vec3>): Double? {
    if (vouchers.isEmpty() || planes.isEmpty()) return null
    val o = ray.origin
    val d = ray.dir
    var best = PIN_NEIGHBOUR_M
    var depth: Double? = null
    for (i in vouchers.indices) {
        val v = vouchers[i]
        for (k in planes.indices) {
            val n = planes[k].normal
            if (n.y < PIN_UP_MIN) continue
            val a = planes[k].point
            if (abs((v.x - a.x) * n.x + (v.y - a.y) * n.y + (v.z - a.z) * n.z) > PIN_CORROBORATE_M) continue
            val dn = d.x * n.x + d.y * n.y + d.z * n.z
            if (dn > -1e-6) continue
            val s = ((v.x - o.x) * n.x + (v.y - o.y) * n.y + (v.z - o.z) * n.z) / dn
            if (!(s > 0.0)) continue
            val qx = o.x + s * d.x
            val qy = o.y + s * d.y
            val qz = o.z + s * d.z
            val off = norm(qx - v.x, qy - v.y, qz - v.z)
            if (off < best) {
                best = off
                depth = -applied(toCamera, qx, qy, qz) { _, _, cz -> cz }
            }
            break
        }
    }
    return depth
}

/** Whether camera depth [z] lies within [PIN_SIZE_RATIO] of the neighbours' surface's [expect] (none: it does) */
private fun neighbourAgrees(z: Double, expect: Double?): Boolean =
    expect == null || !(expect > 0.0) || (z <= expect * PIN_SIZE_RATIO && z * PIN_SIZE_RATIO >= expect)

/** The camera depth of a read with no hit and no nominal width, metres (Phase 3: a birth needs no hit) */
const val PIN_DEFAULT_DEPTH_M = 0.40

/** What an ARCore hit's trackable is */
enum class HitKind { PLANE, POINT, DEPTH_POINT, OTHER }

/**
 * One tracked ARCore hit on a read's world ray: its [kind] and world [point]; for a plane, its [normal] (the hit pose's
 * +Y) and whether the point lies in its polygon ([inPolygon]); for a depth point, the raw depth [confidence] where it
 * lies (null: unknown).
 */
class RayHit(
    val kind: HitKind,
    val point: Vec3,
    val normal: Vec3 = Vec3.ZERO,
    val inPolygon: Boolean = false,
    val confidence: Int? = null,
) {
    /** Geometry the iOS rule takes: a plane in its polygon, a point or a depth point */
    val seeds: Boolean get() = kind == HitKind.POINT || kind == HitKind.DEPTH_POINT || (kind == HitKind.PLANE && inPolygon)

    /**
     * Of [seeds], what the pins kept before Phase 2: not a depth point with a known confidence below
     * [MIN_DEPTH_CONFIDENCE] (an unknown one passed, as no gate)
     */
    val keptBefore: Boolean
        get() = seeds && (kind != HitKind.DEPTH_POINT || confidence == null || confidence >= MIN_DEPTH_CONFIDENCE)
}

/** Why [pickHit] skipped a hit; [trace] names it in the trace's `hit` lines */
enum class HitReject(val trace: String, val label: String) {
    OFF_POLYGON("offPolygon", "planes off their polygon"),
    BACK_OF_PLANE("backOfPlane", "planes seen from behind"),
    LOW_CONFIDENCE("lowConfidence", "depth points with no confidence $MIN_DEPTH_CONFIDENCE"),
    NEAR("near", "nearer than $PIN_MIN_DEPTH_M m"),
    FAR("far", "beyond $PIN_MAX_DEPTH_M m"),
    WIDTH("width", "off the nominal width"),
    KIND("kind", "other trackables"),
    NEIGHBOUR("neighbour", "off the depth of the verified labels beside"),
}

/** Where a sighting's point came from; [trace] is the `hit` line's outcome */
enum class HitSource(val trace: String) {
    /** A valid hit */
    HIT("ok"),

    /** An EAN/UPC with no valid hit: the point at its nominal width's depth */
    WIDTH("width"),

    /** Another code with no valid hit: the nearest hit the pins kept before Phase 2 ([RayHit.keptBefore]), within [PIN_MAX_HIT_M] */
    NEAREST("nearest"),

    /** No hit at all, under [PinRules.ANDROID] (Phase 3, §3.4 rule 5): the point at [PIN_DEFAULT_DEPTH_M] ([defaultPick]) */
    DEFAULT("default"),

    /**
     * No hit test: a verified pin of its code lies in the read's quad at the capture, so the point is that pin's
     * distance along the read's ray (a claim matches in the image and refines on rays; only a birth or a pitch uses
     * the point, and a verified neighbour's depth beats a hit). Saves ARCore's ~0.8 ms hit test per read on the GL thread.
     */
    PIN("pin"),
}

/**
 * [pickHit]'s answer: the world [point], the hit it is ([index] in the hits; -1 for none), [source], whether it lies on
 * a plane, and whether that plane was taken off its polygon on verified pins' word ([vouched])
 */
class HitPick(val point: Vec3, val index: Int, val source: HitSource, val onPlane: Boolean, val vouched: Boolean = false)

/**
 * The point a listed [read] of [capture] seeds its pin with, from [hits] on its world centre [ray] (ARCore's order,
 * nearest first): the nearest valid hit ([hitReject]), or a valid plane at most [PIN_PLANE_BEHIND_M] behind it. With
 * none, an upward plane (ARCore's tracked [planes], or one [hits] met off its polygon) a depth point on the ray agrees
 * with ([corroboratedPlane]); then, so no birth is lost: an EAN/UPC takes the point at its nominal width's depth
 * ([nominalPoint]); another code the nearest hit the pins kept before Phase 2 ([RayHit.keptBefore]) within
 * [PIN_MAX_HIT_M], so it admits nothing they dropped; null when there is none. Each hit skipped before the pick is counted in [rejects] by [HitReject.ordinal].
 * [toCamera] (the inverse of [capture]'s camera) and [width] ([read]'s nominal width) are for a caller that has them.
 * An upward plane met off its polygon, or one of [planes] the ray meets, is as valid as one met in it where the verified
 * pins' world points [vouchers] say the surface runs on there ([vouched]): a hit in the order the ray meets them, so the
 * table beats the floor behind it; a tracked plane after the depth-corroborated one.
 * [labelM], for a code with no nominal [width]: its label's width as its verified pins measured it ([learnedLabelWidthM]);
 * a hit whose depth makes [read]'s width in pixels disagree with it by more than [PIN_SIZE_RATIO] is no hit ([hitReject]).
 * Every point is held to the surface of the verified labels beside the read too ([neighbourDepth], [PIN_SIZE_RATIO]):
 * any code's, as labels on one sheet lie on one surface, so a code no pin of which is verified yet keeps off the floor.
 */
fun pickHit(
    hits: List<RayHit>,
    read: Read,
    capture: PoseRecord,
    ray: Ray,
    rejects: IntArray? = null,
    toCamera: Pose = capture.camera.inverse(),
    width: Double? = nominalWidthM(read.symbology),
    planes: List<PlaneRef> = emptyList(),
    vouchers: List<Vec3> = emptyList(),
    labelM: Double? = null,
): HitPick? {
    val expect = neighbourDepth(ray, toCamera, planes, vouchers)
    var pick = -1
    var pickM = 0.0
    for (i in hits.indices) {
        val h = hits[i]
        val along = alongRay(h, ray)
        if (pick >= 0) {
            if (along > pickM + PIN_PLANE_BEHIND_M) break
            if (h.kind == HitKind.PLANE && hitReject(h, read, capture, ray, toCamera, width, labelM, expect) == null) return HitPick(h.point, i, HitSource.HIT, true)
            continue
        }
        val why = hitReject(h, read, capture, ray, toCamera, width, labelM, expect)
        if (why == HitReject.OFF_POLYGON && vouched(h.point, h.normal, vouchers) &&
            hitReject(RayHit(HitKind.PLANE, h.point, h.normal, inPolygon = true), read, capture, ray, toCamera, width, labelM, expect) == null
        ) {
            return HitPick(h.point, i, HitSource.HIT, true, vouched = true)
        }
        if (why != null) {
            rejects?.let { it[why.ordinal]++ }
            continue
        }
        if (h.kind == HitKind.PLANE) return HitPick(h.point, i, HitSource.HIT, true)
        pick = i
        pickM = along
    }
    if (pick >= 0) return HitPick(hits[pick].point, pick, HitSource.HIT, false)
    corroboratedPlane(hits, read, capture, ray, toCamera, width, planes, labelM, expect)?.let { return it }
    vouchedPlane(read, capture, ray, toCamera, width, planes, vouchers, labelM, expect)?.let { return it }
    if (width != null) {
        nominalPoint(read, capture, width)?.takeIf { neighbourAgrees(cameraDepth(it, toCamera), expect) }?.let { return HitPick(it, -1, HitSource.WIDTH, false) }
    }
    var i = 0
    while (i < hits.size && !hits[i].keptBefore) i++
    if (i == hits.size || alongRay(hits[i], ray) > PIN_MAX_HIT_M || !neighbourAgrees(cameraDepth(hits[i].point, toCamera), expect)) return null
    return HitPick(hits[i].point, i, HitSource.NEAREST, hits[i].kind == HitKind.PLANE)
}

/** [p]'s camera depth ([toCamera], the capture camera's inverse) */
private fun cameraDepth(p: Vec3, toCamera: Pose): Double = -applied(toCamera, p.x, p.y, p.z) { _, _, cz -> cz }

/** An upward plane ARCore tracks, its polygon aside: a [point] on it and its unit [normal], world */
class PlaneRef(val point: Vec3, val normal: Vec3)

/**
 * With no valid hit: where [ray] meets an upward plane ([planes], ARCore's tracked ones, or one [hits] met off its
 * polygon) that a depth point on the ray, of any confidence, lies within [PIN_CORROBORATE_M] of, seen from above at a
 * valid depth; null when there is none. Neither cue alone is enough, as the polygon has not reached the label yet and
 * the depth point's confidence is low: 2026-10-07 12:34, UNIT-Q's depth points lay within 2 cm of the table on half its
 * reads, where the surface gate held two of its three units' births for a minute; at 12:03, UNIT-A's lay 5.8 cm (median)
 * from it, so few of its reads take it.
 */
private fun corroboratedPlane(hits: List<RayHit>, read: Read, capture: PoseRecord, ray: Ray, toCamera: Pose, width: Double?, planes: List<PlaneRef>, labelM: Double?, expect: Double?): HitPick? {
    for (j in hits.indices) {
        val depth = hits[j]
        if (depth.kind != HitKind.DEPTH_POINT) continue
        for (k in planes.indices) {
            onPlaneNear(depth.point, planes[k].point, planes[k].normal, read, capture, ray, toCamera, width, labelM, expect)?.let { return it }
        }
        for (k in hits.indices) {
            val h = hits[k]
            if (h.kind != HitKind.PLANE || h.inPolygon) continue
            onPlaneNear(depth.point, h.point, h.normal, read, capture, ray, toCamera, width, labelM, expect)?.let { return it }
        }
    }
    return null
}

/** [ray]'s point on the upward plane through [at] with normal [n] when [p] lies within [PIN_CORROBORATE_M] of that plane */
private fun onPlaneNear(p: Vec3, at: Vec3, n: Vec3, read: Read, capture: PoseRecord, ray: Ray, toCamera: Pose, width: Double?, labelM: Double?, expect: Double?): HitPick? {
    if (n.y < PIN_UP_MIN) return null
    if (abs((p.x - at.x) * n.x + (p.y - at.y) * n.y + (p.z - at.z) * n.z) > PIN_CORROBORATE_M) return null
    val q = meet(at, n, read, capture, ray, toCamera, width, labelM, expect) ?: return null
    return HitPick(q, -1, HitSource.HIT, true)
}

/** The nearest point where [ray] meets one of [planes] that [vouchers] vouch for there ([vouched]); null when none does */
private fun vouchedPlane(read: Read, capture: PoseRecord, ray: Ray, toCamera: Pose, width: Double?, planes: List<PlaneRef>, vouchers: List<Vec3>, labelM: Double?, expect: Double?): HitPick? {
    if (vouchers.isEmpty()) return null
    var best: Vec3? = null
    var bestM = Double.MAX_VALUE
    for (k in planes.indices) {
        val n = planes[k].normal
        if (n.y < PIN_UP_MIN) continue
        val q = meet(planes[k].point, n, read, capture, ray, toCamera, width, labelM, expect) ?: continue
        val m = norm(q.x - ray.origin.x, q.y - ray.origin.y, q.z - ray.origin.z)
        if (m < bestM && vouched(q, n, vouchers)) {
            best = q
            bestM = m
        }
    }
    return best?.let { HitPick(it, -1, HitSource.HIT, true, vouched = true) }
}

/** Where [ray] meets the plane through [at] with unit normal [n] from above, as a hit in its polygon would be judged; null: not, or not validly */
private fun meet(at: Vec3, n: Vec3, read: Read, capture: PoseRecord, ray: Ray, toCamera: Pose, width: Double?, labelM: Double?, expect: Double?): Vec3? {
    val o = ray.origin
    val d = ray.dir
    val dn = d.x * n.x + d.y * n.y + d.z * n.z
    if (dn > -1e-6) return null // the ray runs along the plane, or meets it from below
    val s = ((at.x - o.x) * n.x + (at.y - o.y) * n.y + (at.z - o.z) * n.z) / dn
    if (!(s > 0.0)) return null
    val q = Vec3(o.x + s * d.x, o.y + s * d.y, o.z + s * d.z)
    if (hitReject(RayHit(HitKind.PLANE, q, n, inPolygon = true), read, capture, ray, toCamera, width, labelM, expect) != null) return null
    return q
}

/** How far [h] lies from [ray]'s origin, (h.point - ray.origin).norm() as scalars */
private fun alongRay(h: RayHit, ray: Ray): Double {
    val p = h.point
    val o = ray.origin
    return norm(p.x - o.x, p.y - o.y, p.z - o.z)
}

/**
 * Why [h] cannot be [read]'s label (null: it can): a plane off its polygon or seen from behind (the camera of [capture],
 * at [ray]'s origin, is not on its normal's side), a depth point without a known confidence of [MIN_DEPTH_CONFIDENCE],
 * another trackable, a camera depth in [capture] outside [PIN_MIN_DEPTH_M]..[PIN_MAX_DEPTH_M], or, for an EAN/UPC of
 * nominal [widthM], a width at that depth outside [PIN_MIN_WIDTH_RATIO]..[PIN_MAX_WIDTH_RATIO] of it; for a code with none,
 * a width at that depth more than [PIN_SIZE_RATIO] either way from the label width its verified pins measured ([labelM]):
 * 2026-10-07 (the third replay of 15:54's recording) four pins were born on floor hits 75-85 cm below the sheet; or a
 * camera depth off the surface of the verified labels beside it ([expect], [neighbourDepth]) by more than [PIN_SIZE_RATIO].
 */
fun hitReject(
    h: RayHit,
    read: Read,
    capture: PoseRecord,
    ray: Ray,
    toCamera: Pose = capture.camera.inverse(),
    widthM: Double? = nominalWidthM(read.symbology),
    labelM: Double? = null,
    expect: Double? = null,
): HitReject? {
    val p = h.point
    when (h.kind) {
        HitKind.PLANE -> {
            if (!h.inPolygon) return HitReject.OFF_POLYGON
            // (ray.origin - h.point) dot h.normal, as scalars
            val o = ray.origin
            val n = h.normal
            if ((o.x - p.x) * n.x + (o.y - p.y) * n.y + (o.z - p.z) * n.z <= 0.0) return HitReject.BACK_OF_PLANE
        }
        HitKind.POINT -> Unit
        HitKind.DEPTH_POINT -> if (!depthConfident(h.confidence)) return HitReject.LOW_CONFIDENCE
        HitKind.OTHER -> return HitReject.KIND
    }
    val z = -applied(toCamera, p.x, p.y, p.z) { _, _, cz -> cz }
    if (z < PIN_MIN_DEPTH_M) return HitReject.NEAR
    if (z > PIN_MAX_DEPTH_M) return HitReject.FAR
    val px = read.widthPx
    if (widthM != null && px > 0.0) {
        val ratio = px * z / capture.intrinsics.fx / widthM
        if (ratio < PIN_MIN_WIDTH_RATIO || ratio > PIN_MAX_WIDTH_RATIO) return HitReject.WIDTH
    } else if (labelM != null && labelM > 0.0 && px > 0.0) {
        val ratio = px * z / capture.intrinsics.fx / labelM
        if (ratio > PIN_SIZE_RATIO || ratio < 1.0 / PIN_SIZE_RATIO) return HitReject.WIDTH
    }
    if (!neighbourAgrees(z, expect)) return HitReject.NEIGHBOUR
    return null
}

/**
 * [code]'s label width as its verified pins measured it at their verification ([PinEstimator.widthM]), their median;
 * null with none: [pickHit]'s size check for a code with no nominal width
 */
fun learnedLabelWidthM(pins: List<Pin>, code: String): Double? {
    var n = 0
    val w = DoubleArray(pins.size)
    for (i in pins.indices) {
        val p = pins[i]
        if (p.code == code && p.verified && p.est.widthM > 0.0) w[n++] = p.est.widthM
    }
    if (n == 0) return null
    java.util.Arrays.sort(w, 0, n)
    return if (n % 2 == 1) w[n / 2] else (w[n / 2 - 1] + w[n / 2]) / 2
}

/**
 * The world point on [read]'s centre ray at the camera depth its nominal [widthM] gives in [capture] (fx · width /
 * widthPx, as the outlines are carried), clamped to [PIN_MIN_DEPTH_M]..[PIN_MAX_DEPTH_M]; null for a quad with no width.
 */
fun nominalPoint(read: Read, capture: PoseRecord, widthM: Double): Vec3? {
    val px = read.widthPx
    if (!(px > 0.0)) return null
    return pointAtDepth(read, capture, (capture.intrinsics.fx * widthM / px).coerceIn(PIN_MIN_DEPTH_M, PIN_MAX_DEPTH_M))
}

/**
 * Phase 3 (§3.4 rule 5): where a listed [read] of [capture] seeds its pin when [pickHit] has nothing, so a birth never
 * needs a hit: its centre ray at camera depth [PIN_DEFAULT_DEPTH_M]
 */
fun defaultPick(read: Read, capture: PoseRecord): HitPick = HitPick(pointAtDepth(read, capture, PIN_DEFAULT_DEPTH_M), -1, HitSource.DEFAULT, false)

/** The world point on [read]'s centre ray at camera depth [z] in [capture] */
private fun pointAtDepth(read: Read, capture: PoseRecord, z: Double): Vec3 {
    val k = capture.intrinsics
    return applied(capture.camera, (read.centreU - k.cx) / k.fx * z, -(read.centreV - k.cy) / k.fy * z, -z) { x, y, w -> Vec3(x, y, w) }
}

/**
 * Whether [pin] is drawn as a ring with no dot, under [PinRules.ANDROID] only: with [refine] (Phase 4) until its rays
 * verify its depth ([Pin.verified]), whatever it was born on, as its depth is still the prior's; with pins frozen
 * (Phases 2 and 3) when it was born on a plane or on no hit, as its depth is the plane's or a guess
 */
fun drawnAsRing(rules: PinRules, refine: Boolean, pin: Pin): Boolean =
    rules == PinRules.ANDROID && (if (refine) !pin.verified else pin.bornOnPlane || pin.bornGuessed)
