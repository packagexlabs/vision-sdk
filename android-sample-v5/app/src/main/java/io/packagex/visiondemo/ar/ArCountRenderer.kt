package io.packagex.visiondemo.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.SessionPausedException
import io.packagex.arcount.BreakReason
import io.packagex.arcount.CountView
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Read
import io.packagex.arcount.SectionState
import io.packagex.arcount.Tracking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The GL thread of an AR Count session (spec 5.8): `Session.update()` (BLOCKING, paced by the camera), then the
 * anchor ops of the mapper (create or let go of the section anchor), the frame's [PoseRecord] to the mapper, the
 * camera background, AR Item Count's pins ([ArPins], in place of the core's unit markers) and the outlines of the
 * unlisted codes read. No section bracket and no gap markers (spec 5.10). No counting here. Under
 * [OverlayRules.ANDROID] (drift plan Phase 1) each outline is carried from the frame it was read in to the frame drawn
 * ([chooseOutline], [transfer]), so it stays on its code while the phone moves; a world anchor of its own (the map
 * probe, [watchMap]) shows when ARCore corrects its map, even while no pin or section holds an anchor.
 *
 * Measured (drift plan Phase 0): the thread CPU of each new frame after `update()` and the pins' share of it, capture
 * to `update()`, the GCs, once a second whether ARCore's CPU image can be had, and the measuring's own cost after the
 * frame ([FrameCosts], a line every 3 s, with every frame since the start pooled and the window's slowest pin frame's
 * work beside it); and M3, each new unlisted read against the outline for its track on its own frame, drawn or not, by
 * the rules it was drawn under ([DrawnOutlines]). Each frame takes ARCore's camera once ([Frame.getCamera] makes a
 * finalizable wrapper per call) and hands it to every step.
 */
