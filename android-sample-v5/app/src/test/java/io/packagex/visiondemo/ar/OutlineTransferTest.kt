package io.packagex.visiondemo.ar

import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

/** Drift plan §3.5 (Phase 1): unlisted outlines carried from the frame they were read in to the frame shown. */
class OutlineTransferTest {
    private val ms = 1_000_000L
    private val k = Intrinsics(2880.0, 2900.0, 1920.0, 1080.0, 3840, 2160)
    private val y = Vec3(0.0, 1.0, 0.0)
    private val x = Vec3(1.0, 0.0, 0.0)
    private val z = Vec3(0.0, 0.0, 1.0)

    private fun record(camera: Pose, tsMs: Long = 0, tracking: Tracking = Tracking.TRACKING) =
        PoseRecord(tsMs * ms, camera, null, tracking, null, k)

    /** A label [widthM] x [heightM] facing camera [cam] at camera depth [depthM], centred [offU] px right of the image centre: its world corners tl, tr, br, bl */
    private fun label(cam: Pose, depthM: Double, widthM: Double = EAN13_WIDTH_M, heightM: Double = 0.02, offU: Double = 0.0): List<Vec3> {
        val cx = offU * depthM / k.fx
        return listOf(
            Vec3(cx - widthM / 2, heightM / 2, -depthM), Vec3(cx + widthM / 2, heightM / 2, -depthM),
            Vec3(cx + widthM / 2, -heightM / 2, -depthM), Vec3(cx - widthM / 2, -heightM / 2, -depthM),
        ).map { cam.apply(it) }
    }

    /** The world points as camera [cam] sees them, stream pixels x0, y0 .. x3, y3 */
    private fun seen(points: List<Vec3>, cam: Pose): List<Double> =
        points.flatMap { p -> k.project(cam.inverse().apply(p))!!.toList() }

    private fun read(corners: List<Double>, tsMs: Long = 0, symbology: String? = "ean13", engineId: Int = 1) =
        Read(tsMs * ms, "X", corners, engineId, symbology)

    /** ARCore's view matrix of a camera at [pose]: its inverse, column-major */
    private fun viewOf(pose: Pose): FloatArray {
        val inv = pose.inverse()
        val cols = listOf(inv.rotate(x), inv.rotate(y), inv.rotate(z), inv.t)
        val m = FloatArray(16)
        for (c in 0 until 4) {
            m[4 * c] = cols[c].x.toFloat(); m[4 * c + 1] = cols[c].y.toFloat(); m[4 * c + 2] = cols[c].z.toFloat()
        }
        m[15] = 1f
        return m
    }

    /** A projection that puts camera point (x, y, z) at the pinhole's pixel of [ki] in a view of its size, column-major */
    private fun projectionOf(ki: Intrinsics, near: Double = 0.05, far: Double = 100.0): FloatArray {
        val m = FloatArray(16)
        m[0] = (2 * ki.fx / ki.width).toFloat()
        m[5] = (2 * ki.fy / ki.height).toFloat()
        m[8] = (1 - 2 * ki.cx / ki.width).toFloat()
        m[9] = (2 * ki.cy / ki.height - 1).toFloat()
        m[10] = (-(far + near) / (far - near)).toFloat()
        m[11] = -1f
        m[14] = (-2 * far * near / (far - near)).toFloat()
        return m
    }

    /** Carries [corners] read by [from] to a camera at [to] whose view is the stream image, at [depthM]; null when behind */
    private fun carried(corners: List<Double>, from: Pose, to: Pose, depthM: Double): FloatArray? {
        val out = FloatArray(8)
        return if (transfer(corners, record(from), viewOf(to), projectionOf(k), depthM, k.width, k.height, out)) out else null
    }

    private fun assertPixels(expected: List<Double>, actual: FloatArray?, tolPx: Double = 0.02) {
        assertArrayEquals(expected.map { it.toFloat() }.toFloatArray(), actual!!, tolPx.toFloat())
    }

