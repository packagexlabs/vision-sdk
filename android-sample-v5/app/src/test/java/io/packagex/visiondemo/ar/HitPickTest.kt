package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
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

/** Drift plan Phase 2: the hit a listed read seeds its pin with ([pickHit]) */
class HitPickTest {
    private val k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)
    private val capture = PoseRecord(0, Pose.IDENTITY, null, Tracking.TRACKING, null, k)

    /**
     * A [symbology] read at the image centre whose quad is as wide as an EAN-13 at camera depth [depthM] turned
     * [yawDeg] about the vertical (its width foreshortened by the cosine)
     */
    private fun read(depthM: Double, yawDeg: Double = 0.0, symbology: String = "ean13", u: Double = 1920.0, v: Double = 1080.0): Read {
        val w = k.fx * EAN13_WIDTH_M * cos(Math.toRadians(yawDeg)) / depthM
        val h = 0.3 * w
        return Read(0, "5901234123457", listOf(u - w / 2, v - h / 2, u + w / 2, v - h / 2, u + w / 2, v + h / 2, u - w / 2, v + h / 2), 1, symbology)
    }

    private fun Read.centreRay(c: PoseRecord = capture) = ray(c, null)

    // Hits on the read's ray, [m] metres along it
    private fun Read.point(m: Double) = RayHit(HitKind.POINT, centreRay().at(m))
    private fun Read.depth(m: Double, confidence: Int?) = RayHit(HitKind.DEPTH_POINT, centreRay().at(m), confidence = confidence)

    /** A plane facing the camera ([facing]) or turned away, its point in its polygon or not */
    private fun Read.plane(m: Double, inPolygon: Boolean = true, facing: Boolean = true) =
        RayHit(HitKind.PLANE, centreRay().at(m), Vec3(0.0, 0.0, if (facing) 1.0 else -1.0), inPolygon)

    private fun pick(r: Read, vararg hits: RayHit, rejects: IntArray? = null) = pickHit(hits.toList(), r, capture, r.centreRay(), rejects)

    private fun rejects() = IntArray(HitReject.entries.size)

    @Test fun theNearestValidHitWinsOverAFartherPlaneInItsPolygon() {
        // The label at 0.40 m, the table 0.30 m behind it: the iOS rule took the plane
        val r = read(0.40)
        val skipped = rejects()
        val p = pick(r, r.point(0.40), r.plane(0.70), rejects = skipped)!!
        assertEquals(0, p.index); assertEquals(HitSource.HIT, p.source); assertFalse(p.onPlane)
        assertEquals(r.centreRay().at(0.40), p.point)
        assertTrue(skipped.all { it == 0 }) // nothing nearer was skipped; the plane behind is never judged a rejection
    }

    @Test fun aFarPlaneIsRejectedByTheWidthGateAndAnEanTakesItsNominalWidth() {
        // The barcode at 0.20 m, the only hit a plane at 0.78 m: 3.9 times the nominal width there
        val r = read(0.20)
        val skipped = rejects()
        val p = pick(r, r.plane(0.78), rejects = skipped)!!
        assertEquals(1, skipped[HitReject.WIDTH.ordinal])
        assertEquals(HitSource.WIDTH, p.source); assertEquals(-1, p.index); assertFalse(p.onPlane)
        assertEquals(0.20, -p.point.z, 1e-9)
        assertEquals(HitReject.WIDTH, hitReject(r.plane(0.78), r, capture, r.centreRay()))
    }

    @Test fun aValidPlaneAtMostThreeCentimetresBehindThePickIsPreferred() {
        val r = read(0.40)
        val onIt = pick(r, r.point(0.40), r.point(0.41), r.plane(0.425))!!
        assertEquals(2, onIt.index); assertTrue(onIt.onPlane); assertEquals(HitSource.HIT, onIt.source)
        assertEquals(0, pick(r, r.point(0.40), r.plane(0.45))!!.index) // 5 cm behind: another surface
        assertEquals(0, pick(r, r.point(0.40), r.plane(0.42, facing = false))!!.index) // not a valid plane
        assertEquals(0, pick(r, r.plane(0.40), r.plane(0.42))!!.index) // a valid plane picked first stays
    }

    @Test fun anEanAtSixtyDegreesOfYawPassesTheWidthGate() {
        // Half the nominal width at the hit's depth
        val r = read(0.30, yawDeg = 60.0)
        assertEquals(HitSource.HIT, pick(r, r.point(0.30))!!.source)
        // At 75° the quad is a quarter of the width: beyond what 80% magnification at 60° explains
        val steep = read(0.30, yawDeg = 75.0)
        assertEquals(HitReject.WIDTH, hitReject(steep.point(0.30), steep, capture, steep.centreRay()))
        // Twice the nominal width passes, more does not
        val r2 = read(0.30)
        assertNull(hitReject(r2.point(0.59), r2, capture, r2.centreRay()))
        assertEquals(HitReject.WIDTH, hitReject(r2.point(0.61), r2, capture, r2.centreRay()))
    }

    @Test fun aDepthPointNeedsAKnownConfidence() {
        val r = read(0.30, symbology = "code128")
        val skipped = rejects()
        val p = pick(r, r.depth(0.30, null), r.point(0.35), rejects = skipped)!!
        assertEquals(1, p.index); assertEquals(1, skipped[HitReject.LOW_CONFIDENCE.ordinal])
        assertEquals(1, pick(r, r.depth(0.30, MIN_DEPTH_CONFIDENCE - 1), r.point(0.35))!!.index)
        assertEquals(0, pick(r, r.depth(0.30, MIN_DEPTH_CONFIDENCE), r.point(0.35))!!.index)
        assertFalse(depthConfident(null))
    }

    @Test fun codesWithNoNominalWidthHaveNoWidthGate() {
        val r = read(0.20, symbology = "code128")
        val p = pick(r, r.plane(0.78))!!
        assertEquals(0, p.index); assertEquals(HitSource.HIT, p.source); assertTrue(p.onPlane)
        assertNull(hitReject(r.plane(0.78), r, capture, r.centreRay(), widthM = nominalWidthM(r.symbology)))
        // Every EAN/UPC is gated, by its own width
        for (s in listOf("ean13", "UPC-A", "ean8", "upce")) {
            val q = read(0.20, symbology = s)
            assertEquals(s, HitReject.WIDTH, hitReject(q.plane(0.78), q, capture, q.centreRay()))
        }
    }

    @Test fun theCameraDepthMustLieBetweenFifteenCentimetresAndOneAndAHalfMetres() {
        val r = read(0.30, symbology = "qr")
        assertEquals(HitReject.NEAR, hitReject(r.point(0.10), r, capture, r.centreRay()))
        assertEquals(HitReject.FAR, hitReject(r.point(1.60), r, capture, r.centreRay()))
        assertNull(hitReject(r.point(0.16), r, capture, r.centreRay()))
        assertNull(hitReject(r.point(1.49), r, capture, r.centreRay()))
        // Off the image centre the depth is the camera's, not the distance along the ray
        val off = read(0.30, symbology = "qr", u = 3600.0)
        val along = 1.55 // 1.55 m along a ray 30° off the axis: 1.34 m deep
        assertNull(hitReject(off.point(along), off, capture, off.centreRay()))
    }

    @Test fun planesOffTheirPolygonOrSeenFromBehindAndOtherTrackablesAreSkipped() {
        val r = read(0.50, symbology = "code128")
        val skipped = rejects()
        val other = RayHit(HitKind.OTHER, r.centreRay().at(0.30))
        val p = pick(r, other, r.plane(0.35, inPolygon = false), r.plane(0.40, facing = false), r.point(0.50), rejects = skipped)!!
        assertEquals(3, p.index)
        assertEquals(1, skipped[HitReject.KIND.ordinal]); assertEquals(1, skipped[HitReject.OFF_POLYGON.ordinal]); assertEquals(1, skipped[HitReject.BACK_OF_PLANE.ordinal])
    }

    @Test fun withNoValidHitOtherCodesTakeTheNearestHitKeptBeforeWithinThreeMetres() {
        val r = read(0.30, symbology = "code128")
        // Off its polygon is not one the iOS rule takes; the point is beyond 1.5 m, so not valid, but within 3 m
        val p = pick(r, r.plane(0.30, inPolygon = false), r.point(2.0))!!
        assertEquals(HitSource.NEAREST, p.source); assertEquals(1, p.index); assertFalse(p.onPlane)
        val plane = pick(r, r.plane(2.0, facing = false))!!
        assertEquals(HitSource.NEAREST, plane.source); assertTrue(plane.onPlane)
        assertNull(pick(r, r.point(3.5)))
        assertNull(pick(r))
        assertNull(pick(r, r.plane(0.3, inPolygon = false)))
    }

    @Test fun withNoValidHitOtherCodesStillDropADepthPointBelowTheConfidenceGate() {
        // A plain label with depth only: the pins dropped a known confidence below the gate before Phase 2, and still do
        val r = read(0.30, symbology = "code128")
        val skipped = rejects()
        assertNull(pick(r, r.depth(0.30, MIN_DEPTH_CONFIDENCE - 1), rejects = skipped))
        assertEquals(1, skipped[HitReject.LOW_CONFIDENCE.ordinal])
        assertNull(pick(r, r.depth(0.30, 10)))
        // The next hit kept takes it, though not valid (beyond 1.5 m)
        val behind = pick(r, r.depth(0.30, 10), r.point(2.0))!!
        assertEquals(1, behind.index); assertEquals(HitSource.NEAREST, behind.source)
        // An unknown confidence is not valid, but passed before Phase 2: it still seeds, so no birth is lost
        val unknown = pick(r, r.depth(0.30, null))!!
        assertEquals(0, unknown.index); assertEquals(HitSource.NEAREST, unknown.source); assertFalse(unknown.onPlane)
        assertEquals(HitReject.LOW_CONFIDENCE, hitReject(r.depth(0.30, null), r, capture, r.centreRay()))
    }

    @Test fun anEanWithNoHitAtAllSitsOnItsRayAtItsNominalWidth() {
        val camera = Pose(Vec3(0.1, 0.2, 0.3), Quat.axisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(30.0)))
        val c = PoseRecord(0, camera, null, Tracking.TRACKING, null, k)
        val r = read(0.50, u = 2400.0, v = 700.0)
        val p = pickHit(emptyList(), r, c, r.centreRay(c))!!
        assertEquals(HitSource.WIDTH, p.source)
        val check = checkHit(p.point, r, c)
        assertEquals(0.0, check.errorPx, 1e-6); assertEquals(0.50, check.depthM, 1e-9)
        assertEquals(0.0, r.centreRay(c).distanceTo(p.point), 1e-9)
    }

    @Test fun theNominalDepthIsClampedToThePinDepths() {
        assertEquals(PIN_MAX_DEPTH_M, -nominalPoint(read(3.0), capture, EAN13_WIDTH_M)!!.z, 1e-9)
        assertEquals(PIN_MIN_DEPTH_M, -nominalPoint(read(0.05), capture, EAN13_WIDTH_M)!!.z, 1e-9)
        // An EAN-8 quad as wide as one at 0.18 m
        assertEquals(0.18, -nominalPoint(read(0.18 * EAN13_WIDTH_M / EAN8_WIDTH_M), capture, EAN8_WIDTH_M)!!.z, 1e-9)
        val flat = Read(0, "A", List(8) { 1000.0 }, 1, "ean13")
        assertNull(nominalPoint(flat, capture, EAN13_WIDTH_M))
    }

    @Test fun withNoHitAtAllAnotherCodeSitsOnItsRayAtTheDefaultDepth() {
        // Phase 3: a birth needs no hit (§3.4 rule 5)
        val camera = Pose(Vec3(0.1, 0.2, 0.3), Quat.axisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(30.0)))
        val c = PoseRecord(0, camera, null, Tracking.TRACKING, null, k)
        val r = read(0.50, symbology = "code128", u = 2400.0, v = 700.0)
        assertNull(pickHit(emptyList(), r, c, r.centreRay(c)))
        val p = defaultPick(r, c)
        assertEquals(HitSource.DEFAULT, p.source); assertEquals(-1, p.index); assertFalse(p.onPlane)
        val check = checkHit(p.point, r, c)
        assertEquals(0.0, check.errorPx, 1e-6); assertEquals(PIN_DEFAULT_DEPTH_M, check.depthM, 1e-9)
    }

    @Test fun onlyAndroidRulesDrawAPinBornOnNoHitAsARing() {
        val guessed = Pin(3, "A", Pose.IDENTITY, bornGuessed = true)
        for (refine in listOf(true, false)) {
            assertTrue(drawnAsRing(PinRules.ANDROID, refine, guessed))
            assertFalse(drawnAsRing(PinRules.IOS, refine, guessed))
        }
    }

    @Test fun withPinsFrozenOnlyThoseBornOnAPlaneOrNoHitAreRings() {
        // Phases 2 and 3: a pin born on a valid point hit is trusted, one on a plane or a guess is not
        val onPlane = Pin(1, "A", Pose.IDENTITY, bornOnPlane = true)
        val guessed = Pin(2, "A", Pose.IDENTITY, bornGuessed = true)
        val onPoint = Pin(3, "A", Pose.IDENTITY)
        assertTrue(drawnAsRing(PinRules.ANDROID, false, onPlane))
        assertTrue(drawnAsRing(PinRules.ANDROID, false, guessed))
        assertFalse(drawnAsRing(PinRules.ANDROID, false, onPoint))
        assertFalse(drawnAsRing(PinRules.IOS, false, onPlane))
    }

    @Test fun underAndroidRulesEveryPinIsARingUntilItsRaysVerifyIt() {
        // Phase 4 extends Phase 2's ring from pins born on a plane to every unverified pin, whatever it was born on
        val onPlane = Pin(1, "A", Pose.IDENTITY, bornOnPlane = true)
        val onPoint = Pin(2, "A", Pose.IDENTITY)
        assertTrue(drawnAsRing(PinRules.ANDROID, true, onPlane))
        assertFalse(drawnAsRing(PinRules.IOS, true, onPlane))
        assertTrue(drawnAsRing(PinRules.ANDROID, true, onPoint))
        assertFalse(drawnAsRing(PinRules.IOS, true, onPoint))
        // Rays from 10 cm of baseline at 0.4 m pass the core's gate: a dot
        val e = onPoint.est
        e.start(0.0, 0.0, -0.4, 0.0, 0.0, -1.0, 0.4, PIN_PRIOR_DEFAULT_M, k.fx, PriorSource.DEFAULT)
        for (i in 0..10) {
            val ox = -0.05 + 0.01 * i
            val n = kotlin.math.hypot(ox, 0.4)
            e.add(ox, 0.0, 0.0, -ox / n, 0.0, -0.4 / n)
        }
        assertTrue(onPoint.verified)
        assertFalse(drawnAsRing(PinRules.ANDROID, true, onPoint))
    }
}
