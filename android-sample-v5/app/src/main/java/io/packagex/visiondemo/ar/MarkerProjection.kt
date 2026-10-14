package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Marker
import io.packagex.arcount.Pose
import io.packagex.arcount.Vec3

/** Where a marker is drawn in one frame: normalized (0..1) in the unrotated stream image, and its size along u */
data class MarkerPlace(val u: Double, val v: Double, val sizeU: Double)

/**
 * The marker at [point] of the section anchor's frame, [sizeM] metres across, in the frame drawn now (spec 5.5, no
 * frame of lag): the point goes to the camera's frame with T_ac(now) = [camera]⁻¹ · [anchor] (both world poses of this
 * frame) and through the stream's [intrinsics]; its size is [sizeM] at that depth. Null when the point is not in
 * front of the camera: it is not drawn.
 */
fun projectMarker(point: Vec3, sizeM: Double, camera: Pose, anchor: Pose, intrinsics: Intrinsics): MarkerPlace? {
    val inCamera = camera.inverse().apply(anchor.apply(point))
    val (x, y) = intrinsics.project(inCamera) ?: return null
    val sizePx = intrinsics.fx * sizeM / -inCamera.z
    return MarkerPlace(x / intrinsics.width, y / intrinsics.height, sizePx / intrinsics.width)
}

/**
 * Where [marker] is drawn this frame: projected with this frame's [camera] and [anchor] when it has an anchor point and
 * an anchor is held, else where the counter put it ([Marker.u], [Marker.v], [Marker.sizeU]); null when it is not drawn.
 */
fun placeMarker(marker: Marker, camera: Pose, anchor: Pose?, intrinsics: Intrinsics): MarkerPlace? {
    val point = marker.anchorPoint
    if (point == null || anchor == null) return MarkerPlace(marker.u, marker.v, marker.sizeU)
    return projectMarker(point, marker.sizeM, camera, anchor, intrinsics)
}
