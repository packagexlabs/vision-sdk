package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.Quat
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.ar.core.Pose as ArPose
import com.google.ar.core.TrackingState as ArTracking

class PoseRecordsTest {
    @Test fun theLargestOfferedStreamFirst() {
        assertEquals(listOf(AppStream.UHD, AppStream.FHD), AppStream.offered(listOf(1920 to 1080, 640 to 480, 3840 to 2160)))
        assertEquals(listOf(AppStream.UHD, AppStream.QHD, AppStream.FHD, AppStream.HD), AppStream.offered(listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)))
        assertEquals(listOf(AppStream.HD), AppStream.offered(listOf(1280 to 720, 640 to 480)))   // the emulator's virtual scene
    }

    @Test fun theCpuImageIs720pElseAnother16by9ElseTheSmallest() {
        assertEquals(1280 to 720, cpuImageSize(listOf(640 to 480, 1920 to 1080, 1280 to 720)))
        assertEquals(1920 to 1080, cpuImageSize(listOf(640 to 480, 1920 to 1080)))   // no 720p at 30 fps: not the 4:3 band
        assertEquals(640 to 360, cpuImageSize(listOf(1920 to 1080, 640 to 480, 640 to 360)))
        assertEquals(640 to 480, cpuImageSize(listOf(1600 to 1200, 640 to 480)))      // no 16:9 at all: the smallest
        assertNull(cpuImageSize(emptyList()))
    }

    @Test fun intrinsicsScaleByTheWidthRatio() {
        // ARCore's 1280x720 CPU image
        assertEquals(Intrinsics(3000.0, 3000.0, 1920.0, 1080.0, 3840, 2160), StreamGeometry(3840, 2160, 1280, 720).intrinsics(1000.0, 1000.0, 640.0, 360.0))
        assertEquals(Intrinsics(2000.0, 2000.0, 1280.0, 720.0, 2560, 1440), StreamGeometry(2560, 1440, 1280, 720).intrinsics(1000.0, 1000.0, 640.0, 360.0))
        assertEquals(Intrinsics(1500.0, 1500.0, 960.0, 540.0, 1920, 1080), StreamGeometry(1920, 1080, 1280, 720).intrinsics(1000.0, 1000.0, 640.0, 360.0))
        assertEquals(Intrinsics(1000.0, 1000.0, 640.0, 360.0, 1280, 720), StreamGeometry(1280, 720, 1280, 720).intrinsics(1000.0, 1000.0, 640.0, 360.0))
        val g = StreamGeometry(3840, 2160, 1280, 720)
        assertEquals(0.25, g.cpuU(0.25), 1e-12); assertEquals(0.75, g.cpuV(0.75), 1e-12)
        assertTrue(g.sameAspect); assertTrue(StreamGeometry(2560, 1440, 1920, 1080).sameAspect)
    }

    // A 4:3 CPU image (an emulator's) with a 16:9 stream: the stream is the middle band of the same width.
    @Test fun aStreamOfAnotherAspectIsTheMiddleBand() {
        val g = StreamGeometry(1280, 720, 640, 480)
        assertFalse(g.sameAspect)
        val cpu = Intrinsics(500.0, 500.0, 320.0, 240.0, 640, 480)
        val stream = g.intrinsics(cpu.fx, cpu.fy, cpu.cx, cpu.cy)
        assertEquals(Intrinsics(1000.0, 1000.0, 640.0, 360.0, 1280, 720), stream)
        // One point seen through both: the stream's normalized coordinates map onto the CPU image's
        val p = Vec3(0.05, -0.08, -0.6)
        val (su, sv) = stream.project(p)!!
        val (cu, cv) = cpu.project(p)!!
        assertEquals(cu / cpu.width, g.cpuU(su / stream.width), 1e-9)
        assertEquals(cv / cpu.height, g.cpuV(sv / stream.height), 1e-9)
    }

    @Test fun aPoseRecordCarriesTheFrame() {
        val camera = Pose(Vec3(0.1, 0.2, 0.3), Quat.IDENTITY)
        val anchor = Pose(Vec3(0.0, 0.0, -0.4), Quat.IDENTITY)
        val rec = poseRecordOf(
            timestampNs = 123L, camera = camera, frameTracking = Tracking.TRACKING, anchor = anchor to Tracking.PAUSED,
            focal = floatArrayOf(965f, 966f), principal = floatArrayOf(641f, 359f), geometry = StreamGeometry(3840, 2160, 1280, 720), exposureNs = 33_000_000L,
        )
        assertEquals(123L, rec.timestampNs); assertEquals(camera, rec.camera); assertEquals(anchor, rec.anchor)
        assertEquals(Tracking.TRACKING, rec.frameTracking); assertEquals(Tracking.PAUSED, rec.anchorTracking)
        assertEquals(Intrinsics(2895.0, 2898.0, 1923.0, 1077.0, 3840, 2160), rec.intrinsics)
        assertEquals(33_000_000L, rec.exposureNs)
        val none = poseRecordOf(124L, camera, Tracking.PAUSED, null, floatArrayOf(965f, 966f), floatArrayOf(641f, 359f), StreamGeometry(3840, 2160, 1280, 720), -1L)
        assertNull(none.anchor); assertNull(none.anchorTracking)
    }

    // ARCore's quaternion order is x, y, z, w, as the counter's: both turn a point the same way.
    @Test fun arCorePosesConvertBothWays() {
        val ar = ArPose(floatArrayOf(1f, 2f, 3f), floatArrayOf(0f, 0.38268343f, 0f, 0.9238795f))   // 45 degrees about +Y
        val pose = ar.toPose()
        val p = floatArrayOf(0.3f, -0.2f, -0.5f)
        val expected = ar.transformPoint(p)
        val got = pose.apply(Vec3(p[0].toDouble(), p[1].toDouble(), p[2].toDouble()))
        assertArrayEquals(expected, floatArrayOf(got.x.toFloat(), got.y.toFloat(), got.z.toFloat()), 1e-5f)
        val back = pose.toArPose()
        assertArrayEquals(ar.translation, back.translation, 1e-6f)
        assertArrayEquals(ar.rotationQuaternion, back.rotationQuaternion, 1e-6f)
    }

    @Test fun trackingStatesMapOneToOne() {
        assertEquals(listOf(Tracking.TRACKING, Tracking.PAUSED, Tracking.STOPPED), listOf(ArTracking.TRACKING, ArTracking.PAUSED, ArTracking.STOPPED).map { it.toTracking() })
    }

    @Test fun exposureComesFromTheCaptureElseTheNewest() {
        val ring = CaptureMetaRing(capacity = 2)
        assertEquals(-1L, ring.exposureAt(100L))
        ring.add(meta(100L, 10_000_000L)); ring.add(meta(133L, 20_000_000L))
        assertEquals(10_000_000L, ring.exposureAt(100L))
        assertEquals(20_000_000L, ring.exposureAt(166L))   // not come yet: the newest
        ring.add(meta(166L, 30_000_000L))                   // 100 drops out
        assertNull(ring.at(100L)); assertEquals(30_000_000L, ring.exposureAt(166L))
    }

    private fun meta(ts: Long, exposureNs: Long) = CaptureMeta(ts, exposureNs, 1550, 32_500_000L, 0, "[30, 30]", 2)
}
