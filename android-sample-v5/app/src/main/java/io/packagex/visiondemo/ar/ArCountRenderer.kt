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
import io.packagex.arcount.CountView
import io.packagex.arcount.Marker
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.UnitState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The GL thread of an AR Count session (spec 5.8): `Session.update()` (BLOCKING, paced by the camera), then the
 * anchor ops of the mapper (create or let go of the section anchor), the frame's [PoseRecord] to the mapper, the
 * camera background, and the newest view's markers, gaps and bracket point. Those come in normalized coordinates of the
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

    /** ARCore was resumed: the next frame tells the counter, with its timestamp (spec 5.1 start-up guard). */
    fun resumed() {
        resumePending = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        marks.createOnGlThread()
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
        val g = geometry ?: return
        val bracket = view.bracket?.takeIf { it.inImage }
        val markers = placeMarkers(frame, view, g)
        // AR Item Count: a neutral ring on each code read lately that is not listed (spec 5.10)
        val unlisted = unlistedReads(mapper.recentReads(), mapper.items, lastTimestampNs)
        // Per marker its centre and a point sizeU to its right (its size on screen), then the unlisted reads' centres,
        // then the gaps, then the bracket
        val n = markers.size * 2 + unlisted.size + view.gaps.size + (if (bracket != null) 1 else 0)
        if (n == 0) {
            publish(ArScreen.NONE)
            return
        }
        val image = FloatArray(n * 2)
        var i = 0
        fun put(u: Double, v: Double) {
            image[i++] = g.cpuU(u).toFloat()
            image[i++] = g.cpuV(v).toFloat()
        }
        markers.forEach { (_, p) -> put(p.u, p.v); put(p.u + p.sizeU, p.v) }
        unlisted.forEach { put(it.centreU / g.streamWidth, it.centreV / g.streamHeight) }
        view.gaps.forEach { put(it.u, it.v) }
        bracket?.let { put(it.u, it.v) }
        val onView = FloatArray(n * 2)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, image, Coordinates2d.VIEW, onView)

        val out = ArrayList<ScreenMarker>(n)
        var k = 0
        for ((m, _) in markers) {
            val x = onView[k]
            val y = onView[k + 1]
            val sizePx = hypot(onView[k + 2] - x, onView[k + 3] - y)
            k += 4
            val r = (sizePx * 0.25f).coerceIn(dp(5f), dp(16f))
            out += ScreenMarker(x, y, r, colorOf(m.state), r + dp(3f), WHITE)
        }
        repeat(unlisted.size) {
            out += ScreenMarker(onView[k], onView[k + 1], 0f, null, dp(10f), WHITE)
            k += 2
        }
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

    /**
     * The markers of [view] where this frame shows them (spec 5.5, no frame of lag): one with an anchor point is
     * projected with this frame's camera and anchor poses and the stream's intrinsics; the others stay where the
     * counter put them. A point behind the camera is not drawn.
     */
    private fun placeMarkers(frame: Frame, view: CountView, g: StreamGeometry): List<Pair<Marker, MarkerPlace>> {
        if (view.markers.isEmpty()) return emptyList()
        val a = anchor?.takeIf { it.trackingState != TrackingState.STOPPED }
        if (a == null || view.markers.none { it.anchorPoint != null }) return view.markers.map { it to MarkerPlace(it.u, it.v, it.sizeU) }
        val camera = frame.camera
        val i = camera.imageIntrinsics
        val focal = i.focalLength
        val principal = i.principalPoint
        val intrinsics = g.intrinsics(focal[0].toDouble(), focal[1].toDouble(), principal[0].toDouble(), principal[1].toDouble())
        val cameraPose = camera.pose.toPose()
        val anchorPose = a.pose.toPose()
        return view.markers.mapNotNull { m -> placeMarker(m, cameraPose, anchorPose, intrinsics)?.let { m to it } }
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

    private fun colorOf(state: UnitState) = when (state) {
        UnitState.COUNTED -> GREEN
        UnitState.TENTATIVE -> GREY
        UnitState.AMBIGUOUS -> AMBER
        UnitState.MANUAL -> BLUE
    }

    private companion object {
        const val TAG = "ArCountRenderer"

        // Spec 5.5: COUNTED green, TENTATIVE grey, AMBIGUOUS amber, MANUAL blue; the bracket point is the brand neon
        val GREEN = floatArrayOf(27 / 255f, 242 / 255f, 163 / 255f)
        val GREY = floatArrayOf(0.62f, 0.62f, 0.66f)
        val AMBER = floatArrayOf(1f, 176 / 255f, 32 / 255f)
        val BLUE = floatArrayOf(0.25f, 0.55f, 1f)
        val NEON = floatArrayOf(71 / 255f, 234 / 255f, 226 / 255f)
        val WHITE = floatArrayOf(1f, 1f, 1f)
    }
}
