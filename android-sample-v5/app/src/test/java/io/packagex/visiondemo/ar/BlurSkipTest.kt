package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurSkipTest {
    private val ms = 1_000_000L
    private val intr = Intrinsics(2880.0, 2880.0, 1920.0, 1080.0, 3840, 2160)

    private fun rec(ts: Long, pose: Pose, tracking: Tracking = Tracking.TRACKING) =
        PoseRecord(ts, pose, null, tracking, null, intr, 33 * ms)

    @Test fun aTurnOfOneRadianASecondAt33MsBlursByRateTimesExposureTimesF() {
        val prev = rec(0, Pose.IDENTITY)
        val cur = rec(100 * ms, Pose(Vec3.ZERO, Quat.axisAngle(Vec3(0.0, 1.0, 0.0), 0.1)))
        assertEquals(1.0 * 0.033 * 2880, predictedBlurPx(prev, cur, 33 * ms)!!, 1e-6) // 95 px
    }

    @Test fun aSlideBlursBySpeedTimesExposureTimesFOverDepth() {
        val prev = rec(0, Pose.IDENTITY)
        val cur = rec(100 * ms, Pose(Vec3(0.005, 0.0, 0.0), Quat.IDENTITY)) // 5 cm/s
        assertEquals(0.05 * 0.033 * 2880 / 0.4, predictedBlurPx(prev, cur, 33 * ms)!!, 1e-6) // 11.9 px
        assertEquals(0.05 * 0.033 * 2880 / 0.2, predictedBlurPx(prev, cur, 33 * ms, z = 0.2)!!, 1e-6)
    }

    @Test fun noExposureNoTimeOrNoTrackingTellsNothing() {
        val prev = rec(0, Pose.IDENTITY)
        assertNull(predictedBlurPx(prev, rec(33 * ms, Pose.IDENTITY), -1))
        assertNull(predictedBlurPx(prev, rec(0, Pose.IDENTITY), 33 * ms))
        assertNull(predictedBlurPx(prev, rec(33 * ms, Pose.IDENTITY, Tracking.PAUSED), 33 * ms))
    }

    @Test fun skippedAbove24PxAt4KScaledWithTheStream() {
        assertFalse(skipForBlur(null, 3840))
        assertFalse(skipForBlur(24.0, 3840))
        assertTrue(skipForBlur(24.1, 3840))
        assertTrue(skipForBlur(12.1, 1920)); assertFalse(skipForBlur(11.9, 1920))
    }

    @Test fun theNewestTwoPosesPredictAndAStaleOneDoesNot() {
        val poses = LatestPoses()
        assertNull(poses.blurPx(0, 33 * ms))
        poses.add(rec(0, Pose.IDENTITY))
        assertNull(poses.blurPx(33 * ms, 33 * ms)) // one pose: no motion yet
        poses.add(rec(33 * ms, Pose(Vec3.ZERO, Quat.axisAngle(Vec3(1.0, 0.0, 0.0), 0.033))))
        assertEquals(95.04, poses.blurPx(66 * ms, 33 * ms)!!, 1e-6) // the image after the newest pose
        assertNull(poses.blurPx(200 * ms, 33 * ms)) // GL stalled: the poses are 167 ms old
        poses.add(rec(66 * ms, Pose(Vec3.ZERO, Quat.axisAngle(Vec3(1.0, 0.0, 0.0), 0.033))))
        assertEquals(0.0, poses.blurPx(66 * ms, 33 * ms)!!, 1e-9) // the newest two: still
        poses.clear()
        assertNull(poses.blurPx(66 * ms, 33 * ms))
    }
}
