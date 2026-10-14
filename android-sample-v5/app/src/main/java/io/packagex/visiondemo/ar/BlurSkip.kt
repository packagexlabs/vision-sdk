package io.packagex.visiondemo.ar

import io.packagex.arcount.Blur
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Tracking

/**
 * Predicted blur, in pixels of the 4K stream, above which an app-stream image is not handed to the engine (spec 5.6
 * pre-skip, simplified: a fixed depth and a threshold in pixels, not modules). On the Memor trace of 2026-10-02, images
 * above 24 px took 34% of the engine's time and gave 9% of the reads. Initial; to be set from traces.
 */
internal const val BLUR_SKIP_PX = 24.0

/** The camera depth the pre-skip assumes, in metres: the 4K working distance */
internal const val BLUR_DEPTH_M = 0.4

/** A newest pose older than this before the image says nothing of the image's motion */
internal const val BLUR_POSE_MAX_AGE_NS = 100_000_000L

/**
 * The blur predicted for an image exposed for [exposureNs], in pixels of the stream [cur]'s intrinsics are of, from the
 * motion between [prev] and [cur]: rotation rate x exposure x f + speed x exposure x f / [z]. Null when it can't be
 * told: no exposure, no time between the two, or a frame that ARCore did not track.
 */
fun predictedBlurPx(prev: PoseRecord, cur: PoseRecord, exposureNs: Long, z: Double = BLUR_DEPTH_M): Double? {
    val dtS = (cur.timestampNs - prev.timestampNs) / 1e9
    if (exposureNs <= 0 || dtS <= 0 || z <= 0 || prev.frameTracking != Tracking.TRACKING || cur.frameTracking != Tracking.TRACKING) return null
    val exposureS = exposureNs / 1e9
    val f = cur.intrinsics.fx
    return Blur.rotationRate(prev.camera, cur.camera, dtS) * exposureS * f + Blur.speed(prev.camera, cur.camera, dtS) * exposureS * f / z
}

/** Whether an image with [blurPx] predicted, of a stream [streamWidth] wide, is kept from the engine: [BLUR_SKIP_PX] at 4K, scaled with the width. */
fun skipForBlur(blurPx: Double?, streamWidth: Int): Boolean = blurPx != null && blurPx > BLUR_SKIP_PX * streamWidth / 3840.0

/** The two newest [PoseRecord]s, for the pre-skip: written on the GL thread only, read on the camera thread. */
class LatestPoses {
    private class Two(val prev: PoseRecord?, val cur: PoseRecord)

    @Volatile
    private var two: Two? = null

    fun add(rec: PoseRecord) {
        two = Two(two?.cur, rec)
    }

    fun clear() {
        two = null
    }

    /** The blur predicted for the image taken at [imageTimestampNs] from the two newest poses; null when they are missing or stale. */
    fun blurPx(imageTimestampNs: Long, exposureNs: Long): Double? {
        val t = two ?: return null
        val prev = t.prev ?: return null
        if (imageTimestampNs - t.cur.timestampNs > BLUR_POSE_MAX_AGE_NS) return null
        return predictedBlurPx(prev, t.cur, exposureNs)
    }
}
