package io.packagex.visiondemo.ar

import android.media.Image
import android.opengl.Matrix
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import io.packagex.arcount.CountView
import io.packagex.arcount.ItemCount
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.hypot
import kotlin.math.min
import com.google.ar.core.Pose as ArPose

/**
 * AR Item Count's pins on the GL thread, for listed codes only, placed and removed as the iOS demo's AR scanner does
 * ([PinBook]). Each new frame: the motion and warm-up gates, the anchors' poses (kept with their pins, [Pin.anchors]),
 * then every read batch at most 0.5 s old whose frame tracked: each listed read's capture-time centre ray (its frame's
 * [PoseRecord]) is hit-tested on this frame (a depth point only where its depth is confident), and the [PinBook] claims
 * a pin or confirms a candidate, which gets its own world anchor. Which hit a read takes follows [rules]:
 * [PinRules.IOS] a plane in its polygon first, [PinRules.ANDROID] (drift plan Phase 2) the nearest valid one
 * ([pickHit]), else a prior that needs no hit (Phase 3, [defaultPick]). The rules also pick the [PinBook]'s identity
 * rules: under [PinRules.IOS] pins go only by the sibling merge, [clear] (New Scan) or the session going, and move only
 * with their anchors; under [PinRules.ANDROID] (drift plan §3.4) by the physical merge, retirement, [clear] or the
 * session going, and with [refine] each claim refines its pin in its anchor's frame as it stood at the capture
 * (Phase 4). A pin whose anchor stops, or with [refine] whose point strays [PIN_REANCHOR_M] from its anchor, gets a new
 * anchor there (rule 8). Through tracking pauses they are kept and not drawn. Drawn each frame with that frame's camera,
 * at a constant size: a dot, or under [PinRules.ANDROID] a ring while its depth is unsure ([drawnAsRing]). With
 * [refine] and [readBoost], while a listed pin waits for its depth, the engine reads at full rate ([wantFullRate],
 * P2c). For the unlisted outlines (drift plan §3.5) it also answers which record a capture had
 * ([recordAt]) and whether the map moved since ([mapCorrectedSince]): a pin anchor, the section anchor or the
 * renderer's map probe stepping more than [MAP_STEP_M] between frames, or a frame that did not track.
 *
 * Measured (drift plan Phase 0): where each pin was drawn on each frame ([DrawnPins]), so every claim is compared with
 * its pin as drawn on the read's own frame (M1), and a pin claimed after a second out of view is followed until it
 * settles (M2) ([PinMetrics]); every listed read against its nearest same-code pin, the batches the pins drop (not
 * tracking, stale) included; the hits and why reads were turned away, births and merges, every [STAT_NS] in
 * [logStats], and per read and per pin event into the open trace ([ArMapper.diag]); and what each frame's pin work was
 * ([work], M6's slowest frame).
 *
 * The GL thread runs this per read, per hit and per pin on every frame, so it leaves no garbage there: one ARCore
 * trackable and hit pose per hit, each camera inverted once ([Inverses]), each read's key made once per text
 * ([ReadKeys]), the anchors' poses taken once a frame, the lists and arrays kept, and the log and trace text built only
 * when written. The numbers are the same as computed before, bit for bit.
 */
