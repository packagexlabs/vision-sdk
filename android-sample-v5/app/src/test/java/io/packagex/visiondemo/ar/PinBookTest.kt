package io.packagex.visiondemo.ar

import io.packagex.arcount.ItemCount
import io.packagex.arcount.Pose
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The old AR Barcode renderer's pin rules (ceff1a4^ ArBarcodeRenderer, MarkerPlacementTest), as [PinBook] and [PinMotion] keep them. */
class PinBookTest {
    private val ms = 1_000_000L

    /** A read of [payload] from a camera at the origin looking at [at] (rays along -Z, as ARCore's camera looks) */
    private fun seen(payload: String, at: Vec3, from: Vec3 = Vec3(at.x, at.y, 0.0)) =
        Sighting(payload, null, at, Quat.IDENTITY, Ray(from, (at - from).unit()))

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

    @Test fun colourIsGreenCountedGreyListedWhiteUnlisted() {
        val listed = setOf("5901234123457", "A1")
        val items = listOf(ItemCount("5901234123457", 2, 2, true), ItemCount("A1", 0, 1, true))
        assertEquals(PinColour.COUNTED, pinColour("5901234123457", "EAN_13", listed, items))
        assertEquals(PinColour.LISTED, pinColour("A1", null, listed, items)) // range 0..1: not counted yet
        assertEquals(PinColour.UNLISTED, pinColour("B2", null, listed, items))
        // GTINs compare as 14 digits, as the counter does
        assertEquals(PinColour.COUNTED, pinColour("05901234123457", null, listed, items))
        assertEquals(PinColour.LISTED, pinColour("5901234123457", null, listed, emptyList()))
    }
}
