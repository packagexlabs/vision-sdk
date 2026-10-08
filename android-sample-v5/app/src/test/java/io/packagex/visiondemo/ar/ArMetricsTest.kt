package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drift plan's Phase 0 measurements (M1-M3, M5, M6), off the device. */
class ArMetricsTest {
    private val ms = 1_000_000L
    private val k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)

    private fun read(text: String, tsMs: Long, u: Double, engineId: Int = 1, symbology: String? = "ean13") =
        Read(tsMs * ms, text, listOf(u - 100, 1030.0, u + 100, 1030.0, u + 100, 1130.0, u - 100, 1130.0), engineId, symbology)

    // --- the building blocks ---

    @Test fun quantilesAreNearestRankAndTheArrayGrows() {
        val s = Samples(capacity = 2)
        for (v in listOf(5.0, 1.0, 4.0, 2.0, 3.0)) s.add(v)
        assertEquals(5, s.size)
        val q = s.quantiles(0.5, 0.9, 1.0)
        assertEquals(3.0, q[0], 0.0); assertEquals(5.0, q[1], 0.0); assertEquals(5.0, q[2], 0.0)
        assertEquals(0.4, s.shareAbove(3.0), 1e-9)
        s.clear()
        assertTrue(s.quantiles(0.5)[0].isNaN()); assertTrue(s.shareAbove(1.0).isNaN())
    }

    @Test fun aHistogramGivesQuantilesToTheBinBelowWithoutKeepingTheSamples() {
        val h = Histogram()
        assertTrue(h.quantiles(0.5)[0].isNaN())
        for (v in listOf(5.0, 1.2, 4.9, 2.0, 3.0)) h.add(v)
        h.add(Double.NaN)   // not a sample
        assertEquals(5, h.size)
        val q = h.quantiles(0.5, 0.9, 1.0)
        assertEquals(3.0, q[0], 0.0); assertEquals(5.0, q[1], 0.0); assertEquals(5.0, q[2], 0.0)
        assertEquals(1.0, h.quantiles(0.0)[0], 0.0)   // 1.2, to the pixel below
        h.add(Double.POSITIVE_INFINITY); h.add(2500.0)
        assertEquals("2000+", h.format(h.quantiles(1.0)[0])); assertEquals("3", h.format(q[0]))
        val tenMs = Histogram(width = 10.0, bins = 1000)
        tenMs.add(604.0)
        assertEquals(600.0, tenMs.quantiles(0.5)[0], 0.0)
    }

    @Test fun binsTravelSpeedRowsAndAges() {
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(0.0, 0.02, 0.07, 0.29, 0.5).map { binOf(it, TRAVEL_EDGES_M) })
        assertEquals(listOf(0, 1, 2), listOf(13.9, 14.0, 31.0).map { binOf(it, OMEGA_EDGES_DPS) })
        assertEquals(listOf(0, 1, 2, 2), listOf(0.0, 1080.0, 1500.0, 2160.0).map { rowBand(it, 2160) })
        assertEquals(listOf(0, 1, 2, 3), listOf(33.0, 100.0, 200.0, 499.0).map { binOf(it, AGE_EDGES_MS) })
    }

    @Test fun theRotationSpeedIsTheWholeTurnBetweenTwoRecords() {
        fun rec(tsMs: Long, deg: Double) =
            PoseRecord(tsMs * ms, Pose(Vec3.ZERO, Quat.axisAngle(Vec3(0.3, 1.0, 0.2), Math.toRadians(deg))), null, Tracking.TRACKING, null, k)
        assertEquals(30.0, omegaDps(rec(0, 10.0), rec(100, 13.0)), 1e-6)
        assertTrue(omegaDps(rec(100, 0.0), rec(100, 5.0)).isNaN())
    }

    @Test fun aPointIsMeasuredInTheImageOfItsCamera() {
        val camera = Pose(Vec3(0.1, 0.0, 0.0), Quat.IDENTITY)
        // 0.40 m in front, 1 cm right of the camera: 72.4 px right of the principal point
        val p = Vec3(0.11, 0.0, -0.4)
        assertEquals(72.4, registrationPx(p, 1920.0, 1080.0, camera, k), 1e-6)
        assertEquals(0.4, depthM(p, camera), 1e-12)
        assertEquals(Double.POSITIVE_INFINITY, registrationPx(Vec3(0.1, 0.0, 0.5), 1920.0, 1080.0, camera, k), 0.0)
    }

    // --- M1: pins as drawn ---

    @Test fun aPinIsFoundAsDrawnOnItsFrameAndNotOnAFrameItWasNotDrawnOn() {
        val d = DrawnPins(frames = 4)
        d.begin(1); d.add(7, 0.0, 0.0, -0.5)
        d.begin(2); d.add(7, 0.01, 0.0, -0.5); d.add(8, 0.2, 0.0, -0.5)
        assertEquals(Vec3(0.0, 0.0, -0.5), d.at(1, 7))
        assertEquals(Vec3(0.01, 0.0, -0.5), d.at(2, 7))
        assertNull(d.at(1, 8))   // born after frame 1
        assertNull(d.at(3, 7))   // no such frame
        assertTrue(d.has(2)); assertFalse(d.has(3))
        d.begin(2); d.add(8, 0.3, 0.0, -0.5)   // frame 2 drawn again: it starts over
        assertNull(d.at(2, 7)); assertEquals(Vec3(0.3, 0.0, -0.5), d.at(2, 8))
        for (ts in 3L..6L) d.begin(ts)
        assertNull(d.at(1, 7)); assertFalse(d.has(1))   // only the newest four frames are kept
        assertTrue(d.has(6))   // drawn, with no pin
    }

    @Test fun aFrameHoldsAnyNumberOfPins() {
        val d = DrawnPins(frames = 2)
        d.begin(1)
        for (id in 0 until 40) d.add(id, id.toDouble(), 0.0, 0.0)
        assertEquals(Vec3(39.0, 0.0, 0.0), d.at(1, 39))
    }

    @Test fun claimsAreBinnedByTravelRowsAndRepeatedCodes() {
        val m = PinMetrics()
        m.born(1, 0, Vec3.ZERO)
        m.batch(listOf("B", "A", "B"))
        assertTrue(m.isRepeated("B")); assertFalse(m.isRepeated("A"))
        val near = m.claim(1, "A", 300 * ms, Vec3(0.01, 0.0, 0.0), 12.0, 0.4, 5.0, 1)
        assertEquals(0.01, near.travelM, 1e-12); assertEquals(300 * ms, near.gapNs); assertFalse(near.reacquired); assertFalse(near.repeated)
        val far = m.claim(1, "A", 600 * ms, Vec3(0.06, 0.0, 0.0), 300.0, 0.4, 40.0, 2)
        assertEquals(0.06, far.travelM, 1e-12)
        m.claim(1, "A", 700 * ms, Vec3(0.06, 0.0, 0.0), Double.NaN, Double.NaN, Double.NaN, 0)   // not drawn on its frame
        val line = m.summary()!!
        assertTrue(line, line.startsWith("M1 since start, 4K px median/p90: claims 2 12/300, >150 px 50.0%, 1 not drawn on their frame"))
        assertTrue(line, line.contains("; 0-2 cm 1 12/12; 5-10 cm 1 300/300; <14 °/s 1 12/12; >30 °/s 1 300/300; rows middle 1 12/12; rows bottom 1 300/300; unique 2 12/300."))
    }

    @Test fun listedReadsWithNoPinWithinOneFiftyPixelsAreMissesUsedOrNot() {
        val m = PinMetrics()
        m.unmeasured()
        assertTrue(m.summary()!!, m.summary()!!.contains("Listed reads 0, no pin within 150 px n/a"))
        m.nearest(20.0); m.nearest(151.0)
        m.unused(Double.POSITIVE_INFINITY, Unused.NOT_TRACKING)   // hidden while tracking was lost: a miss
        m.unused(150.0, Unused.STALE)
        assertTrue(
            m.summary()!!,
            m.summary()!!.contains(
                "Listed reads 4, no pin within 150 px 50.0%, of them not used by the pins 1 not tracking, 1 stale, " +
                    "0 their frame not tracking; 1 unmeasured (their frame never drawn).",
            ),
        )
    }

    // --- M2: re-acquisition ---

    /** Pin [id] on screen on every frame (33 ms) from [fromMs] to [toMs] */
    private fun PinMetrics.onScreen(id: Int, fromMs: Long, toMs: Long) {
        var t = fromMs
        while (t <= toMs) {
            inView(id, t * ms)
            t += 33
        }
    }

    @Test fun aStillPinClaimedEverySecondIsNeverReacquired() {
        // A still camera's engine at its 1000 ms refresh: claims 1000-1033 ms apart, each used 150 ms after its capture
        val m = PinMetrics()
        m.born(1, 0, Vec3.ZERO)
        var captureMs = 0L
        var claims = 0
        for (f in 1..606) {
            val ts = f * 33L
            m.inView(1, ts * ms)
            val next = captureMs + if (claims % 2 == 0) 1_000 else 1_033
            if (next + 150 <= ts) {
                val c = m.claim(1, "A", next * ms, Vec3.ZERO, 8.0, 0.4, 0.0, 1)
                assertTrue(c.gapNs >= 1_000 * ms); assertTrue(c.awayNs <= 33 * ms); assertFalse(c.reacquired)
                captureMs = next
                claims++
            }
        }
        assertEquals(19, claims)
        assertTrue(m.summary()!!, m.summary()!!.endsWith("M2 re-acquisitions 0, 0 not drawn, under 20 px after (ms) 0, 0 never, 0 settling"))
    }

    @Test fun theFirstClaimAfterThePinWasASecondOutOfViewIsAReacquisitionFollowedUntilItSettles() {
        val m = PinMetrics()
        m.born(1, 0, Vec3.ZERO)
        m.onScreen(1, 33, 891)
        assertFalse(m.claim(1, "A", 891 * ms, Vec3.ZERO, 10.0, 0.4, 0.0, 1).reacquired)
        m.onScreen(1, 2_000, 2_099)   // looked away 1.1 s; the read of 2066 ms is used on the frame of 2099 ms
        val back = m.claim(1, "A", 2_066 * ms, Vec3.ZERO, 80.0, 0.4, 0.0, 1)
        assertTrue(back.reacquired); assertEquals(1_109 * ms, back.awayNs); assertEquals(1_175 * ms, back.gapNs)
        m.onScreen(1, 2_132, 2_396)
        assertFalse(m.claim(1, "A", 2_366 * ms, Vec3.ZERO, 30.0, 0.4, 0.0, 1).reacquired)
        assertTrue(m.summary()!!, m.summary()!!.endsWith("M2 re-acquisitions 1 80/80, 0 not drawn, under 20 px after (ms) 0, 0 never, 1 settling"))
        m.onScreen(1, 2_429, 2_696)
        m.claim(1, "A", 2_666 * ms, Vec3.ZERO, 15.0, 0.4, 0.0, 1)
        assertTrue(m.summary()!!, m.summary()!!.endsWith("M2 re-acquisitions 1 80/80, 0 not drawn, under 20 px after (ms) 1 600/600, 0 never, 0 settling"))
    }

    @Test fun aPinClaimedWhileStillOutOfViewIsReacquiredThenAndNotAgainWhenItIsBack() {
        val m = PinMetrics()
        m.born(1, 0, Vec3.ZERO)
        m.onScreen(1, 33, 33)
        val off = m.claim(1, "A", 1_500 * ms, Vec3.ZERO, 400.0, 0.4, 0.0, 1)   // its pin drawn off screen, 400 px from its read
        assertTrue(off.reacquired); assertEquals(1_467 * ms, off.awayNs)
        m.onScreen(1, 1_600, 1_699)   // on screen again 100 ms after that claim
        assertFalse(m.claim(1, "A", 1_700 * ms, Vec3.ZERO, 10.0, 0.4, 0.0, 1).reacquired)
        assertTrue(m.summary()!!, m.summary()!!.endsWith("M2 re-acquisitions 1 400/400, 0 not drawn, under 20 px after (ms) 1 200/200, 0 never, 0 settling"))
    }

    @Test fun aReacquisitionThatNeverSettlesIsCountedWhenThePinGoesOrIsLostAgain() {
        val m = PinMetrics()
        m.born(1, 0, Vec3.ZERO)
        m.born(2, 0, Vec3.ZERO)
        m.born(3, 0, Vec3.ZERO)
        // Never on screen (tracking lost from their birth): each first claim after 1.5 s is a re-acquisition
        m.claim(1, "A", 1_500 * ms, Vec3.ZERO, 90.0, 0.4, 0.0, 1)
        m.claim(1, "A", 3_000 * ms, Vec3.ZERO, 5.0, 0.4, 0.0, 1)   // lost again first: that one never settled; this one did at once
        m.claim(2, "A", 1_500 * ms, Vec3.ZERO, 90.0, 0.4, 0.0, 1)
        m.removed(2)
        assertTrue(m.claim(3, "A", 1_500 * ms, Vec3.ZERO, Double.NaN, Double.NaN, 0.0, 1).reacquired)   // not drawn on its frame
        assertTrue(m.summary()!!, m.summary()!!.endsWith("M2 re-acquisitions 3 90/90, 1 not drawn, under 20 px after (ms) 1 0/0, 2 never, 0 settling"))
    }

    // --- M3: outlines ---

    @Test fun anOutlineIsMeasuredAgainstTheNextReadOfItsTrackOnThatReadsFrame() {
        val d = DrawnOutlines(frames = 4)
        val first = read("X", 0, 1000.0)
        d.begin(100 * ms, OverlayRules.IOS)
        d.add(first, OutlineShown.WHERE_READ, first.centreU, first.centreV); d.add(read("Y", 0, 3000.0, engineId = 2), OutlineShown.WHERE_READ, 3000.0, 1080.0)
        val next = read("X", 100, 1030.0)
        val s = d.sample(next)!!
        assertEquals(first, s.drawn); assertEquals(100 * ms, s.ageNs); assertEquals(30.0, s.errPx, 1e-9)
        assertNull(d.sample(read("X", 100, 1030.0, engineId = 9)))   // another track of the same text
        assertNull(d.sample(read("X", 133, 1030.0)))                  // its frame had none
        d.begin(200 * ms, OverlayRules.IOS); d.add(read("X", 200, 1000.0), OutlineShown.WHERE_READ, 1000.0, 1080.0)
        assertNull(d.sample(read("X", 200, 1000.0)))                  // drawn from itself: no prediction
        assertFalse(s.carried); assertEquals(first.centreU, s.atU, 1e-9)
        assertEquals(OverlayRules.IOS, s.rules); assertEquals(OutlineShown.WHERE_READ, s.shown)
    }

    @Test fun aCarriedOutlineIsMeasuredWhereItWasDrawnWithItsDepthAndRules() {
        val d = DrawnOutlines(frames = 4)
        val first = read("X", 0, 1000.0)
        d.begin(100 * ms, OverlayRules.ANDROID); d.add(first, OutlineShown.CARRIED, 1024.0, 1080.0, 0.31)   // drawn at its centre on this frame, not where it was read
        val s = d.sample(read("X", 100, 1030.0))!!
        assertTrue(s.carried); assertEquals(0.31, s.depthM, 1e-12); assertEquals(OverlayRules.ANDROID, s.rules); assertFalse(s.farSafe)
        assertEquals(1024.0, s.atU, 1e-9); assertEquals(6.0, s.errPx, 1e-9)
        d.begin(133 * ms, OverlayRules.ANDROID, farSafe = true); d.add(first, OutlineShown.CARRIED, 1000.0, 1080.0, Double.POSITIVE_INFINITY)   // rotation only is carried too
        val r = d.sample(read("X", 133, 1000.0))!!
        assertTrue(r.carried); assertTrue(r.farSafe)
    }

    @Test fun anOutlineNotDrawnIsStillSampledWithNoError() {
        val d = DrawnOutlines(frames = 4)
        val first = read("X", 0, 1000.0)
        d.begin(100 * ms, OverlayRules.ANDROID); d.add(first, OutlineShown.MAP_MOVED)
        val moved = d.sample(read("X", 100, 1030.0))!!
        assertEquals(OutlineShown.MAP_MOVED, moved.shown); assertFalse(moved.shown.drawn); assertFalse(moved.carried)
        assertTrue(moved.errPx.isNaN()); assertTrue(moved.atU.isNaN()); assertTrue(moved.depthM.isNaN())
        d.begin(133 * ms, OverlayRules.ANDROID); d.add(first, OutlineShown.BEHIND, depthM = 0.4)
        val behind = d.sample(read("X", 133, 1030.0))!!
        assertEquals(OutlineShown.BEHIND, behind.shown); assertTrue(behind.errPx.isNaN()); assertEquals(0.4, behind.depthM, 0.0)
    }

    @Test fun outlineErrorsAreSummedUpBySymbologyArmAndAge() {
        val e = OutlineErrors()
        assertNull(e.summary())
        val drawn = read("X", 0, 0.0)
        fun ios(ageMs: Long, err: Double) = OutlineSample(drawn, ageMs * ms, OverlayRules.IOS, OutlineShown.WHERE_READ, err)
        fun android(ageMs: Long, shown: OutlineShown, err: Double = Double.NaN, farSafe: Boolean = false) =
            OutlineSample(drawn, ageMs * ms, OverlayRules.ANDROID, shown, err, farSafe = farSafe)
        e.add("ean13", ios(100, 9.0))
        e.add("ean13", ios(120, 22.0))
        e.add("code128", ios(400, 64.0))
        assertEquals("M3 outline centre error since start, 4K px median/p90: ean13 50-150 ms 2: 9/22, code128 300-500 ms 1: 64/64", e.summary())
        // Android's: carried apart from the iOS baseline's labels, its fallback where read apart again; the far-safe flag too
        e.add("ean13", android(100, OutlineShown.CARRIED, 2.0))
        e.add("ean13", android(110, OutlineShown.WHERE_READ, 30.0))
        e.add("code128", android(400, OutlineShown.CARRIED, 8.0, farSafe = true))
        // Not drawn: counted beside the carried bin's error, never in it
        e.add("ean13", android(120, OutlineShown.MAP_MOVED))
        e.add("ean13", android(130, OutlineShown.BEHIND))
        e.add("ean13", android(200, OutlineShown.MAP_MOVED))
        assertEquals(
            "M3 outline centre error since start, 4K px median/p90: ean13 50-150 ms 2: 9/22, code128 300-500 ms 1: 64/64, " +
                "ean13 carried 50-150 ms 1: 2/2 (2 not drawn), ean13 carried 150-300 ms 0: - (1 not drawn), ean13 fallback 50-150 ms 1: 30/30, " +
                "code128 carried far-safe 300-500 ms 1: 8/8",
            e.summary(),
        )
    }

    // --- M5, M6: the GL thread and the camera thread ---

    @Test fun theGlWindowClosesAfterThreeSecondsWithQuantilesGcsAndProbes() {
        val c = FrameCosts()
        c.frame(0, 2 * ms, ms / 2, 40 * ms)
        c.diag(2 * ms)
        assertTrue(c.probeDue(0))
        c.probe(0, true, 400_000, "1280x720 format 35")
        assertFalse(c.probeDue(999 * ms)); assertTrue(c.probeDue(1_000 * ms))
        for (i in 1 until 90) c.frame(i * 33 * ms, (2 + i % 3) * ms, ms / 2, 40 * ms)
        c.diag(ms / 2)
        assertFalse(c.due(2_999 * ms)); assertTrue(c.due(3_000 * ms))
        val w = c.close(3_000 * ms, gcCount = 10)
        assertEquals(90, w.frames); assertEquals(3.0, w.seconds, 1e-9)
        assertEquals(3.0, w.glP50Ms, 1e-9); assertEquals(4.0, w.glP99Ms, 1e-9); assertEquals(0.5, w.pinMaxMs, 1e-9)
        assertEquals(40.0, w.updateP90Ms, 1e-9); assertEquals(-1L, w.gcs)
        assertEquals(1, w.probesOk); assertEquals(0.4, w.probeMeanMs, 1e-9)
        assertEquals(90, w.glMs.size); assertEquals(3.0, w.glMs[1], 1e-9)   // every frame's, in order
        assertEquals(2.5, w.diagMs, 1e-9); assertEquals(2.0, w.diagMaxMs, 1e-9)
        assertTrue(w.logLine(), w.logLine().endsWith("; measuring outside the frames 2.50 ms, max 2.00 ms on a frame"))
        assertTrue(w.logLine(), w.logLine().startsWith("GL: 90 frames in 3.0 s, thread CPU after update() p50 3.00 p99 4.00 max 4.00 ms, pins p50 0.50"))
        c.frame(3_033 * ms, ms, 0, 40 * ms)
        val next = c.close(6_000 * ms, gcCount = 13)
        assertEquals(3L, next.gcs); assertEquals(0.0, next.diagMs, 0.0)
    }

    @Test fun m6PoolsEveryFrameSinceTheStartAndNamesTheWindowsSlowestPinFrame() {
        // A window of 90 frames has its nearest-rank p99 at its max; pooled over many, one slow frame a window is not the p99
        val c = FrameCosts()
        val work = PinWork()
        var w: GlWindow? = null
        for (i in 0 until 1_000) {
            val ts = i * 33 * ms + ms
            work.clear()
            work.batches = 1
            val slow = i % 400 == 50 // in 3 of the 10 windows, the last one's among them
            if (slow) {
                work.births = 1
                work.anchorCall(3 * ms)
                work.anchorCall(ms)
                work.placeNs = 5 * ms
            }
            c.frame(ts, (if (slow) 12 else 2) * ms, (if (slow) 9 else 1) * ms / 2, 40 * ms, work)
            if (c.due(ts)) w = c.close(ts, gcCount = 1, frames = false)
        }
        val last = w!!
        assertEquals(4.5, last.pinP99Ms, 1e-9) // the window's p99 is its slowest frame
        assertEquals(4.5, last.slowPinMs, 1e-9)
        assertEquals(12.0, last.slowGlMs, 1e-9)
        assertEquals(911, last.sessionFrames)
        assertEquals(0.5, last.sessionPinP99Ms, 1e-9) // pooled: 3 slow frames in 911 are above the p99
        assertEquals(2.0, last.sessionGlP50Ms, 1e-9)
        assertEquals(1, last.slow!!.births); assertEquals(4 * ms, last.slow!!.anchorNs); assertEquals(3 * ms, last.slow!!.anchorMaxNs)
        assertEquals(0, last.glMs.size) // every frame's numbers only for a trace
        val line = last.sessionLine()
        assertTrue(line, line.startsWith("GL since start: ${last.sessionFrames} frames, thread CPU after update() p50 2.00 p99 2.00 ms, pins p50 0.50 p99 0.50 ms (0.05 ms bins); this window's slowest pin frame 4.50 ms (thread CPU 12.00 ms): 1 batches placed"))
        assertTrue(line, line.contains("1 births") && line.contains("placing 5.00, ARCore anchor calls 4.00 (max 3.00)"))
    }

    @Test fun theProbesNoteIsMadeWhenTheWindowClosesAsItWasWhenProbed() {
        val c = FrameCosts()
        c.frame(0, ms, 0, 40 * ms)
        c.probeImage(0, 400_000, 1280, 720, 35)
        assertEquals("1280x720 format 35", c.close(3_000 * ms, gcCount = 1).probeNote)
        c.frame(3_033 * ms, ms, 0, 40 * ms)
        c.probeFailed(3_033 * ms, 1_000, IllegalStateException())
        assertEquals("IllegalStateException", c.close(6_000 * ms, gcCount = 1).probeNote)
        c.frame(6_033 * ms, ms, 0, 40 * ms)
        assertEquals("IllegalStateException", c.close(9_000 * ms, gcCount = 1).probeNote) // the last probe's, until the next
        c.probe(9_033 * ms, true, 1_000, "as text")
        assertEquals("as text", c.close(12_000 * ms, gcCount = 1).probeNote)
    }

    @Test fun aPauseStartsTheGlWindowOver() {
        val c = FrameCosts()
        c.frame(0, 9 * ms, 0, 40 * ms)
        c.frame(20_000 * ms, ms, 0, 40 * ms)   // paused 20 s: the frame before is not in this window
        assertFalse(c.due(22_000 * ms))
        val w = c.close(23_000 * ms, gcCount = 5)
        assertEquals(1, w.frames); assertEquals(3.0, w.seconds, 1e-9); assertEquals(1.0, w.glMaxMs, 1e-9); assertEquals(-1L, w.gcs)
    }

    @Test fun arrivalsAreSummedUpEveryThreeSecondsOfCaptureTime() {
        val a = ArrivalWindow()
        var line: String? = null
        for (i in 0..91) a.add(i * 33 * ms, (30 + i % 10) * ms, skippedForBlur = i % 30 == 0)?.let { line = it }   // the 92nd closes the window
        assertEquals("4K images: 91 in 3.0 s, capture to arrival p50 34.0 p90 38.0 max 39.0 ms, 4 skipped for blur", line)
    }

    @Test fun aPauseStartsTheArrivalWindowOver() {
        val a = ArrivalWindow()
        a.add(0, 500 * ms, false)
        assertNull(a.add(10_000 * ms, 30 * ms, false))   // after a 10 s pause: no window spans it
        assertNull(a.add(12_000 * ms, 30 * ms, false))
        assertEquals("4K images: 2 in 3.0 s, capture to arrival p50 30.0 p90 30.0 max 30.0 ms, 0 skipped for blur", a.add(13_000 * ms, 30 * ms, false))
    }
}
