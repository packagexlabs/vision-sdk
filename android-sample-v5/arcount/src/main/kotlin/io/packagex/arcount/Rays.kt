package io.packagex.arcount

/** A ray from [origin] along the unit direction [dir], in one frame (the section anchor's, or the world's) */
data class Ray(val origin: Vec3, val dir: Vec3) {
    /** The point [s] metres along the ray */
    fun at(s: Double) = origin + dir * s

    /** How far point [p] lies from the ray's line */
    fun distanceTo(p: Vec3) = ((p - origin) cross dir).norm()
}

/**
 * The camera's pose in the anchor's frame, T_ac = A⁻¹ · C (spec 5.1): every prediction, warp and jump test uses it.
 * Without an anchor the frame is the world's (identity anchor).
 */
fun cameraInAnchor(camera: Pose, anchor: Pose?): Pose = if (anchor == null) camera else anchor.inverse() * camera

/** T_ac of this record, in the frame of [anchor] (by default the record's own) */
fun PoseRecord.cameraInAnchor(anchor: Pose? = this.anchor): Pose = cameraInAnchor(camera, anchor)

/** The ray through pixel ([u], [v]) of this record's image: origin T_ac.t, direction T_ac.rotate(ray in the camera) */
fun PoseRecord.ray(u: Double, v: Double, anchor: Pose? = this.anchor): Ray {
    val tac = cameraInAnchor(anchor)
    return Ray(tac.t, tac.rotate(intrinsics.rayInCamera(u, v)))
}

/** This read's centre ray, in the frame of [anchor] (by default the record's own) */
fun Read.ray(record: PoseRecord, anchor: Pose? = record.anchor): Ray = record.ray(centreU, centreV, anchor)
