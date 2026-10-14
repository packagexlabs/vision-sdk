package io.packagex.visiondemo.ar

import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import kotlin.math.sqrt

/*
 * Drift plan §3.5 (Phase 1, P4a): the outline of an unlisted code, decoded in an older app-stream image, drawn fixed to
 * the world on the frame shown now. The shape comes from the decode and the motion from ARCore (learning 2): each
 * decoded corner goes back along its capture's ray to a camera depth picked by symbology, then through the shown frame's
 * view and projection, as the pins are drawn (ArPins.toView). Pure, for the JVM tests; no allocation per corner.
 */

/** What AR Item Count draws for unlisted codes (drift plan §4's `OverlayRules`, Settings › Advanced) */
enum class OverlayRules {
    /** The decoded quad where it was read, on whichever frame is shown (the outline before Phase 1) */
    IOS,

    /** The decoded quad carried to the frame shown ([transfer]), the old outline only as the fallback ([chooseOutline]) */
    ANDROID,
}

/** EAN-13 and UPC-A: 95 modules of 0.33 mm (100% magnification), guard bar to guard bar */
const val EAN13_WIDTH_M = 95 * 0.33e-3

/** EAN-8: 67 modules */
const val EAN8_WIDTH_M = 67 * 0.33e-3

/** UPC-E: 51 modules */
const val UPCE_WIDTH_M = 51 * 0.33e-3

/** The camera depths a nominal width may give, metres (§3.5.2) */
const val OUTLINE_MIN_DEPTH_M = 0.08
const val OUTLINE_MAX_DEPTH_M = 1.5

/** The far-safe depth of codes with no nominal width, behind a flag: never worse than ∞ for codes nearer than 1.6 m */
const val FAR_SAFE_DEPTH_M = 0.8

/** An anchor that moves more than this between two frames it tracked marks a map correction (§3.5.4) */
const val MAP_STEP_M = 0.02

/** The map probe ([probePose]) is made this far along the camera's view, where the codes are (the section anchor's depth) */
const val PROBE_DEPTH_M = 0.4

/** The map probe is made again once the camera is farther than this from it */
const val PROBE_REACH_M = 1.5

/**
 * The nominal width of a symbol of [symbology] (the engine's id, as "ean13"; case and punctuation ignored), metres;
 * null for every symbology with no fixed width.
 */
fun nominalWidthM(symbology: String?): Double? = when (symbology?.lowercase()?.filter { it.isLetterOrDigit() }) {
    "ean13", "upca" -> EAN13_WIDTH_M
    "ean8" -> EAN8_WIDTH_M
    "upce" -> UPCE_WIDTH_M
    else -> null
}

/**
 * The camera depth [read]'s corners are carried at (§3.5.2), metres: fx · nominal width / [Read.widthPx], [fx] that of
 * its own image, clamped to [OUTLINE_MIN_DEPTH_M]..[OUTLINE_MAX_DEPTH_M]. Foreshortening only makes it too far, so
 * the error is under-correction. Every other code (or a quad with no width) gets +∞, rotation only, or
 * [FAR_SAFE_DEPTH_M] with [farSafe].
 */
fun outlineDepthM(read: Read, fx: Double, farSafe: Boolean = false): Double {
    val width = nominalWidthM(read.symbology)
    val px = read.widthPx
    if (width == null || !(px > 0.0) || !(fx > 0.0)) return if (farSafe) FAR_SAFE_DEPTH_M else Double.POSITIVE_INFINITY
    return (fx * width / px).coerceIn(OUTLINE_MIN_DEPTH_M, OUTLINE_MAX_DEPTH_M)
}

/**
 * [corners] (x0, y0 .. x3, y3, pixels of the image of [capture]) carried to the frame drawn now (§3.5.1): each corner
 * taken back along [capture]'s ray to camera depth [depthM] (+∞: a direction, w = 0, which is exactly the rotation
 * homography K·R_n⁻¹·R_k·K⁻¹) and projected with that frame's [view] and [projection] (ARCore's, column-major) into
 * pixels of a [viewportWidth] x [viewportHeight] view, written to [out] from [offset]. False when a corner lands
 * behind the camera: nothing is drawn.
 */