    private fun maxErrPx(expected: List<Double>, actual: FloatArray): Double =
        (0 until 4).maxOf { hypot(expected[2 * it] - actual[2 * it], expected[2 * it + 1] - actual[2 * it + 1]) }

    private val camK = Pose(Vec3(0.1, 1.2, -0.3), Quat.axisAngle(Vec3(0.2, 1.0, 0.1), 0.6))

    @Test fun pureRotationIsExactAtAnyDepth() {
        val corners = label(camK, 0.4, offU = 600.0)
        val at = seen(corners, camK)
        // Panned 12° and tilted 5° about the same centre: every depth on the ray sees the same pixel
        val camN = Pose(camK.t, camK.q * Quat.axisAngle(y, 12 * PI / 180) * Quat.axisAngle(x, 5 * PI / 180))
        val truth = seen(corners, camN)
        assertTrue(maxErrPx(at, truth.map { it.toFloat() }.toFloatArray()) > 300) // the old outline is this far off
        for (d in listOf(0.08, 0.4, 1.5, Double.POSITIVE_INFINITY)) assertPixels(truth, carried(at, camK, camN, d))
    }

    @Test fun translationIsExactAtTheTrueDepth() {
        val corners = label(camK, 0.35, offU = -400.0)
        val at = seen(corners, camK)
        val camN = Pose(camK.apply(Vec3(0.05, -0.02, 0.03)), camK.q * Quat.axisAngle(y, -8 * PI / 180))
        val truth = seen(corners, camN)
        assertPixels(truth, carried(at, camK, camN, 0.35))
        assertTrue(maxErrPx(truth, carried(at, camK, camN, Double.POSITIVE_INFINITY)!!) > 100) // rotation only leaves the parallax
        // A depth too far under-corrects, never past the truth
        val far = carried(at, camK, camN, 0.7)!!
        assertTrue(maxErrPx(truth, far) < maxErrPx(truth, carried(at, camK, camN, Double.POSITIVE_INFINITY)!!))
    }

    @Test fun theQuadKeepsItsShape() {
        val corners = label(camK, 0.3)
        val at = seen(corners, camK)
        // Sideways along the label: every corner moves by the same fx·b/Z
        val slid = carried(at, camK, Pose(camK.apply(Vec3(0.04, 0.0, 0.0)), camK.q), 0.3)!!
        for (c in 0 until 4) {
            assertEquals(at[2 * c] - k.fx * 0.04 / 0.3, slid[2 * c].toDouble(), 0.02)
            assertEquals(at[2 * c + 1], slid[2 * c + 1].toDouble(), 0.02)
        }
        // Rolled 30° about the optical axis: the sides keep their lengths (to the 0.7% the pixels are taller than wide)
        val rolled = carried(at, camK, Pose(camK.t, camK.q * Quat.axisAngle(z, PI / 6)), 0.3)!!
        for (c in 0 until 4) {
            val n = (c + 1) % 4
            val before = hypot(at[2 * n] - at[2 * c], at[2 * n + 1] - at[2 * c + 1])
            val after = hypot((rolled[2 * n] - rolled[2 * c]).toDouble(), (rolled[2 * n + 1] - rolled[2 * c + 1]).toDouble())
            assertEquals(before, after, before * 0.01)
        }
    }

    @Test fun anyViewAndProjectionAreHonoured() {
        // A portrait display: the view's camera is the physical one rolled 90° about its axis, with the view's own pinhole
        val corners = label(camK, 0.5, offU = 300.0)
        val at = seen(corners, camK)
        val camN = Pose(camK.apply(Vec3(-0.03, 0.01, 0.02)), camK.q * Quat.axisAngle(x, 0.1))
        val display = Pose(camN.t, camN.q * Quat.axisAngle(z, PI / 2))
        val portrait = Intrinsics(1500.0, 1500.0, 540.0, 1100.0, 1080, 2200)
        val out = FloatArray(10)
        assertTrue(transfer(at, record(camK), viewOf(display), projectionOf(portrait), 0.5, 1080, 2200, out, offset = 2))
        val truth = corners.flatMap { p -> portrait.project(display.inverse().apply(p))!!.toList() }
        assertPixels(truth, out.copyOfRange(2, 10))
    }

