package io.packagex.visiondemo.ar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One mark to draw this frame, in view pixels: a dot of [dotColor] (RGB 0..1) inside [dotRadius], and a ring of
 * [ringColor] at [ringRadius]. A null [dotColor] draws the ring only (a gap's outline). A [label] goes on a dark pill
 * above it (iOS `MarkerNodeFactory`: the item's name, else its code).
 */
class ScreenMarker(
    val x: Float,
    val y: Float,
    val dotRadius: Float,
    val dotColor: FloatArray?,
    val ringRadius: Float,
    val ringColor: FloatArray,
    val label: String? = null,
)

/**
 * Draws dots, rings and their labels directly into the GL frame, in the same `onDrawFrame` call that draws the camera background.
 * A separate overlay View invalidated via `postInvalidateOnAnimation` lands 1-2 vsyncs after the camera frame it
 * belongs to and was visible as marker swim during a pan; drawing here keeps marks locked to the frame that produced
 * their positions.
 */
class MarkerGlRenderer(density: Float) {
    private var program = 0
    private var localPosAttrib = 0
    private var centerUniform = 0
    private var halfSizeUniform = 0
    private var maxRadiusUniform = 0
    private var dotRUniform = 0
    private var ringRUniform = 0
    private var strokeHalfUniform = 0
    private var dotColorUniform = 0
    private var ringColorUniform = 0

    /** Half the ring's stroke: 2 dp, split either side of the radius */
    private val strokeHalf = density

    // Label pill above the dot (iOS MarkerNodeFactory.makeLabel): 11pt semibold white text on a 75% black pill, 8pt
    // side / 4pt top-bottom padding, centred 20pt above the dot
    private val labelOffset = 20f * density
    private val labelPadX = 8f * density
    private val labelPadY = 4f * density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(191, 0, 0, 0) }

    private class LabelTexture(val id: Int, val width: Int, val height: Int)

    /** One texture per label drawn; a label not drawn in a frame has its texture deleted (GL thread only) */
    private val labels = HashMap<String, LabelTexture>()
    private val labelsDrawn = HashSet<String>()
    private var labelProgram = 0
    private var labelPosAttrib = 0
    private var labelCenterUniform = 0
    private var labelHalfSizeUniform = 0
    private var labelTextureUniform = 0

    private val quad =
        ByteBuffer
            .allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            .apply { position(0) }

    fun createOnGlThread() {
        labels.clear() // a new EGL context: the old texture names went with the old one
        createLabelProgram()
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
        // Nested distance checks composite dot and ring in one pass; fragments outside both are discarded so the
        // quad blends cleanly over the camera background.
        val fragment =
            """
            precision mediump float;
            varying vec2 v_Local;
            uniform float u_MaxRadiusPx;
            uniform float u_DotR;
            uniform float u_RingR;
            uniform float u_StrokeHalf;
            uniform vec3 u_DotColor;
            uniform vec3 u_RingColor;
            void main() {
                float d = length(v_Local) * u_MaxRadiusPx;
                vec4 outColor = vec4(0.0);
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
        dotColorUniform = GLES20.glGetUniformLocation(program, "u_DotColor")
        ringColorUniform = GLES20.glGetUniformLocation(program, "u_RingColor")
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
        GLES20.glUniform1f(strokeHalfUniform, strokeHalf)

        val halfViewportW = viewportWidth / 2f
        val halfViewportH = viewportHeight / 2f
        for (m in markers) {
            val maxRadiusPx = maxOf(m.dotRadius, m.ringRadius + strokeHalf)
            GLES20.glUniform2f(centerUniform, m.x / viewportWidth * 2f - 1f, 1f - m.y / viewportHeight * 2f)
            GLES20.glUniform2f(halfSizeUniform, maxRadiusPx / halfViewportW, maxRadiusPx / halfViewportH)
            GLES20.glUniform1f(maxRadiusUniform, maxRadiusPx)
            GLES20.glUniform1f(dotRUniform, if (m.dotColor == null) -1f else m.dotRadius)
            GLES20.glUniform1f(ringRUniform, m.ringRadius)
            GLES20.glUniform3fv(dotColorUniform, 1, m.dotColor ?: m.ringColor, 0)
            GLES20.glUniform3fv(ringColorUniform, 1, m.ringColor, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glDisableVertexAttribArray(localPosAttrib)
        drawLabels(markers, viewportWidth, viewportHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun createLabelProgram() {
        val vertex =
            """
            attribute vec2 a_LocalPos;
            uniform vec2 u_Center;
            uniform vec2 u_HalfSizeNdc;
            varying vec2 v_Tex;
            void main() {
                v_Tex = vec2(a_LocalPos.x * 0.5 + 0.5, 0.5 - a_LocalPos.y * 0.5);
                gl_Position = vec4(u_Center + a_LocalPos * u_HalfSizeNdc, 0.0, 1.0);
            }
            """.trimIndent()
        val fragment =
            """
            precision mediump float;
            varying vec2 v_Tex;
            uniform sampler2D u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_Tex);
            }
            """.trimIndent()
        labelProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(labelProgram, compileShader(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(labelProgram, compileShader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(labelProgram)
        labelPosAttrib = GLES20.glGetAttribLocation(labelProgram, "a_LocalPos")
        labelCenterUniform = GLES20.glGetUniformLocation(labelProgram, "u_Center")
        labelHalfSizeUniform = GLES20.glGetUniformLocation(labelProgram, "u_HalfSizeNdc")
        labelTextureUniform = GLES20.glGetUniformLocation(labelProgram, "u_Texture")
    }

    /** Each marker's [ScreenMarker.label] on its pill, over the dots */
    private fun drawLabels(markers: List<ScreenMarker>, viewportWidth: Int, viewportHeight: Int) {
        GLES20.glUseProgram(labelProgram)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // the bitmap is premultiplied
        quad.position(0)
        GLES20.glVertexAttribPointer(labelPosAttrib, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(labelPosAttrib)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(labelTextureUniform, 0)
        labelsDrawn.clear()
        for (m in markers) {
            val text = m.label ?: continue
            val tex = labels.getOrPut(text) { makeLabel(text) }
            labelsDrawn.add(text)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex.id)
            GLES20.glUniform2f(labelCenterUniform, m.x / viewportWidth * 2f - 1f, 1f - (m.y - labelOffset) / viewportHeight * 2f)
            GLES20.glUniform2f(labelHalfSizeUniform, tex.width / viewportWidth.toFloat(), tex.height / viewportHeight.toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(labelPosAttrib)
        if (labels.size > labelsDrawn.size) {
            val unused = labels.keys.filter { it !in labelsDrawn }
            GLES20.glDeleteTextures(unused.size, unused.map { labels.remove(it)!!.id }.toIntArray(), 0)
        }
    }

    /** [label] (cut to 24 characters, as iOS) on a pill, into a new texture */
    private fun makeLabel(label: String): LabelTexture {
        val text = if (label.length > 24) label.take(21) + "…" else label
        val fm = labelPaint.fontMetrics
        val w = (labelPaint.measureText(text) + labelPadX * 2).toInt().coerceAtLeast(1)
        val h = (fm.descent - fm.ascent + labelPadY * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, pillPaint)
            drawText(text, labelPadX, labelPadY - fm.ascent, labelPaint)
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return LabelTexture(ids[0], w, h)
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

/**
 * Thin outlines of quads, in view pixels, drawn into the GL frame like [MarkerGlRenderer]'s marks: AR Item Count's
 * unlisted reads (the decoded quad, white, 2 px where the GL implementation allows wide lines, else 1 px).
 */
class OutlineGlRenderer {
    private var program = 0
    private var posAttrib = 0
    private var colorUniform = 0
    private var buffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun createOnGlThread() {
        val vertex =
            """
            attribute vec2 a_Pos;
            void main() { gl_Position = vec4(a_Pos, 0.0, 1.0); }
            """.trimIndent()
        val fragment =
            """
            precision mediump float;
            uniform vec3 u_Color;
            void main() { gl_FragColor = vec4(u_Color, 1.0); }
            """.trimIndent()
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        posAttrib = GLES20.glGetAttribLocation(program, "a_Pos")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
    }

    /**
     * The first [count] quads of [quads], 8 numbers a quad (x0, y0 .. x3, y3, view pixels), each drawn as a closed
     * outline of [color] (RGB 0..1)
     */
    fun draw(quads: FloatArray, color: FloatArray, viewportWidth: Int, viewportHeight: Int, count: Int = quads.size / 8) {
        if (count <= 0 || viewportWidth <= 0 || viewportHeight <= 0) return
        val n = count * 8
        if (buffer.capacity() < n) buffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.clear()
        for (i in 0 until n step 2) {
            buffer.put(quads[i] / viewportWidth * 2f - 1f)
            buffer.put(1f - quads[i + 1] / viewportHeight * 2f)
        }
        buffer.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glLineWidth(2f)
        GLES20.glUniform3fv(colorUniform, 1, color, 0)
        GLES20.glVertexAttribPointer(posAttrib, 2, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glEnableVertexAttribArray(posAttrib)
        for (q in 0 until count) GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, q * 4, 4)
        GLES20.glDisableVertexAttribArray(posAttrib)
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
}
