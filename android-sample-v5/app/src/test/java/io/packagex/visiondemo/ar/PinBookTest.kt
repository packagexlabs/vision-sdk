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
import io.packagex.arcount.UnitPoint
import io.packagex.arcount.UnitState
import io.packagex.arcount.Vec3
import io.packagex.arcount.ray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The old AR Barcode renderer's pin rules (ceff1a4^ ArBarcodeRenderer, MarkerPlacementTest), as [PinBook] and [PinMotion] keep them. */
class PinBookTest {
    private val ms = 1_000_000L

    /** A read of [payload] from a camera at the origin looking at [at] (rays along -Z, as ARCore's camera looks) */
    private fun seen(code: String, at: Vec3, from: Vec3 = Vec3(at.x, at.y, 0.0)) =
        Sighting(code, at, Quat.IDENTITY, Ray(from, (at - from).unit()))

    private fun bookWithPin(payload: String, at: Vec3): Pair<PinBook, Pin> {
        val b = PinBook()
        var born: List<Pin> = emptyList()
        repeat(PIN_CONFIRM_COUNT) { born = b.place(listOf(seen(payload, at)), it * 33 * ms, mayCreate = true) }
        return b to born.single()
    }

    @Test fun aPinNeedsThreeAgreeingSightings() {
        val b = PinBook()
        val at = Vec3(0.0, 0.0, -0.5)
        assertTrue(b.place(listOf(seen("A", at)), 0, true).isEmpty())
        assertTrue(b.place(listOf(seen("A", at.copy(x = 0.01))), 33 * ms, true).isEmpty())
        val born = b.place(listOf(seen("A", at.copy(x = 0.02))), 66 * ms, true)
        assertEquals(1, born.size)
        // Born at the raw latest agreeing hit, not an average (old AR_MARKER_SMOOTHING = false)
        assertEquals(at.copy(x = 0.02), born[0].pose.t)
        assertEquals(0, b.candidateCount)
    }

    @Test fun sightingsBeyondTheCandidateRadiusStartTheirOwnCandidate() {
        val b = PinBook()
        repeat(3) { i ->
            val x = i * (PIN_CANDIDATE_RADIUS_M + 0.02) // each one 10 cm from the last: never agrees
            assertTrue(b.place(listOf(seen("A", Vec3(x, 0.0, -0.5))), i * 33 * ms, true).isEmpty())
        }
        assertEquals(3, b.candidateCount)
    }

    @Test fun aCandidateExpiresAfterOneAndAHalfSeconds() {
        val b = PinBook()
        val at = Vec3(0.0, 0.0, -0.5)
        b.place(listOf(seen("A", at)), 0, true)
        b.expireCandidates(PIN_CANDIDATE_TIMEOUT_NS)
        assertEquals(1, b.candidateCount)
        b.expireCandidates(PIN_CANDIDATE_TIMEOUT_NS + 1)
        assertEquals(0, b.candidateCount)
    }

    @Test fun noCandidateWhilePlacementIsGated() {
        val b = PinBook()
        repeat(5) { assertTrue(b.place(listOf(seen("A", Vec3(0.0, 0.0, -0.5))), it * 33 * ms, mayCreate = false).isEmpty()) }
        assertEquals(0, b.candidateCount)
    }