    @Test fun aCornerBehindTheShownCameraDrawsNothing() {
        val at = seen(label(camK, 0.4), camK)
        val turned = Pose(camK.t, camK.q * Quat.axisAngle(y, PI))
        assertNull(carried(at, camK, turned, 0.4))
        assertNull(carried(at, camK, turned, Double.POSITIVE_INFINITY))
        assertFalse(transferredCentre(at, record(camK), 0.4, record(turned), DoubleArray(2)))
    }

    @Test fun theCarriedCentreIsInTheShownFramesImage() {
        val corners = label(camK, 0.3, offU = 800.0)
        val at = seen(corners, camK)
        val camN = Pose(camK.apply(Vec3(0.03, 0.02, -0.01)), camK.q * Quat.axisAngle(y, 0.15))
        val truth = seen(corners, camN)
        val out = DoubleArray(2)
        assertTrue(transferredCentre(at, record(camK), 0.3, record(camN), out))
        assertEquals((0 until 4).sumOf { truth[2 * it] } / 4, out[0], 1e-6)
        assertEquals((0 until 4).sumOf { truth[2 * it + 1] } / 4, out[1], 1e-6)
    }

    @Test fun theDepthComesFromTheSymbologysNominalWidth() {
        fun depth(widthM: Double, symbology: String?, depthM: Double = 0.3, farSafe: Boolean = false) =
            outlineDepthM(read(seen(label(camK, depthM, widthM), camK), symbology = symbology), k.fx, farSafe)
        assertEquals(0.03135, EAN13_WIDTH_M, 1e-12); assertEquals(0.02211, EAN8_WIDTH_M, 1e-12); assertEquals(0.01683, UPCE_WIDTH_M, 1e-12)
        assertEquals(0.3, depth(EAN13_WIDTH_M, "ean13"), 1e-9)
        assertEquals(0.3, depth(EAN13_WIDTH_M, "upca"), 1e-9)
        assertEquals(0.3, depth(EAN8_WIDTH_M, "ean8"), 1e-9)
        assertEquals(0.3, depth(UPCE_WIDTH_M, "upce"), 1e-9)
        assertEquals(0.3, depth(EAN13_WIDTH_M, "EAN_13"), 1e-9) // case and punctuation do not matter
        assertEquals(0.3, depth(EAN13_WIDTH_M, "UPC-A"), 1e-9)
        assertEquals(1.0, depth(EAN13_WIDTH_M * 2, "ean13", depthM = 2.0), 1e-9) // a symbol at 200% is taken as nearer
        assertEquals(OUTLINE_MIN_DEPTH_M, depth(EAN13_WIDTH_M, "ean13", depthM = 0.05), 1e-12)
        assertEquals(OUTLINE_MAX_DEPTH_M, depth(EAN13_WIDTH_M, "ean13", depthM = 2.5), 1e-12)
        for (s in listOf("code128", "qrcode", "itf", null)) {
            assertEquals(Double.POSITIVE_INFINITY, depth(0.05, s), 0.0)
            assertEquals(FAR_SAFE_DEPTH_M, depth(0.05, s, farSafe = true), 0.0)
        }
        assertEquals(0.3, depth(EAN13_WIDTH_M, "ean13", farSafe = true), 1e-9) // the flag is for codes with no width
        assertEquals(Double.POSITIVE_INFINITY, outlineDepthM(read(List(8) { 5.0 }), k.fx), 0.0) // a quad with no width
    }

    @Test fun anEanAtItsNominalWidthIsCarriedExactlyAcrossATranslation() {
        val corners = label(camK, 0.32, offU = 500.0)
        val r = read(seen(corners, camK))
        val camN = Pose(camK.apply(Vec3(0.06, 0.0, 0.02)), camK.q * Quat.axisAngle(y, -0.1))
        assertPixels(seen(corners, camN), carried(r.corners, camK, camN, outlineDepthM(r, k.fx)))
    }

