package io.packagex.visiondemo.ar

import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.packagex.arcount.CountView
import io.packagex.arcount.ItemCode
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import io.packagex.arcount.ray
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.hypot
import com.google.ar.core.Pose as ArPose

/**
 * AR Item Count's pins on the GL thread, for listed codes only. Each new frame: the motion gate, the anchors' positions
 * (a STOPPED anchor re-homed at its last position), the core's unit points ([CountView.unitPoints]: a pin at each,
 * moved only when the core's point moves more than 3 cm), the sibling merge, candidate expiry, then every read batch
 * that is fresh and captured since the camera was last immoderate: each listed read's capture-time centre ray (its
 * frame's [PoseRecord]) is hit-tested on this frame, the hit must reproject onto the read and lie at a plausible depth
 * ([checkHit]), and the [PinBook] matches or confirms a provisional pin. Every pin is its own world anchor: pins stay
 * through freezes, closed and abandoned sections and tracking pauses (not drawn while the frame or the anchor does not
 * track) until [clear] (New Scan) or the session goes. Drawn each frame with that frame's camera, at a constant size.
 */
class ArPins(private val density: Float) {
    private val book = PinBook()
    private val motion = PinMotion()
    private val anchors = HashMap<Int, Anchor>()

    /** The newest frames' records, for the capture-time camera of a read batch */
    private val records = ArrayDeque<PoseRecord>()

    @Volatile
    private var clearWanted = false

    private var listed: Set<String>? = null
    private var keys: Set<String> = emptySet()
    private var followed: CountView? = null

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val world = FloatArray(4)
    private val eye = FloatArray(4)
    private val clip = FloatArray(4)

    // The 3 s line: hit kinds, the hits' reprojection errors and rejections, and the ray check
    private val hitKinds = HashMap<String, Int>()
    private val errors = ArrayList<Double>()
    private var rejectedReprojection = 0
    private var rejectedDepth = 0
    private var statNs = 0L

    /** New Scan (main thread): every pin and candidate goes on the next frame. */
    fun clear() {
        clearWanted = true
    }

    /** GL thread, once per new frame, with its record ([PoseRecord.timestampNs] is the reads' clock). */
    fun onFrame(
        session: Session,
        frame: Frame,
        rec: PoseRecord,
        reads: ConcurrentLinkedQueue<ArEvent.Reads>,
        view: CountView,
        items: Set<String>,
        g: StreamGeometry,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        if (clearWanted) {
            clearWanted = false
            anchors.values.forEach { it.detach() }
            anchors.clear()
            book.clear()
            reads.clear()
            Log.i(TAG, "pins cleared (New Scan), live=0")
        }
        keysOf(items)
        val ts = rec.timestampNs
        val tracking = rec.frameTracking == Tracking.TRACKING
        motion.onFrame(ts, rec.camera, tracking)
        records.addLast(rec)
        while (records.size > RECORDS) records.removeFirst()
        if (!tracking) { // old renderer: batches decoded before or during the gap are not trusted
            reads.clear()
            return
        }
        refreshPositions(session)
        if (view !== followed) {
            followed = view
            for (pin in book.follow(view.unitPoints, ts)) place(session, pin)
        }
        if (book.mergePending) book.mergeSiblings().forEach { removed(it, "merged with its sibling") }
        book.expireCandidates(ts)
        var later: ArrayList<ArEvent.Reads>? = null
        while (true) {
            val batch = reads.poll() ?: break
            if (!motion.accepts(batch.timestampNs, ts)) continue
            val capture = records.lastOrNull { it.timestampNs == batch.timestampNs }
            if (capture == null) {
                if (batch.timestampNs > ts) (later ?: ArrayList<ArEvent.Reads>().also { later = it }) += batch // its frame is not drawn yet
                continue
            }
            if (capture.frameTracking != Tracking.TRACKING) continue
            val sightings = sightings(frame, batch.reads, capture, g, viewportWidth, viewportHeight)
            book.place(sightings, ts, motion.mapReady && motion.cameraModerate).forEach { create(session, it) }
        }
        later?.let { reads.addAll(it) }
        if (ts - statNs > STAT_NS) {
            statNs = ts
            logStats(frame, rec, g, viewportWidth, viewportHeight)
        }
    }

