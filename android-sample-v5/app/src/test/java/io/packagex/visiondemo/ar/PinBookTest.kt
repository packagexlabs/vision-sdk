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

/**
 * The iOS demo's AR marker rules (ScannerController at cabf9d9c), as [PinBook] and [PinMotion] keep them under
 * [PinRules.IOS], and the identity rules the drift plan's §3.4 adds under [PinRules.ANDROID] (Phase 3), with the
 * re-init that Phase 4's estimator brings (rule 4; the estimator itself is [PinEstimatorTest]'s) and, with pins frozen
 * ([PinBook.refine] off), Phase 3's stand-in for it.
 */
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

    private fun PinBook.see(s: Sighting, t: Long, mayCreate: Boolean = true) = place(listOf(s), t, t, mayCreate, anchor = yes)

    private fun bookWithPin(code: String, at: Vec3, rules: PinRules = PinRules.ANDROID): Pair<PinBook, Pin> {
        val b = PinBook().also { it.rules = rules }
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

    @Test fun aPinIsBornOnAPlaneWhenMostOfItsSightingsHitOne() {
        val at = Vec3(0.0, 0.0, -0.5)
        fun bornFrom(vararg onPlane: Boolean): Pin {
            val b = PinBook()
            onPlane.forEachIndexed { i, plane -> b.see(seen("A", at).copy(onPlane = plane), i * 33 * ms) }
            return b.pins.single()
        }
        assertTrue(bornFrom(true, false, true).bornOnPlane)
        assertFalse(bornFrom(true, false, false).bornOnPlane)
        assertFalse(bornFrom(false, false, false).bornOnPlane)
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

    @Test fun aReReadClaimsThePinAndUnderIosNeverMovesIt() {
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.5), PinRules.IOS)
        b.see(seen("A", Vec3(0.0, 0.0, -0.5), from = Vec3(0.2, 0.0, 0.0), hit = Vec3(0.05, 0.0, -0.5)), 500 * ms)
        assertEquals(500 * ms, pin.lastSeenNs)
        assertEquals(Vec3(0.0, 0.0, -0.5), pin.position)
        assertEquals(0, b.candidateCount)
    }

    @Test fun theClaimsComeBackWithTheirSightingsAndABirthIsNone() {
        val b = PinBook()
        val at = Vec3(0.0, 0.0, -0.5)
        repeat(PIN_CONFIRM_COUNT) { assertTrue(b.see(seen("A", at), it * 33 * ms).claims.isEmpty()) }
        val pin = b.pins.single()
        val again = seen("A", at, from = Vec3(0.05, 0.0, 0.0))
        val other = seen("B", at)
        val placed = b.place(listOf(other, again), 500 * ms, 500 * ms, true, anchor = yes)
        assertEquals(1, placed.claims.size)
        assertEquals(again, placed.claims.single().sighting); assertEquals(pin, placed.claims.single().pin)
        assertTrue(placed.removed.isEmpty())
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
        // One image (one camera) reads the code twice. Under iOS both reads' boxes hold the far pin; under Android the
        // pair also sets the code's pitch (9 cm here), and `off` lies beyond half of it
        for (rules in PinRules.entries) {
            val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -2.0), rules)
            val camera = Vec3(0.05, 0.0, 0.0)
            val off = seen("A", Vec3(0.10, 0.0, -2.0), from = camera)
            val on = seen("A", Vec3(0.01, 0.0, -2.0), from = camera)
            val placed = b.place(listOf(off, on), 500 * ms, 500 * ms, true, anchor = yes)
            assertEquals(500 * ms, pin.lastSeenNs)
            assertEquals(on, placed.claims.single().sighting)
            assertEquals(1, b.candidateCount) // `off` lost the pin to `on` and started a candidate
        }
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

    /** One item read from viewpoints within [span] of it, its hits anywhere within [scatterM] of the barcode */
    private fun oneItemFromManyAngles(rules: PinRules, scatterM: Double, seed: Int) {
        val rnd = Random(seed)
        val target = Vec3(0.0, 0.0, -0.4)
        val b = PinBook().also { it.rules = rules }
        repeat(300) { i ->
            val from = Vec3(rnd.nextDouble(-0.3, 0.3), rnd.nextDouble(-0.15, 0.15), rnd.nextDouble(-0.05, 0.1))
            val theta = rnd.nextDouble(0.0, Math.PI)
            val phi = rnd.nextDouble(0.0, 2 * Math.PI)
            val r = rnd.nextDouble(0.0, scatterM)
            val hit = target + Vec3(r * sin(theta) * cos(phi), r * sin(theta) * sin(phi), r * cos(theta))
            b.see(seen("A", target, from = from, hit = hit), i * 33 * ms)
            assertTrue("$rules pins after sighting $i: ${b.pins.size}", b.pins.size <= 1)
        }
        assertEquals(1, b.pins.size)
    }

    @Test fun oneItemReadFromManyAnglesWithHitsScatteredSixCentimetresKeepsOnePin() {
        // iOS's 15 cm newest-wins merge absorbs any second pin; under Android the claims' rays move the pin onto its
        // label, so it stays good from 30 cm aside
        oneItemFromManyAngles(PinRules.IOS, 0.06, 7)
        for (seed in 7..9) oneItemFromManyAngles(PinRules.ANDROID, 0.06, seed)
    }

    @Test fun oneItemReadFromManyAnglesWithHitsScatteredThreeCentimetresKeepsOnePinUnderAndroid() {
        for (seed in 7..9) oneItemFromManyAngles(PinRules.ANDROID, 0.03, seed)
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

    private fun birthRace(rules: PinRules): Triple<PinBook, Pin, Pin> {
        val b = PinBook().also { it.rules = rules }
        // Two candidates 10 cm apart mature in the same batches; each birth merges, so the first one born is kept
        // only until the second is born beside it
        val born = ArrayList<Pin>()
        repeat(PIN_CONFIRM_COUNT) {
            b.place(listOf(seen("A", Vec3(0.0, 0.0, -0.4)), seen("A", Vec3(0.10, 0.0, -0.4))), it * 33 * ms, it * 33 * ms, true) { p -> born += p; true }
        }
        return Triple(b, born[0], born[1])
    }

    // Drift plan Phase 3 (§4): the iOS merge stays behind PinRules.IOS; these two tests now say so, and Android's
    // physical merge (rule 6) has its own below

    @Test fun iosSiblingsWithinFifteenCentimetresMergeOnATieKeepingTheLater() {
        val (b, _, second) = birthRace(PinRules.IOS)
        assertEquals(listOf(second), b.pins)
    }

    @Test fun iosMergeKeepsTheMostRecentlySeen() {
        val (b, first) = bookWithPin("A", Vec3(0.0, 0.0, -0.4), PinRules.IOS)
        val second = Pin(99, "A", Pose(Vec3(0.10, 0.0, -0.4), Quat.IDENTITY)).also { it.lastSeenNs = 10 * ms }
        first.lastSeenNs = 20 * ms
        val list = b.pins as MutableList<Pin>
        list += second
        assertEquals(listOf(second), b.mergeSiblings())
        assertEquals(listOf(first), b.pins)
    }

    @Test fun copiesBeyondFifteenCentimetresAreNotMerged() {
        for (rules in PinRules.entries) {
            val (b, first) = bookWithPin("A", Vec3(0.0, 0.0, -0.4), rules)
            (b.pins as MutableList<Pin>) += Pin(99, "A", Pose(Vec3(0.16, 0.0, -0.4), Quat.IDENTITY))
            assertTrue(b.mergeSiblings().isEmpty())
            assertEquals(2, b.pins.size)
            assertEquals(first, b.pins[0])
        }
    }

    @Test fun androidKeepsTwinsBornTenCentimetresApartInOneBatch() {
        val (b, first, second) = birthRace(PinRules.ANDROID)
        assertEquals(listOf(first, second), b.pins)
    }

    @Test fun androidMergesOnlyPinsOneLabelApartKeepingTheMostClaimed() {
        val (b, first) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        first.claims = 1
        val near = Pin(99, "A", Pose(Vec3(0.012, 0.0, -0.4), Quat.IDENTITY)).also { it.claims = 3 }
        val apart = Pin(98, "A", Pose(Vec3(0.05, 0.0, -0.4), Quat.IDENTITY)).also { it.lastSeenNs = Long.MAX_VALUE }
        (b.pins as MutableList<Pin>) += listOf(near, apart)
        assertEquals(listOf(first), b.mergeSiblings()) // 1.2 cm apart: one label; 5 cm: twins, however recent
        assertEquals(listOf(near, apart), b.pins)
        // A tie keeps the older; half the label's width bounds the radius below 1.5 cm
        val (c, older) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        (c.pins as MutableList<Pin>) += Pin(97, "A", Pose(Vec3(0.012, 0.0, -0.4), Quat.IDENTITY))
        c.mergeSiblings()
        assertEquals(listOf(older), c.pins)
        val small = PinBook()
        (small.pins as MutableList<Pin>) += listOf(
            Pin(1, "A", Pose(Vec3(0.0, 0.0, -0.4), Quat.IDENTITY), widthM = 0.02),
            Pin(2, "A", Pose(Vec3(0.012, 0.0, -0.4), Quat.IDENTITY), widthM = 0.02),
        )
        assertTrue(small.mergeSiblings().isEmpty()) // 2 cm labels: 1 cm
    }

    @Test fun clearDropsPinsCandidatesAndPitches() {
        val (b, _) = bookWithPin("A", Vec3(0.0, 0.0, -0.5))
        b.see(seen("B", Vec3(0.3, 0.0, -0.5)), 100 * ms)
        b.place(listOf(seen("C", Vec3(0.0, 0.0, -0.4), Vec3(0.04, 0.0, 0.0)), seen("C", Vec3(0.08, 0.0, -0.4), Vec3(0.04, 0.0, 0.0))), 200 * ms, 200 * ms, true, anchor = yes)
        b.clear()
        assertTrue(b.pins.isEmpty())
        assertEquals(0, b.candidateCount)
        assertNull(b.pitchOf("C"))
    }

    // --- Android identity rules (drift plan §3.4, Phase 3, on frozen pins) ---

    /** A read of the pin's twin 3.5 cm aside, seen from between them: 253 px off the pin, inside its 320 px match radius */
    private val twin = Vec3(0.035, 0.0, -0.4)
    private val between = Vec3(0.0175, 0.0, 0.0)

    @Test fun aCodeReadTwiceInOneImageLearnsItsPitch() {
        val camera = Vec3(0.04, 0.0, 0.0)
        val reads = listOf(seen("A", Vec3(0.0, 0.0, -0.4), camera), seen("A", Vec3(0.08, 0.0, -0.4), camera), seen("B", Vec3(0.2, 0.0, -0.4), camera))
        val b = PinBook()
        b.place(reads, 0, 0, true, anchor = yes)
        assertEquals(0.08, b.pitchOf("A")!!, 0.001) // the rays' angle times their range
        assertNull(b.pitchOf("B"))
        val close = PinBook()
        val mid = Vec3(0.0025, 0.0, 0.0)
        close.place(listOf(seen("A", Vec3(0.0, 0.0, -0.4), mid), seen("A", Vec3(0.005, 0.0, -0.4), mid)), 0, 0, true, anchor = yes)
        assertEquals(PIN_MIN_PITCH_M, close.pitchOf("A")!!, 1e-12)
        val ios = PinBook().also { it.rules = PinRules.IOS }
        ios.place(reads, 0, 0, true, anchor = yes)
        assertNull(ios.pitchOf("A"))
    }

    @Test fun onceThePitchIsKnownAPinIsClaimedOnlyWithinHalfOfIt() {
        // Unknown pitch: the twin's read claims the pin (over a second after its last good claim, so not voided)
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        assertEquals(1, b.see(seen("A", twin, between), 2_000 * ms).claims.size)
        assertEquals(2_000 * ms, pin.lastSeenNs)
        // An image that read two units 6 cm apart caps claims at 3 cm: the twin's read starts a candidate instead
        val (c, capped) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        val far = Vec3(0.53, 0.0, 0.0)
        c.place(listOf(seen("A", Vec3(0.5, 0.0, -0.4), far), seen("A", Vec3(0.56, 0.0, -0.4), far)), 1_000 * ms, 1_000 * ms, true, anchor = yes)
        assertEquals(0.06, c.pitchOf("A")!!, 0.001)
        val candidates = c.candidateCount
        assertTrue(c.see(seen("A", twin, between), 2_000 * ms).claims.isEmpty())
        assertEquals(66 * ms, capped.lastSeenNs)
        assertEquals(candidates + 1, c.candidateCount)
    }

    @Test fun aBadClaimSoonAfterAGoodOneIsVoidedAndItsReadFeedsTheCandidates() {
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.4)) // born, so good, at 66 ms
        val placed = b.see(seen("A", twin, between), 500 * ms) // 253 px off: over max(60, 200 / 2) px
        assertTrue(placed.claims.isEmpty())
        assertEquals(pin, placed.voided.single().pin)
        assertEquals(66 * ms, pin.lastSeenNs)
        assertEquals(1, b.candidateCount)
        // A second after its last good claim the same read is applied, though not good
        assertEquals(pin, b.see(seen("A", twin, between), 1_100 * ms).claims.single().pin)
        assertEquals(1_100 * ms, pin.lastSeenNs)
        assertEquals(66 * ms, pin.lastGoodNs)
        assertEquals(1, pin.claims)
        // A good claim is one; after a map correction a bad one is applied at once; iOS voids nothing
        val (c, good) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        c.see(seen("A", Vec3(0.0, 0.0, -0.4), between), 500 * ms) // its own label, from aside: 0 px off
        assertEquals(500 * ms, good.lastGoodNs)
        val (d, moved) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        d.mapMovedNs = 200 * ms
        assertTrue(d.see(seen("A", twin, between), 500 * ms).voided.isEmpty())
        assertEquals(500 * ms, moved.lastSeenNs)
        val (e, ios) = bookWithPin("A", Vec3(0.0, 0.0, -0.4), PinRules.IOS)
        assertTrue(e.see(seen("A", twin, between), 500 * ms).voided.isEmpty())
        assertEquals(500 * ms, ios.lastSeenNs)
    }

    @Test fun aCandidateJoinsOnItsMedianSoHitsCannotChainAcrossUnits() {
        // Rays 5 cm apart: each within iOS's 8 cm of the last hit, so iOS chains them into a pin between units; Android
        // needs the candidate's median within half a label of the ray (1.4 cm here)
        for (rules in PinRules.entries) {
            val b = PinBook().also { it.rules = rules }
            repeat(3) { i -> b.see(seen("A", Vec3(i * 0.05, 0.0, -0.4)), i * 33 * ms) }
            if (rules == PinRules.IOS) {
                assertEquals(Vec3(0.05, 0.0, -0.4), b.pins.single().pose.t)
            } else {
                assertTrue(b.pins.isEmpty())
                assertEquals(3, b.candidateCount)
            }
        }
    }

    @Test fun aReadOnTheTableJoinsNoCandidateOnTheFloorBehindIt() {
        // 2026-10-08 12:17: two reads of a UNIT-T got points on the floor 1.07 m away (a weak depth point agreed with it),
        // then a read of it on the table at 0.24 m joined them on the same line of sight, and the median bore the pin on the
        // floor. Rule 5 now joins only points within PIN_SIZE_RATIO of each other's depth along the ray
        val table = Vec3(0.02, -0.03, -0.24)
        val floor = table * (1.07 / table.norm())
        val b = PinBook()
        b.see(seen("T", table, from = Vec3.ZERO, hit = floor), 0)
        b.see(seen("T", table, from = Vec3.ZERO, hit = floor), 33 * ms)
        b.see(seen("T", table, from = Vec3.ZERO, hit = table), 66 * ms)
        assertTrue(b.pins.isEmpty())
        assertEquals(2, b.candidateCount)
        b.see(seen("T", table, from = Vec3.ZERO, hit = table), 99 * ms)
        b.see(seen("T", table, from = Vec3.ZERO, hit = table), 132 * ms)
        assertEquals(table.norm(), b.pins.single().pose.t.norm(), 1e-9) // on the table, from its own three reads
    }

    @Test fun aPinBornMostlyOnGuessesIsMarkedAndKnowsItsLabelsWidth() {
        val at = Vec3(0.0, 0.0, -0.4)
        val b = PinBook()
        listOf(true, false, true).forEachIndexed { i, g -> b.see(seen("A", at).copy(source = if (g) HitSource.DEFAULT else HitSource.HIT), i * 33 * ms) }
        val pin = b.pins.single()
        assertTrue(pin.bornGuessed)
        assertEquals(66 * ms, pin.bornNs)
        assertEquals(66 * ms, pin.lastGoodNs)
        assertEquals(200 * 0.4 / 2896, pin.widthM, 1e-12) // the 200 px quad at 0.4 m
    }

    @Test fun aPinThatKeepsMissingReadsOfItsCodeInViewRetires() {
        // Its unit stops decoding while its twin 10 cm aside is read every 300 ms from between them, from 1.1 s
        val camera = Vec3(0.05, 0.0, 0.0)
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        var retiredAt = -1L
        for (t in 1_100L..3_500L step 300) {
            val placed = b.see(seen("A", Vec3(0.10, 0.0, -0.4), camera), t * ms)
            if (placed.retired.isNotEmpty()) {
                assertEquals(pin, placed.retired.single())
                retiredAt = t
            }
        }
        assertEquals(3_200L, retiredAt) // 6 misses by 2.6 s; 2 s after the first at 1.1 s
        assertEquals(1, b.pins.size) // the twin's own pin, born at 1.7 s
        // A claim between restarts the count
        val (c, kept) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        for (t in 1_100L..3_500L step 300) {
            val own = if (t == 2_000L) listOf(seen("A", Vec3(0.0, 0.0, -0.4), camera)) else emptyList()
            assertTrue(c.place(own + seen("A", Vec3(0.10, 0.0, -0.4), camera), t * ms, t * ms, true, anchor = yes).retired.isEmpty())
        }
        assertTrue(kept in c.pins)
    }

    /** A 200 x 100 px engine box around [at] as a camera at [camera] sees it, inflated as [trackedBoxOf] does; [code] null: unread */
    private fun boxAt(at: Vec3, camera: Vec3, code: String? = "A"): TrackedBox {
        val (u, v) = k.project(Pose(camera, Quat.IDENTITY).inverse().apply(at))!!
        return trackedBoxOf(Read(0, code.orEmpty(), listOf(u - 100, v - 50, u + 100, v - 50, u + 100, v + 50, u - 100, v + 50), 1), code)
    }

    /**
     * Pins of "A" born on [units] and a [ghost], all seen from [camera]; then from 1.1 s to 3.5 s every 300 ms a batch
     * that decodes [units]' first only, with the engine's undecoded [tracked] boxes: the x of the pins retired, to the mm
     */
    private fun retiredWith(units: List<Vec3>, ghost: Vec3, camera: Vec3, tracked: List<TrackedBox>): List<Double> {
        val b = PinBook().also { it.rules = PinRules.ANDROID }
        repeat(PIN_CONFIRM_COUNT) { n -> b.place((units + ghost).map { seen("A", it, camera) }, n * 33 * ms, n * 33 * ms, true, anchor = yes) }
        assertEquals(units.size + 1, b.pins.size)
        val retired = ArrayList<Double>()
        for (t in 1_100L..3_500L step 300) {
            b.place(listOf(seen("A", units[0], camera)), t * ms, t * ms, true, tracked, yes).retired.forEach { retired += Math.round(it.position.x * 1000) / 1000.0 }
        }
        return retired.sorted()
    }

    @Test fun identicalUnitsTheEngineStillTracksDoNotRetireButAGhostDoes() {
        // 4 identical labels 4.5 cm apart and a ghost pin of their code 6 cm left of the row. From 1.1 s each batch
        // decodes the first label only; with the engine tracking the other 3 (undecoded in that image) they stay, the
        // ghost, with nothing at its spot, retires; told nothing of the tracked boxes, the 3 retire too (the device bug).
        // Boxes the engine has not read at all (detector boxes, no text) shield as well
        val units = (0 until 4).map { Vec3(0.045 * it, 0.0, -0.4) }
        val ghost = Vec3(-0.06, 0.0, -0.4)
        val camera = Vec3(0.0675, 0.0, 0.0)
        assertEquals(listOf(ghost.x), retiredWith(units, ghost, camera, units.drop(1).map { boxAt(it, camera) }))
        assertEquals(listOf(ghost.x), retiredWith(units, ghost, camera, units.drop(1).map { boxAt(it, camera, code = null) }))
        assertEquals(listOf(ghost.x) + units.drop(1).map { it.x }, retiredWith(units, ghost, camera, emptyList()))
    }

    @Test fun oneUndecodedBoxShieldsOnlyThePinNearestItsCentre() {
        // UNIT-T on 2026-10-07: 5 identical labels 32 mm apart and a ghost 16 mm between the 4th and 5th. The 4th's and
        // 5th's boxes (120 px a side) both cover the ghost, 116 px off their centres, but each shields only its own
        // label's pin, at its centre: the ghost retires, the labels stay
        val units = (0 until 5).map { Vec3(0.032 * it, 0.0, -0.4) }
        val ghost = Vec3(0.032 * 3.5, 0.0, -0.4)
        val camera = Vec3(0.064, 0.0, 0.0)
        assertEquals(listOf(0.112), retiredWith(units, ghost, camera, units.drop(1).map { boxAt(it, camera) }))
    }

    @Test fun aCountedPinTakesTheBoxBeforeAGhostNearerItsCentre() {
        // 15:03: a never-claimed UNIT-T ghost beside a counted unit's pin outlived the run, kept by the unit's box. Here a
        // label and a ghost 1.8 cm aside, born together; the label is then read every batch (counted from its second
        // claim) and the engine's box over it lies 1.2 cm toward the ghost, so the ghost is nearer its centre: the box
        // now goes to the counted pin, and the ghost retires
        val label = Vec3(0.0, 0.0, -0.4)
        val ghost = Vec3(0.018, 0.0, -0.4)
        val camera = Vec3(0.009, 0.0, 0.0)
        assertEquals(listOf(ghost.x), retiredWith(listOf(label), ghost, camera, listOf(boxAt(Vec3(0.012, 0.0, -0.4), camera))))
    }

    @Test fun aLongLabelsFlatReadsAreItsOwnButAScanLineAmongWideReadsClaimsNothing() {
        // 11:53: PX-0001-2026-ARCOUNT reads 0.15-0.20 as tall as wide every time, and a fixed 0.2 called each one thin: no
        // pin all run. Thin is now against its own code's reads
        val at = Vec3(0.0, 0.0, -0.4)
        val origin = Vec3(0.0, 0.0, 0.0)
        val long = PinBook()
        repeat(PIN_CONFIRM_COUNT) { long.see(seenSized("PX", at, origin, 500.0, 90.0), it * 33 * ms) }
        assertEquals(1, long.pins.size)
        // 12:34: UNIT-T reads 0.37 tall; three scan-line ones (0.10) landing 1.7 cm aside re-initialised its pin there, between
        // two units. A read 0.15 tall (over the 0.12 floor, under half its code's 0.37) now claims nothing and moves nothing
        val b = PinBook()
        repeat(6) { b.see(seenSized("T", at, origin, 200.0, 74.0), it * 33 * ms) }
        val pin = b.pins.single()
        val claims = pin.claims
        val aside = Vec3(0.017, 0.0, -0.4)
        repeat(PIN_REINIT_BAD + 2) { b.see(seenSized("T", aside, Vec3(0.017, 0.0, 0.0), 200.0, 30.0), (300 + it * 33) * ms) }
        assertEquals(claims, pin.claims)
        assertEquals(0.0, pin.position.x, 0.002)
        assertEquals(listOf(pin), b.pins)
    }

    @Test fun aSingleUnitsPinAMapCorrectionLeftBehindGoesForItsNewPin() {
        // 11:53: tracking was lost for 1.1 s and ARCore came back 16 cm off; PX-0001's counted pin stayed 22 cm from its
        // label, out of view, and its new pin was born on the label. Here a counted pin, a map correction after its last
        // good claim, then its label read 28 cm off it, the old pin out of view: the new pin is born and the old one goes.
        // With no map correction both stay (in view and unread, it stays too: aCountedPinStaysAfterAMapCorrection...)
        for (corrected in listOf(true, false)) {
            val old = Vec3(0.0, 0.0, -0.4)
            val (b, pin) = bookWithPin("A", old)
            for (n in 1..PIN_COUNTED_CLAIMS) b.see(seen("A", old), (100L + 33 * n) * ms)
            assertTrue(pin.goodClaims >= PIN_COUNTED_CLAIMS)
            if (corrected) b.mapMovedNs = 500 * ms
            val label = Vec3(0.28, 0.0, -0.4)
            repeat(PIN_CONFIRM_COUNT) { b.see(seen("A", label), (1_200 + it * 33) * ms) }
            val xs = b.pins.map { it.position.x }.sorted()
            if (corrected) assertEquals(1, xs.size) else assertEquals(2, xs.size)
            assertEquals(0.28, xs.last(), 0.002)
        }
    }

    /** [seen], its quad [w] x [h] px */
    private fun seenSized(code: String, target: Vec3, from: Vec3, w: Double, h: Double): Sighting {
        val camera = Pose(from, Quat.IDENTITY)
        val (u, v) = k.project(camera.inverse().apply(target))!!
        val corners = listOf(u - w / 2, v - h / 2, u + w / 2, v - h / 2, u + w / 2, v + h / 2, u - w / 2, v + h / 2)
        val capture = PoseRecord(0, camera, null, Tracking.TRACKING, null, k)
        return sightingOf(Read(0, code, corners, 1), capture, target, Quat.IDENTITY, Ray(from, (target - from).unit()), 104.0).copy(code = code)
    }

    @Test fun thinReadsBirthNoPinAndTeachNoPitch() {
        // 15:03: UNIT-T scan-line decodes (quads 172 x 3-35 px) landed 1.1-1.6 cm off every unit and bore two ghosts
        val at = Vec3(0.0, 0.0, -0.4)
        val from = Vec3(0.0, 0.0, 0.0)
        val thin = PinBook()
        repeat(PIN_CONFIRM_COUNT) { thin.see(seenSized("A", at, from, 200.0, 20.0), it * 33 * ms) }
        assertTrue(thin.pins.isEmpty())
        assertEquals(0, thin.candidateCount)
        val wide = PinBook()
        repeat(PIN_CONFIRM_COUNT) { wide.see(seenSized("A", at, from, 200.0, 50.0), it * 33 * ms) }
        assertEquals(1, wide.pins.size)
        // One image reading a unit and, 1.4 cm aside, a thin read of it: no pitch; two wide reads 8 cm apart: 8 cm
        val mid = Vec3(0.04, 0.0, 0.0)
        val p = PinBook()
        p.place(listOf(seenSized("A", at, mid, 200.0, 100.0), seenSized("A", Vec3(0.014, 0.0, -0.4), mid, 200.0, 20.0)), 0, 0, true, anchor = yes)
        assertNull(p.pitchOf("A"))
        p.place(listOf(seenSized("A", at, mid, 200.0, 100.0), seenSized("A", Vec3(0.08, 0.0, -0.4), mid, 200.0, 100.0)), 33 * ms, 33 * ms, true, anchor = yes)
        assertEquals(0.08, p.pitchOf("A")!!, 0.001)
    }

    @Test fun onceThePitchIsKnownNoPinIsBornWithinHalfOfItOfOneOfItsCode() {
        // 15:03, UNIT-C (12 cm pitch): reads 1.9 cm off a unit's pin were voided as a neighbour's (rule 3) and bore a
        // ghost. Here: the pitch learned at 12 cm, then reads 2 cm off the pin within a second of its last good claim,
        // each voided and fed to the candidates: no pin is born there. Reads of the next unit, 12 cm on, bear its pin.
        val unit = Vec3(0.0, 0.0, -0.4)
        val (b, pin) = bookWithPin("A", unit)
        val mid = Vec3(0.06, 0.0, 0.0)
        b.place(listOf(seen("A", unit, mid), seen("A", Vec3(0.12, 0.0, -0.4), mid)), 100 * ms, 100 * ms, true, anchor = yes)
        assertEquals(0.12, b.pitchOf("A")!!, 0.001)
        val off = Vec3(0.02, 0.0, -0.4)
        var t = 133L
        repeat(PIN_CONFIRM_COUNT + 2) {
            b.see(seen("A", unit), t * ms) // a good claim, so the next read is voided
            b.see(seen("A", off, Vec3(0.02, 0.0, 0.0)), (t + 16) * ms)
            t += 33
        }
        assertEquals(listOf(pin), b.pins)
        repeat(PIN_CONFIRM_COUNT) { b.see(seen("A", Vec3(0.12, 0.0, -0.4)), (t + it * 33) * ms) }
        assertEquals(1, b.pins.count { (it.position - Vec3(0.12, 0.0, -0.4)).norm() < 0.01 })
    }

    @Test fun aReadNearAPinInItsImageBearsNoPinWhateverItsDepth() {
        // Device replays of 15:54's recording: ARCore put the table at 2-4 heights in one replay, and reads of labelled
        // units, voided off their pins, bore second pins on the lower table, 6 cm from them in the world. Here the pitch
        // learned at 4.5 cm, then reads 2 cm off the pin in the image (voided, within a second of its last good claim)
        // whose hits lie 6 cm farther along their rays: no pin is born there. The next unit, one pitch on, read with
        // the same 6 cm deeper hits, bears its pin.
        val unit = Vec3(0.0, 0.0, -0.4)
        val (b, pin) = bookWithPin("A", unit)
        val mid = Vec3(0.0225, 0.0, 0.0)
        b.place(listOf(seen("A", unit, mid), seen("A", Vec3(0.045, 0.0, -0.4), mid)), 100 * ms, 100 * ms, true, anchor = yes)
        assertEquals(0.045, b.pitchOf("A")!!, 0.001)
        val from = Vec3(0.0, 0.0, 0.0)
        fun deeper(target: Vec3) = seen("A", target, from, hit = target + (target - from).unit() * 0.06)
        var t = 133L
        repeat(PIN_CONFIRM_COUNT + 2) {
            b.see(seen("A", unit, from), t * ms) // a good claim, so the next read is voided
            b.see(deeper(Vec3(0.02, 0.0, -0.4)), (t + 16) * ms)
            t += 33
        }
        assertEquals(listOf(pin), b.pins)
        val next = Vec3(0.045, 0.0, -0.4)
        repeat(PIN_CONFIRM_COUNT) { b.see(deeper(next), (t + it * 33) * ms) }
        assertEquals(2, b.pins.size)
    }

    @Test fun aPinBornOnAPlaneStaysOnItWhileItsReadsPullItBelow() {
        // 15:03: two UNIT-C pins born on the table slid 10-17 cm below it within 0.5 s and stayed as counted ghosts. Here
        // a label on a table 0.1 m below the camera's height, its pin born from plane hits (normal up), then reads from a
        // 2 cm wander whose rays meet 15 cm farther along, below the table: the pin stays on the table; born off a plane,
        // the same reads take it down
        val label = Vec3(0.0, -0.1, -0.4)
        val below = label + label.unit() * 0.15
        for (plane in listOf(true, false)) {
            val b = PinBook()
            repeat(PIN_CONFIRM_COUNT) { b.see(seen("A", label, Vec3(0.0, 0.0, 0.0)).copy(onPlane = plane), it * 33 * ms) }
            val pin = b.pins.single()
            assertEquals(plane, pin.bornOnPlane)
            for (i in 0..40) {
                val from = Vec3(0.02 * sin(i * 0.4), 0.0, 0.0)
                b.see(seen("A", below, from), (200L + 100 * i) * ms)
            }
            if (plane) assertEquals(label.y, pin.position.y, 1e-6) else assertTrue("${pin.position}", pin.position.y < label.y - 0.03)
        }
    }

    @Test fun aPinOnAPlaneFollowsTheHeightARCoreReEstimatesForIt() {
        // The replays of 15:54's recording: ARCore moved the table from y -0.19 to -0.117 and the pins held at -0.19 landed
        // 172-359 px off their units, where new pins were born at the new height. A pin born on a table 0.1 m below the
        // camera at the origin; the table re-estimated 6 cm higher: the pin moves onto it along its newest ray (keeping its
        // place in that image) and is held there; under PIN_PLANE_FOLLOW_M it stays; a pin born off a plane never follows
        val label = Vec3(0.0, -0.1, -0.4)
        val up = Vec3(0.0, 1.0, 0.0)
        val b = PinBook()
        repeat(PIN_CONFIRM_COUNT) { b.see(seen("A", label, Vec3(0.0, 0.0, 0.0)).copy(onPlane = true), it * 33 * ms) }
        val pin = b.pins.single()
        assertFalse(pin.followPlane(Vec3(0.3, -0.095, -0.2), up))
        assertEquals(label.y, pin.position.y, 1e-9)
        assertTrue(pin.followPlane(Vec3(0.3, -0.04, -0.2), up))
        val p = pin.position
        assertEquals(-0.04, p.y, 1e-9)
        val was = label.unit()
        val now = p.unit()
        assertEquals(was.x, now.x, 1e-9); assertEquals(was.y, now.y, 1e-9); assertEquals(was.z, now.z, 1e-9)
        // Held on the new plane: reads whose rays run below it keep it there
        val below = p + p.unit() * 0.15
        for (i in 0..20) b.see(seen("A", below, Vec3(0.02 * sin(i * 0.4), 0.0, 0.0)), (200L + 100 * i) * ms)
        assertEquals(-0.04, pin.position.y, 1e-6)
        val off = PinBook()
        repeat(PIN_CONFIRM_COUNT) { off.see(seen("A", label, Vec3(0.0, 0.0, 0.0)), it * 33 * ms) }
        assertFalse(off.pins.single().followPlane(Vec3(0.3, -0.04, -0.2), up))
    }

    @Test fun birthsWaitForARealSurfaceUntilAPlaneOrTheTimeout() {
        // 14:07 and 14:10: no plane, and every pin born 2-4 s after tracking began at the default depth or the nominal
        // width. Held while there is no surface; born on the first real (plane) hits; with no plane at all, the old
        // births return GATE_TIMEOUT_NS after the first tracked frame
        val at = Vec3(0.0, 0.0, -0.4)
        val b = PinBook()
        val g = BirthGate()
        var t = 0L
        fun frame(plane: Boolean = false) {
            g.frame(t * ms, true, plane)
            b.birthsNeedSurface = g.needsSurface
        }
        repeat(PIN_CONFIRM_COUNT + 2) {
            frame()
            b.see(seen("A", at).copy(source = HitSource.DEFAULT), t * ms)
            t += 100
        }
        assertTrue(b.pins.isEmpty())
        assertEquals(PIN_CONFIRM_COUNT + 2, b.held)
        assertEquals(0, b.candidateCount)
        frame(plane = true)
        assertEquals(BirthGate.State.SURFACE, g.state)
        frame()
        b.see(seen("A", at).copy(source = HitSource.WIDTH), t * ms) // still no surface's: held
        assertEquals(PIN_CONFIRM_COUNT + 3, b.held)
        repeat(PIN_CONFIRM_COUNT) {
            t += 33
            frame()
            b.see(seen("A", at).copy(onPlane = true), t * ms)
        }
        assertEquals(1, b.pins.size)
        // No plane ever: held until the timeout, then the default-depth reads make a pin
        val c = PinBook()
        val h = BirthGate()
        val timeoutMs = GATE_TIMEOUT_NS / 1_000_000
        for (s in listOf(0L, timeoutMs - 100, timeoutMs, timeoutMs + 33, timeoutMs + 66)) {
            h.frame(s * ms, true, false)
            c.birthsNeedSurface = h.needsSurface
            c.see(seen("A", at).copy(source = HitSource.DEFAULT), s * ms)
        }
        assertEquals(BirthGate.State.FALLBACK, h.state)
        assertEquals(2, c.held)
        assertEquals(1, c.pins.size)
        // The gate is the Android rules': iOS births are as they were
        val ios = PinBook().also { it.rules = PinRules.IOS; it.birthsNeedSurface = true }
        repeat(PIN_CONFIRM_COUNT) { ios.see(seen("A", at).copy(source = HitSource.DEFAULT), it * 33 * ms) }
        assertEquals(1, ios.pins.size)
    }

    @Test fun missesSpreadBeyondTheWindowDoNotAddUp() {
        // As aPinThatKeepsMissingReadsOfItsCodeInViewRetires (6 misses within 2.1 s: retired), but the twin is read every
        // 1.3 s: 6 misses take 6.5 s, beyond PIN_RETIRE_WINDOW_NS, so the run keeps restarting and the pin stays
        val camera = Vec3(0.05, 0.0, 0.0)
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        for (t in 1_100L..15_400L step 1_300) assertTrue(b.see(seen("A", Vec3(0.10, 0.0, -0.4), camera), t * ms).retired.isEmpty())
        assertTrue(pin in b.pins)
        assertEquals(2, pin.misses) // 14.1 s restarted the run, 15.4 s is its second
    }

    @Test fun aCountedPinNeverRetiresButOneReadOnceDoes() {
        // 14:23: four counted units retired after the camera came back and the engine boxed 14 of 17 units. Here the
        // pin's own label is read twice (counted), then, as in aPinThatKeepsMissingReadsOfItsCodeInViewRetires, only its
        // twin 10 cm aside, unboxed: it stays. Read once, it retires there as that test's never-read pin does
        val camera = Vec3(0.05, 0.0, 0.0)
        val label = Vec3(0.0, 0.0, -0.4)
        for (reads in 1..PIN_COUNTED_CLAIMS) {
            val (b, pin) = bookWithPin("A", label)
            for (n in 1..reads) b.see(seen("A", label), (200L + 300 * n) * ms)
            assertEquals(reads, pin.goodClaims)
            var retiredAt = -1L
            for (t in 1_100L..3_500L step 300) if (b.see(seen("A", Vec3(0.10, 0.0, -0.4), camera), t * ms).retired.isNotEmpty()) retiredAt = t
            if (reads < PIN_COUNTED_CLAIMS) assertEquals(3_200L, retiredAt) else assertEquals(-1L, retiredAt)
            assertEquals(reads >= PIN_COUNTED_CLAIMS, pin in b.pins)
        }
    }

    @Test fun aCountedPinStaysAfterAMapCorrectionWhileNoNewerPinCoversItsLabel() {
        // Counted (read twice), then the map is corrected and only its twin 10 cm aside is read, unboxed: it misses every
        // batch, but no newer pin of its code lies on its label's reads, so the item keeps its pin
        val camera = Vec3(0.05, 0.0, 0.0)
        val label = Vec3(0.0, 0.0, -0.4)
        val (b, pin) = bookWithPin("A", label)
        for (n in 1..PIN_COUNTED_CLAIMS) b.see(seen("A", label), (200L + 300 * n) * ms)
        b.mapMovedNs = 900 * ms
        for (t in 1_100L..3_500L step 300) assertTrue("at $t", b.see(seen("A", Vec3(0.10, 0.0, -0.4), camera), t * ms).retired.isEmpty())
        assertTrue(pin in b.pins)
        assertTrue(pin.misses >= PIN_RETIRE_BATCHES)
    }

    @Test fun aCountedPinIsNotTakenOverByItsNeighbouringUnitsOlderPin() {
        // 15:39: EAN pin 6 (86 claims) retired at 28.5 s for pin 7, its neighbour one pitch (6 cm) away, born 0.2 s after
        // it and long before its last good claim; no EAN pin was born after it and EAN ended 2 of 3. Only a pin born
        // since the last good claim, within half a pitch of where its label's reads now land, takes its place (UNIT-T
        // pins 9, 10 and 15 went for pins born at 26.3 s, 1.0-1.3 cm off, after their last claims at 25.6-26.2 s)
        val pitch = 0.06
        val counted = Pin(6, "02000000000107", Pose.IDENTITY, bornNs = 10_200 * ms).also { it.lastGoodNs = 26_000 * ms }
        val neighbour = Pin(7, "02000000000107", Pose.IDENTITY, bornNs = 10_400 * ms)
        val radius = takeOverRadius(pitch, widthM = 0.0)
        assertEquals(0.03, radius, 1e-12)
        assertFalse(takesOver(neighbour, counted, lateralM = 0.058, radius = radius))
        assertFalse(takesOver(neighbour, counted, lateralM = 0.005, radius = radius)) // born before its last claim: a neighbour
        val replacement = Pin(20, "02000000000107", Pose.IDENTITY, bornNs = 26_300 * ms)
        assertTrue(takesOver(replacement, counted, lateralM = 0.013, radius = radius))
        assertFalse(takesOver(replacement, counted, lateralM = 0.058, radius = radius)) // one pitch off: the next unit
        assertFalse(takesOver(Pin(21, "UNIT-T", Pose.IDENTITY, bornNs = 26_300 * ms), counted, lateralM = 0.0, radius = radius))
        // No pitch learned yet: the label's width, else the merge radius
        assertEquals(0.035, takeOverRadius(null, widthM = 0.035), 1e-12)
        assertEquals(PIN_MERGE_M, takeOverRadius(null, widthM = 0.0), 1e-12)
    }

    @Test fun aMissRunRestartsAfterItsCodeWentUnreadLongerThanTheRetireTime() {
        // 3 misses (1.1-1.7 s), the camera away until 4.0 s, 3 more (4.0-4.6 s): 6 within the window and over 2 s, but
        // the code went unread for 2.3 s between, so the run restarted at 4.0 s and the pin stays
        val camera = Vec3(0.05, 0.0, 0.0)
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        for (t in listOf(1_100L, 1_400L, 1_700L, 4_000L, 4_300L, 4_600L)) {
            assertTrue("at $t", b.see(seen("A", Vec3(0.10, 0.0, -0.4), camera), t * ms).retired.isEmpty())
        }
        assertTrue(pin in b.pins)
        assertEquals(3, pin.misses)
    }

    @Test fun aPinOffItsLabelReInitialisesOnItsThirdBadClaimInARow() {
        // A map correction the anchor did not follow: the pin's label is now read 3 cm aside, 217 px off it
        val label = Vec3(0.03, 0.0, -0.4)
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        // Over a second after its last good claim: the first two bad claims are applied, held from the estimate and no
        // claim for rule 7
        for (t in listOf(1_100L, 1_400L)) assertEquals(pin, b.see(seen("A", label), t * ms).claims.single().pin)
        assertEquals(2, pin.badInRow)
        assertEquals(2, pin.misses)
        assertEquals(0.0, pin.position.x, 1e-9)
        // The third restarts the pin on the three reads' rays, at its depth: it now lies on its label, a good claim
        val third = b.see(seen("A", label), 1_700 * ms)
        assertEquals(pin, third.claims.single().pin)
        assertEquals(listOf(pin), third.reinits)
        assertEquals(0, pin.badInRow)
        assertEquals(1_700 * ms, pin.lastGoodNs)
        assertEquals(0, pin.misses)
        assertEquals(PriorSource.REINIT, pin.est.priorSource)
        assertEquals(0.0, (pin.position - label).norm(), 1e-6)
        // Its reads stay good: no re-birth, nothing retires
        for (t in 2_000L..3_500L step 300) {
            val placed = b.see(seen("A", label), t * ms)
            assertEquals(pin, placed.claims.single().pin)
            assertTrue(placed.retired.isEmpty())
        }
        assertEquals(listOf(pin), b.pins)
        assertEquals(0, b.candidateCount)
        // A good claim ends the run (the bad claim after it is the run's second, not its third); iOS applies every bad
        // claim and keeps the one pin
        val (c, kept) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        c.see(seen("A", label), 1_100 * ms)
        c.see(seen("A", Vec3(0.0, 0.0, -0.4)), 1_400 * ms)
        assertEquals(0, kept.badInRow)
        c.see(seen("A", label), 2_500 * ms)
        assertEquals(kept, c.see(seen("A", label), 2_800 * ms).claims.single().pin)
        assertEquals(listOf(kept), c.pins)
        val (ios, one) = bookWithPin("A", Vec3(0.0, 0.0, -0.4), PinRules.IOS)
        for (t in 1_100L..3_500L step 300) assertEquals(one, ios.see(seen("A", label), t * ms).claims.single().pin)
        assertEquals(listOf(one), ios.pins)
    }

    @Test fun withPinsFrozenTheThirdBadClaimInARowIsVoidedAndItsRunSeedsANewPin() {
        // Phase 3's stand-in for rule 4, the arm Phase 4 is measured against: nothing refines a frozen pin
        val at = Vec3(0.0, 0.0, -0.4)
        val b = PinBook().also { it.refine = false }
        repeat(PIN_CONFIRM_COUNT) { b.see(seen("A", at, hit = at.copy(z = -0.45)), it * 33 * ms) }
        val pin = b.pins.single()
        assertFalse(pin.est.started)
        assertEquals(pin, b.see(seen("A", at, from = Vec3(0.02, 0.0, 0.0)), 200 * ms).claims.single().pin)
        assertEquals(Vec3(0.0, 0.0, -0.45), pin.position) // a good claim moves it no more than under iOS
        // Its label is now read 3 cm aside, over a second after its last good claim: the first two bad claims are applied
        val label = Vec3(0.03, 0.0, -0.45)
        for (t in listOf(1_300L, 1_600L)) assertEquals(pin, b.see(seen("A", label), t * ms).claims.single().pin)
        assertEquals(2, pin.badInRow)
        // The third is voided, and with the two before it seeds a candidate: a new pin on the label, no re-init
        val third = b.see(seen("A", label), 1_900 * ms)
        assertEquals(pin, third.voided.single().pin)
        assertTrue(third.claims.isEmpty())
        assertTrue(third.reinits.isEmpty())
        val born = b.pins.single { it !== pin }
        assertEquals(1_900 * ms, born.bornNs)
        assertEquals(0.0, (born.position - label).norm(), 1e-9)
        assertEquals(Vec3(0.0, 0.0, -0.45), pin.position)
        // The new pin takes its reads; the stale one misses them and retires 2 s after its first miss at 1.3 s
        var retiredAt = -1L
        for (t in 2_200L..3_700L step 300) {
            val placed = b.see(seen("A", label), t * ms)
            assertEquals(born, placed.claims.single().pin)
            if (placed.retired.isNotEmpty()) {
                assertEquals(pin, placed.retired.single())
                retiredAt = t
            }
        }
        assertEquals(3_400L, retiredAt)
        assertEquals(listOf(born), b.pins)
    }

    @Test fun switchingRulesRestartsTheRetirementCount() {
        // Five misses under Android (its twin read 20 cm aside, beyond iOS's merge), then its own reads under iOS
        val camera = Vec3(0.10, 0.0, 0.0)
        val twin = Vec3(0.20, 0.0, -0.4)
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        for (t in 1_100L..2_300L step 300) b.see(seen("A", twin, camera), t * ms)
        assertEquals(5, pin.misses)
        b.rules = PinRules.IOS
        assertEquals(0, pin.misses)
        for (t in 2_600L..9_800L step 300) b.see(seen("A", Vec3(0.0, 0.0, -0.4), camera), t * ms)
        // Back under Android its own reads under iOS have counted it: a twin-only batch is no miss at all
        b.rules = PinRules.ANDROID
        assertTrue(pin.goodClaims >= PIN_COUNTED_CLAIMS)
        assertTrue(b.see(seen("A", twin, camera), 10_100 * ms).retired.isEmpty())
        assertEquals(0, pin.misses)
        assertTrue(pin in b.pins)
    }

    @Test fun onlyAnAndroidPinOverASecondOldAndWellInsideTheImageMisses() {
        val camera = Vec3(0.05, 0.0, 0.0)
        val other = Vec3(0.10, 0.0, -0.4)
        val (y, young) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        for (t in 100L..1_000L step 100) y.see(seen("A", other, camera), t * ms)
        assertEquals(0, young.misses)
        // The camera 22 cm aside: the pin lies outside the image's inner 80 %
        val (b, aside) = bookWithPin("A", Vec3(0.0, 0.0, -0.4))
        for (t in 1_100L..5_000L step 300) b.see(seen("A", Vec3(0.30, 0.0, -0.4), Vec3(0.22, 0.0, 0.0)), t * ms)
        assertTrue(aside in b.pins)
        assertEquals(0, aside.misses)
        // Under iOS nothing retires (the twin 20 cm aside: beyond iOS's 15 cm merge)
        val (ios, kept) = bookWithPin("A", Vec3(0.0, 0.0, -0.4), PinRules.IOS)
        for (t in 1_100L..5_000L step 300) ios.see(seen("A", Vec3(0.20, 0.0, -0.4), camera), t * ms)
        assertTrue(kept in ios.pins)
        assertEquals(2, ios.pins.size)
    }

    @Test fun wellInsideIsTheImagesInnerEightyPercentInFrontWithinOneAndAHalfMetres() {
        assertTrue(wellInside(Vec3(0.0, 0.0, -0.4), Pose.IDENTITY, k))
        assertFalse(wellInside(Vec3(0.0, 0.0, 0.4), Pose.IDENTITY, k))
        assertFalse(wellInside(Vec3(0.0, 0.0, -1.6), Pose.IDENTITY, k))
        // u = 1920 + 2896 x / 0.4, and the inner 80 % ends at 3456 px (x = 0.212 m)
        assertTrue(wellInside(Vec3(0.21, 0.0, -0.4), Pose.IDENTITY, k))
        assertFalse(wellInside(Vec3(0.215, 0.0, -0.4), Pose.IDENTITY, k))
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

    @Test fun aDepthPointNeedsAKnownConfidenceOfOneTwentyEight() {
        assertFalse(depthConfident(127))
        assertTrue(depthConfident(MIN_DEPTH_CONFIDENCE))
        assertTrue(depthConfident(255))
        assertFalse(depthConfident(null)) // drift plan Phase 2: no depth image, or outside it, no longer passes
    }
}
