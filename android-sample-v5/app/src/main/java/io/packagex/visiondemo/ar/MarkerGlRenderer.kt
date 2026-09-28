package io.packagex.visiondemo.ar

import android.opengl.GLES20
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A marker projected into screen space, ready to draw. */
data class ScreenMarker(
    val x: Float,
    val y: Float,
    val label: String,
    val format: String,
    /** Physical instances of this payload currently anchored (≥2 shows a count pip). */
    val count: Int,
    /** uptimeMillis at anchor birth; drives the one-shot pulse. */
    val bornMs: Long,
)

private const val PULSE_MS = 700L

/**
 * Draws marker dots + rings + a one-shot birth pulse directly into the GL
 * frame, in the same [ArBarcodeRenderer.onDrawFrame] call that draws the
 * camera background.
 *
 * This used to be a separate [MarkerOverlayView] invalidated via
 * `postInvalidateOnAnimation`, which lands 1-2 vsyncs after the camera frame
 * it corresponds to and was visible as marker swim during a pan. Drawing here
 * keeps markers locked to the frame that produced their projection.
 */
class MarkerGlRenderer(
    private val density: Float,
) {
    private var program = 0
    private var localPosAttrib = 0
    private var centerUniform = 0
    private var halfSizeUniform = 0
    private var maxRadiusUniform = 0
    private var dotRUniform = 0
    private var ringRUniform = 0
    private var strokeHalfUniform = 0
    private var pulseRUniform = 0
    private var pulseAlphaUniform = 0
    private var dotColorUniform = 0
    private var ringColorUniform = 0
    private var pulseColorUniform = 0

    private fun dp(v: Float) = v * density

    // Same sizes as the View-based overlay this replaces.
    private val dotR = dp(6f)
    private val ringR = dp(11f)
    private val strokeHalf = dp(1f) // 2dp stroke, split either side of the radius
    private val pulseGrowth = dp(28f)

    private val accent = floatArrayOf(33f / 255f, 217f / 255f, 115f / 255f)
    private val white = floatArrayOf(1f, 1f, 1f)

    private val quad =
        ByteBuffer
            .allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            .apply { position(0) }

    fun createOnGlThread() {
        val vertex =
            """
            attribute vec2 a_LocalPos;
            uniform vec2 u_Center;
            uniform vec2 u_HalfSizeNdc;
            varying vec2 v_Local;
            void main() {
                v_Local = a_LocalPos;
                gl_Position = vec4(u_Center + a_LocalPos * u_HalfSizeNdc, 0.0, 1.0);
            }
            """.trimIndent()
        // Nested distance checks composite dot + ring + pulse in one pass;
        // the three regions never overlap so draw order among them doesn't
        // matter. Fragments outside all three are discarded so the quad
        // blends cleanly over the camera background.
        val fragment =
            """
            precision mediump float;
            varying vec2 v_Local;
            uniform float u_MaxRadiusPx;
            uniform float u_DotR;
            uniform float u_RingR;
            uniform float u_StrokeHalf;
            uniform float u_PulseR;
            uniform float u_PulseAlpha;
            uniform vec3 u_DotColor;
            uniform vec3 u_RingColor;
            uniform vec3 u_PulseColor;
            void main() {
                float d = length(v_Local) * u_MaxRadiusPx;
                vec4 outColor = vec4(0.0);
                if (u_PulseR >= 0.0 && abs(d - u_PulseR) < u_StrokeHalf) {
                    outColor = vec4(u_PulseColor, u_PulseAlpha);
                }
                if (abs(d - u_RingR) < u_StrokeHalf) {
                    outColor = vec4(u_RingColor, 1.0);
                }
                if (d < u_DotR) {
                    outColor = vec4(u_DotColor, 1.0);
                }
                if (outColor.a <= 0.0) discard;
                gl_FragColor = outColor;
            }
            """.trimIndent()

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compileShader(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compileShader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)

        localPosAttrib = GLES20.glGetAttribLocation(program, "a_LocalPos")
        centerUniform = GLES20.glGetUniformLocation(program, "u_Center")
        halfSizeUniform = GLES20.glGetUniformLocation(program, "u_HalfSizeNdc")
        maxRadiusUniform = GLES20.glGetUniformLocation(program, "u_MaxRadiusPx")
        dotRUniform = GLES20.glGetUniformLocation(program, "u_DotR")
        ringRUniform = GLES20.glGetUniformLocation(program, "u_RingR")
        strokeHalfUniform = GLES20.glGetUniformLocation(program, "u_StrokeHalf")
        pulseRUniform = GLES20.glGetUniformLocation(program, "u_PulseR")
        pulseAlphaUniform = GLES20.glGetUniformLocation(program, "u_PulseAlpha")
        dotColorUniform = GLES20.glGetUniformLocation(program, "u_DotColor")
        ringColorUniform = GLES20.glGetUniformLocation(program, "u_RingColor")
        pulseColorUniform = GLES20.glGetUniformLocation(program, "u_PulseColor")
    }

    /** Markers stay in the tens, so one draw call per marker is cheap. */
    fun draw(
        markers: List<ScreenMarker>,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        if (markers.isEmpty() || viewportWidth <= 0 || viewportHeight <= 0) return

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glVertexAttribPointer(localPosAttrib, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(localPosAttrib)

        GLES20.glUniform1f(dotRUniform, dotR)
        GLES20.glUniform1f(ringRUniform, ringR)
        GLES20.glUniform1f(strokeHalfUniform, strokeHalf)
        GLES20.glUniform3fv(dotColorUniform, 1, accent, 0)
        GLES20.glUniform3fv(ringColorUniform, 1, white, 0)
        GLES20.glUniform3fv(pulseColorUniform, 1, accent, 0)

        val now = SystemClock.uptimeMillis()
        val halfViewportW = viewportWidth / 2f
        val halfViewportH = viewportHeight / 2f
        for (m in markers) {
            val age = now - m.bornMs
            val pulsing = age in 0 until PULSE_MS
            val t = age / PULSE_MS.toFloat()
            val pulseR = if (pulsing) ringR + pulseGrowth * t else -1f
            val pulseAlpha = if (pulsing) 1f - t else 0f
            val maxRadiusPx = (if (pulsing) pulseR else ringR) + strokeHalf

            val ndcX = m.x / viewportWidth * 2f - 1f
            val ndcY = 1f - m.y / viewportHeight * 2f

            GLES20.glUniform2f(centerUniform, ndcX, ndcY)
            GLES20.glUniform2f(halfSizeUniform, maxRadiusPx / halfViewportW, maxRadiusPx / halfViewportH)
            GLES20.glUniform1f(maxRadiusUniform, maxRadiusPx)
            GLES20.glUniform1f(pulseRUniform, pulseR)
            GLES20.glUniform1f(pulseAlphaUniform, pulseAlpha)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glDisableVertexAttribArray(localPosAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun compileShader(
        type: Int,
        source: String,
    ): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }
}
