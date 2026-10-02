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
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import io.packagex.arcount.ray
import java.util.concurrent.ConcurrentLinkedQueue
import com.google.ar.core.Pose as ArPose

/**
 * AR Item Count's pins on the GL thread: the old AR Barcode renderer's placement (ceff1a4^ ArBarcodeRenderer) over the
 * mapper's reads. Each new frame: the motion gate, the anchors' positions (a STOPPED anchor re-homed at its last
 * position), the sibling merge, candidate expiry, then every read batch that is fresh and captured since the camera was
 * last immoderate: each read's capture-time centre ray (its frame's [PoseRecord]) is hit-tested on this frame, and the
 * [PinBook] matches or confirms; a confirmed pin gets its own world anchor. Pins stay through freezes, abandoned
 * sections and tracking pauses (not drawn while the frame or the anchor does not track) until [clear] (New Scan) or the
 * session goes. Drawn each frame with that frame's camera, at a constant size on screen.
 */
class ArPins(private val density: Float) {
    private val book = PinBook()
    private val motion = PinMotion()
    private val anchors = HashMap<Int, Anchor>()

    /** The newest frames' records, for the capture-time camera of a read batch */
    private val records = ArrayDeque<PoseRecord>()

    @Volatile
    private var clearWanted = false

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val world = FloatArray(4)
    private val eye = FloatArray(4)
    private val clip = FloatArray(4)
    private val hitKinds = HashMap<String, Int>()
    private var hitStatNs = 0L

    /** New Scan (main thread): every pin and candidate goes on the next frame. */
    fun clear() {
        clearWanted = true
    }

    /** GL thread, once per new frame, with its record ([PoseRecord.timestampNs] is the reads' clock). */
    fun onFrame(session: Session, frame: Frame, rec: PoseRecord, reads: ConcurrentLinkedQueue<ArEvent.Reads>, g: StreamGeometry, viewportWidth: Int, viewportHeight: Int) {
        if (clearWanted) {
            clearWanted = false
            anchors.values.forEach { it.detach() }
            anchors.clear()
            book.clear()
            reads.clear()
            Log.i(TAG, "pins cleared (New Scan), live=0")
        }
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
    }

    /** GL thread, every drawn frame: each pin whose anchor tracks, projected with this frame's camera. */
    fun marks(frame: Frame, view: CountView, listed: Set<String>, viewportWidth: Int, viewportHeight: Int): List<ScreenMarker> {
        val camera = frame.camera
        if (book.pins.isEmpty() || camera.trackingState != TrackingState.TRACKING) return emptyList()
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)
        val out = ArrayList<ScreenMarker>(book.pins.size)
        for (pin in book.pins) {
            val a = anchors[pin.id] ?: continue
            if (a.trackingState != TrackingState.TRACKING) continue
            val p = a.pose
            world[0] = p.tx()
            world[1] = p.ty()
            world[2] = p.tz()
            world[3] = 1f
            Matrix.multiplyMV(eye, 0, viewMatrix, 0, world, 0)
            Matrix.multiplyMV(clip, 0, projMatrix, 0, eye, 0)
            val w = clip[3]
            if (w <= 0f) continue // behind the camera
            val nx = clip[0] / w
            val ny = clip[1] / w
            if (nx < -1.1f || nx > 1.1f || ny < -1.1f || ny > 1.1f) continue
            val x = (nx + 1f) / 2f * viewportWidth
            val y = (1f - ny) / 2f * viewportHeight
            out += when (pinColour(pin.payload, pin.symbology, listed, view.items)) {
                PinColour.COUNTED -> ScreenMarker(x, y, dp(DOT_DP), GREEN, dp(RING_DP), WHITE)
                PinColour.LISTED -> ScreenMarker(x, y, dp(DOT_DP), GREY, dp(RING_DP), WHITE)
                PinColour.UNLISTED -> ScreenMarker(x, y, 0f, null, dp(RING_DP), WHITE)
            }
        }
        return out
    }

    /** The batch's reads that land in the view and hit real geometry within reach (old drainDetections' per-detection rules) */
    private fun sightings(frame: Frame, reads: List<Read>, capture: PoseRecord, g: StreamGeometry, viewportWidth: Int, viewportHeight: Int): List<Sighting> {
        val usable = reads.filter { !it.touchesBorder } // a cut symbol's corners, and so its centre, are guessed
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
            val hit = bestHit(frame, ray, capture.timestampNs) ?: return@forEachIndexed
            val h = hit.hitPose
            val at = Vec3(h.tx().toDouble(), h.ty().toDouble(), h.tz().toDouble())
            if ((at - ray.origin).norm() > PIN_MAX_HIT_M) return@forEachIndexed
            out += Sighting(r.text, r.symbology, at, Quat(h.qx().toDouble(), h.qy().toDouble(), h.qz().toDouble(), h.qw().toDouble()), ray)
        }
        return out
    }

    /** Real geometry only, the nearest: a tracked plane hit inside its polygon, a depth point or a feature point (old bestHit) */
    private fun bestHit(frame: Frame, ray: Ray, ts: Long): HitResult? {
        val o = floatArrayOf(ray.origin.x.toFloat(), ray.origin.y.toFloat(), ray.origin.z.toFloat())
        val d = floatArrayOf(ray.dir.x.toFloat(), ray.dir.y.toFloat(), ray.dir.z.toFloat())
        val hit = frame.hitTest(o, 0, d, 0)
            .filter { it.trackable.trackingState == TrackingState.TRACKING }
            .firstOrNull { h ->
                val t = h.trackable
                (t is Plane && t.isPoseInPolygon(h.hitPose)) || t is DepthPoint || t is Point
            }
        hitKinds.merge(hit?.trackable?.javaClass?.simpleName ?: "NONE", 1, Int::plus)
        if (ts - hitStatNs > HIT_STAT_NS) { // how often real geometry is there (old HitDiag)
            hitStatNs = ts
            Log.d(TAG, "hits=$hitKinds candidates=${book.candidateCount} live=${book.pins.size} warm=${motion.mapReady}")
            hitKinds.clear()
        }
        return hit
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
        Log.i(TAG, "pin ${pin.id} created ${pin.payload} at %.3f,%.3f,%.3f, live=${book.pins.size}".format(t.x, t.y, t.z))
    }

    private fun removed(pin: Pin, why: String) {
        anchors.remove(pin.id)?.detach()
        Log.i(TAG, "pin ${pin.id} removed ($why) ${pin.payload}, live=${book.pins.size}")
    }

    private fun dp(v: Float) = v * density

    private companion object {
        const val TAG = "ArPins"
        const val RECORDS = 32
        const val HIT_STAT_NS = 3_000_000_000L

        /** The old renderer's and iOS's marker: a 6 dp dot inside an 11 dp ring */
        const val DOT_DP = 6f
        const val RING_DP = 11f
        val GREEN = floatArrayOf(27 / 255f, 242 / 255f, 163 / 255f)
        val GREY = floatArrayOf(0.62f, 0.62f, 0.66f)
        val WHITE = floatArrayOf(1f, 1f, 1f)
    }
}
