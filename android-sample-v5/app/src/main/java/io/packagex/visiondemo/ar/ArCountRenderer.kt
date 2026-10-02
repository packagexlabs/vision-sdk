package io.packagex.visiondemo.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.SessionPausedException
import io.packagex.arcount.CountView
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
    private var geometry: StreamGeometry? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var viewportChanged = false
    private var textureSet = false
    private var lastTimestampNs = Long.MIN_VALUE
    private var lastScreen = ArScreen.NONE

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
            mapper.post(ArEvent.Frame(record(frame, ts)))
        }
        draw(frame, mapper.latestView())
    }

    /** In the mapper's order; every Create is answered, before the frame whose record carries the new anchor. */
    private fun applyAnchorOps(s: Session) {
        while (true) {
            when (val op = mapper.anchorOps.poll() ?: return) {
                AnchorOp.Detach -> {
                    anchor?.detach()
                    anchor = null
                }
                is AnchorOp.Create -> {
                    anchor?.detach()
                    anchor = runCatching { s.createAnchor(op.request.world.toArPose()) }
                        .onFailure { Log.w(TAG, "section anchor not created", it) }
                        .getOrNull()
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
            ?: StreamGeometry(st.width, st.height, cpu[0], cpu[1]).also { geometry = it }
        val a = anchor
        return poseRecordOf(
            timestampNs = ts,
            camera = camera.pose.toPose(),
            frameTracking = camera.trackingState.toTracking(),
            anchor = a?.let { it.pose.toPose() to it.trackingState.toTracking() },
            focal = intrinsics.focalLength,
            principal = intrinsics.principalPoint,
            geometry = g,
            exposureNs = metas.exposureAt(ts),
        )
    }

    private fun draw(frame: Frame, view: CountView) {
        val g = geometry ?: return
        val bracket = view.bracket?.takeIf { it.inImage }
        // Per marker its centre and a point sizeU to its right (its size on screen), then the gaps, then the bracket
        val n = view.markers.size * 2 + view.gaps.size + (if (bracket != null) 1 else 0)
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
        view.markers.forEach { put(it.u, it.v); put(it.u + it.sizeU, it.v) }
        view.gaps.forEach { put(it.u, it.v) }
        bracket?.let { put(it.u, it.v) }
        val onView = FloatArray(n * 2)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, image, Coordinates2d.VIEW, onView)

        val out = ArrayList<ScreenMarker>(n)
        var k = 0
        for (m in view.markers) {
            val x = onView[k]
            val y = onView[k + 1]
            val sizePx = hypot(onView[k + 2] - x, onView[k + 3] - y)
            k += 4
            val r = (sizePx * 0.25f).coerceIn(dp(5f), dp(16f))
            out += ScreenMarker(x, y, r, colorOf(m.state), r + dp(3f), WHITE)
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
