package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drift plan P2c: the engine reads at full rate while a pin needs rays, at most 2 s per pin ([ReadBoost]) */
class ReadBoostTest {
    private val ms = 1_000_000L
    private val k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)
    private val keys = setOf("A")

    private fun frame(t: Long, camera: Vec3 = Vec3.ZERO) = PoseRecord(t, Pose(camera, Quat.IDENTITY), null, Tracking.TRACKING, null, k)

    /** A pin of [code] at [at], verified when [verified] (its rays over ±5 cm of baseline) */
    private fun pin(at: Vec3 = Vec3(0.0, 0.0, -0.4), code: String = "A", verified: Boolean = false): Pin {
        val p = Pin(1, code, Pose(at, Quat.IDENTITY))
        if (verified) {
            p.est.start(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.4, PIN_PRIOR_HITS_M, k.fx, PriorSource.HITS)
            for (i in 0..10) {
                val o = Vec3(-0.05 + 0.01 * i, 0.0, 0.0) - at
                val d = (Vec3.ZERO - o).unit()
                p.est.add(o.x, o.y, o.z, d.x, d.y, d.z)
            }
            assertTrue(p.verified)
        }
        return p
    }

    @Test fun anUnverifiedListedPinInViewBoostsForTwoSecondsAtMost() {
        val boost = ReadBoost()
        val p = pin()
        var t = 0L
        var frames = 0
        while (boost.frame(frame(t), listOf(p), keys, true)) {
            frames++
            t += 33 * ms
        }
        assertEquals(PIN_BOOST_NS.toDouble(), p.boostNs.toDouble(), 33.0 * ms)
        assertEquals(62, frames) // 61 frames of 33 ms after the first, which counts none
        assertFalse(boost.on)
        assertEquals(frames, boost.forPins)
        assertEquals(0, boost.forReads)
        // A second unverified pin has its own 2 s
        assertTrue(boost.frame(frame(t), listOf(p, pin()), keys, true))
    }

    @Test fun aVerifiedUnlistedOrOffCentrePinDoesNotBoost() {
        val boost = ReadBoost()
        assertFalse(boost.frame(frame(0), listOf(pin(verified = true)), keys, true))
        assertFalse(boost.frame(frame(33 * ms), listOf(pin(code = "B")), keys, true))
        // u = 1920 + 2896 x / 0.4: the image's inner 80 % ends at x = 0.212 m
        assertFalse(boost.frame(frame(66 * ms), listOf(pin(Vec3(0.22, 0.0, -0.4))), keys, true))
        assertTrue(boost.frame(frame(99 * ms), listOf(pin(Vec3(0.2, 0.0, -0.4))), keys, true))
        // Beyond 1.5 m its depth can never verify
        assertFalse(boost.frame(frame(132 * ms), listOf(pin(Vec3(0.0, 0.0, -1.6))), keys, true))
    }

    @Test fun anUnclaimedListedReadBoostsForASecondAfterItsCapture() {
        val boost = ReadBoost()
        assertFalse(boost.frame(frame(0), emptyList(), keys, true))
        boost.unclaimed(100 * ms)
        assertTrue(boost.frame(frame(250 * ms), emptyList(), keys, true))
        assertTrue(boost.frame(frame(1_099 * ms), emptyList(), keys, true))
        assertFalse(boost.frame(frame(1_100 * ms), emptyList(), keys, true))
        assertEquals(2, boost.forReads)
        assertEquals(4, boost.frames)
        boost.resetCounts()
        assertEquals(0, boost.frames)
    }

    @Test fun offUnderIosRulesOrWithTheBoostSwitchedOffAndWhileNotTracking() {
        val boost = ReadBoost()
        val p = pin()
        boost.unclaimed(0)
        assertFalse(boost.frame(frame(10 * ms), listOf(p), keys, false))
        assertEquals(0L, p.boostNs)
        assertTrue(boost.frame(frame(43 * ms), listOf(p), keys, true))
        boost.paused()
        assertFalse(boost.on)
        // New Scan: the reads before it no longer count
        boost.clear()
        assertFalse(boost.frame(frame(76 * ms), emptyList(), keys, true))
    }

    @Test fun aPauseBetweenFramesCountsAsOneFrameAtMost() {
        val boost = ReadBoost()
        val p = pin()
        boost.frame(frame(0), listOf(p), keys, true)
        boost.frame(frame(5_000 * ms), listOf(p), keys, true)
        assertEquals(100 * ms, p.boostNs)
    }
}
