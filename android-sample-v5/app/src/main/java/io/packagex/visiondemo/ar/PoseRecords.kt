package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import com.google.ar.core.Pose as ArPose
import com.google.ar.core.TrackingState as ArTracking

/**
 * The app's own YUV stream on the shared camera, the one the engine reads (spec 5.2): the largest of these the camera
 * offers, the next one after a failed configure. Fixed for the life of a session. [workingDistanceCm] is where a 2 px
 * module is still read, which the UI shows.
 */
enum class AppStream(val width: Int, val height: Int, val workingDistanceCm: Int) {
    UHD(3840, 2160, 40),
    QHD(2560, 1440, 30),
    FHD(1920, 1080, 20),

    /** Not in the spec: for the emulator, whose virtual-scene camera offers none of the others. */
    HD(1280, 720, 13),
    ;

    override fun toString() = "${width}x$height"

    companion object {
        /** The streams of [sizes] (width to height, what the camera offers as YUV_420_888), largest first. */
        fun offered(sizes: Collection<Pair<Int, Int>>): List<AppStream> = entries.filter { (it.width to it.height) in sizes }
    }
}

/**
 * How the app stream's pixels relate to ARCore's CPU image of the same capture. Both are of the whole sensor width;
 * a stream of another aspect ratio is cut from the middle of the frame (Camera2 crops the active array to an
 * output's aspect ratio about its centre). With the 1280x720 CPU image and a 16:9 stream it is a plain scale by the
 * width ratio (spec 5.2), which assumes the two share a field of view (P2 checked it on the Memor 35).
 */
data class StreamGeometry(val streamWidth: Int, val streamHeight: Int, val cpuWidth: Int, val cpuHeight: Int) {
    /** Stream pixels per CPU-image pixel */
    val scale: Double get() = streamWidth.toDouble() / cpuWidth

    /** CPU-image rows above the stream's first row (0 for equal aspect ratios) */
    private val cpuTop: Double get() = (cpuHeight - streamHeight / scale) / 2

    /** The stream's intrinsics from the CPU image's ([fx], [fy] focal length, [cx], [cy] principal point, CPU pixels). */
    fun intrinsics(fx: Double, fy: Double, cx: Double, cy: Double) =
        Intrinsics(fx * scale, fy * scale, cx * scale, (cy - cpuTop) * scale, streamWidth, streamHeight)

    /** A point in normalized coordinates of the stream, in normalized coordinates of the CPU image (ARCore's IMAGE_NORMALIZED). */
    fun cpuU(u: Double): Double = u

    fun cpuV(v: Double): Double = (cpuTop + v * streamHeight / scale) / cpuHeight
}

fun ArPose.toPose() = Pose(
    Vec3(tx().toDouble(), ty().toDouble(), tz().toDouble()),
    Quat(qx().toDouble(), qy().toDouble(), qz().toDouble(), qw().toDouble()),
)

fun Pose.toArPose() = ArPose(
    floatArrayOf(t.x.toFloat(), t.y.toFloat(), t.z.toFloat()),
    floatArrayOf(q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), q.w.toFloat()),
)

fun ArTracking.toTracking() = when (this) {
    ArTracking.TRACKING -> Tracking.TRACKING
    ArTracking.PAUSED -> Tracking.PAUSED
    ArTracking.STOPPED -> Tracking.STOPPED
}

/**
 * The [PoseRecord] of one ARCore frame (spec 5.2), written on the GL thread after `Session.update()`: [timestampNs] is
 * `Frame.getAndroidCameraTimestamp()`, the timestamp of the same capture's image on the app stream; [camera] is
 * `Camera.getPose()`; [anchor] the section anchor's pose and tracking state while one is held; the intrinsics are the
 * CPU image's ([focal], [principal], CPU pixels) scaled to the stream by [geometry]; [exposureNs] from the capture's
 * metadata, -1 when unknown.
 */
fun poseRecordOf(
    timestampNs: Long,
    camera: Pose,
    frameTracking: Tracking,
    anchor: Pair<Pose, Tracking>?,
    focal: FloatArray,
    principal: FloatArray,
    geometry: StreamGeometry,
    exposureNs: Long,
) = PoseRecord(
    timestampNs = timestampNs,
    camera = camera,
    anchor = anchor?.first,
    frameTracking = frameTracking,
    anchorTracking = anchor?.second,
    intrinsics = geometry.intrinsics(focal[0].toDouble(), focal[1].toDouble(), principal[0].toDouble(), principal[1].toDouble()),
    exposureNs = exposureNs,
)
