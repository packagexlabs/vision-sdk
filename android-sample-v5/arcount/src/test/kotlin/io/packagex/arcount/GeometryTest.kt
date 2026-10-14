package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class GeometryTest {
    private fun near(expected: Vec3, actual: Vec3, eps: Double = 1e-9) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
        assertEquals(expected.z, actual.z, eps)
    }

    @Test
    fun aQuarterTurnAboutYTakesXToMinusZ() {
        val q = Quat.axisAngle(Vec3(0.0, 1.0, 0.0), PI / 2)
        near(Vec3(0.0, 0.0, -1.0), q.rotate(Vec3(1.0, 0.0, 0.0)))
    }

    @Test
    fun aProductTurnsByTheRightThenTheLeft() {
        val a = Quat.axisAngle(Vec3(0.0, 0.0, 1.0), 0.3)
        val b = Quat.axisAngle(Vec3(1.0, 1.0, 0.0), 1.1)
        val v = Vec3(0.2, -0.5, 0.9)
        near(a.rotate(b.rotate(v)), (a * b).rotate(v))
    }

    @Test
    fun aPoseAndItsInverseUndoEachOther() {
        val pose = Pose(Vec3(0.1, -0.4, 2.0), Quat.axisAngle(Vec3(0.3, 1.0, -0.2), 0.7))
        val p = Vec3(-1.0, 0.5, 0.25)
        near(p, pose.inverse().apply(pose.apply(p)))
        near(p, (pose.inverse() * pose).apply(p))
    }

    @Test
    fun aComposedPoseAppliesTheRightOneFirst() {
        val a = Pose(Vec3(1.0, 0.0, 0.0), Quat.axisAngle(Vec3(0.0, 1.0, 0.0), 0.5))
        val b = Pose(Vec3(0.0, 2.0, 0.0), Quat.axisAngle(Vec3(1.0, 0.0, 0.0), -0.2))
        val p = Vec3(0.3, 0.3, 0.3)
        near(a.apply(b.apply(p)), (a * b).apply(p))
    }

    @Test
    fun theCentrePixelLooksStraightAheadAndImageDownIsCameraDown() {
        val k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)
        near(Vec3(0.0, 0.0, -1.0), k.rayInCamera(1920.0, 1080.0))
        val below = k.rayInCamera(1920.0, 1500.0)
        assertEquals(true, below.y < 0)
    }

    @Test
    fun aPixelsRayProjectsBackToThatPixel() {
        val k = Intrinsics(2896.0, 2900.0, 1915.0, 1083.0, 3840, 2160)
        val (u, v) = k.project(k.rayInCamera(300.0, 1800.0) * 0.4)!!
        assertEquals(300.0, u, 1e-6)
        assertEquals(1800.0, v, 1e-6)
    }

    @Test
    fun aPointBehindTheCameraIsNotSeen() {
        val k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)
        assertNull(k.project(Vec3(0.0, 0.0, 0.5)))
    }
}