fun transfer(
    corners: List<Double>,
    capture: PoseRecord,
    view: FloatArray,
    projection: FloatArray,
    depthM: Double,
    viewportWidth: Int,
    viewportHeight: Int,
    out: FloatArray,
    offset: Int = 0,
): Boolean {
    val invZ = 1.0 / depthM
    for (c in 0 until 4) {
        val ok = lift(corners[2 * c], corners[2 * c + 1], capture, invZ) { x, y, z, w ->
            // eye = view · p, then clip = projection · eye (column-major)
            val ex = view[0] * x + view[4] * y + view[8] * z + view[12] * w
            val ey = view[1] * x + view[5] * y + view[9] * z + view[13] * w
            val ez = view[2] * x + view[6] * y + view[10] * z + view[14] * w
            val ew = view[3] * x + view[7] * y + view[11] * z + view[15] * w
            val cx = projection[0] * ex + projection[4] * ey + projection[8] * ez + projection[12] * ew
            val cy = projection[1] * ex + projection[5] * ey + projection[9] * ez + projection[13] * ew
            val cw = projection[3] * ex + projection[7] * ey + projection[11] * ez + projection[15] * ew
            if (cw <= BEHIND) {
                false
            } else {
                out[offset + 2 * c] = ((cx / cw + 1) / 2 * viewportWidth).toFloat()
                out[offset + 2 * c + 1] = ((1 - cy / cw) / 2 * viewportHeight).toFloat()
                true
            }
        }
        if (!ok) return false
    }
    return true
}

/**
 * Where [transfer] puts the centre of [corners] (their mean, as [Read.centreU] is), in pixels of the image of [now]
 * (its camera and intrinsics), for M3; written to [out] (u, v). False when a corner lands behind that camera.
 */
fun transferredCentre(corners: List<Double>, capture: PoseRecord, depthM: Double, now: PoseRecord, out: DoubleArray): Boolean {
    val invZ = 1.0 / depthM
    val q = now.camera.q
    val t = now.camera.t
    val k = now.intrinsics
    var su = 0.0
    var sv = 0.0
    for (c in 0 until 4) {
        val ok = lift(corners[2 * c], corners[2 * c + 1], capture, invZ) { x, y, z, w ->
            // camera = R_nᵀ · (p - t_n · w), then the pinhole (image down is camera -y)
            rotate(-q.x, -q.y, -q.z, q.w, x - t.x * w, y - t.y * w, z - t.z * w) { px, py, pz ->
                if (pz >= -BEHIND) {
                    false
                } else {
                    su += k.cx + k.fx * px / -pz
                    sv += k.cy - k.fy * py / -pz
                    true
                }
            }
        }
        if (!ok) return false
    }
    out[0] = su / 4
    out[1] = sv / 4
    return true
}

/**
 * Pixel ([u], [v]) of [capture]'s image taken back along its ray to camera depth 1 / [invZ], world, as homogeneous
 * (x, y, z, w) scaled by [invZ] (positive): R_k · ray + t_k · invZ, w = invZ. With invZ 0 it is the ray's direction.
 */
private inline fun <T> lift(u: Double, v: Double, capture: PoseRecord, invZ: Double, then: (Double, Double, Double, Double) -> T): T {
    val k = capture.intrinsics
    val q = capture.camera.q
    val t = capture.camera.t
    // The ray in the camera at depth 1: +x right, +y up (image down is -y), -z forward
    return rotate(q.x, q.y, q.z, q.w, (u - k.cx) / k.fx, -(v - k.cy) / k.fy, -1.0) { x, y, z ->
        then(x + t.x * invZ, y + t.y * invZ, z + t.z * invZ, invZ)
    }
}

/** ([vx], [vy], [vz]) turned by the unit quaternion ([qx], [qy], [qz], [qw]), as [io.packagex.arcount.Quat.rotate] */
private inline fun <T> rotate(qx: Double, qy: Double, qz: Double, qw: Double, vx: Double, vy: Double, vz: Double, then: (Double, Double, Double) -> T): T {
    val tx = 2 * (qy * vz - qz * vy)
    val ty = 2 * (qz * vx - qx * vz)
    val tz = 2 * (qx * vy - qy * vx)
    return then(vx + qw * tx + (qy * tz - qz * ty), vy + qw * ty + (qz * tx - qx * tz), vz + qw * tz + (qx * ty - qy * tx))
}

/** A clip w (or camera z) this close to zero is behind the camera */
private const val BEHIND = 1e-9

/** The frames a read can be carried from: their records, and whether the map moved since (ArPins keeps both) */
interface CaptureFrames {
    /** The record of the frame captured at [timestampNs], while it is kept; null when it is not */
    fun recordAt(timestampNs: Long): PoseRecord?

    /** Whether ARCore corrected its map or lost tracking on a frame after the one captured at [timestampNs] */
    fun mapCorrectedSince(timestampNs: Long): Boolean
}

/**
 * One unlisted track's outline on the frame drawn: [read]'s quad, carried from [capture]; drawn where it was read when
 * [capture] is null; not drawn when [mapMoved] (the map moved since [capture])
 */
