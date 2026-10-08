package io.packagex.visiondemo.ar

/**
 * One capture's Camera2 result while ARCore owns the capture request, from `SharedCamera.setCaptureCallback` (spec 5.2):
 * the app's own repeating-request callback stops once ARCore resumes. [sensorTimestampNs] is the capture's
 * SENSOR_TIMESTAMP, the same number as its app-stream image's timestamp and its frame's Android camera timestamp. The
 * AE keys show whether ARCore kept the app's settings (risk R9). [focusDiopters] is LENS_FOCUS_DISTANCE (-1: not
 * given), for focus breathing (the focal length moves with it).
 */
data class CaptureMeta(
    val sensorTimestampNs: Long,
    val exposureNs: Long,
    val sensitivity: Int,
    val rollingShutterSkewNs: Long,
    val aeCompensation: Int,
    val fpsRange: String,
    val afState: Int,
    val focusDiopters: Float = -1f,
)

/** The last [capacity] captures' [CaptureMeta] (2 s at 30 fps): written on the camera thread, read on the GL and mapper threads. */
class CaptureMetaRing(private val capacity: Int = 60) {
    private val metas = ArrayDeque<CaptureMeta>()

    fun add(meta: CaptureMeta) = synchronized(metas) {
        metas.addLast(meta)
        while (metas.size > capacity) metas.removeFirst()
    }

    /** The metadata of the capture taken at [timestampNs], if it has come and is still kept */
    fun at(timestampNs: Long): CaptureMeta? = synchronized(metas) { metas.lastOrNull { it.sensorTimestampNs == timestampNs } }

    /**
     * The exposure of the capture at [timestampNs]; while its result has not come yet, the newest capture's (the blur
     * cue of spec 5.6 uses the newest); -1 before any capture.
     */
    fun exposureAt(timestampNs: Long): Long = synchronized(metas) {
        (metas.lastOrNull { it.sensorTimestampNs == timestampNs } ?: metas.lastOrNull())?.exposureNs ?: -1L
    }

    fun clear() = synchronized(metas) { metas.clear() }
}