    // The redraw/move fix: a same-payload read nearby is the SAME pin; it only refreshes lastSeen, never moves it.
    @Test fun aReReadWithinTheAssignRadiusClaimsThePinAndDoesNotMoveIt() {
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.5))
        val born = b.place(listOf(seen("A", Vec3(0.10, 0.0, -0.5), from = Vec3(0.10, 0.0, 0.0))), 500 * ms, true)
        assertTrue(born.isEmpty())
        assertEquals(500 * ms, pin.lastSeenNs)
        assertEquals(Vec3(0.0, 0.0, -0.5), pin.position)
        assertEquals(0, b.candidateCount)
    }

    // A read clearly beyond the assign radius is another physical copy of the same text: its own pin.
    @Test fun aSecondCopyBeyondTheAssignRadiusGetsItsOwnPin() {
        val (b, _) = bookWithPin("A", Vec3(0.0, 0.0, -0.5))
        val copy = Vec3(0.30, 0.0, -0.5)
        var born: List<Pin> = emptyList()
        repeat(PIN_CONFIRM_COUNT) { born = b.place(listOf(seen("A", copy)), (200 + it * 33) * ms, true) }
        assertEquals(1, born.size)
        assertEquals(2, b.pins.size)
        assertTrue(b.mergeSiblings().isEmpty())
        assertEquals(2, b.pins.size)
    }

    @Test fun anotherPayloadNeverClaimsAPin() {
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.5))
        b.place(listOf(seen("B", Vec3(0.0, 0.0, -0.5))), 500 * ms, true)
        assertEquals(66 * ms, pin.lastSeenNs)
        assertEquals(1, b.candidateCount)
    }

    // Exclusive one-to-one: two copies read in one batch claim one pin each, the best-fitting pairing first,
    // whatever the batch order; the copy left over feeds a candidate rather than stealing the claimed pin.
    @Test fun assignmentIsExclusiveAndSmallestLateralFirst() {
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.5))
        val far = seen("A", Vec3(0.12, 0.0, -0.5), from = Vec3(0.12, 0.0, 0.0)) // 12 cm off: within 15 cm
        val near = seen("A", Vec3(0.01, 0.0, -0.5), from = Vec3(0.01, 0.0, 0.0))
        b.place(listOf(far, near), 500 * ms, true)
        assertEquals(500 * ms, pin.lastSeenNs)
        assertEquals(1, b.candidateCount) // `far` lost the pin to `near` and started a candidate
    }

    @Test fun lateralIsDepthIndependentAndBehindTheRayIsFar() {
        val ray = Ray(Vec3.ZERO, Vec3(0.0, 0.0, -1.0))
        assertEquals(0.05, lateral(ray, Vec3(0.05, 0.0, -0.3)), 1e-9)
        assertEquals(0.05, lateral(ray, Vec3(0.05, 0.0, -2.0)), 1e-9)
        assertEquals(Double.MAX_VALUE, lateral(ray, Vec3(0.0, 0.0, 0.5)), 0.0)
    }

    // A birth race (two candidates maturing in the same batch, 10 cm apart) leaves one pin: the most recently seen,
    // on a tie the later one.
    private fun birthRace(): Triple<PinBook, Pin, Pin> {
        val b = PinBook()
        var born: List<Pin> = emptyList()
        repeat(PIN_CONFIRM_COUNT) {
            born = b.place(listOf(seen("A", Vec3(0.0, 0.0, -0.5)), seen("A", Vec3(0.10, 0.0, -0.5))), it * 33 * ms, true)
        }
        assertEquals(2, born.size)
        assertTrue(b.mergePending)
        return Triple(b, born[0], born[1])
    }

    @Test fun siblingsAtTheSameSpotMergeOnATieKeepingTheLater() {
        val (b, first, second) = birthRace()
        assertEquals(listOf(first), b.mergeSiblings())
        assertEquals(listOf(second), b.pins)
        assertFalse(b.mergePending)
    }

    @Test fun siblingsAtTheSameSpotMergeKeepingTheMostRecentlySeen() {
        val (b, first, second) = birthRace()
        b.place(listOf(seen("A", Vec3(0.0, 0.0, -0.5))), 100 * ms, true) // claims `first`, the nearer to its ray
        assertEquals(100 * ms, first.lastSeenNs)
        assertEquals(listOf(second), b.mergeSiblings())
        assertEquals(listOf(first), b.pins)
    }

    @Test fun clearDropsPinsAndCandidates() {
        val (b, _) = bookWithPin("A", Vec3(0.0, 0.0, -0.5))
        b.place(listOf(seen("B", Vec3(0.3, 0.0, -0.5))), 100 * ms, true)
        b.clear()
        assertTrue(b.pins.isEmpty())
        assertEquals(0, b.candidateCount)
    }

    // Staleness and motion (old drainDetections): a batch at most 500 ms old, captured after the last immoderate frame.
    @Test fun batchesOlderThanHalfASecondAreDropped() {
        val m = PinMotion()
        m.onFrame(1_000 * ms, Pose.IDENTITY, tracking = true)
        assertTrue(m.accepts(1_000 * ms + 1, 1_500 * ms))
        assertTrue(m.accepts(1_000 * ms + 1, 1_000 * ms + 1 + PIN_MAX_BATCH_AGE_NS))
        assertFalse(m.accepts(1_000 * ms + 1, 1_000 * ms + 2 + PIN_MAX_BATCH_AGE_NS))
    }

    @Test fun aBatchCapturedBeforeImmoderateMotionIsDropped() {
        val m = PinMotion()
        m.onFrame(0, Pose.IDENTITY, true)
        m.onFrame(33 * ms, Pose.IDENTITY, true)
        assertTrue(m.cameraModerate)
        // 2 cm in 33 ms: 0.6 m/s, beyond the moderate 0.4 m/s
        m.onFrame(66 * ms, Pose(Vec3(0.02, 0.0, 0.0), Quat.IDENTITY), true)
        assertFalse(m.cameraModerate)
        assertFalse(m.accepts(50 * ms, 70 * ms))
        assertFalse(m.accepts(66 * ms, 70 * ms))
        assertTrue(m.accepts(67 * ms, 70 * ms))
    }

    @Test fun rotationAboveSixtyDegreesPerSecondIsImmoderate() {
        val m = PinMotion()
        m.onFrame(0, Pose.IDENTITY, true)
        m.onFrame(100 * ms, Pose(Vec3.ZERO, Quat.axisAngle(Vec3(0.0, 0.0, 1.0), Math.toRadians(5.0))), true) // roll: 50 deg/s
        assertEquals(50.0, m.rotationDps, 1e-3)
        assertTrue(m.cameraModerate)
        m.onFrame(200 * ms, Pose(Vec3.ZERO, Quat.axisAngle(Vec3(0.0, 0.0, 1.0), Math.toRadians(12.0))), true) // 70 deg/s
        assertFalse(m.cameraModerate)
    }

    @Test fun trackingLossDistrustsEverythingCapturedBefore() {
        val m = PinMotion()
        m.onFrame(0, Pose.IDENTITY, true)
        m.onFrame(33 * ms, Pose.IDENTITY, false)
        assertFalse(m.accepts(20 * ms, 40 * ms))
        assertFalse(m.accepts(33 * ms, 40 * ms))
    }

    @Test fun warmUpNeedsSixtyTrackedFrames() {
        val m = PinMotion()
        repeat(PIN_WARM_UP_FRAMES - 1) { m.onFrame(it * 33 * ms, Pose.IDENTITY, true) }
        m.onFrame(5_000 * ms, Pose.IDENTITY, false) // untracked frames do not count
        assertFalse(m.mapReady)
        m.onFrame(5_033 * ms, Pose.IDENTITY, true)
        assertTrue(m.mapReady)
    }

    @Test fun colourIsGreenCountedGreyListedAndNoneUnlisted() {
        val keys = listedKeys(setOf("5901234123457", "A1"))
        val items = listOf(ItemCount("5901234123457", 2, 2, true), ItemCount("A1", 0, 1, true))
        assertEquals(PinColour.COUNTED, pinColour(ItemCode.key("5901234123457"), keys, items))
        assertEquals(PinColour.LISTED, pinColour("A1", keys, items)) // range 0..1: not counted yet
        assertNull(pinColour("B2", keys, items))
        // GTINs compare as 14 digits, as the counter keys them
        assertEquals(PinColour.COUNTED, pinColour(ItemCode.key("05901234123457"), keys, items))
        assertEquals(PinColour.LISTED, pinColour(ItemCode.key("5901234123457"), keys, emptyList()))
    }

    // Hit checks: the hit reprojected into its read's own frame, and its depth there

    private val k4k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)
    private val capture = PoseRecord(0, Pose.IDENTITY, null, Tracking.TRACKING, null, k4k)

    /** A read 200 px wide centred on pixel ([u], [v]) */
    private fun readAt(u: Double, v: Double) = Read(0, "A", listOf(u - 100, v - 50, u + 100, v - 50, u + 100, v + 50, u - 100, v + 50), 1)

    @Test fun aHitOnTheReadsRayReprojectsOntoItsCentre() {
        val r = readAt(2400.0, 700.0)
        val hit = capture.ray(r.centreU, r.centreV, null).at(0.4)
        val c = checkHit(hit, r, capture)
        assertEquals(0.0, c.errorPx, 1e-6)
        assertEquals(0.4 * (-k4k.rayInCamera(2400.0, 700.0).z), c.depthM, 1e-9)
        assertTrue(c.accepted)
    }

    @Test fun aHitOffTheRayByMoreThanHalfTheQuadOrTwentyFivePixelsIsRejected() {
        val r = readAt(1920.0, 1080.0) // 200 px wide: the limit is 100 px
        fun at(px: Double) = Vec3(px / 2896.0 * 0.5, 0.0, -0.5) // px to the right of the centre, 0.5 m away
        assertTrue(checkHit(at(99.0), r, capture).accepted)
        assertFalse(checkHit(at(101.0), r, capture).accepted)
        val small = Read(0, "A", listOf(1900.0, 1070.0, 1940.0, 1070.0, 1940.0, 1090.0, 1900.0, 1090.0), 1) // 40 px wide: 25 px
        assertTrue(checkHit(at(24.0), small, capture).accepted)
        assertFalse(checkHit(at(26.0), small, capture).accepted)
    }

    @Test fun aHitOutsideFifteenCentimetresToOneAndAHalfMetresIsRejected() {
        val r = readAt(1920.0, 1080.0)
        assertFalse(checkHit(Vec3(0.0, 0.0, -0.10), r, capture).accepted)
        assertTrue(checkHit(Vec3(0.0, 0.0, -0.15), r, capture).accepted)
        assertTrue(checkHit(Vec3(0.0, 0.0, -1.5), r, capture).accepted)
        assertFalse(checkHit(Vec3(0.0, 0.0, -1.75), r, capture).accepted) // the floor behind the shelf
        val behind = checkHit(Vec3(0.0, 0.0, 0.5), r, capture)
        assertFalse(behind.accepted)
        assertEquals(Double.POSITIVE_INFINITY, behind.errorPx, 0.0)
    }

    // The core's unit points

    private fun unit(key: String, at: Vec3, code: String = "A") = UnitPoint(key, 1, code, UnitState.COUNTED, at)

    @Test fun aUnitPointMakesAPinAtTheCoresPoint() {
        val b = PinBook()
        val placed = b.follow(listOf(unit("s1/1", Vec3(0.0, 0.0, -0.4))), 0)
        assertEquals(1, placed.size)
        val pin = placed.single()
        assertTrue(pin.fromCore)
        assertEquals("s1/1", pin.unitKey)
        assertEquals(Vec3(0.0, 0.0, -0.4), pin.pose.t)
    }

    @Test fun aCorePinMovesOnlyWhenTheCoresPointMovesMoreThanThreeCentimetres() {
        val b = PinBook()
        val pin = b.follow(listOf(unit("s1/1", Vec3(0.0, 0.0, -0.4))), 0).single()
        assertTrue(b.follow(listOf(unit("s1/1", Vec3(0.02, 0.0, -0.4))), 1).isEmpty())
        assertEquals(Vec3(0.0, 0.0, -0.4), pin.pose.t)
        assertEquals(listOf(pin), b.follow(listOf(unit("s1/1", Vec3(0.04, 0.0, -0.4))), 2))
        assertEquals(Vec3(0.04, 0.0, -0.4), pin.pose.t)
        assertEquals(1, b.pins.size)
    }

    @Test fun aProvisionalPinSnapsToTheCoresPoint() {
        val (b, pin) = bookWithPin("A", Vec3(0.0, 0.0, -0.5))
        assertFalse(pin.fromCore)
        val placed = b.follow(listOf(unit("s1/1", Vec3(0.08, 0.0, -0.45))), 1)
        assertEquals(listOf(pin), placed)
        assertTrue(pin.fromCore)
        assertEquals(Vec3(0.08, 0.0, -0.45), pin.pose.t)
        assertEquals(1, b.pins.size)
    }

    @Test fun identicalUnitsAPitchApartKeepTheirOwnPinsAndAreNeverMerged() {
        val b = PinBook()
        b.follow(listOf(unit("s1/1", Vec3(0.0, 0.0, -0.4)), unit("s1/2", Vec3(0.06, 0.0, -0.4))), 0)
        assertEquals(2, b.pins.size)
        assertTrue(b.mergeSiblings().isEmpty())
        assertEquals(2, b.pins.size)
    }

    @Test fun pinsOutliveTheirSectionAndTheNextSectionsUnitTakesThePinInPlace() {
        val b = PinBook()
        val pin = b.follow(listOf(unit("s1/1", Vec3(0.0, 0.0, -0.4))), 0).single()
        // The section closed: no points; the pin stays
        assertTrue(b.follow(emptyList(), 1).isEmpty())
        assertEquals(listOf(pin), b.pins)
        // A new section's unit of the same barcode, 1 cm off: the same pin, not moved
        assertTrue(b.follow(listOf(unit("s2/1", Vec3(0.01, 0.0, -0.4))), 2).isEmpty())
        assertEquals("s2/1", pin.unitKey)
        assertEquals(1, b.pins.size)
        // Another code at the same place is another pin
        assertEquals(1, b.follow(listOf(unit("s2/2", Vec3(0.01, 0.0, -0.4), code = "B")), 3).size)
        assertEquals(2, b.pins.size)
    }

    @Test fun aProvisionalPinNearACorePinOfItsCodeIsMergedAway() {
        val b = PinBook()
        val core = b.follow(listOf(unit("s1/1", Vec3(0.30, 0.0, -0.4))), 0).single()
        // A provisional pin 20 cm from it (its rays miss the core pin), seen after it
        var born: List<Pin> = emptyList()
        repeat(PIN_CONFIRM_COUNT) { born = b.place(listOf(seen("A", Vec3(0.10, 0.0, -0.4))), (10 + it) * ms, true) }
        val provisional = born.single()
        // A better triangulation moves the core pin to 10 cm from it: the provisional one goes, though seen later
        assertEquals(listOf(core), b.follow(listOf(unit("s1/1", Vec3(0.0, 0.0, -0.4))), 20 * ms))
        assertEquals(listOf(provisional), b.mergeSiblings())
        assertEquals(listOf(core), b.pins)
    }
}