class OutlinePick(val read: Read, val capture: PoseRecord?, val mapMoved: Boolean = false)

/**
 * Which read of an unlisted [track] (its reads newest first, [unlistedTracks]) is outlined on the frame drawn now, and
 * how (§3.5.4). Not [carry] (the [OverlayRules.IOS] rules, or the frame drawn does not track): the newest read, where
 * it was read. Else the newest read whose own frame tracked, carried from it, and not drawn ([OutlinePick.mapMoved])
 * when it was captured before a map correction or a tracking loss; none did: the newest read, where it was read. Null
 * for an empty track.
 */
fun chooseOutline(track: List<Read>, carry: Boolean, frames: CaptureFrames): OutlinePick? {
    val newest = track.firstOrNull() ?: return null
    if (!carry) return OutlinePick(newest, null)
    for (r in track) {
        val capture = frames.recordAt(r.timestampNs) ?: continue
        if (capture.frameTracking != Tracking.TRACKING) continue
        return OutlinePick(r, capture, frames.mapCorrectedSince(r.timestampNs))
    }
    return OutlinePick(newest, null)
}

/**
 * The newest frame on which ARCore was seen to correct its map (an anchor moved more than [stepM] between two frames it
 * tracked) or to lose tracking (§3.5.4): an outline captured before it is not carried across it. GL thread.
 */
internal class MapBreaks(private val stepM: Double = MAP_STEP_M) {
    /** The newest such frame's timestamp; [Long.MIN_VALUE] before any */
    var newestNs = Long.MIN_VALUE
        private set

    /** A frame at [timestampNs]: one that does not track breaks */
    fun frame(timestampNs: Long, tracking: Boolean) {
        if (!tracking) mark(timestampNs)
    }

    /** An anchor moved [metres] since the last frame it tracked, seen on the frame at [timestampNs]; NaN is no step */
    fun anchorMoved(timestampNs: Long, metres: Double) {
        if (metres > stepM) mark(timestampNs)
    }

    /** Whether a break came on a frame after the one captured at [timestampNs] */
    fun since(timestampNs: Long): Boolean = newestNs > timestampNs

    private fun mark(timestampNs: Long) {
        if (timestampNs > newestNs) newestNs = timestampNs
    }
}

/**
 * One anchor's step between the frames it tracked, for [MapBreaks]. Another anchor object starts over: a section
 * handoff or a re-made anchor is no step. GL thread; no allocation.
 */
internal class AnchorStep {
    private var anchor: Any? = null
    private var x = 0.0
    private var y = 0.0
    private var z = 0.0

    /** [anchor] tracked at ([x], [y], [z]) on this frame: metres moved since the last frame it tracked; NaN when it is new here */
    fun at(anchor: Any, x: Double, y: Double, z: Double): Double {
        val step = if (anchor === this.anchor) distance(x, y, z, this.x, this.y, this.z) else Double.NaN
        this.anchor = anchor
        this.x = x
        this.y = y
        this.z = z
        return step
    }
}

/** What the GL thread does with its map probe on a frame whose camera tracks */
internal enum class ProbeAction {
    /** It tracks within reach: its step goes to [MapBreaks] */
    KEEP,

    /** It is paused: nothing this frame */
    WAIT,

    /** None yet, it stopped, or the camera left it behind: (re-)made at [probePose] */
    MAKE,
}

/**
 * The map probe (§3.5.4) is a world anchor of the renderer's own, so a map correction is seen while no pin and no
 * section anchor exists (item mode before a listed code is read, or an empty item list), when the unlisted outlines are
 * the only overlay. On a frame whose camera tracks: the probe's ARCore [tracking] (null: none) and its distance from
 * the camera, metres ([distanceM]).
 */
internal fun probeAction(tracking: Tracking?, distanceM: Double): ProbeAction = when (tracking) {
    null, Tracking.STOPPED -> ProbeAction.MAKE
    Tracking.PAUSED -> ProbeAction.WAIT
    Tracking.TRACKING -> if (distanceM > PROBE_REACH_M) ProbeAction.MAKE else ProbeAction.KEEP
}

/** Where the map probe is made: [PROBE_DEPTH_M] along [camera]'s view, unrotated */
internal fun probePose(camera: Pose): Pose = Pose(camera.apply(Vec3(0.0, 0.0, -PROBE_DEPTH_M)), Quat.IDENTITY)

internal fun distance(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
    val dx = ax - bx
    val dy = ay - by
    val dz = az - bz
    return sqrt(dx * dx + dy * dy + dz * dz)
}