    /** GL thread, every drawn frame: each pin whose anchor tracks, projected with this frame's camera. */
    fun marks(frame: Frame, view: CountView, viewportWidth: Int, viewportHeight: Int): List<ScreenMarker> {
        val camera = frame.camera
        if (book.pins.isEmpty() || camera.trackingState != TrackingState.TRACKING) return emptyList()
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)
        val out = ArrayList<ScreenMarker>(book.pins.size)
        for (pin in book.pins) {
            val colour = pinColour(pin.code, keys, view.items) ?: continue
            val a = anchors[pin.id] ?: continue
            if (a.trackingState != TrackingState.TRACKING) continue
            val p = a.pose
            val (x, y) = toView(p.tx(), p.ty(), p.tz(), viewportWidth, viewportHeight) ?: continue
            if (x < -0.05f * viewportWidth || x > 1.05f * viewportWidth || y < -0.05f * viewportHeight || y > 1.05f * viewportHeight) continue
            out += ScreenMarker(x, y, dp(DOT_DP), if (colour == PinColour.COUNTED) GREEN else GREY, dp(RING_DP), WHITE)
        }
        return out
    }

    /** A world point in view pixels through [viewMatrix] and [projMatrix]; null behind the camera */
    private fun toView(x: Float, y: Float, z: Float, viewportWidth: Int, viewportHeight: Int): Pair<Float, Float>? {
        world[0] = x
        world[1] = y
        world[2] = z
        world[3] = 1f
        Matrix.multiplyMV(eye, 0, viewMatrix, 0, world, 0)
        Matrix.multiplyMV(clip, 0, projMatrix, 0, eye, 0)
        val w = clip[3]
        if (w <= 0f) return null
        return (clip[0] / w + 1f) / 2f * viewportWidth to (1f - clip[1] / w) / 2f * viewportHeight
    }

    private fun keysOf(items: Set<String>) {
        if (items === listed) return
        listed = items
        keys = listedKeys(items)
    }

    /** The batch's listed reads that land in the view and hit real geometry that passes [checkHit] (old drainDetections' per-detection rules) */
    private fun sightings(frame: Frame, reads: List<Read>, capture: PoseRecord, g: StreamGeometry, viewportWidth: Int, viewportHeight: Int): List<Sighting> {
        // A cut symbol's corners, and so its centre, are guessed; unlisted codes get no pin
        val usable = reads.filter { !it.touchesBorder && ItemCode.key(it) in keys }
        if (usable.isEmpty()) return emptyList()
        val image = FloatArray(usable.size * 2)
        usable.forEachIndexed { i, r ->
            image[2 * i] = g.cpuU(r.centreU / g.streamWidth).toFloat()
            image[2 * i + 1] = g.cpuV(r.centreV / g.streamHeight).toFloat()
        }
        val onView = FloatArray(image.size)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, image, Coordinates2d.VIEW, onView)
        val out = ArrayList<Sighting>(usable.size)
        usable.forEachIndexed { i, r ->
            val vx = onView[2 * i]
            val vy = onView[2 * i + 1]
            if (vx < 0 || vy < 0 || vx > viewportWidth || vy > viewportHeight) return@forEachIndexed
            val ray = r.ray(capture, anchor = null) // world ray of the capture-time camera
            val hit = bestHit(frame, ray) ?: return@forEachIndexed
            val h = hit.hitPose
            val at = Vec3(h.tx().toDouble(), h.ty().toDouble(), h.tz().toDouble())
            val check = checkHit(at, r, capture)
            if (check.errorPx.isFinite()) errors += check.errorPx
            if (!check.accepted) {
                if (check.depthM !in PIN_MIN_DEPTH_M..PIN_MAX_DEPTH_M) rejectedDepth++ else rejectedReprojection++
                return@forEachIndexed
            }
            out += Sighting(ItemCode.key(r), at, Quat(h.qx().toDouble(), h.qy().toDouble(), h.qz().toDouble(), h.qw().toDouble()), ray)
        }
        return out
    }

    /** Real geometry only, the nearest: a tracked plane hit inside its polygon, a depth point or a feature point (old bestHit) */
    private fun bestHit(frame: Frame, ray: Ray): HitResult? {
        val o = floatArrayOf(ray.origin.x.toFloat(), ray.origin.y.toFloat(), ray.origin.z.toFloat())
        val d = floatArrayOf(ray.dir.x.toFloat(), ray.dir.y.toFloat(), ray.dir.z.toFloat())
        val hit = frame.hitTest(o, 0, d, 0)
            .filter { it.trackable.trackingState == TrackingState.TRACKING }
            .firstOrNull { h ->
                val t = h.trackable
                (t is Plane && t.isPoseInPolygon(h.hitPose)) || t is DepthPoint || t is Point
            }
        hitKinds.merge(hit?.trackable?.javaClass?.simpleName ?: "NONE", 1, Int::plus)
        return hit
    }

    /**
     * Every [STAT_NS]: the hit kinds (old HitDiag), the hits' reprojection errors into their reads' frames and what the
     * gates rejected, and the ray check: points of the stream image sent 1 m along the model's ray (this frame's pose
     * and stream intrinsics, as the reads' rays are made) and projected back with ARCore's own view and projection,
     * against ARCore's own image-to-view mapping of the same points. A model that is right gives a pixel or two.
     */
    private fun logStats(frame: Frame, rec: PoseRecord, g: StreamGeometry, viewportWidth: Int, viewportHeight: Int) {
        val sorted = errors.sorted()
        val reproj = if (sorted.isEmpty()) "none" else "median %.1f max %.1f px (%d)".format(sorted[sorted.size / 2], sorted.last(), sorted.size)
        val core = book.pins.count { it.fromCore }
        Log.i(
            TAG,
            "hits=$hitKinds reprojection $reproj, rejected $rejectedReprojection reprojection $rejectedDepth depth; " +
                "${rayCheck(frame, rec, g, viewportWidth, viewportHeight)}; candidates=${book.candidateCount} live=${book.pins.size} (core $core, provisional ${book.pins.size - core}) warm=${motion.mapReady}",
        )
        hitKinds.clear()
        errors.clear()
        rejectedReprojection = 0
        rejectedDepth = 0
    }

    private fun rayCheck(frame: Frame, rec: PoseRecord, g: StreamGeometry, viewportWidth: Int, viewportHeight: Int): String {
        val camera = frame.camera
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)
        val k = rec.intrinsics
        val image = FloatArray(CHECK_POINTS.size)
        for (i in CHECK_POINTS.indices step 2) {
            image[i] = g.cpuU(CHECK_POINTS[i]).toFloat()
            image[i + 1] = g.cpuV(CHECK_POINTS[i + 1]).toFloat()
        }
        val arcore = FloatArray(image.size)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, image, Coordinates2d.VIEW, arcore)
        // View pixels per stream pixel, from the centre and the first corner point as ARCore maps them
        val streamPx = hypot((CHECK_POINTS[2] - CHECK_POINTS[0]) * k.width, (CHECK_POINTS[3] - CHECK_POINTS[1]) * k.height)
        val viewPerStream = hypot(arcore[2] - arcore[0], arcore[3] - arcore[1]) / streamPx
        val errs = ArrayList<Double>()
        for (i in CHECK_POINTS.indices step 2) {
            val at = rec.camera.apply(k.rayInCamera(CHECK_POINTS[i] * k.width, CHECK_POINTS[i + 1] * k.height))
            val (x, y) = toView(at.x.toFloat(), at.y.toFloat(), at.z.toFloat(), viewportWidth, viewportHeight) ?: continue
            errs += hypot(x - arcore[i], y - arcore[i + 1]) / viewPerStream * 3840.0 / k.width
        }
        if (errs.isEmpty() || !viewPerStream.isFinite() || viewPerStream <= 0) return "rayCheck n/a"
        errs.sort()
        return "rayCheck model vs ARCore median %.1f max %.1f px (4K)".format(errs[errs.size / 2], errs.last())
    }

    /** Old markerPosition: a tracking anchor's position is cached; a STOPPED one is re-made as a world anchor there */
    private fun refreshPositions(session: Session) {
        for (pin in book.pins) {
            val a = anchors[pin.id] ?: continue
            when (a.trackingState) {
                TrackingState.TRACKING -> a.pose.let { pin.position = Vec3(it.tx().toDouble(), it.ty().toDouble(), it.tz().toDouble()) }
                TrackingState.STOPPED -> runCatching {
                    val p = pin.position
                    session.createAnchor(ArPose.makeTranslation(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
                }.onSuccess {
                    a.detach()
                    anchors[pin.id] = it
                    Log.i(TAG, "pin ${pin.id} re-anchored (its anchor stopped), live=${book.pins.size}")
                } // NotTrackingException: keep the old one, retry next frame
                else -> Unit
            }
        }
    }

    /** A provisional pin from hit tests */
    private fun create(session: Session, pin: Pin) {
        val a = runCatching { session.createAnchor(pin.pose.toArPose()) }
            .onFailure { Log.w(TAG, "pin ${pin.id} anchor not created", it) }
            .getOrNull()
        if (a == null) {
            book.remove(pin.id)
            return
        }
        anchors[pin.id] = a
        val t = pin.pose.t
        Log.i(TAG, "pin ${pin.id} created ${pin.code} provisional (hit) at %.3f,%.3f,%.3f, live=${book.pins.size}".format(t.x, t.y, t.z))
    }

    /** A pin at the core's point: new, snapped from provisional, or moved by a better triangulation; a new anchor there */
    private fun place(session: Session, pin: Pin) {
        val old = anchors[pin.id]
        val a = runCatching { session.createAnchor(pin.pose.toArPose()) }
            .onFailure { Log.w(TAG, "pin ${pin.id} anchor not made at the core's point", it) }
            .getOrNull()
        if (a == null) {
            if (old == null) book.remove(pin.id) else book.unplace(pin.id)
            return
        }
        val t = pin.pose.t
        val what = if (old == null) "created" else "moved"
        val by = old?.pose?.let { " by %.1f cm".format(hypot(hypot(it.tx() - t.x, it.ty() - t.y), it.tz() - t.z) * 100) } ?: ""
        old?.detach()
        anchors[pin.id] = a
        Log.i(TAG, "pin ${pin.id} $what ${pin.code} at core unit ${pin.unitKey} %.3f,%.3f,%.3f$by, live=${book.pins.size}".format(t.x, t.y, t.z))
    }

    private fun removed(pin: Pin, why: String) {
        anchors.remove(pin.id)?.detach()
        Log.i(TAG, "pin ${pin.id} removed ($why) ${pin.code}, live=${book.pins.size}")
    }

    private fun dp(v: Float) = v * density

    private companion object {
        const val TAG = "ArPins"
        const val RECORDS = 32
        const val STAT_NS = 3_000_000_000L

        /** Normalized stream points of the ray check: the centre, then four points near the corners */
        val CHECK_POINTS = doubleArrayOf(0.5, 0.5, 0.2, 0.2, 0.8, 0.2, 0.2, 0.8, 0.8, 0.8)

        /** The old renderer's and iOS's marker: a 6 dp dot inside an 11 dp ring */
        const val DOT_DP = 6f
        const val RING_DP = 11f
        val GREEN = floatArrayOf(27 / 255f, 242 / 255f, 163 / 255f)
        val GREY = floatArrayOf(0.62f, 0.62f, 0.66f)
        val WHITE = floatArrayOf(1f, 1f, 1f)
    }
}