    // --- which read is outlined, and how (§3.5.4) ---

    private class Frames(val records: Map<Long, PoseRecord>, val breakNs: Long = Long.MIN_VALUE) : CaptureFrames {
        override fun recordAt(timestampNs: Long) = records[timestampNs]
        override fun mapCorrectedSince(timestampNs: Long) = breakNs > timestampNs
    }

    private val corners = listOf(100.0, 100.0, 300.0, 100.0, 300.0, 160.0, 100.0, 160.0)
    private val track = listOf(read(corners, 300), read(corners, 200), read(corners, 100)) // newest first

    private fun frames(vararg tracking: Pair<Long, Tracking>, breakMs: Long? = null) =
        Frames(tracking.associate { (t, s) -> t * ms to record(camK, t, s) }, breakMs?.let { it * ms } ?: Long.MIN_VALUE)

    @Test fun theNewestReadWhoseFrameTrackedIsCarried() {
        val all = frames(100L to Tracking.TRACKING, 200L to Tracking.TRACKING, 300L to Tracking.TRACKING)
        val pick = chooseOutline(track, carry = true, all)!!
        assertSame(track[0], pick.read); assertEquals(300 * ms, pick.capture!!.timestampNs); assertFalse(pick.mapMoved)
        // The newest read's frame not kept, or not tracking: the next newest whose frame tracked
        assertSame(track[1], chooseOutline(track, true, frames(100L to Tracking.TRACKING, 200L to Tracking.TRACKING))!!.read)
        val paused = chooseOutline(track, true, frames(100L to Tracking.TRACKING, 200L to Tracking.PAUSED, 300L to Tracking.PAUSED))!!
        assertSame(track[2], paused.read); assertEquals(100 * ms, paused.capture!!.timestampNs)
    }

    @Test fun withNothingToCarryFromTheNewestReadIsDrawnWhereItWasRead() {
        val none = chooseOutline(track, true, frames(200L to Tracking.PAUSED))!!
        assertSame(track[0], none.read); assertNull(none.capture)
        // The frame shown does not track, or the iOS rules: today's outline
        val all = frames(100L to Tracking.TRACKING, 200L to Tracking.TRACKING, 300L to Tracking.TRACKING)
        val flat = chooseOutline(track, carry = false, all)!!
        assertSame(track[0], flat.read); assertNull(flat.capture)
        assertNull(chooseOutline(emptyList(), true, all))
    }

    @Test fun anOutlineIsNotCarriedAcrossAMapCorrectionOrATrackingLoss() {
        val moved = chooseOutline(track, true, frames(300L to Tracking.TRACKING, breakMs = 333))!!
        assertSame(track[0], moved.read); assertTrue(moved.mapMoved) // not drawn, but M3 still knows which read it was
        // The map moved before the read it would be carried from: carried
        assertFalse(chooseOutline(track, true, frames(300L to Tracking.TRACKING, breakMs = 300))!!.mapMoved)
        // The newest read's frame lost tracking; the older read is from before that loss: dropped
        val lost = chooseOutline(track, true, frames(200L to Tracking.TRACKING, 300L to Tracking.PAUSED, breakMs = 300))!!
        assertSame(track[1], lost.read); assertTrue(lost.mapMoved)
    }

    @Test fun mapBreaksAreAnchorStepsOverTwoCentimetresAndFramesThatDoNotTrack() {
        val b = MapBreaks()
        b.frame(100 * ms, tracking = true)
        b.anchorMoved(100 * ms, 0.019)
        assertFalse(b.since(0))
        b.anchorMoved(200 * ms, 0.021)
        assertTrue(b.since(199 * ms)); assertFalse(b.since(200 * ms)) // read on that frame: after the correction
        b.frame(400 * ms, tracking = false)
        assertTrue(b.since(300 * ms)); assertEquals(400 * ms, b.newestNs)
        b.anchorMoved(350 * ms, 0.5) // an older frame does not move the newest back
        assertEquals(400 * ms, b.newestNs)
        b.anchorMoved(500 * ms, Double.NaN) // a new anchor's first frame is no step
        assertEquals(400 * ms, b.newestNs)
    }

