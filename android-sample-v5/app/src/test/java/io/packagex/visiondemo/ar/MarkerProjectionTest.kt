package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Marker
import io.packagex.arcount.Pose
import io.packagex.arcount.Quat
import io.packagex.arcount.UnitState
import io.packagex.arcount.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class MarkerProjectionTest {
    private val intr = Intrinsics(2880.0, 2900.0, 1920.0, 1080.0, 3840, 2160)

    @Test fun aPointOfTheAnchorOneMetreAheadLandsWhereThePinholePutsIt() {
        val anchor = Pose(Vec3(0.0, 0.0, -1.0), Quat.IDENTITY) // 1 m ahead of a camera at the origin (-Z forward)
        val p = projectMarker(Vec3(0.1, 0.05, 0.0), 0.04, Pose.IDENTITY, anchor, intr)!!
        assertEquals((1920 + 288.0) / 3840, p.u, 1e-9) // x right: +fx * 0.1 / 1
        assertEquals((1080 - 145.0) / 2160, p.v, 1e-9) // y up is image up
        assertEquals(2880 * 0.04 / 3840, p.sizeU, 1e-9) // 4 cm at 1 m: 115.2 px
    }

    @Test fun theCameraPoseOfTheFrameDrawnMovesTheMarker() {
        // The camera turned 90 degrees left (about +Y) now faces -X; the anchor 0.5 m along -X is straight ahead
        val camera = Pose(Vec3(0.0, 0.0, 0.0), Quat.axisAngle(Vec3(0.0, 1.0, 0.0), PI / 2))
        val anchor = Pose(Vec3(-0.5, 0.0, 0.0), Quat.axisAngle(Vec3(0.0, 1.0, 0.0), PI / 2))
        val p = projectMarker(Vec3.ZERO, 0.05, camera, anchor, intr)!!
        assertEquals(0.5, p.u, 1e-9); assertEquals(0.5, p.v, 1e-9)
        assertEquals(2880 * 0.05 / 0.5 / 3840, p.sizeU, 1e-9)
        // The same point from a camera 10 cm further right along the row: seen to the left
        val moved = projectMarker(Vec3.ZERO, 0.05, Pose(Vec3(0.0, 0.0, -0.1), camera.q), anchor, intr)!!
        assertEquals((1920 - 2880 * 0.1 / 0.5) / 3840, moved.u, 1e-9)
    }

    @Test fun aPointBehindTheCameraIsNotDrawn() {
        val behind = Pose(Vec3(0.0, 0.0, 1.0), Quat.IDENTITY)
        assertNull(projectMarker(Vec3.ZERO, 0.04, Pose.IDENTITY, behind, intr))
        assertNull(projectMarker(Vec3(0.0, 0.0, 1.0), 0.04, Pose.IDENTITY, Pose.IDENTITY, intr))
    }

    @Test fun aMarkerWithoutAnAnchorPointOrWithoutAnAnchorKeepsItsOwnPlace() {
        val own = Marker(1, UnitState.COUNTED, 0.3, 0.6, 0.02)
        assertEquals(MarkerPlace(0.3, 0.6, 0.02), placeMarker(own, Pose.IDENTITY, Pose.IDENTITY, intr))
        val withPoint = own.copy(anchorPoint = Vec3(0.0, 0.0, -1.0), sizeM = 0.04)
        assertEquals(MarkerPlace(0.3, 0.6, 0.02), placeMarker(withPoint, Pose.IDENTITY, null, intr))
        assertEquals(MarkerPlace(0.5, 0.5, 2880 * 0.04 / 3840), placeMarker(withPoint, Pose.IDENTITY, Pose.IDENTITY, intr))
        assertNull(placeMarker(withPoint.copy(anchorPoint = Vec3(0.0, 0.0, 1.0)), Pose.IDENTITY, Pose.IDENTITY, intr))
    }
}
