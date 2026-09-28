package io.packagex.visiondemo.ar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * ARCore's tracking geometry projected to screen, for the debug overlay.
 *
 * [planes] — one closed polygon per TRACKING plane, as x,y pairs. This is the
 * plane's actual polygon (what `isPoseInPolygon` tests), i.e. exactly where a
 * plane hit can land — more useful than the cosmetic grid texture.
 * [points]  — feature-point cloud as x,y,confidence triples. These are the
 * visual features VIO is tracking against; sparse or absent = poor tracking.
 */
class ScreenDebug(
    val planes: List<FloatArray>,
    val points: FloatArray,
    val pointCount: Int,
)

/**
 * Debug-only overlay for ARCore's plane polygons and feature-point cloud
 * (see [SHOW_TRACKING_DEBUG]). The AR markers themselves are drawn in GL,
 * inside the same frame as the camera background — see [MarkerGlRenderer].
 */
class MarkerOverlayView(
    context: Context,
) : View(context) {
    @Volatile
    private var debug: ScreenDebug? = null

    private val density = resources.displayMetrics.density

    private fun dp(v: Float) = v * density

    private val planeFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(46, 33, 217, 115)
            style = Paint.Style.FILL
        }
    private val planeStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 120, 235, 255)
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
    private val pointPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 214, 0)
            style = Paint.Style.FILL
        }
    private val planePath = Path()

    fun updateDebug(d: ScreenDebug?) {
        debug = d
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        debug?.let { drawDebug(canvas, it) }
    }

    private fun drawDebug(
        canvas: Canvas,
        d: ScreenDebug,
    ) {
        for (poly in d.planes) {
            if (poly.size < 6) continue
            planePath.rewind()
            planePath.moveTo(poly[0], poly[1])
            var i = 2
            while (i < poly.size) {
                planePath.lineTo(poly[i], poly[i + 1])
                i += 2
            }
            planePath.close()
            canvas.drawPath(planePath, planeFillPaint)
            canvas.drawPath(planePath, planeStrokePaint)
        }
        val r = dp(2.2f)
        var i = 0
        val n = d.pointCount * 3
        while (i < n) {
            // confidence 0..1 -> alpha; low-confidence points fade out
            pointPaint.alpha = (60 + 195 * d.points[i + 2]).toInt().coerceIn(0, 255)
            canvas.drawCircle(d.points[i], d.points[i + 1], r, pointPaint)
            i += 3
        }
    }
}