    // --- the map probe: map corrections seen with no pin and no section anchor (§3.5.4) ---

    @Test fun anAnchorStepIsTakenOnTheSameAnchorOnly() {
        val s = AnchorStep()
        val a = Any()
        assertTrue(s.at(a, 0.0, 0.0, -0.4).isNaN())
        assertEquals(0.0, s.at(a, 0.0, 0.0, -0.4), 0.0)
        assertEquals(0.05, s.at(a, 0.03, 0.0, -0.44), 1e-12)
        assertTrue(s.at(Any(), 1.0, 1.0, 1.0).isNaN()) // a handoff or a re-made anchor: no step
    }

    @Test fun theProbeIsMadeAlongTheViewAndMadeAgainWhenStoppedOrLeftBehind() {
        assertEquals(ProbeAction.MAKE, probeAction(null, 0.0))
        assertEquals(ProbeAction.KEEP, probeAction(Tracking.TRACKING, PROBE_REACH_M))
        assertEquals(ProbeAction.MAKE, probeAction(Tracking.TRACKING, PROBE_REACH_M + 0.01))
        assertEquals(ProbeAction.WAIT, probeAction(Tracking.PAUSED, 0.4))
        assertEquals(ProbeAction.MAKE, probeAction(Tracking.STOPPED, 0.4))
        val p = probePose(camK)
        val inCamera = camK.inverse().apply(p.t)
        assertEquals(0.0, inCamera.x, 1e-12); assertEquals(0.0, inCamera.y, 1e-12); assertEquals(-PROBE_DEPTH_M, inCamera.z, 1e-12)
        assertEquals(PROBE_DEPTH_M, distance(p.t.x, p.t.y, p.t.z, camK.t.x, camK.t.y, camK.t.z), 1e-12)
    }

    /** As the renderer drives it: no pin and no section anchor, only the probe, whose steps go to the map breaks */
    private class ProbeOnly(val records: Map<Long, PoseRecord>) : CaptureFrames {
        val breaks = MapBreaks()
        private val step = AnchorStep()

        fun frame(tsMs: Long, probe: Any, at: Vec3) {
            breaks.frame(tsMs * 1_000_000L, tracking = true)
            breaks.anchorMoved(tsMs * 1_000_000L, step.at(probe, at.x, at.y, at.z))
        }

        override fun recordAt(timestampNs: Long) = records[timestampNs]
        override fun mapCorrectedSince(timestampNs: Long) = breaks.since(timestampNs)
    }

    @Test fun withNoPinAndNoSectionTheProbeAloneStopsAnOutlineCrossingAMapCorrection() {
        val f = ProbeOnly(listOf(100L, 133L, 166L, 200L).associate { it * ms to record(camK, it) })
        val probe = Any()
        val at = probePose(camK).t
        f.frame(100, probe, at)
        f.frame(133, probe, at + Vec3(0.001, 0.0, 0.0))
        val read133 = listOf(read(corners, 133))
        assertFalse(chooseOutline(read133, true, f)!!.mapMoved)
        f.frame(166, probe, at + Vec3(0.051, 0.0, 0.0)) // ARCore re-aligns its map by 5 cm
        assertTrue(chooseOutline(read133, true, f)!!.mapMoved)
        assertFalse(chooseOutline(listOf(read(corners, 166)), true, f)!!.mapMoved) // read after it: carried
        // The probe made again a metre off (stopped, or left behind) is no correction
        f.frame(200, Any(), at + Vec3(1.0, 0.0, 0.0))
        assertFalse(chooseOutline(listOf(read(corners, 166)), true, f)!!.mapMoved)
    }
}
