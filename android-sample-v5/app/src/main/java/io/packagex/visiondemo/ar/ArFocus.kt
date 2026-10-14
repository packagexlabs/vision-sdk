package io.packagex.visiondemo.ar

/** A metering region in pixels of the sensor's active array (relative to its top left) */
data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * AR Item Count's tap to focus: the view point ([x], [y], 0..1 of the view, top left origin) as a region of the sensor's
 * [activeWidth] x [activeHeight] active array, [fraction] of it each way (1/8), centred on the point and kept inside the
 * array. The view shows the sensor turned by ([sensorOrientation] - [displayRotation]) clockwise (back camera), so a
 * portrait view on a 90° sensor has view x along the sensor's -y and view y along its +x. The view's crop of the image
 * (fill) is not modelled: the region is wide enough for it.
 */
fun meteringRegion(x: Float, y: Float, sensorOrientation: Int, displayRotation: Int, activeWidth: Int, activeHeight: Int, fraction: Double = 1.0 / 8): Region {
    val vx = x.toDouble().coerceIn(0.0, 1.0)
    val vy = y.toDouble().coerceIn(0.0, 1.0)
    val (sx, sy) = when (((sensorOrientation - displayRotation) % 360 + 360) % 360) {
        90 -> vy to 1 - vx
        180 -> 1 - vx to 1 - vy
        270 -> 1 - vy to vx
        else -> vx to vy
    }
    val w = (activeWidth * fraction).toInt().coerceAtLeast(1)
    val h = (activeHeight * fraction).toInt().coerceAtLeast(1)
    val left = (sx * activeWidth - w / 2.0).toInt().coerceIn(0, activeWidth - w)
    val top = (sy * activeHeight - h / 2.0).toInt().coerceIn(0, activeHeight - h)
    return Region(left, top, w, h)
}
