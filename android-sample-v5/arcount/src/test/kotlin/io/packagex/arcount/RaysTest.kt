package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Test

class RaysTest {
    private val k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)
    private val anchor = Pose(Vec3(0.3, -0.2, -0.5), Quat.axisAngle(Vec3(0.0, 1.0, 0.0), 0.6))
    private val camera = Pose(Vec3(0.1, 0.05, 0.2), Quat.axisAngle(Vec3(0.2, 1.0, 0.1), -0.3))

    private fun near(expected: Vec3, actual: Vec3, eps: Double = 1e-9) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
        assertEquals(expected.z, actual.z, eps)
    }

    /** A read centred on the pixel where [camera] sees world point [p] */
    private fun readOf(p: Vec3): Read {
        val (u, v) = k.project(camera.inverse().apply(p))!!
        return Read(0, "x", listOf(u - 5, v - 5, u + 5, v - 5, u + 5, v + 5, u - 5, v + 5), 1)
    }

    @Test
    fun theCameraInTheAnchorFrameIsTheAnchorsInverseTimesTheCamera() {
        val tac = cameraInAnchor(camera, anchor)
        val p = Vec3(0.2, -0.1, -0.4)
        near(anchor.inverse().apply(camera.apply(p)), tac.apply(p))
    }

    @Test
    fun aReadsRayInTheAnchorFrameStartsAtTheCameraAndPassesThroughThePointItSees() {
        val world = camera.apply(Vec3(0.04, -0.03, -0.35))
        val record = PoseRecord(0, camera, anchor, Tracking.TRACKING, Tracking.TRACKING, k)
        val ray = readOf(world).ray(record)
        near(anchor.inverse().apply(camera.t), ray.origin)
        assertEquals(1.0, ray.dir.norm(), 1e-12)
        assertEquals(0.0, ray.distanceTo(anchor.inverse().apply(world)), 1e-9)
    }

    @Test
    fun beforeAnAnchorExistsRaysAreInTheWorldFrame() {
        val world = camera.apply(Vec3(-0.05, 0.02, -0.5))
        val record = PoseRecord(0, camera, null, Tracking.TRACKING, null, k)
        val ray = readOf(world).ray(record)
        near(camera.t, ray.origin)
        assertEquals(0.0, ray.distanceTo(world), 1e-9)
    }

    @Test
    fun aPointAlongARayIsAtThatDistanceFromItsOrigin() {
        val ray = Ray(Vec3(1.0, 2.0, 3.0), Vec3(0.0, 0.0, -1.0))
        near(Vec3(1.0, 2.0, 2.6), ray.at(0.4))
    }
}
