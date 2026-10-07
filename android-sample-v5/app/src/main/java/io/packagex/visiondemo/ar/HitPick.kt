package io.packagex.visiondemo.ar

import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.Vec3

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

/** [pickHit]'s answer: the world [point], the hit it is ([index] in the hits; -1 for none), [source], and whether it lies on a plane */
class HitPick(val point: Vec3, val index: Int, val source: HitSource, val onPlane: Boolean)

/**
 * The point a listed [read] of [capture] seeds its pin with, from [hits] on its world centre [ray] (ARCore's order,
 * nearest first): the nearest valid hit ([hitReject]), or a valid plane at most [PIN_PLANE_BEHIND_M] behind it. With
 * none, so no birth is lost: an EAN/UPC takes the point at its nominal width's depth ([nominalPoint]); another code the
 * nearest hit the pins kept before Phase 2 ([RayHit.keptBefore]) within [PIN_MAX_HIT_M], so it admits nothing they
 * dropped; null when there is none. Each hit skipped before the pick is counted in [rejects] by [HitReject.ordinal].
 * [toCamera] (the inverse of [capture]'s camera) and [width] ([read]'s nominal width) are for a caller that has them.
 */
fun pickHit(
    hits: List<RayHit>,
    read: Read,
    capture: PoseRecord,
    ray: Ray,
    rejects: IntArray? = null,
    toCamera: Pose = capture.camera.inverse(),
    width: Double? = nominalWidthM(read.symbology),
): HitPick? {
    var pick = -1
    var pickM = 0.0
    for (i in hits.indices) {
        val h = hits[i]
        val along = alongRay(h, ray)
        if (pick >= 0) {
            if (along > pickM + PIN_PLANE_BEHIND_M) break
            if (h.kind == HitKind.PLANE && hitReject(h, read, capture, ray, toCamera, width) == null) return HitPick(h.point, i, HitSource.HIT, true)
            continue
        }
        val why = hitReject(h, read, capture, ray, toCamera, width)
        if (why != null) {
            rejects?.let { it[why.ordinal]++ }
            continue
        }
        if (h.kind == HitKind.PLANE) return HitPick(h.point, i, HitSource.HIT, true)
        pick = i
        pickM = along
    }
    if (pick >= 0) return HitPick(hits[pick].point, pick, HitSource.HIT, false)
    if (width != null) nominalPoint(read, capture, width)?.let { return HitPick(it, -1, HitSource.WIDTH, false) }
    var i = 0
    while (i < hits.size && !hits[i].keptBefore) i++
    if (i == hits.size || alongRay(hits[i], ray) > PIN_MAX_HIT_M) return null
    return HitPick(hits[i].point, i, HitSource.NEAREST, hits[i].kind == HitKind.PLANE)
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
 * nominal [widthM], a width at that depth outside [PIN_MIN_WIDTH_RATIO]..[PIN_MAX_WIDTH_RATIO] of it.
 */
fun hitReject(
    h: RayHit,
    read: Read,
    capture: PoseRecord,
    ray: Ray,
    toCamera: Pose = capture.camera.inverse(),
    widthM: Double? = nominalWidthM(read.symbology),
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
    }
    return null
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