class ArPins internal constructor(
    private val density: Float,
    private val trace: ArMapper,
    private val readKeys: ReadKeys = ReadKeys(),
) : CaptureFrames {
    private val book = PinBook()
    private val motion = PinMotion()

    /** Each pin's anchor, by its id (unboxed: ids grow with every re-birth) */
    private val anchors = IntMap<Anchor>()

    /** What the pins did on the frame being made (M6's slowest frame); the renderer clears it per new frame */
    val work = PinWork()

    /** The newest frames' records, for the capture-time camera of a read batch */
    private val records = ArrayDeque<PoseRecord>()

    /** The newest frame on which the map moved under an anchor or tracking was lost (the unlisted outlines, §3.5.4) */
    private val breaks = MapBreaks()

    /** Each pin as drawn on the newest frames, for M1 */
    private val drawn = DrawnPins(RECORDS)
    private val metrics = PinMetrics()

    @Volatile
    private var clearWanted = false

    /** Which hit a read takes, the [PinBook]'s identity rules, and how an unverified pin is drawn (Settings › Advanced) */
    @Volatile
    var rules: PinRules = PinRules.ANDROID

    /** [rules] as this frame's reads are placed under */
    private var frameRules = PinRules.ANDROID

    /** Under [PinRules.ANDROID], whether claims refine the pins (drift plan Phase 4, [PinBook.refine]; Settings › Advanced) */
    @Volatile
    var refine = true

    /** [refine] as this frame's reads are placed under */
    private var frameRefine = true

    /** P2c under [PinRules.ANDROID] with [refine] (Settings › Advanced) */
    @Volatile
    var readBoost = true

    private val boost = ReadBoost()

    /** P2c, read on the engine worker before every scan: refresh 0 wanted ([ReadBoost]) */
    val wantFullRate: Boolean get() = boost.on

    // A pin's point in its anchor's frame, and in the world, for the GL draw
    private val local = FloatArray(3)
    private val point = FloatArray(3)

    private var listed: Set<String>? = null
    private var keys: Set<String> = emptySet()

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val world = FloatArray(4)
    private val eye = FloatArray(4)
    private val clip = FloatArray(4)

    /** [toView]'s answer, view pixels x, y */
    private val viewXY = FloatArray(2)

    /** The cameras' inverses (a batch's capture and the frame's own): one per pose, not one per read, hit and pin */
    private val inverses = Inverses()

    /** The frame's depth confidence image, one per frame ([DepthConfidence.begin]) */
    private val depth = DepthConfidence()

    // One read's hits (kept per read): ARCore's tracked hits, each one's trackable (one wrapper per hit, not one per
    // question) and hit pose (taken when first needed), and Phase 2's judgement of them; the ray as ARCore takes it
    private val hitResults = ArrayList<HitResult>()
    private val hitTrackables = ArrayList<Trackable>()
    private val hitPoses = ArrayList<ArPose?>()
    private val rayHits = ArrayList<RayHit>()
    private val rayOrigin = FloatArray(3)
    private val rayDir = FloatArray(3)
    private val yAxis = FloatArray(3)

    /** Trackables' class names, as the trace and the 3 s line print them */
    private val simpleNames = HashMap<Class<*>, String>()

    // One batch's listed reads and their keys, those not cut by the border and theirs; the read centres and the view
    // points, one pair of arrays per size
    private val listedReads = ArrayList<Read>()
    private val listedCodes = ArrayList<String>()
    private val usableReads = ArrayList<Read>()
    private val usableCodes = ArrayList<String>()
    private val imageBySize = arrayOfNulls<FloatArray>(SIZES)
    private val viewBySize = arrayOfNulls<FloatArray>(SIZES)

    /** A pin as drawn on a read's frame (M1) */
    private val drawnPoint = DoubleArray(3)

    // The batch being placed, for [createPin]: one callback, not one capturing lambda per batch
    private var batchSession: Session? = null
    private var batchNowNs = 0L
    private var batchCapture: PoseRecord? = null
    private val createPin: (Pin) -> Boolean = { create(batchSession!!, it, batchNowNs, batchCapture!!) }

    // The anchors' poses and tracking states as [refreshPositions] took them on this frame, for [marks] on the same
    // frame (one pose and state per anchor a frame, not two), by anchor identity; [posesFresh] while they are this frame's
    private val frameAnchors = ArrayList<Anchor>()
    private val framePoses = ArrayList<ArPose?>()
    private val frameStates = ArrayList<TrackingState>()
    private var posesFresh = false

    // The drawn pins (one list, drawn at once) and the counted codes of the item list they were coloured by
    private val marked = ArrayList<ScreenMarker>()
    private var countedOf: List<ItemCount>? = null
    private var counted: Set<String> = emptySet()

    // The 3 s line: hit kinds, the hits' reprojection errors and rejections, births and merges, read ages, and the ray check
    private val lines = LineFormat()
    private val hitKinds = HashMap<String, Int>()
    private val errors = Samples(64)
    private var farHits = 0
    private var lowConfidence = 0
    private val hitRejects = IntArray(HitReject.entries.size)
    private val hitSources = IntArray(HitSource.entries.size)
    private var offView = 0
    private var noHit = 0
    private var cut = 0
    private var births = 0
    private var merges = 0
    private var retirements = 0
    private var voids = 0
    private var reinits = 0
    private var reanchors = 0
    private val unusedReads = IntArray(Unused.entries.size)
    private var unmeasuredReads = 0
    private val ages = Samples(64)
    private var statNs = 0L
    private val checkImage = FloatArray(CHECK_POINTS.size)
    private val checkView = FloatArray(CHECK_POINTS.size)
    private val checkErrs = DoubleArray(CHECK_POINTS.size / 2)

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
        items: Set<String>,
        g: StreamGeometry,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        posesFresh = false
        if (clearWanted) {
            clearWanted = false
            for (i in 0 until anchors.size) anchors.valueAt(i).detach()
            anchors.clear()
            for (pin in book.pins) trace.diag { pinGoneLine(rec.timestampNs, pin, "clear", pin.position) }
            book.clear()
            boost.clear()
            metrics.clearPins()
            reads.clear()
            Log.i(TAG, "pins cleared (New Scan), live=0")
        }
        keysOf(items)
        frameRules = rules
        frameRefine = refine
        book.rules = frameRules
        book.refine = frameRefine
        val ts = rec.timestampNs
        val tracking = rec.frameTracking == Tracking.TRACKING
        motion.onFrame(ts, rec.camera, tracking)
        breaks.frame(ts, tracking)
        records.addLast(rec)
        while (records.size > RECORDS) records.removeFirst()
        if (!tracking) { // ARCore hit-tests nothing on a frame it does not track; the reads waiting are stale after it
            while (true) unused(reads.poll() ?: break, Unused.NOT_TRACKING, rec)
            boost.paused()
            return
        }
        val r0 = Debug.threadCpuTimeNanos()
        refreshPositions(session, ts)
        work.refreshNs += Debug.threadCpuTimeNanos() - r0
        book.mapMovedNs = breaks.newestNs // after this frame's anchor steps: the void rule holds only while the map is still (§3.4 rule 3)
        depth.begin(frame)
        try {
            drain(session, frame, rec, reads, g, viewportWidth, viewportHeight)
        } finally {
            depth.close()
        }
        val b0 = Debug.threadCpuTimeNanos()
        boost.frame(rec, book.pins, keys, boosting(), inverses[rec.camera])
        work.boostNs += Debug.threadCpuTimeNanos() - b0
    }

    /** P2c runs for refined pins under the Android rules: the reads it adds are rays for their estimators */
    private fun boosting() = frameRules == PinRules.ANDROID && frameRefine && readBoost

    /** GL thread: the record of the frame captured at [timestampNs], while it is one of the newest [RECORDS] */
    override fun recordAt(timestampNs: Long): PoseRecord? {
        var i = records.size - 1
        while (i >= 0) {
            if (records[i].timestampNs == timestampNs) return records[i]
            i--
        }
        return null
    }

    /** GL thread: whether an anchor moved more than [MAP_STEP_M] between two frames, or tracking was lost, after [timestampNs] */
    override fun mapCorrectedSince(timestampNs: Long): Boolean = breaks.since(timestampNs)

    /** GL thread: an anchor the pins do not hold (the section's, the map probe) moved [metres] since the last frame it tracked; NaN: none */
    fun anchorMoved(timestampNs: Long, metres: Double) = breaks.anchorMoved(timestampNs, metres)

    /**
     * GL thread, after the frame's work is timed (its formatting is not the frame's cost): every [STAT_NS] of tracked
     * frames, the 3 s lines. [camera] is the frame's.
     */
    fun logIfDue(frame: Frame, camera: Camera, rec: PoseRecord, g: StreamGeometry, viewportWidth: Int, viewportHeight: Int) {
        if (rec.frameTracking != Tracking.TRACKING || rec.timestampNs - statNs <= STAT_NS) return
        statNs = rec.timestampNs
        logStats(frame, camera, rec, g, viewportWidth, viewportHeight)
    }

    /** The read batches waiting, in order (iOS place() per decode) */
    private fun drain(
        session: Session,
        frame: Frame,
        rec: PoseRecord,
        reads: ConcurrentLinkedQueue<ArEvent.Reads>,
        g: StreamGeometry,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        val ts = rec.timestampNs
        var later: ArrayList<ArEvent.Reads>? = null
        while (true) {
            val batch = reads.poll() ?: break
            if (!motion.fresh(batch.timestampNs, ts)) {
                unused(batch, Unused.STALE, rec)
                continue
            }
            var at = records.size - 1
            while (at >= 0 && records[at].timestampNs != batch.timestampNs) at--
            if (at < 0) {
                if (batch.timestampNs > ts) {
                    (later ?: ArrayList<ArEvent.Reads>().also { later = it }) += batch // its frame is not drawn yet
                } else {
                    unused(batch, null, rec) // this thread never had its frame
                }
                continue
            }
            val capture = records[at]
            if (capture.frameTracking != Tracking.TRACKING) { // iOS: the detection frame's tracking must be normal
                unused(batch, Unused.CAPTURE_NOT_TRACKING, rec)
                continue
            }
            ages.add((SystemClock.elapsedRealtimeNanos() - batch.timestampNs) / 1e6) // M5: read age when used, by the camera's clock
            work.batches++
            val sightings = sightings(frame, rec, batch.reads, capture, g, viewportWidth, viewportHeight)
            batchSession = session
            batchNowNs = ts
            batchCapture = capture
            val p0 = Debug.threadCpuTimeNanos()
            val placed = book.place(sightings, batch.timestampNs, ts, motion.mayCreate(batch.timestampNs), trackedBoxes(batch.tracked), createPin)
            work.placeNs += Debug.threadCpuTimeNanos() - p0
            batchSession = null
            batchCapture = null
            if (placed.claims.isNotEmpty()) { // before the merges: a claimed pin may be merged away in the same batch
                val omega = records.getOrNull(at - 1)?.let { omegaDps(it, capture) } ?: Double.NaN
                for (i in placed.claims.indices) claimed(placed.claims[i], capture, omega, ts)
            }
            if (placed.claims.size < sightings.size) boost.unclaimed(batch.timestampNs) // P2c: a read with no pin
            voids += placed.voided.size
            work.voids += placed.voided.size
            for (i in placed.voided.indices) {
                val c = placed.voided[i]
                trace.diag { pinVoidLine(ts, capture.timestampNs, c.pin, c.sighting) }
            }
            for (i in placed.reinits.indices) {
                val pin = placed.reinits[i]
                reinits++
                work.reinits++
                trace.diag { pinMovedLine(ts, pin, "reinit", pin.position) }
                Log.i(TAG, "pin ${pin.id} re-initialised on its last $PIN_REINIT_BAD reads (rule 4) ${pin.code}")
            }
            for (i in placed.removed.indices) removed(placed.removed[i], "merged with its sibling", "merge", ts)
            for (i in placed.retired.indices) removed(placed.retired[i], "retired, its code read in view but not by it", "retire", ts, batch.timestampNs)
        }
        later?.let { reads.addAll(it) }
    }

    /**
     * A batch the pins do not use ([why]): its listed reads off the border are still measured against the pins as
     * drawn on their frame, where a hidden pin is a miss (plan §5 M4), and traced. [why] null, or a frame no longer
     * kept: there is nothing to measure them against, and they are counted as unmeasured.
     */
    private fun unused(batch: ArEvent.Reads, why: Unused?, now: PoseRecord) {
        work.dropped++
        listedOf(batch.reads)
        if (listedReads.isEmpty()) return
        metrics.batch(listedCodes)
        val capture = if (why == null) null else recordAt(batch.timestampNs)
        // Measured against the pins only where its frame was drawn here ([nearestDrawnPx]'s null)
        val measured = capture != null && drawn.has(capture.timestampNs)
        for (i in listedReads.indices) {
            val r = listedReads[i]
            if (r.touchesBorder) continue
            val code = listedCodes[i]
            val near = if (measured) nearestDrawnPx(code, r, capture!!) else Double.NaN
            if (why == null || !measured) {
                metrics.unmeasured()
                unmeasuredReads++
            } else {
                metrics.unused(near, why)
                unusedReads[why.ordinal]++
            }
            val outcome = if (why == null || !measured) "unmeasured" else why.trace
            trace.diag {
                hitLine(r, code, now.timestampNs, now.timestampNs - batch.timestampNs, outcome, null, null, null, null, null, emptyList(), near)
            }
        }
    }

    /** The engine's boxes undecoded in the batch's image ([trackedOf]) of listed codes or of none yet, for rule 7 */
    private fun trackedBoxes(tracked: List<Read>): List<TrackedBox> {
        if (tracked.isEmpty()) return emptyList()
        val out = ArrayList<TrackedBox>(tracked.size)
        for (i in tracked.indices) {
            val t = tracked[i]
            if (t.text.isEmpty()) {
                out += trackedBoxOf(t, null)
                continue
            }
            val code = readKeys.key(t)
            if (code in keys) out += trackedBoxOf(t, code)
        }
        return out
    }

    /** [reads]' listed ones into [listedReads], their keys into [listedCodes], each key made once */
    private fun listedOf(reads: List<Read>) {
        listedReads.clear()
        listedCodes.clear()
        for (i in reads.indices) {
            val r = reads[i]
            val code = readKeys.key(r)
            if (code !in keys) continue
            listedReads += r
            listedCodes += code
        }
    }

    /** M1 and M2 for one claim: its pin as drawn on the read's frame [capture] against the read's centre */
    private fun claimed(c: Claim, capture: PoseRecord, omegaDps: Double, nowNs: Long) {
        val s = c.sighting
        val p = drawnPoint
        val wasDrawn = drawn.pointInto(capture.timestampNs, c.pin.id, p)
        var err = Double.NaN
        var depth = Double.NaN
        if (wasDrawn) {
            val toCamera = inverses[capture.camera]
            err = registrationPx(p[0], p[1], p[2], s.centreU, s.centreV, toCamera, capture.intrinsics)
            depth = depthM(p[0], p[1], p[2], toCamera)
        }
        metrics.claimed(c.pin.id, s.code, capture.timestampNs, capture.camera.t, err, depth, omegaDps, rowBand(s.centreV, s.intrinsics.height))
        trace.diag { pinClaimLine(nowNs, capture.timestampNs, c.pin, s, if (wasDrawn) Vec3(p[0], p[1], p[2]) else null, metrics.lastClaim()) }
    }

    /**
     * GL thread, every drawn frame (the frame at [timestampNs], [camera] its camera): each pin whose anchor tracks, at
     * that anchor's pose on this frame applied to the pin's point (A(n)·local), projected with this frame's camera: a
     * dot in its colour inside a white ring, or a ring in its colour alone while its depth is unsure ([drawnAsRing]);
     * where it was drawn is kept for M1, and which pins were on screen for M2. The list is reused by the next call: the
     * caller draws it at once.
     */
    fun marks(camera: Camera, timestampNs: Long, view: CountView, viewportWidth: Int, viewportHeight: Int): List<ScreenMarker> {
        val rules = this.rules
        val refine = this.refine
        // The anchors' poses [refreshPositions] took, while they are this frame's (it ran for this frame just before)
        val fresh = posesFresh
        posesFresh = false
        drawn.begin(timestampNs)
        val out = marked
        out.clear()
        if (book.pins.isEmpty() || camera.trackingState != TrackingState.TRACKING) return out
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)
        val items = view.items
        if (items !== countedOf) { // every view brings its own list: the keys are made again only when what is counted changed
            val was = countedOf
            if (was == null || !sameCounted(was, items)) counted = countedKeys(items)
            countedOf = items
        }
        val pins = book.pins
        for (i in pins.indices) {
            val pin = pins[i]
            val colour = pinColour(pin.code, keys, counted) ?: continue
            val a = anchors[pin.id] ?: continue
            val known = if (fresh) frameAnchorIndex(a) else -1
            if ((if (known >= 0) frameStates[known] else a.trackingState) != TrackingState.TRACKING) continue
            local[0] = pin.est.x.toFloat()
            local[1] = pin.est.y.toFloat()
            local[2] = pin.est.z.toFloat()
            (if (known >= 0) framePoses[known]!! else a.pose).transformPoint(local, 0, point, 0)
            drawn.add(pin.id, point[0].toDouble(), point[1].toDouble(), point[2].toDouble())
            if (!toView(point[0], point[1], point[2], viewportWidth, viewportHeight)) continue
            val x = viewXY[0]
            val y = viewXY[1]
            if (x < -0.05f * viewportWidth || x > 1.05f * viewportWidth || y < -0.05f * viewportHeight || y > 1.05f * viewportHeight) continue
            metrics.inView(pin.id, timestampNs)
            val c = if (colour == PinColour.COUNTED) GREEN else GREY
            out += if (drawnAsRing(rules, refine, pin)) ScreenMarker(x, y, 0f, null, dp(RING_DP), c) else ScreenMarker(x, y, dp(DOT_DP), c, dp(RING_DP), WHITE)
        }
        return out
    }

    /** Whether [a] and [b] list the same codes, each counted ([ItemCount.countLow] above 0) in both or in neither: the same [countedKeys] */
    private fun sameCounted(a: List<ItemCount>, b: List<ItemCount>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i].code != b[i].code || (a[i].countLow > 0) != (b[i].countLow > 0)) return false
        return true
    }

    /** Where [a]'s pose and state of this frame are kept, -1 when they are not (made after [refreshPositions]) */
    private fun frameAnchorIndex(a: Anchor): Int {
        for (i in frameAnchors.indices) if (frameAnchors[i] === a) return i
        return -1
    }

    /** A world point in view pixels through [viewMatrix] and [projMatrix], into [viewXY]; false behind the camera */
    private fun toView(x: Float, y: Float, z: Float, viewportWidth: Int, viewportHeight: Int): Boolean {
        world[0] = x
        world[1] = y
        world[2] = z
        world[3] = 1f
        Matrix.multiplyMV(eye, 0, viewMatrix, 0, world, 0)
        Matrix.multiplyMV(clip, 0, projMatrix, 0, eye, 0)
        val w = clip[3]
        if (w <= 0f) return false
        viewXY[0] = (clip[0] / w + 1f) / 2f * viewportWidth
        viewXY[1] = (1f - clip[1] / w) / 2f * viewportHeight
        return true
    }

    private fun keysOf(items: Set<String>) {
        if (items === listed) return
        listed = items
        keys = listedKeys(items)
    }

    /**
     * The batch's listed reads that land in the view, each with its point and its box in its own stream image and the
     * 40 dp match floor in that image's pixels. The point: under [PinRules.IOS] real geometry within [PIN_MAX_HIT_M] of
     * the capture camera (iOS place()'s per-detection rules, [bestHit]); under [PinRules.ANDROID] [pickHit]'s. Each
     * listed read is also measured against its nearest same-code pin as drawn on its frame, counted by why it was turned
     * away (and the hits [pickHit] skipped, by why), and traced with every hit's verdict.
     */
    private fun sightings(
        frame: Frame,
        now: PoseRecord,
        reads: List<Read>,
        capture: PoseRecord,
        g: StreamGeometry,
        viewportWidth: Int,
        viewportHeight: Int,
    ): List<Sighting> {
        // A cut symbol's corners, and so its centre, are guessed (iOS skips it too); unlisted codes get no pin
        listedOf(reads)
        metrics.batch(listedCodes) // a code read twice in one image, cut or not, is repeated
        usableReads.clear()
        usableCodes.clear()
        for (i in listedReads.indices) {
            if (listedReads[i].touchesBorder) continue
            usableReads += listedReads[i]
            usableCodes += listedCodes[i]
        }
        cut += listedReads.size - usableReads.size
        if (usableReads.isEmpty()) return emptyList()
        val n = usableReads.size
        work.reads += n
        // The read centres, then two points 100 stream pixels apart for the view's scale
        val image = floats(imageBySize, n * 2 + 4)
        for (i in 0 until n) {
            val r = usableReads[i]
            image[2 * i] = g.cpuU(r.centreU / g.streamWidth).toFloat()
            image[2 * i + 1] = g.cpuV(r.centreV / g.streamHeight).toFloat()
        }
        image[2 * n] = g.cpuU(0.5).toFloat()
        image[2 * n + 1] = g.cpuV(0.5).toFloat()
        image[2 * n + 2] = g.cpuU(0.5 + 100.0 / g.streamWidth).toFloat()
        image[2 * n + 3] = g.cpuV(0.5).toFloat()
        val onView = floats(viewBySize, image.size)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, image, Coordinates2d.VIEW, onView)
        val viewPerStream = hypot(onView[2 * n + 2] - onView[2 * n], onView[2 * n + 3] - onView[2 * n + 1]) / 100.0
        val floorPx = if (viewPerStream > 0) PIN_MATCH_FLOOR_DP * density / viewPerStream else 0.0
        val out = ArrayList<Sighting>(n)
        val toCamera = inverses[capture.camera]
        // Measured against the pins only where its frame was drawn here ([nearestDrawnPx]'s null)
        val measured = drawn.has(capture.timestampNs)
        for (i in 0 until n) {
            val r = usableReads[i]
            val code = usableCodes[i]
            val near = if (measured) nearestDrawnPx(code, r, capture) else Double.NaN
            if (!measured) metrics.unmeasured() else metrics.nearest(near)
            val width = readKeys.nominalWidth(r.symbology)
            val vx = onView[2 * i]
            val vy = onView[2 * i + 1]
            val ray = centreRay(r, capture) // world ray of the capture-time camera
            var outcome = "ok"
            clearHits() // the hits are this read's only
            var judged: List<RayHit>? = null
            var hit = -1
            var at: Vec3? = null
            var conf: Int? = null
            var check: HitCheck? = null
            val known = if (frameRules == PinRules.ANDROID && refine) verifiedRange(code, r, capture, toCamera, ray) else Double.NaN
            if (vx < 0 || vy < 0 || vx > viewportWidth || vy > viewportHeight) {
                outcome = "offView"
                offView++
            } else if (known.isFinite()) {
                hitSources[HitSource.PIN.ordinal]++
                hitKinds.merge("Pin", 1, Int::plus)
                outcome = HitSource.PIN.trace
                val p = ray.at(known)
                at = p
                out += sightingOf(r, capture, p, Quat.IDENTITY, ray, floorPx, source = HitSource.PIN, code = code, nominalM = width ?: 0.0)
            } else if (frameRules == PinRules.ANDROID) {
                hitTest(frame, ray)
                for (k in hitResults.indices) rayHits += rayHit(k, now, g)
                val all = rayHits
                judged = all
                val pick = pickHit(all, r, capture, ray, hitRejects, toCamera, width)
                hit = if (pick != null && pick.index >= 0 && pick.index < hitResults.size) pick.index else -1
                hitKinds.merge(if (hit >= 0) simpleName(hitTrackables[hit]) else if (pick != null) "NominalWidth" else "NONE", 1, Int::plus)
                if (pick == null) {
                    if (!anyHit(all, SEEDS)) {
                        noHit++
                    } else if (!anyHit(all, KEPT_BEFORE)) { // only depth points below the confidence gate
                        lowConfidence++
                    } else { // the nearest geometry kept lies beyond 3 m
                        farHits++
                    }
                    // Phase 3: a birth needs no hit (§3.4 rule 5); the trace's hits say why there was none
                    val guess = defaultPick(r, capture)
                    hitSources[guess.source.ordinal]++
                    outcome = guess.source.trace
                    at = guess.point
                    out += sightingOf(r, capture, guess.point, Quat.IDENTITY, ray, floorPx, source = guess.source, code = code, nominalM = width ?: 0.0)
                } else {
                    hitSources[pick.source.ordinal]++
                    outcome = pick.source.trace
                    at = pick.point
                    conf = all.getOrNull(pick.index)?.confidence
                    val c = checkHit(pick.point, r, capture, toCamera)
                    check = c
                    if (c.errorPx.isFinite()) errors.add(c.errorPx)
                    val rotation = if (hit >= 0) poseOf(hit).rotation() else Quat.IDENTITY
                    out += sightingOf(r, capture, pick.point, rotation, ray, floorPx, pick.onPlane, pick.source, code, width ?: 0.0)
                }
            } else {
                hitTest(frame, ray)
                val best = bestHit()
                hit = best
                if (best < 0) {
                    outcome = "noHit"
                    noHit++
                } else {
                    val h = poseOf(best)
                    val p = Vec3(h.tx().toDouble(), h.ty().toDouble(), h.tz().toDouble())
                    at = p
                    val depthPoint = hitTrackables[best] is DepthPoint
                    val far = norm(p.x - ray.origin.x, p.y - ray.origin.y, p.z - ray.origin.z) > PIN_MAX_HIT_M
                    if (depthPoint && !far) conf = depth.at(p, now, g)
                    if (far) {
                        outcome = "far"
                        farHits++
                    } else if (depthPoint && conf != null && !depthConfident(conf)) {
                        // A depth point's distance came from depth-from-motion: kept only where its depth is confident
                        // (no confidence known: no gate, as before Phase 2)
                        outcome = "lowConfidence"
                        lowConfidence++
                    } else {
                        val c = checkHit(p, r, capture, toCamera)
                        check = c
                        if (c.errorPx.isFinite()) errors.add(c.errorPx)
                        out += sightingOf(r, capture, p, h.rotation(), ray, floorPx, hitTrackables[best] is Plane, code = code, nominalM = width ?: 0.0)
                    }
                }
            }
            trace.diag {
                // Each traced hit's verdict under Phase 2's rules, whichever rules placed the read (S4: a plane behind a
                // nearer valid hit)
                val traced = min(hitResults.size, TRACED_HITS)
                val j = judged ?: List(traced) { rayHit(it, now, g) }
                val verdicts = List(traced) { hitReject(j[it], r, capture, ray)?.trace ?: "ok" }
                hitLine(
                    r, code, now.timestampNs, now.timestampNs - capture.timestampNs, outcome, if (hit >= 0) simpleName(hitTrackables[hit]) else null,
                    at?.let { (it - ray.origin).norm() }, check?.depthM ?: at?.let { depthM(it, capture.camera) }, check?.errorPx, conf,
                    List(traced) { k -> seen(k, ray, verdicts[k]) }, near,
                )
            }
        }
        clearHits() // ARCore's hit results and trackables go with the batch, as the per-read list did, not at the next read
        return out
    }

    /**
     * [HitSource.PIN]: the distance along [ray] of a verified pin of [code] that lies, where it stood at the capture,
     * in [r]'s quad (its corners' box, not inflated); NaN when none does, and the read gets its hit test
     */
    private fun verifiedRange(code: String, r: Read, capture: PoseRecord, toCamera: Pose, ray: Ray): Double {
        val c = r.corners
        val minU = minOf(c[0], c[2], c[4], c[6])
        val maxU = maxOf(c[0], c[2], c[4], c[6])
        val minV = minOf(c[1], c[3], c[5], c[7])
        val maxV = maxOf(c[1], c[3], c[5], c[7])
        val pins = book.pins
        for (i in pins.indices) {
            val pin = pins[i]
            if (pin.code != code || !pin.verified) continue
            val p = pin.positionAt(capture.timestampNs)
            val inQuad = applied(toCamera, p.x, p.y, p.z) { x, y, z ->
                projected(capture.intrinsics, x, y, z, { false }) { u, v -> u in minU..maxU && v in minV..maxV }
            }
            if (!inQuad) continue
            val o = ray.origin
            val d = ray.dir
            return maxOf((p.x - o.x) * d.x + (p.y - o.y) * d.y + (p.z - o.z) * d.z, PIN_MIN_RANGE_M)
        }
        return Double.NaN
    }

    /** One of [cache]'s arrays of [size] floats (kept per size: ARCore maps a whole array, so it must be exact) */
    private fun floats(cache: Array<FloatArray?>, size: Int): FloatArray {
        val i = size / 2
        if (i >= cache.size) return FloatArray(size)
        return cache[i] ?: FloatArray(size).also { cache[i] = it }
    }

    /**
     * The read's centre against the nearest pin of [code] as drawn on its frame [capture], stream pixels; +∞ when none
     * was (hidden: the camera or the anchors did not track). Only for a frame drawn here ([DrawnPins.has]).
     */
    private fun nearestDrawnPx(code: String, r: Read, capture: PoseRecord): Double {
        val toCamera = inverses[capture.camera]
        var best = Double.POSITIVE_INFINITY
        val pins = book.pins
        for (i in pins.indices) {
            val pin = pins[i]
            if (pin.code != code) continue
            if (!drawn.pointInto(capture.timestampNs, pin.id, drawnPoint)) continue
            best = minOf(best, registrationPx(drawnPoint[0], drawnPoint[1], drawnPoint[2], r.centreU, r.centreV, toCamera, capture.intrinsics))
        }
        return best
    }

    /** A hit for the trace: its kind, its distance along [ray], for a plane whether it lies in its polygon, and [verdict] */
    private fun seen(k: Int, ray: Ray, verdict: String): HitSeen {
        val p = poseOf(k)
        val d = Vec3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble()) - ray.origin
        val t = hitTrackables[k]
        return HitSeen(simpleName(t), d.norm(), (t as? Plane)?.isPoseInPolygon(p), verdict)
    }

    /**
     * The [k]th hit as [pickHit] judges it: its kind and world point, for a plane its normal (the hit pose's +Y, as
     * ARCore's hello_ar tests the camera's side) and polygon, for a depth point its confidence on the frame [now]
     */
    private fun rayHit(k: Int, now: PoseRecord, g: StreamGeometry): RayHit {
        val p = poseOf(k)
        val at = Vec3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble())
        return when (val t = hitTrackables[k]) {
            is Plane -> {
                p.getTransformedAxis(1, 1f, yAxis, 0) // ArPose.yAxis, into an array kept
                RayHit(HitKind.PLANE, at, Vec3(yAxis[0].toDouble(), yAxis[1].toDouble(), yAxis[2].toDouble()), t.isPoseInPolygon(p))
            }
            is Point -> RayHit(HitKind.POINT, at)
            is DepthPoint -> RayHit(HitKind.DEPTH_POINT, at, confidence = depth.at(at, now, g))
            else -> RayHit(HitKind.OTHER, at)
        }
    }

    /** ARCore's tracked hits on [ray] in this frame, nearest first, into [hitResults] with their trackables */
    private fun hitTest(frame: Frame, ray: Ray) {
        rayOrigin[0] = ray.origin.x.toFloat()
        rayOrigin[1] = ray.origin.y.toFloat()
        rayOrigin[2] = ray.origin.z.toFloat()
        rayDir[0] = ray.dir.x.toFloat()
        rayDir[1] = ray.dir.y.toFloat()
        rayDir[2] = ray.dir.z.toFloat()
        val t0 = Debug.threadCpuTimeNanos()
        val all = frame.hitTest(rayOrigin, 0, rayDir, 0)
        for (i in all.indices) {
            val h = all[i]
            val t = h.trackable // one wrapper per hit: each is a JNI acquire and a finalizable object
            if (t.trackingState != TrackingState.TRACKING) continue
            hitResults += h
            hitTrackables += t
            hitPoses += null
        }
        work.hitTestNs += Debug.threadCpuTimeNanos() - t0
        work.hitTests++
        work.hits += hitResults.size
    }

    private fun clearHits() {
        hitResults.clear()
        hitTrackables.clear()
        hitPoses.clear()
        rayHits.clear()
    }

    /** The [k]th hit's pose, taken from ARCore once */
    private fun poseOf(k: Int): ArPose = hitPoses[k] ?: hitResults[k].hitPose.also { hitPoses[k] = it }

    private fun simpleName(t: Trackable): String = simpleNames.getOrPut(t.javaClass) { t.javaClass.simpleName }

    /** Whether any of [hits] is [SEEDS] or [KEPT_BEFORE] */
    private fun anyHit(hits: List<RayHit>, which: Int): Boolean {
        for (i in hits.indices) if (if (which == SEEDS) hits[i].seeds else hits[i].keptBefore) return true
        return false
    }

    /**
     * [PinRules.IOS]: real geometry only (iOS raycast): of the tracked hits, a plane hit inside its polygon first;
     * else the nearest feature point (or depth point, ARCore's other measured geometry). Its index, -1 for none.
     */
    private fun bestHit(): Int {
        var hit = -1
        for (i in hitTrackables.indices) {
            val t = hitTrackables[i]
            if (t is Plane && t.isPoseInPolygon(poseOf(i))) {
                hit = i
                break
            }
        }
        if (hit < 0) {
            for (i in hitTrackables.indices) {
                val t = hitTrackables[i]
                if (t is Point || t is DepthPoint) {
                    hit = i
                    break
                }
            }
        }
        hitKinds.merge(if (hit >= 0) simpleName(hitTrackables[hit]) else "NONE", 1, Int::plus)
        return hit
    }

    /**
     * Every [STAT_NS]: the hit kinds (old HitDiag), the hits' reprojection errors into their reads' frames and what the
     * gates rejected, the listed reads the pins did not use, births, merges, retirements and voided claims, how old the
     * reads were when used, and the ray
     * check: points of the stream image sent 1 m along the model's ray (this frame's pose and stream intrinsics, as the
     * reads' rays are made) and projected back with ARCore's own view and projection, against ARCore's own
     * image-to-view mapping of the same points. A model that is right gives a pixel or two. Then M1 and M2 so far.
     */
    private fun logStats(frame: Frame, camera: Camera, rec: PoseRecord, g: StreamGeometry, viewportWidth: Int, viewportHeight: Int) {
        val reproj = if (errors.size == 0) {
            "none"
        } else {
            val n = errors.size
            val sorted = errors.sorted() // as a sorted copy of the list, in place
            lines.format("median %.1f max %.1f px (%d)", sorted[n / 2], sorted[n - 1], n)
        }
        val age = if (ages.size == 0) "none" else ages.quantiles(0.5, 0.9).let { lines.format("p50 %.0f p90 %.0f ms", it[0], it[1]) }
        Log.i(
            TAG,
            "hits=$hitKinds ($frameRules: ${hitSourceText()}; hits skipped ${hitRejectText()}) " +
                "reprojection $reproj, rejected $farHits beyond ${PIN_MAX_HIT_M} m, $lowConfidence depth points below confidence $MIN_DEPTH_CONFIDENCE, " +
                "$offView off the view, $noHit with no hit, $cut cut by the border; not used ${unusedText()}, $unmeasuredReads unmeasured; " +
                "births $births merges $merges retirements $retirements voided $voids re-inits $reinits re-anchored $reanchors; " +
                "read age when used $age; ${rayCheck(frame, camera, rec, g, viewportWidth, viewportHeight)}; candidates=${book.candidateCount} " +
                "live=${book.pins.size} verified=${book.pins.count { it.verified }} warm=${motion.mapReady}; ${boostText()}",
        )
        metrics.summary()?.let { Log.i(TAG, it) }
        hitKinds.clear()
        errors.clear()
        farHits = 0
        lowConfidence = 0
        hitRejects.fill(0)
        hitSources.fill(0)
        offView = 0
        noHit = 0
        cut = 0
        births = 0
        merges = 0
        retirements = 0
        voids = 0
        reinits = 0
        reanchors = 0
        boost.resetCounts()
        unusedReads.fill(0)
        unmeasuredReads = 0
        ages.clear()
    }

    private fun unusedText() = Unused.entries.joinToString(", ") { "${unusedReads[it.ordinal]} ${it.label}" }

    /** M6: the read-rate boost's duty over the tracked frames since the last line, and why */
    private fun boostText(): String {
        val n = boost.frames
        if (!boosting()) return "read boost off"
        fun pct(k: Int) = if (n == 0) "n/a" else lines.format("%.0f%%", 100.0 * k / n)
        return "read boost on ${pct(boost.forPins)} of $n frames for unverified pins, ${pct(boost.forReads)} for unclaimed reads"
    }

    private fun hitSourceText() =
        "${hitSources[HitSource.HIT.ordinal]} valid, ${hitSources[HitSource.WIDTH.ordinal]} at the nominal width, " +
            "${hitSources[HitSource.NEAREST.ordinal]} nearest with none valid, ${hitSources[HitSource.DEFAULT.ordinal]} at the default depth, " +
            "${hitSources[HitSource.PIN.ordinal]} at a verified pin's depth with no hit test"

    private fun hitRejectText() = HitReject.entries.joinToString(", ") { "${hitRejects[it.ordinal]} ${it.label}" }

    private fun rayCheck(frame: Frame, camera: Camera, rec: PoseRecord, g: StreamGeometry, viewportWidth: Int, viewportHeight: Int): String {
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100f)
        val k = rec.intrinsics
        val image = checkImage
        for (i in CHECK_POINTS.indices step 2) {
            image[i] = g.cpuU(CHECK_POINTS[i]).toFloat()
            image[i + 1] = g.cpuV(CHECK_POINTS[i + 1]).toFloat()
        }
        val arcore = checkView
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, image, Coordinates2d.VIEW, arcore)
        // View pixels per stream pixel, from the centre and the first corner point as ARCore maps them
        val streamPx = hypot((CHECK_POINTS[2] - CHECK_POINTS[0]) * k.width, (CHECK_POINTS[3] - CHECK_POINTS[1]) * k.height)
        val viewPerStream = hypot(arcore[2] - arcore[0], arcore[3] - arcore[1]) / streamPx
        val errs = checkErrs
        var n = 0
        for (i in CHECK_POINTS.indices step 2) {
            val at = rec.camera.apply(k.rayInCamera(CHECK_POINTS[i] * k.width, CHECK_POINTS[i + 1] * k.height))
            if (!toView(at.x.toFloat(), at.y.toFloat(), at.z.toFloat(), viewportWidth, viewportHeight)) continue
            errs[n++] = hypot(viewXY[0] - arcore[i], viewXY[1] - arcore[i + 1]) / viewPerStream * 3840.0 / k.width
        }
        if (n == 0 || !viewPerStream.isFinite() || viewPerStream <= 0) return "rayCheck n/a"
        Arrays.sort(errs, 0, n) // the order List<Double>.sort() gives
        return lines.format("rayCheck model vs ARCore median %.1f max %.1f px (4K)", errs[n / 2], errs[n - 1])
    }

    /**
     * Old markerPosition: a tracking anchor's pose on this frame is kept with its pin ([Pin.anchored], rule 9), and how
     * far it moved since the last frame it tracked goes to [breaks]. Rule 8: under [PinRules.ANDROID] with [refine] a pin
     * whose point lies over [PIN_REANCHOR_M] from its anchor, and under any rules one whose anchor STOPPED, gets a new world
     * anchor at its point, turned as the old one was last tracked, and its rays move into it ([Pin.reanchor]).
     */
    private fun refreshPositions(session: Session, timestampNs: Long) {
        frameAnchors.clear()
        framePoses.clear()
        frameStates.clear()
        val pins = book.pins
        for (i in pins.indices) {
            val pin = pins[i]
            val a = anchors[pin.id] ?: continue
            val state = a.trackingState
            frameAnchors += a // kept for [marks] on this frame
            frameStates += state
            when (state) {
                TrackingState.TRACKING -> {
                    val p = a.pose
                    framePoses += p
                    val step = pin.anchored(
                        timestampNs, p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble(),
                        p.qx().toDouble(), p.qy().toDouble(), p.qz().toDouble(), p.qw().toDouble(),
                    )
                    breaks.anchorMoved(timestampNs, step)
                    if (frameRules == PinRules.ANDROID && frameRefine && pin.localNorm > PIN_REANCHOR_M) reanchor(session, pin, a, timestampNs, pin.localNorm * 100)
                }
                TrackingState.STOPPED -> {
                    framePoses += null
                    reanchor(session, pin, a, timestampNs, Double.NaN)
                }
                else -> framePoses += null
            }
        }
        posesFresh = true
    }

    /**
     * Rule 8 on the frame at [nowNs]: [pin] gets a new world anchor at its point, turned as [old] was last tracked;
     * [old] goes. For the log: its point strayed [strayCm] from it, NaN when its anchor stopped (made into words only
     * once the new anchor is made, as a failed one is tried again on every frame).
     */
    private fun reanchor(session: Session, pin: Pin, old: Anchor, nowNs: Long, strayCm: Double) {
        val to = Pose(pin.position, pin.anchors.pose(pin.anchors.newest).q)
        val c0 = Debug.threadCpuTimeNanos()
        val made = runCatching { session.createAnchor(to.toArPose()) }.getOrNull()
        work.anchorCall(Debug.threadCpuTimeNanos() - c0)
        if (made == null) return // NotTrackingException: retry next frame
        val d0 = Debug.threadCpuTimeNanos()
        old.detach()
        work.anchorCall(Debug.threadCpuTimeNanos() - d0)
        anchors[pin.id] = made
        pin.reanchor(to)
        reanchors++
        work.reanchors++
        trace.diag { pinMovedLine(nowNs, pin, "reanchor", to.t) }
        val why = if (strayCm.isNaN()) "its anchor stopped" else lines.format("its point strayed %.0f cm", strayCm)
        Log.i(TAG, "pin ${pin.id} re-anchored ($why), live=${book.pins.size}")
    }

    /**
     * A confirmed candidate's own world anchor (iOS create), on the frame [nowNs] from the batch of [capture]; false
     * when ARCore makes none, and the pin is not born.
     */
    private fun create(session: Session, pin: Pin, nowNs: Long, capture: PoseRecord): Boolean {
        val c0 = Debug.threadCpuTimeNanos()
        val made = runCatching { session.createAnchor(pin.pose.toArPose()) }
        work.anchorCall(Debug.threadCpuTimeNanos() - c0)
        val a = made.onFailure { Log.w(TAG, "pin ${pin.id} anchor not created", it) }.getOrNull() ?: return false
        anchors[pin.id] = a
        val t = pin.pose.t
        births++
        work.births++
        metrics.born(pin.id, capture.timestampNs, capture.camera.t)
        trace.diag { pinBirthLine(nowNs, capture.timestampNs, pin, t, capture.camera.t) }
        val on = if (pin.bornOnPlane) " on a plane" else if (pin.bornGuessed) " at the default depth" else ""
        val prior = if (pin.est.started) lines.format(", its point %.3f,%.3f,%.3f (prior ${pin.est.priorSource})", pin.position.x, pin.position.y, pin.position.z) else ""
        // The code is not part of the pattern: a listed Code 128 / Code 39 text may hold a '%'
        Log.i(TAG, "pin ${pin.id} created ${pin.code} at " + lines.format("%.3f,%.3f,%.3f", t.x, t.y, t.z) + "$on$prior, live=${book.pins.size + 1}")
        return true
    }

    /** [pin] went ([event] "merge" or "retire", [why] for the log; a retirement's batch captured at [captureNs]): its anchor is detached */
    private fun removed(pin: Pin, why: String, event: String, nowNs: Long, captureNs: Long = -1L) {
        val d0 = Debug.threadCpuTimeNanos()
        anchors.remove(pin.id)?.detach()
        work.anchorCall(Debug.threadCpuTimeNanos() - d0)
        work.removals++
        if (event == "retire") retirements++ else merges++
        metrics.removed(pin.id)
        trace.diag { pinGoneLine(nowNs, pin, event, pin.position, captureNs) }
        Log.i(TAG, "pin ${pin.id} removed ($why) ${pin.code}, live=${book.pins.size}")
    }

    private fun dp(v: Float) = v * density

    private fun ArPose.rotation() = Quat(qx().toDouble(), qy().toDouble(), qz().toDouble(), qw().toDouble())

    /**
     * The frame's raw depth confidence image, acquired on the first lookup of a frame ([begin]) and closed with
     * [close]; none (depth off, not yet available) means no gate. Its size, strides and buffer are taken once, at the
     * acquire; each lookup is then the same pixel of the same image, without a Pair, a plane or a buffer per hit.
     */
    private inner class DepthConfidence {
        private var frame: Frame? = null
        private var image: Image? = null
        private var tried = false
        private var width = 0
        private var height = 0
        private var rowStride = 0
        private var pixelStride = 0
        private var buffer: ByteBuffer? = null
        private val point = FloatArray(2)
        private val tex = FloatArray(2)

        /** A new frame: its image is acquired on its first lookup */
        fun begin(frame: Frame) {
            this.frame = frame
            tried = false
        }

        /** The confidence (0-255) where [hit] lies in this frame ([now], whose intrinsics are the stream's of [g]); null when unknown */
        fun at(hit: Vec3, now: PoseRecord, g: StreamGeometry): Int? {
            val t0 = Debug.threadCpuTimeNanos()
            val c = lookup(hit, now, g)
            work.depthNs += Debug.threadCpuTimeNanos() - t0
            return c
        }

        private fun lookup(hit: Vec3, now: PoseRecord, g: StreamGeometry): Int? {
            if (image() == null) return null
            val k = now.intrinsics
            // imageNormalized, then depthPixel, as scalars; the frame camera's inverse made once a frame
            return applied(inverses[now.camera], hit.x, hit.y, hit.z) { cx, cy, cz ->
                projected(k, cx, cy, cz, { null }) { pu, pv ->
                    val u = pu / k.width
                    val v = pv / k.height
                    point[0] = g.cpuU(u).toFloat()
                    point[1] = g.cpuV(v).toFloat()
                    frame!!.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, point, Coordinates2d.TEXTURE_NORMALIZED, tex)
                    val texU = tex[0]
                    val texV = tex[1]
                    if (!(texU >= 0f && texU < 1f && texV >= 0f && texV < 1f)) {
                        null
                    } else {
                        val x = (texU * width).toInt().coerceIn(0, width - 1)
                        val y = (texV * height).toInt().coerceIn(0, height - 1)
                        CONFIDENCES[buffer!!.get(y * rowStride + x * pixelStride).toInt() and 0xFF]
                    }
                }
            }
        }

        private fun image(): Image? {
            if (!tried) {
                tried = true
                // NotYetAvailableException, or depth not enabled: no image, no gate
                image = runCatching { frame!!.acquireRawDepthConfidenceImage() }.getOrNull()?.also { img ->
                    width = img.width
                    height = img.height
                    val plane = img.planes[0]
                    rowStride = plane.rowStride
                    pixelStride = plane.pixelStride
                    buffer = plane.buffer
                }
            }
            return image
        }

        fun close() {
            image?.close()
            image = null
            buffer = null
            frame = null
        }
    }

    private companion object {
        const val TAG = "ArPins"
        const val RECORDS = 32
        const val STAT_NS = 3_000_000_000L

        /** Sizes of [imageBySize]'s arrays kept: up to 8 listed reads in one image */
        const val SIZES = 11

        // [anyHit]'s questions
        const val SEEDS = 0
        const val KEPT_BEFORE = 1

        /** Every confidence boxed once, so a lookup boxes none (Integer caches only up to 127) */
        val CONFIDENCES: Array<Int?> = Array(256) { it }

        /** Hits on a read's ray written to the trace, nearest first */
        const val TRACED_HITS = 6

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
