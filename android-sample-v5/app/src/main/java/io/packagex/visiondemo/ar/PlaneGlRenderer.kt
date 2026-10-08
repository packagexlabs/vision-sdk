package io.packagex.visiondemo.ar

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Camera
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The surface ARCore found under the view, over the camera picture, to see whether ARCore has found the surface the
 * labels lie on (a pin born before it takes a default depth): one plane ([refresh]), a clear white veil and a white
 * outline, so it reads apart from the green pins. It is chosen and its polygon read every [REFRESH_FRAMES] frames, as
 * world points: Plane.getPolygon and getCenterPose allocate, and a plane changes slowly. GL thread only.
 */
class PlaneGlRenderer {
    private var program = 0
    private var posAttrib = 0
    private var colorUniform = 0
    private var viewProjUniform = 0

    /** The planes drawn and their polygons in world metres (x, y, z a vertex), from the last refresh */
    private val planes = ArrayList<Plane>()
    private val polygons = ArrayList<FloatArray>()
    private val colors = ArrayList<FloatArray>()
    private var session: Session? = null
    private var frames = 0

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val centre = FloatArray(16)
    private var buffer: FloatBuffer = ByteBuffer.allocateDirect(64 * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun createOnGlThread() {
        val vertex =
            """
            uniform mat4 u_ViewProj;
            attribute vec3 a_Pos;
            void main() { gl_Position = u_ViewProj * vec4(a_Pos, 1.0); }
            """.trimIndent()
        val fragment =
            """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = u_Color; }
            """.trimIndent()
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        posAttrib = GLES20.glGetAttribLocation(program, "a_Pos")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        viewProjUniform = GLES20.glGetUniformLocation(program, "u_ViewProj")
    }

    /** The planes of [session] on this frame, seen by [camera] */
    fun draw(session: Session, camera: Camera) {
        if (session !== this.session) { // a rebuilt session's planes are new objects
            this.session = session
            planes.clear()
            polygons.clear()
            colors.clear()
            frames = 0
        }
        if (frames++ % REFRESH_FRAMES == 0 && camera.trackingState == TrackingState.TRACKING) refresh(session, camera)
        if (planes.isEmpty() || camera.trackingState != TrackingState.TRACKING) return
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, NEAR_M, FAR_M)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(viewProjUniform, 1, false, viewProj, 0)
        GLES20.glLineWidth(3f)
        GLES20.glEnableVertexAttribArray(posAttrib)
        for (i in planes.indices) {
            if (planes[i].trackingState != TrackingState.TRACKING) continue
            val points = polygons[i]
            val n = points.size / 3
            if (buffer.capacity() < points.size) buffer = ByteBuffer.allocateDirect(points.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.clear()
            buffer.put(points)
            buffer.position(0)
            GLES20.glVertexAttribPointer(posAttrib, 3, GLES20.GL_FLOAT, false, 0, buffer)
            val c = colors[i]
            GLES20.glUniform4f(colorUniform, c[0], c[1], c[2], FILL_ALPHA)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, n) // ARCore's polygons are convex
            GLES20.glUniform4f(colorUniform, c[0], c[1], c[2], LINE_ALPHA)
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, n)
        }
        GLES20.glDisableVertexAttribArray(posAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * The one plane worth showing, its polygon turned to world points: the upward-facing plane the centre of the view
     * looks at (the nearest, when the ray meets several), else the largest upward-facing one. Drawing every plane ARCore
     * keeps stacked their veils into a fog (a table found at a few heights, the sheet, the floor).
     */
    private fun refresh(session: Session, camera: Camera) {
        planes.clear()
        polygons.clear()
        colors.clear()
        val eye = camera.pose
        val back = eye.zAxis // the camera looks along -z
        val dx = -back[0]
        val dy = -back[1]
        val dz = -back[2]
        var aimed: Plane? = null
        var aimedAt = Float.MAX_VALUE
        var largest: Plane? = null
        var largestArea = 0f
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val area = plane.extentX * plane.extentZ
            if (area > largestArea) {
                largestArea = area
                largest = plane
            }
            // Where the view's centre ray meets the plane: eye + s * d, with n . (eye + s * d - centre) = 0
            val c = plane.centerPose
            val n = c.yAxis
            val nd = n[0] * dx + n[1] * dy + n[2] * dz
            if (nd > -1e-3f) continue // the view runs along the plane, or meets it from below
            val s = (n[0] * (c.tx() - eye.tx()) + n[1] * (c.ty() - eye.ty()) + n[2] * (c.tz() - eye.tz())) / nd
            if (s <= 0f || s >= aimedAt) continue
            if (!plane.isPoseInPolygon(Pose.makeTranslation(eye.tx() + s * dx, eye.ty() + s * dy, eye.tz() + s * dz))) continue
            aimedAt = s
            aimed = plane
        }
        (aimed ?: largest)?.let { show(it) }
    }

    /** [plane]'s polygon as world points, drawn until the next refresh */
    private fun show(plane: Plane) {
        val local = plane.polygon // x, z pairs in the plane's frame
        val n = local.limit() / 2
        if (n < 3) return
        plane.centerPose.toMatrix(centre, 0)
        val world = FloatArray(n * 3)
        for (k in 0 until n) {
            val x = local.get(2 * k)
            val z = local.get(2 * k + 1)
            world[3 * k] = centre[0] * x + centre[8] * z + centre[12]
            world[3 * k + 1] = centre[1] * x + centre[9] * z + centre[13]
            world[3 * k + 2] = centre[2] * x + centre[10] * z + centre[14]
        }
        planes += plane
        polygons += world
        colors += WHITE
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private companion object {
        /** About twice a second at 30 fps */
        const val REFRESH_FRAMES = 15
        const val NEAR_M = 0.05f
        const val FAR_M = 100f
        const val FILL_ALPHA = 0.12f
        const val LINE_ALPHA = 0.7f
        val WHITE = floatArrayOf(1f, 1f, 1f)
    }
}
