package io.packagex.visiondemo.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.SessionPausedException
import io.packagex.arcount.BreakReason
import io.packagex.arcount.CountView
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.SectionState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * The GL thread of an AR Count session (spec 5.8): `Session.update()` (BLOCKING, paced by the camera), then the
 * anchor ops of the mapper (create or let go of the section anchor), the frame's [PoseRecord] to the mapper, the
 * camera background, AR Item Count's pins ([ArPins], in place of the core's unit markers), and the newest view's gaps and
 * bracket point. Those come in normalized coordinates of the
 * unrotated stream image; ARCore maps them to the view (`transformCoordinates2d`, IMAGE_NORMALIZED -> VIEW). The
 * bracket's and the gaps' view points go to [onScreen] for the Compose overlay. No counting here.
 */
class ArCountRenderer(
    private val mapper: ArMapper,
    private val metas: CaptureMetaRing,
    private val density: Float,
    private val onScreen: (ArScreen) -> Unit,
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

    @Volatile
    private var resumePending = false

    private val background = BackgroundRenderer()
    private val marks = MarkerGlRenderer(density)
    private val outlines = OutlineGlRenderer()

    /** AR Item Count's persistent markers, one per physical barcode (GL thread) */
    private val pins = ArPins(density)

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
    private var lastScreen = ArScreen.NONE
    private var lastTracking: TrackingState? = null
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
        background.draw(frame)
        val ts = frame.androidCameraTimestamp
        if (ts != 0L && ts != lastTimestampNs) {
            lastTimestampNs = ts
            if (resumePending) {
                resumePending = false
                mapper.post(ArEvent.Resumed(ts))
            }
            applyAnchorOps(s)
            val rec = record(frame, ts)
            poses?.add(rec)
            mapper.post(ArEvent.Frame(rec))
            geometry?.let { pins.onFrame(s, frame, rec, mapper.pinReads, mapper.items, it, viewportWidth, viewportHeight) }
        }
        draw(frame, mapper.latestView())
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

    private fun record(frame: Frame, ts: Long): PoseRecord {
        val camera = frame.camera
        val intrinsics = camera.imageIntrinsics
        val cpu = intrinsics.imageDimensions
        val st = stream
        val g = geometry?.takeIf { it.streamWidth == st.width && it.cpuWidth == cpu[0] && it.cpuHeight == cpu[1] }
            ?: StreamGeometry(st.width, st.height, cpu[0], cpu[1]).also {
                geometry = it
                if (!it.sameAspect) Log.w(TAG, "the $st app stream is the middle band of the ${cpu[0]}x${cpu[1]} CPU image: a mapping not yet run on hardware")
            }
        if (camera.trackingState != lastTracking) { // one line per change: device and emulator runs read tracking from the log
            lastTracking = camera.trackingState
            Log.i(TAG, "ARCore tracking ${camera.trackingState} (${camera.trackingFailureReason})")
        }
        val a = anchor
        val old = replaced
        replaced = null
        val previous = handoverPose(old?.let { it.pose.toPose() to it.trackingState.toTracking() })
        old?.detach()
        return poseRecordOf(
            timestampNs = ts,
            camera = camera.pose.toPose(),
            frameTracking = camera.trackingState.toTracking(),
            anchor = a?.let { it.pose.toPose() to it.trackingState.toTracking() },
            focal = intrinsics.focalLength,
            principal = intrinsics.principalPoint,
            geometry = g,
            exposureNs = metas.exposureAt(ts),
            previousAnchor = previous,
        )
    }

    private fun draw(frame: Frame, view: CountView) {
        logView(view)
        // AR Item Count: pins on listed codes replace the core's unit markers; unlisted reads get their quad outlined
        val pinMarks = pins.marks(frame, view, viewportWidth, viewportHeight)
        val g = geometry
        g?.let { drawUnlisted(frame, it) }
        val bracket = view.bracket?.takeIf { it.inImage }
        // The gaps' points, then the bracket's
        val n = if (g == null) 0 else view.gaps.size + (if (bracket != null) 1 else 0)
        if (n == 0 || g == null) {
            marks.draw(pinMarks, viewportWidth, viewportHeight)
            publish(ArScreen.NONE)
            return
        }
        val image = FloatArray(n * 2)
        var i = 0
        fun put(u: Double, v: Double) {
            image[i++] = g.cpuU(u).toFloat()
            image[i++] = g.cpuV(v).toFloat()
        }
        view.gaps.forEach { put(it.u, it.v) }
        bracket?.let { put(it.u, it.v) }
        val onView = FloatArray(n * 2)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, image, Coordinates2d.VIEW, onView)

        val out = ArrayList<ScreenMarker>(pinMarks.size + n)
        out += pinMarks
        var k = 0
        val gaps = ArrayList<ScreenGap>(view.gaps.size)
        for (gap in view.gaps) {
            val x = onView[k]
            val y = onView[k + 1]
            k += 2
            out += ScreenMarker(x, y, 0f, null, dp(12f), WHITE)
            gaps += ScreenGap(gap.gapId, x, y)
        }
        val bracketPoint = bracket?.let { ScreenPoint(onView[k], onView[k + 1]) }
        bracketPoint?.let { out += ScreenMarker(it.x, it.y, dp(7f), NEON, dp(11f), WHITE) }
        marks.draw(out, viewportWidth, viewportHeight)
        publish(ArScreen(bracketPoint, gaps))
    }

    /** The decoded quad of each unlisted code read in the last 0.5 s (its newest read per engine track), white and thin */
    private fun drawUnlisted(frame: Frame, g: StreamGeometry) {
        val reads = unlistedReads(mapper.recentReads(), mapper.items, lastTimestampNs)
        if (reads.isEmpty()) return
        val image = FloatArray(reads.size * 8)
        var i = 0
        for (r in reads) {
            for (c in 0 until 4) {
                image[i++] = g.cpuU(r.corners[2 * c] / g.streamWidth).toFloat()
                image[i++] = g.cpuV(r.corners[2 * c + 1] / g.streamHeight).toFloat()
            }
        }
        val onView = FloatArray(image.size)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, image, Coordinates2d.VIEW, onView)
        outlines.draw(onView, WHITE, viewportWidth, viewportHeight)
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

    /** To the overlay, when a point moved by a pixel or more, or one came or went. */
    private fun publish(screen: ArScreen) {
        val last = lastScreen
        val same = (screen.bracket == null) == (last.bracket == null) &&
            (screen.bracket == null || near(screen.bracket.x, screen.bracket.y, last.bracket!!.x, last.bracket.y)) &&
            screen.gaps.size == last.gaps.size &&
            screen.gaps.indices.all { screen.gaps[it].gapId == last.gaps[it].gapId && near(screen.gaps[it].x, screen.gaps[it].y, last.gaps[it].x, last.gaps[it].y) }
        if (same) return
        lastScreen = screen
        onScreen(screen)
    }

    private fun near(x0: Float, y0: Float, x1: Float, y1: Float) = abs(x0 - x1) < 1f && abs(y0 - y1) < 1f

    private fun dp(v: Float) = v * density

    private companion object {
        const val TAG = "ArCountRenderer"

        // The bracket point is the brand neon
        val NEON = floatArrayOf(71 / 255f, 234 / 255f, 226 / 255f)
        val WHITE = floatArrayOf(1f, 1f, 1f)
    }
}
