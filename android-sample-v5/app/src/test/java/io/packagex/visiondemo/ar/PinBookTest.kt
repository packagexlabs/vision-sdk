package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.ItemCode
import io.packagex.arcount.ItemCount
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import io.packagex.arcount.ray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The iOS demo's AR marker rules (ScannerController at cabf9d9c), as [PinBook] and [PinMotion] keep them. */
class PinBookTest {
    private val ms = 1_000_000L
    private val k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)

    /**
     * A read of [code] at [target] by a camera at [from] (turned so it looks along -Z), hit at [hit]: its box is a
     * 200 x 100 px quad around [target]'s pixel, inflated 30 % a side, its match floor 104 px (40 dp at 2.6).
     */
    private fun seen(code: String, target: Vec3, from: Vec3 = Vec3(target.x, target.y, 0.0), hit: Vec3 = target): Sighting {
        val camera = Pose(from, Quat.IDENTITY)
        val (u, v) = k.project(camera.inverse().apply(target))!!
        val corners = listOf(u - 100, v - 50, u + 100, v - 50, u + 100, v + 50, u - 100, v + 50)
        val capture = PoseRecord(0, camera, null, Tracking.TRACKING, null, k)
        return sightingOf(Read(0, code, corners, 1), capture, hit, Quat.IDENTITY, Ray(from, (target - from).unit()), 104.0).copy(code = code)
    }

    private val yes: (Pin) -> Boolean = { true }

    private fun PinBook.see(s: Sighting, t: Long, mayCreate: Boolean = true) = place(listOf(s), t, t, mayCreate, yes)

    private fun bookWithPin(code: String, at: Vec3): Pair<PinBook, Pin> {
        val b = PinBook()
        repeat(PIN_CONFIRM_COUNT) { b.see(seen(code, at), it * 33 * ms) }
        return b to b.pins.single()
    }

    // --- candidates ---

    @Test fun aPinNeedsThreeAgreeingSightingsAndIsBornAtTheirMedian() {
        val b = PinBook()
        val at = Vec3(0.0, 0.0, -0.5)
        b.see(seen("A", at, hit = at.copy(x = 0.01)), 0)
        b.see(seen("A", at, hit = at.copy(x = -0.01)), 33 * ms)
        assertTrue(b.pins.isEmpty())
        b.see(seen("A", at, hit = at.copy(x = 0.03)), 66 * ms)
        assertEquals(Vec3(0.01, 0.0, -0.5), b.pins.single().pose.t) // the per-axis median, not the last hit
        assertEquals(0, b.candidateCount)
    }

    @Test fun sightingsBeyondTheCandidateRadiusStartTheirOwnCandidate() {
        val b = PinBook()
        repeat(3) { i -> b.see(seen("A", Vec3(i * (PIN_CANDIDATE_RADIUS_M + 0.02), 0.0, -0.5)), i * 33 * ms) }
        assertTrue(b.pins.isEmpty())
        assertEquals(3, b.candidateCount)
    }

    @Test fun aCandidateExpiresAfterOneAndAHalfSecondsOfCaptureTime() {
        val b = PinBook()
        val at = Vec3(0.0, 0.0, -0.5)
        b.see(seen("A", at), 0)
        b.see(seen("A", at), 1_600 * ms) // the first expired: this one starts again
        b.see(seen("A", at), 1_633 * ms)
        assertTrue(b.pins.isEmpty())
        b.see(seen("A", at), 1_666 * ms)
        assertEquals(1, b.pins.size)
    }

    @Test fun noCandidateWhilePlacementIsGatedAndNoPinWithoutAnAnchor() {
        val b = PinBook()
        repeat(5) { b.see(seen("A", Vec3(0.0, 0.0, -0.5)), it * 33 * ms, mayCreate = false) }
        assertEquals(0, b.candidateCount)
        repeat(3) { b.place(listOf(seen("A", Vec3(0.0, 0.0, -0.5))), it * 33 * ms, it * 33 * ms, true) { false } }
        assertTrue(b.pins.isEmpty())
    }

    // --- assignment: the box (or match radius) in the read's own frame, and the lateral radius ---

    @Test fun aReReadClaimsThePinAndNeverMovesIt() {
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.5))
        b.see(seen("A", Vec3(0.0, 0.0, -0.5), from = Vec3(0.2, 0.0, 0.0), hit = Vec3(0.05, 0.0, -0.5)), 500 * ms)
        assertEquals(500 * ms, pin.lastSeenNs)
        assertEquals(Vec3(0.0, 0.0, -0.5), pin.position)
        assertEquals(0, b.candidateCount)
    }

    @Test fun aPinWithinTheLateralRadiusButOffTheReadsBoxIsNotClaimed() {
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        // A read 10 cm to the side at the same depth: lateral 0.10 m <= 0.12 m, but 724 px off its centre (box and radius 320 px)
        b.see(seen("A", Vec3(0.10, 0.0, -0.4)), 500 * ms)
        assertEquals(66 * ms, pin.lastSeenNs)
        assertEquals(1, b.candidateCount)
    }

    @Test fun anotherCodeNeverClaimsAPin() {
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.5))
        b.see(seen("B", Vec3(0.0, 0.0, -0.5)), 500 * ms)
        assertEquals(66 * ms, pin.lastSeenNs)
        assertEquals(1, b.candidateCount)
    }

    @Test fun assignmentIsExclusiveAndSmallestLateralFirst() {
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -2.0)) // far: both reads' boxes hold it
        val off = seen("A", Vec3(0.10, 0.0, -2.0))
        val on = seen("A", Vec3(0.01, 0.0, -2.0))
        b.place(listOf(off, on), 500 * ms, 500 * ms, true, yes)
        assertEquals(500 * ms, pin.lastSeenNs)
        assertEquals(1, b.candidateCount) // `off` lost the pin to `on` and started a candidate
    }

    @Test fun lateralIsDepthIndependentAndBehindTheRayIsFar() {
        val ray = Ray(Vec3.ZERO, Vec3(0.0, 0.0, -1.0))
        assertEquals(0.05, lateral(ray, Vec3(0.05, 0.0, -0.3)), 1e-9)
        assertEquals(0.05, lateral(ray, Vec3(0.05, 0.0, -2.0)), 1e-9)
        assertEquals(Double.MAX_VALUE, lateral(ray, Vec3(0.0, 0.0, 0.5)), 0.0)
    }

    @Test fun theBoxIsInflatedThirtyPercentASideAndTheRadiusIsItsLargerSideOrTheFloor() {
        val s = seen("A", Vec3(0.0, 0.0, -0.5))
        assertEquals(1920.0 - 160, s.minU, 1e-9); assertEquals(1920.0 + 160, s.maxU, 1e-9)
        assertEquals(1080.0 - 80, s.minV, 1e-9); assertEquals(1080.0 + 80, s.maxV, 1e-9)
        assertEquals(320.0, s.matchRadiusPx, 1e-9)
        val capture = PoseRecord(0, Pose.IDENTITY, null, Tracking.TRACKING, null, k)
        val tiny = Read(0, "A", listOf(1910.0, 1075.0, 1930.0, 1075.0, 1930.0, 1085.0, 1910.0, 1085.0), 1)
        assertEquals(104.0, sightingOf(tiny, capture, Vec3.ZERO, Quat.IDENTITY, Ray(Vec3.ZERO, Vec3(0.0, 0.0, -1.0)), 104.0).matchRadiusPx, 1e-9)
    }

    // --- one item, one pin; two copies, two pins ---

    @Test fun oneItemReadFromManyAnglesWithHitsScatteredSixCentimetresKeepsOnePin() {
        val rnd = Random(7)
        val target = Vec3(0.0, 0.0, -0.4)
        val b = PinBook()
        repeat(300) { i ->
            val from = Vec3(rnd.nextDouble(-0.3, 0.3), rnd.nextDouble(-0.15, 0.15), rnd.nextDouble(-0.05, 0.1))
            // a hit anywhere within 6 cm of the barcode
            val theta = rnd.nextDouble(0.0, Math.PI)
            val phi = rnd.nextDouble(0.0, 2 * Math.PI)
            val r = rnd.nextDouble(0.0, 0.06)
            val hit = target + Vec3(r * sin(theta) * cos(phi), r * sin(theta) * sin(phi), r * cos(theta))
            b.see(seen("A", target, from = from, hit = hit), i * 33 * ms)
            assertTrue("pins after sighting $i: ${b.pins.size}", b.pins.size <= 1)
        }
        assertEquals(1, b.pins.size)
    }

    @Test fun twoCopiesTwentyCentimetresApartKeepTwoPins() {
        val rnd = Random(11)
        val a = Vec3(0.0, 0.0, -0.4)
        val c = Vec3(0.20, 0.0, -0.4)
        val b = PinBook()
        repeat(300) { i ->
            val target = if (i % 2 == 0) a else c
            val from = Vec3(rnd.nextDouble(-0.1, 0.3), rnd.nextDouble(-0.1, 0.1), 0.0)
            val hit = target + Vec3(rnd.nextDouble(-0.01, 0.01), rnd.nextDouble(-0.01, 0.01), rnd.nextDouble(-0.01, 0.01))
            b.see(seen("A", target, from = from, hit = hit), i * 33 * ms)
        }
        assertEquals(2, b.pins.size)
        val (p, q) = b.pins
        assertTrue((p.position - q.position).norm() > PIN_SIBLING_MERGE_M)
    }

    // --- removal: only the sibling merge ---

    private fun birthRace(): Triple<PinBook, Pin, Pin> {
        val b = PinBook()
        // Two candidates 10 cm apart mature in the same batches; each birth merges, so the first one born is kept
        // only until the second is born beside it
        val born = ArrayList<Pin>()
        repeat(PIN_CONFIRM_COUNT) {
            b.place(listOf(seen("A", Vec3(0.0, 0.0, -0.4)), seen("A", Vec3(0.10, 0.0, -0.4))), it * 33 * ms, it * 33 * ms, true) { p -> born += p; true }
        }
        return Triple(b, born[0], born[1])
    }

    @Test fun siblingsWithinFifteenCentimetresMergeOnATieKeepingTheLater() {
        val (b, _, second) = birthRace()
        assertEquals(listOf(second), b.pins)
    }

    @Test fun theMergeKeepsTheMostRecentlySeen() {
        val (b, first) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        val second = Pin(99, "A", Pose(Vec3(0.10, 0.0, -0.4), Quat.IDENTITY)).also { it.lastSeenNs = 10 * ms }
        first.lastSeenNs = 20 * ms
        val list = b.pins as MutableList<Pin>
        list += second
        assertEquals(listOf(second), b.mergeSiblings())
        assertEquals(listOf(first), b.pins)
    }

    @Test fun copiesBeyondFifteenCentimetresAreNotMerged() {
        val (b, first) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        (b.pins as MutableList<Pin>) += Pin(99, "A", Pose(Vec3(0.16, 0.0, -0.4), Quat.IDENTITY))
        assertTrue(b.mergeSiblings().isEmpty())
        assertEquals(2, b.pins.size)
        assertEquals(first, b.pins[0])
    }

    @Test fun clearDropsPinsAndCandidates() {
        val (b, _) = bookWithPin("A", Vec3(0.0, 0.0, -0.5))
        b.see(seen("B", Vec3(0.3, 0.0, -0.5)), 100 * ms)
        b.clear()
        assertTrue(b.pins.isEmpty())
        assertEquals(0, b.candidateCount)
    }

    // --- staleness, motion and warm-up (iOS updateMotionEstimate) ---

    @Test fun batchesOlderThanHalfASecondAreStale() {
        val m = PinMotion()
        assertTrue(m.fresh(1_000 * ms, 1_500 * ms))
        assertFalse(m.fresh(1_000 * ms, 1_500 * ms + 1))
    }

    @Test fun candidatesNeedMotionModerateSinceBeforeTheCaptureAndTheWarmUp() {
        val m = PinMotion()
        repeat(PIN_WARM_UP_FRAMES) { m.onFrame(it * 33 * ms, Pose.IDENTITY, true) }
        val t = (PIN_WARM_UP_FRAMES - 1) * 33 * ms
        assertTrue(m.mayCreate(t))
        // 2 cm in 33 ms: 0.6 m/s, beyond the moderate 0.4 m/s
        m.onFrame(t + 33 * ms, Pose(Vec3(0.02, 0.0, 0.0), Quat.IDENTITY), true)
        assertFalse(m.cameraModerate)
        assertFalse(m.mayCreate(t + 33 * ms))
        m.onFrame(t + 66 * ms, Pose(Vec3(0.02, 0.0, 0.0), Quat.IDENTITY), true)
        assertTrue(m.cameraModerate)
        assertFalse(m.mayCreate(t + 33 * ms)) // captured before the camera became moderate again
        assertTrue(m.mayCreate(t + 66 * ms))
    }

    @Test fun theTurnRateIsTheForwardVectorsAsOnIos() {
        val m = PinMotion()
        m.onFrame(0, Pose.IDENTITY, true)
        m.onFrame(100 * ms, Pose(Vec3.ZERO, Quat.axisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(5.0))), true) // pan 5 deg in 0.1 s
        assertEquals(50.0, m.rotationDps, 1e-3)
        m.onFrame(200 * ms, Pose(Vec3.ZERO, Quat.axisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(5.0)) * Quat.axisAngle(Vec3(0.0, 0.0, 1.0), Math.toRadians(20.0))), true)
        assertEquals(0.0, m.rotationDps, 1e-3) // a roll about the forward axis does not turn it
    }

    @Test fun warmUpCountsTrackedFramesOnly() {
        val m = PinMotion()
        repeat(PIN_WARM_UP_FRAMES - 1) { m.onFrame(it * 33 * ms, Pose.IDENTITY, true) }
        m.onFrame(5_000 * ms, Pose.IDENTITY, false)
        assertFalse(m.mapReady)
        m.onFrame(5_033 * ms, Pose.IDENTITY, true)
        assertTrue(m.mapReady)
    }

    // --- colour ---

    @Test fun colourIsGreenCountedGreyListedAndNoneUnlisted() {
        val keys = listedKeys(setOf("5901234123457", "A1"))
        val items = listOf(ItemCount("5901234123457", 2, 2, true), ItemCount("A1", 0, 1, true))
        assertEquals(PinColour.COUNTED, pinColour(ItemCode.key("5901234123457"), keys, items))
        assertEquals(PinColour.LISTED, pinColour("A1", keys, items)) // range 0..1: not counted yet
        assertNull(pinColour("B2", keys, items))
        assertEquals(PinColour.COUNTED, pinColour(ItemCode.key("05901234123457"), keys, items)) // GTINs as 14 digits
        assertEquals(PinColour.LISTED, pinColour(ItemCode.key("5901234123457"), keys, emptyList()))
    }

    // --- diagnostics and the depth-confidence gate ---

    private val capture = PoseRecord(0, Pose.IDENTITY, null, Tracking.TRACKING, null, k)

    @Test fun aHitOnTheReadsRayReprojectsOntoItsCentre() {
        val r = Read(0, "A", listOf(2300.0, 650.0, 2500.0, 650.0, 2500.0, 750.0, 2300.0, 750.0), 1)
        val hit = capture.ray(r.centreU, r.centreV, null).at(0.4)
        val c = checkHit(hit, r, capture)
        assertEquals(0.0, c.errorPx, 1e-6)
        assertEquals(0.4 * (-k.rayInCamera(2400.0, 700.0).z), c.depthM, 1e-9)
    }

    @Test fun aHitMapsToItsImageAndThenToADepthPixel() {
        val (u, v) = imageNormalized(Vec3(0.1, -0.05, -0.5), Pose.IDENTITY, k)!!
        assertEquals((1920 + 2896 * 0.2) / 3840, u, 1e-9)
        assertEquals((1080 + 2896 * 0.1) / 2160, v, 1e-9)
        assertNull(imageNormalized(Vec3(0.0, 0.0, 0.5), Pose.IDENTITY, k))
        assertEquals(80 to 45, depthPixel(0.5f, 0.5f, 160, 90))
        assertEquals(0 to 0, depthPixel(0f, 0f, 160, 90))
        assertEquals(159 to 89, depthPixel(0.9999f, 0.9999f, 160, 90))
        assertNull(depthPixel(1f, 0.5f, 160, 90))
        assertNull(depthPixel(-0.01f, 0.5f, 160, 90))
    }

    @Test fun aDepthPointNeedsConfidenceOneTwentyEightAndNoImageMeansNoGate() {
        assertFalse(depthConfident(127))
        assertTrue(depthConfident(MIN_DEPTH_CONFIDENCE))
        assertTrue(depthConfident(255))
        assertTrue(depthConfident(null))
    }
}