class ArCountRenderer(
    private val mapper: ArMapper,
    private val metas: CaptureMetaRing,
    private val density: Float,
    /** `update()` threw [FatalException]: the session is to be rebuilt (spec 6). Nothing is updated until it resumes. */
    private val onFatal: (FatalException) -> Unit,
    /** Every frame's [PoseRecord] goes here too, for the camera thread's blur pre-skip (spec 5.6) */
    private val poses: LatestPoses? = null,
) : GLSurfaceView.Renderer {
    /** Set once ARCore runs; null draws nothing. */
    @Volatile
    var session: Session? = null

    /** The app stream the reads come from, for the intrinsics and the image-to-view mapping */
    @Volatile
    var stream: AppStream = AppStream.UHD

    /** How the unlisted outlines are drawn (Settings › Advanced) */
    @Volatile
    var overlayRules: OverlayRules = OverlayRules.ANDROID

    /** Codes with no nominal width are carried at [FAR_SAFE_DEPTH_M] rather than rotation only (Settings › Advanced) */
    @Volatile
    var outlineFarSafe: Boolean = false

    @Volatile
    private var resumePending = false

    /** While the session records (Settings › "AR record session"): what reached the app since the last fresh frame, for its sample of the reads track */
    @Volatile
    internal var trackOut: TrackQueue? = null

    /**
     * A replay ([ArSessionController.replay]): the reads come from the recording's reads track, each sample on the frame it
     * was written on, as the engine posted them; [replay] gets the pins' summary once ARCore says the recording ended
     */
    @Volatile
    var replay: ((String) -> Unit)? = null
    private var replayEnded = false

    /** How many frames late the replay's reads track samples have come at the most, logged when it grows */
    private var replayLagFrames = 0

    private val background = BackgroundRenderer()
    private val marks = MarkerGlRenderer(density)
    private val outlines = OutlineGlRenderer()
    private val planes = PlaneGlRenderer()

    /** The reads' code keys, made once per text (GL thread: the pins' reads, the unlisted outlines' every frame) */
    private val readKeys = ReadKeys()

    /** AR Item Count's persistent markers, one per physical barcode (GL thread) */
    private val pins = ArPins(density, mapper, readKeys)

    /** The item list the unlisted outlines were last keyed against, and its keys ([listedKeys]) */
    private var listedItems: Set<String>? = null
    private var listedKeySet: Set<String> = emptySet()

    /** [drawnWhereRead]'s image and view points, one pair of arrays per read count (ARCore maps whole arrays) */
    private val flatImage = arrayOfNulls<FloatArray>(FLAT_SIZES)
    private val flatView = arrayOfNulls<FloatArray>(FLAT_SIZES)

    /** Which hit seeds a pin, which identity rules place and retire pins, and how an unverified pin is drawn (Settings › Advanced) */
    var pinRules: PinRules
        get() = pins.rules
        set(value) {
            pins.rules = value
        }

    /** Whether claims refine the pins under the Android pin rules (drift plan Phase 4, Settings › Advanced) */
    var pinRefine: Boolean
        get() = pins.refine
        set(value) {
            pins.refine = value
        }

    /** The read-rate boost under the Android pin rules, refining their pins (drift plan P2c, Settings › Advanced) */
    var readBoost: Boolean
        get() = pins.readBoost
        set(value) {
            pins.readBoost = value
        }

    /** P2c, read on the engine worker before every scan: the pins want every shown code read in every frame (refresh 0) */
    val pinsWantFullRate: Boolean get() = pins.wantFullRate

    /** Any thread: the surface gate held a listed read's birth within the last second ([ArPins.holdingBirths]) */
    val holdingBirths: Boolean get() = pins.holdingBirths

    // Phase 0's measurements (GL thread)
    private val costs = FrameCosts()
    private val outlinesDrawn = DrawnOutlines()
    private val outlineErrors = OutlineErrors()

    /** The newest unlisted read measured for M3 */
    private var outlinesMeasuredNs = Long.MIN_VALUE

    // The unlisted outlines of one frame (GL thread): the frame's view and projection, the quads drawn (view pixels,
    // grown as needed), a carried centre, and how many were carried, drawn flat, dropped or behind since the last 3 s line
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private var quads = FloatArray(8 * 8)
    private val centre = DoubleArray(2)
    private val outlineCounts = IntArray(4)

    /** The section anchor's step between the frames it tracked, for [ArPins.anchorMoved] */
    private val sectionStep = AnchorStep()

    /** The map probe ([watchMap]) and its step (GL thread only); the session's close lets it go */
    private var probe: Anchor? = null
    private val probeStep = AnchorStep()

    /** The section anchor while the mapper wants one (GL thread only) */
    private var anchor: Anchor? = null

    /** The anchor a Create replaced, held until the first record with the new anchor is built (GL thread only) */
    private var replaced: Anchor? = null
    private var geometry: StreamGeometry? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var viewportChanged = false
    private var textureSet = false
    private var lastTimestampNs = Long.MIN_VALUE
    /** ARCore's tracking on the last frame (GL thread writes; read for the tap-to-focus log) */
    @Volatile
    var lastTracking: TrackingState? = null
        private set
    private var lastState: SectionState? = null
    private val loggedBreaks = HashSet<Pair<Long, BreakReason>>()
    private var closedLogged = 0

    /** ARCore was resumed: the next frame tells the counter, with its timestamp (spec 5.1 start-up guard). */
    fun resumed() {
        resumePending = true
    }

    /** New Scan: the pins go on the next frame. */
    fun clearPins() = pins.clear()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        marks.createOnGlThread()
        outlines.createOnGlThread()
        planes.createOnGlThread()
        textureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        val ending = replay
        if (ending != null && !replayEnded && s.playbackStatus == PlaybackStatus.FINISHED) {
            replayEnded = true
            ending(pins.replaySummary())
            return
        }
        if (!textureSet) {
            s.setCameraTextureName(background.textureId)
            textureSet = true
        }
        if (viewportChanged) {
            s.setDisplayGeometry(Surface.ROTATION_0, viewportWidth, viewportHeight) // portrait-locked activity
            viewportChanged = false
        }
        val frame = try {
            s.update()
        } catch (e: CameraNotAvailableException) {
            return
        } catch (e: SessionPausedException) {
            return
        } catch (e: FatalException) {
            session = null // until the rebuilt session resumes (ArSessionController sets it again)
            onFatal(e)
            return
        }
        val ts = frame.androidCameraTimestamp
        val fresh = ts != 0L && ts != lastTimestampNs
        // M5: capture to update(), on the camera's clock (REALTIME, as the session's camera line says)
        val updateNs = SystemClock.elapsedRealtimeNanos() - ts
        val cpu0 = Debug.threadCpuTimeNanos()
        background.draw(frame)
        val camera = frame.camera // once a frame, for every step below
        planes.draw(s, camera) // under the outlines and the pins
        var pinNs = 0L
        var rec: PoseRecord? = null
        if (fresh) {
            lastTimestampNs = ts
            pins.work.clear()
            // The reads that reached the app since the last frame: into the recording, or out of it, before this frame's record
            trackOut?.drain(ts)?.let { recordReads(frame, it) }
            if (replay != null) replayReads(frame, ts)
            if (resumePending) {
                resumePending = false
                mapper.post(ArEvent.Resumed(ts))
            }
            applyAnchorOps(s)
            rec = record(camera, ts).also {
                poses?.add(it)
                mapper.post(ArEvent.Frame(it))
            }
            watchMap(s, rec)
            geometry?.let {
                val p0 = Debug.threadCpuTimeNanos()
                pins.onFrame(s, frame, rec, mapper.pinReads, mapper.items, it, viewportWidth, viewportHeight)
                pinNs = Debug.threadCpuTimeNanos() - p0
            }
        }
        pinNs += draw(frame, camera, mapper.latestView())
        if (rec != null) {
            val d0 = Debug.threadCpuTimeNanos()
            costs.frame(ts, d0 - cpu0, pinNs, updateNs, pins.work)
            // After the frame's clock stops: the measuring's own cost, timed apart (a closing window's lands in the next)
            if (costs.probeDue(ts)) probeCameraImage(frame, ts)
            geometry?.let { pins.logIfDue(frame, camera, rec, it, viewportWidth, viewportHeight) }
            if (costs.due(ts)) logCosts(ts)
            costs.diag(Debug.threadCpuTimeNanos() - d0)
        }
    }

    /** [sample] ([TrackQueue.drain]) as this frame's sample of the reads track */
    private fun recordReads(frame: Frame, sample: ByteArray) {
        val data = ByteBuffer.allocateDirect(sample.size).order(ByteOrder.nativeOrder()).put(sample)
        data.flip()
        runCatching { frame.recordTrackData(READS_TRACK, data) }.onFailure { Log.w(TAG, "reads track sample not recorded", it) }
    }

    /**
     * The recording's reads track samples that came with this frame ([decodeSample]): the item lists and the engine's
     * batches go to the mapper, the batches' Camera2 results to [metas], as the engine and the camera thread gave them.
     * Playback frames keep the recording's timestamps (2026-10-07: 909 of a replay's 981 frames were recorded ones, the
     * rest frames the recording never drew), so the reads keep theirs; a sample can come a few frames after the frame it
     * was written on, as reads came after their capture when it was recorded
     */
    private fun replayReads(frame: Frame, ts: Long) {
        for (data in frame.getUpdatedTrackData(READS_TRACK)) {
            val sample = runCatching { decodeSample(data.data) }.onFailure { Log.w(TAG, "reads track sample not read", it) }.getOrNull() ?: continue
            val lag = ((ts - sample.frameCameraNs) / 33_000_000L).toInt()
            if (lag > replayLagFrames) {
                replayLagFrames = lag
                Log.i(TAG, "replay: reads track samples come up to $lag frames after the frame they were written on")
            }
            for (e in sample.entries) when (e) {
                is TrackEntry.Items -> mapper.post(ArEvent.Items(e.codes))
                is TrackEntry.Reads -> {
                    e.meta?.let(metas::add)
                    mapper.post(e.event)
                }
            }
        }
    }

    /**
     * Plan Phase 0: whether ARCore's CPU image can be had under SHARED_CAMERA, and how long it takes (once a second);
     * its note is made only when the 3 s line is
     */
    private fun probeCameraImage(frame: Frame, ts: Long) {
        val t0 = System.nanoTime()
        try {
            frame.acquireCameraImage().use { img -> costs.probeImage(ts, System.nanoTime() - t0, img.width, img.height, img.format) }
        } catch (e: Exception) { // NotYetAvailableException, ResourceExhaustedException, or none under SHARED_CAMERA
            costs.probeFailed(ts, System.nanoTime() - t0, e)
        }
    }

    /**
     * The 3 s lines: the GL thread's cost (and, while a trace is open, its `gl` line, the only reader of every frame's
     * numbers), M6 pooled since the start with the window's slowest pin frame, and M3 so far
     */
    private fun logCosts(ts: Long) {
        val tracing = mapper.tracing
        val w = costs.close(ts, Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L, frames = tracing)
        Log.i(TAG, w.logLine())
        Log.i(TAG, w.sessionLine())
        Log.i(TAG, "unlisted outlines ($overlayRules${if (outlineFarSafe) ", far-safe" else ""}) on the frames drawn since the last line: " +
            "${outlineCounts[CARRIED]} carried, ${outlineCounts[FLAT]} where read, ${outlineCounts[DROPPED]} dropped (map moved since), " +
            "${outlineCounts[BEHIND]} behind the camera")
        outlineCounts.fill(0)
        outlineErrors.summary()?.let { Log.i(TAG, it) }
        if (tracing) mapper.diag { glLine(w) } // a trace opened since the window closed gets the next one's
    }

    /** In the mapper's order; every Create is answered, before the frame whose record carries the new anchor. */
    private fun applyAnchorOps(s: Session) {
        while (true) {
            when (val op = mapper.anchorOps.poll() ?: return) {
                AnchorOp.Detach -> {
                    replaced?.detach()
                    replaced = null
                    anchor?.detach()
                    anchor = null
                }
                is AnchorOp.Create -> {
                    replaced?.detach()
                    replaced = null
                    val old = anchor
                    anchor = runCatching { s.createAnchor(op.request.world.toArPose()) }
                        .onFailure { Log.w(TAG, "section anchor not created", it) }
                        .getOrNull()
                    if (anchor != null) replaced = old else old?.detach()
                    mapper.post(ArEvent.AnchorCreated(anchor != null, op.id))
                }
            }
        }
    }

    private fun record(camera: Camera, ts: Long): PoseRecord {
        val intrinsics = camera.imageIntrinsics
        val cpu = intrinsics.imageDimensions
        val st = stream
        val g = geometry?.takeIf { it.streamWidth == st.width && it.cpuWidth == cpu[0] && it.cpuHeight == cpu[1] }
            ?: StreamGeometry(st.width, st.height, cpu[0], cpu[1]).also {
                geometry = it
                if (!it.sameAspect) Log.w(TAG, "the $st app stream is the middle band of the ${cpu[0]}x${cpu[1]} CPU image: a mapping not yet run on hardware")
            }
        val tracking = camera.trackingState // the frame's: one JNI call, not three
        if (tracking != lastTracking) { // one line per change: device and emulator runs read tracking from the log
            lastTracking = tracking
            Log.i(TAG, "ARCore tracking $tracking (${camera.trackingFailureReason})")
        }
        val a = anchor
        val old = replaced
        replaced = null
        val previous = handoverPose(old?.let { it.pose.toPose() to it.trackingState.toTracking() })
        old?.detach()
        val held = a?.let { it.pose.toPose() to it.trackingState.toTracking() }
        if (a != null && held?.second == Tracking.TRACKING) held.first.t.let { pins.anchorMoved(ts, sectionStep.at(a, it.x, it.y, it.z)) }
        return poseRecordOf(
            timestampNs = ts,
            camera = camera.pose.toPose(),
            frameTracking = tracking.toTracking(),
            anchor = held,
            focal = intrinsics.focalLength,
            principal = intrinsics.principalPoint,
            geometry = g,
            exposureNs = metas.exposureAt(ts),
            previousAnchor = previous,
        )
    }

    /**
     * The map probe on the frame [rec] (§3.5.4): a world anchor [PROBE_DEPTH_M] along the view, made on a frame whose
     * camera tracks, made again once STOPPED or out of reach ([probeAction]); its step between the frames it tracked
     * goes to the outlines' map breaks, as the pins' and the section's do. In item mode the core holds no anchor until a
     * listed code is read, and none with an empty list, when the unlisted outlines are the only overlay.
     */
    private fun watchMap(s: Session, rec: PoseRecord) {
        if (rec.frameTracking != Tracking.TRACKING) return
        val p = probe
        val tracking = p?.trackingState?.toTracking()
        val at = if (tracking == Tracking.TRACKING) p?.pose else null
        val c = rec.camera.t
        val away = at?.let { distance(it.tx().toDouble(), it.ty().toDouble(), it.tz().toDouble(), c.x, c.y, c.z) } ?: 0.0
        when (probeAction(tracking, away)) {
            ProbeAction.KEEP -> if (p != null && at != null) {
                pins.anchorMoved(rec.timestampNs, probeStep.at(p, at.tx().toDouble(), at.ty().toDouble(), at.tz().toDouble()))
            }
            ProbeAction.WAIT -> Unit
            ProbeAction.MAKE -> {
                p?.detach()
                probe = runCatching { s.createAnchor(probePose(rec.camera).toArPose()) }
                    .onFailure { Log.w(TAG, "map probe not made", it) }
                    .getOrNull()
                if (probe != null) Log.i(TAG, if (p == null) "map probe made" else "map probe made again ($tracking, %.2f m away)".format(away))
            }
        }
    }

    /** The pins and the unlisted outlines (spec 5.10: no bracket, no gaps); the pins' thread CPU goes back */
    private fun draw(frame: Frame, camera: Camera, view: CountView): Long {
        logView(view)
        // AR Item Count: pins on listed codes replace the core's unit markers; unlisted reads get their quad outlined
        val p0 = Debug.threadCpuTimeNanos()
        val pinMarks = pins.marks(camera, lastTimestampNs, view, viewportWidth, viewportHeight)
        val pinNs = Debug.threadCpuTimeNanos() - p0
        pins.work.marksNs = pinNs
        geometry?.let { drawUnlisted(frame, camera, it) }
        marks.draw(pinMarks, viewportWidth, viewportHeight)
        return pinNs
    }

    /**
     * The decoded quad of each unlisted code read in the last 0.5 s (one per engine track), white and thin. Under
     * [OverlayRules.ANDROID], on a frame that tracks: carried from its newest read whose frame tracked, at the depth its
     * symbology gives ([outlineDepthM]), and not drawn when the map moved since that read ([chooseOutline]); else (and
     * for a track with no such read) the newest read's quad where it was read, as before. M3 keeps the outlines not
     * drawn too, with the rules of the frame.
     */
    private fun drawUnlisted(frame: Frame, camera: Camera, g: StreamGeometry) {
        val items = mapper.items
        if (items !== listedItems) { // the list's keys once per list, not every frame
            listedItems = items
            listedKeySet = listedKeys(items)
        }
        val tracks = unlistedTracksOf(mapper.recentReads(), listedKeySet, lastTimestampNs, readKeys = readKeys)
        measureOutlines(tracks)
        val rules = overlayRules
        val farSafe = outlineFarSafe
        outlinesDrawn.begin(lastTimestampNs, rules, farSafe)
        if (tracks.isEmpty()) return
        val now = pins.recordAt(lastTimestampNs)
        val carry = rules == OverlayRules.ANDROID && now?.frameTracking == Tracking.TRACKING &&
            camera.trackingState == TrackingState.TRACKING
        if (carry) {
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, NEAR_M, FAR_M)
        }
        if (quads.size < tracks.size * 8) quads = FloatArray(tracks.size * 8)
        var n = 0
        var flat: ArrayList<Read>? = null
        for (track in tracks) {
            val pick = chooseOutline(track, carry, pins) ?: continue // a track has a read
            val r = pick.read
            val capture = pick.capture
            when {
                capture == null -> (flat ?: ArrayList<Read>().also { flat = it }).add(r)
                pick.mapMoved -> {
                    outlinesDrawn.add(r, OutlineShown.MAP_MOVED)
                    outlineCounts[DROPPED]++
                }
                else -> {
                    val z = outlineDepthM(r, capture.intrinsics.fx, farSafe)
                    if (transfer(r.corners, capture, viewMatrix, projMatrix, z, viewportWidth, viewportHeight, quads, 8 * n) &&
                        transferredCentre(r.corners, capture, z, now!!, centre)
                    ) {
                        outlinesDrawn.add(r, OutlineShown.CARRIED, centre[0], centre[1], z)
                        outlineCounts[CARRIED]++
                        n++
                    } else {
                        outlinesDrawn.add(r, OutlineShown.BEHIND, depthM = z)
                        outlineCounts[BEHIND]++
                    }
                }
            }
        }
        flat?.let { n = drawnWhereRead(frame, g, it, n) }
        outlines.draw(quads, WHITE, viewportWidth, viewportHeight, n)
    }

    /** [reads]' quads where they were read (the outline before Phase 1), into [quads] after the first [from]; the count after */
    private fun drawnWhereRead(frame: Frame, g: StreamGeometry, reads: List<Read>, from: Int): Int {
        val image = floatsFor(flatImage, reads.size)
        var i = 0
        for (r in reads) {
            outlinesDrawn.add(r, OutlineShown.WHERE_READ, r.centreU, r.centreV)
            outlineCounts[FLAT]++
            for (c in 0 until 4) {
                image[i++] = g.cpuU(r.corners[2 * c] / g.streamWidth).toFloat()
                image[i++] = g.cpuV(r.corners[2 * c + 1] / g.streamHeight).toFloat()
            }
        }
        val onView = floatsFor(flatView, reads.size)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, image, Coordinates2d.VIEW, onView)
        System.arraycopy(onView, 0, quads, from * 8, onView.size)
        return from + reads.size
    }

    /** [cache]'s array of 8 floats per read for [reads] reads, made once per count */
    private fun floatsFor(cache: Array<FloatArray?>, reads: Int): FloatArray {
        if (reads >= cache.size) return FloatArray(reads * 8)
        return cache[reads] ?: FloatArray(reads * 8).also { cache[reads] = it }
    }

    /** M3: each unlisted track's newest read new since the last call, against the outline for its track on its own frame (drawn or not) */
    private fun measureOutlines(tracks: List<List<Read>>) {
        var newest = outlinesMeasuredNs
        for (t in tracks) {
            val r = t.first()
            if (r.timestampNs <= outlinesMeasuredNs) continue
            if (r.timestampNs > newest) newest = r.timestampNs
            val sample = outlinesDrawn.sample(r) ?: continue
            outlineErrors.add(r.symbology, sample)
            mapper.diag { outLine(r, sample) }
        }
        outlinesMeasuredNs = newest
    }

    /** One line per core state change, per break (as it happens, or with its section once closed) and per closed section */
    private fun logView(view: CountView) {
        if (view.state != lastState) {
            Log.i(TAG, "core state $lastState -> ${view.state}")
            lastState = view.state
        }
        for (b in view.breaks) if (loggedBreaks.add(b)) Log.i(TAG, "core break ${b.second} at ${b.first}")
        if (view.closed.size < closedLogged) closedLogged = 0 // New Scan: a new counter
        for (r in view.closed.drop(closedLogged)) {
            for (b in r.breaks) if (loggedBreaks.add(b)) Log.i(TAG, "core break ${b.second} at ${b.first}")
            Log.i(TAG, "core section ${r.sectionId} ${r.status} ${r.countLow}..${r.countHigh} breaks=${r.breaks.map { it.second }}")
        }
        closedLogged = view.closed.size
    }

    private companion object {
        const val TAG = "ArCountRenderer"
        val WHITE = floatArrayOf(1f, 1f, 1f)

        /** The projection's clip planes, as the pins' */
        const val NEAR_M = 0.05f
        const val FAR_M = 100f

        /** Read counts whose [drawnWhereRead] arrays are kept */
        const val FLAT_SIZES = 9

        // outlineCounts
        const val CARRIED = 0
        const val FLAT = 1
        const val DROPPED = 2
        const val BEHIND = 3
    }
}
