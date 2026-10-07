package io.packagex.visiondemo.ar

import io.packagex.arcount.CountConfig
import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Quat
import io.packagex.arcount.Ray
import io.packagex.arcount.Read
import io.packagex.arcount.Tracking
import io.packagex.arcount.Triangulation
import io.packagex.arcount.Vec3
import io.packagex.arcount.ray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/** Drift plan Phase 4 (P2): each pin refined from its claims' rays, in its anchor's frame (§3.4 rules 1, 4, 8 and 9) */
class PinEstimatorTest {
    private val ms = 1_000_000L
    private val k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)
    private val sigma = PIN_SIGMA_PX / k.fx

    /** [e] takes the ray from [from] through [at], its direction off by N(0, [noisePx]) pixels at fx */
    private fun PinEstimator.see(from: Vec3, at: Vec3, noisePx: Double = 0.0, rnd: Random = Random(0)) {
        val d = noisy(from, at, noisePx, rnd)
        add(from.x, from.y, from.z, d.x, d.y, d.z)
    }

    private fun noisy(from: Vec3, at: Vec3, noisePx: Double, rnd: Random): Vec3 {
        val d = (at - from).unit()
        if (noisePx == 0.0) return d
        return (d + Vec3(rnd.gaussian(), rnd.gaussian(), rnd.gaussian()) * (noisePx / k.fx / kotlin.math.sqrt(2.0))).unit()
    }

    /** N(0, 1), Box-Muller */
    private fun Random.gaussian(): Double = kotlin.math.sqrt(-2 * kotlin.math.ln(1 - nextDouble())) * kotlin.math.cos(2 * Math.PI * nextDouble())

    private fun PinEstimator.point() = Vec3(x, y, z)

    /** A prior at [at] on the ray from the origin, [sigmaM] along it */
    private fun startedAt(at: Vec3, sigmaM: Double = PIN_PRIOR_HITS_M, source: PriorSource = PriorSource.HITS) = PinEstimator().also {
        val d = at.unit()
        it.start(at.x, at.y, at.z, d.x, d.y, d.z, at.norm(), sigmaM, k.fx, source)
    }

    /**
     * A read of [code] at [target] by a camera at [from] looking along -Z, hit at [hit] ([source]): a [w] x [w]/2 px quad
     * around [target]'s pixel
     */
    private fun seen(
        code: String,
        target: Vec3,
        from: Vec3 = Vec3(target.x, target.y, 0.0),
        hit: Vec3 = target,
        source: HitSource = HitSource.HIT,
        symbology: String? = null,
        w: Double = 200.0,
    ): Sighting {
        val camera = Pose(from, Quat.IDENTITY)
        val (u, v) = k.project(camera.inverse().apply(target))!!
        val corners = listOf(u - w / 2, v - w / 4, u + w / 2, v - w / 4, u + w / 2, v + w / 4, u - w / 2, v + w / 4)
        val capture = PoseRecord(0, camera, null, Tracking.TRACKING, null, k)
        val read = Read(0, code, corners, 1, symbology)
        return sightingOf(read, capture, hit, Quat.IDENTITY, read.ray(capture, null), 104.0, source = source).copy(code = code)
    }

    private val yes: (Pin) -> Boolean = { true }

    private fun PinBook.see(s: Sighting, t: Long) = place(listOf(s), t, t, true, anchor = yes)

    // --- rule 1: the estimator ---

    @Test fun aPinBornTwentyCentimetresTooDeepConvergesWithinACentimetreAfterFiveCentimetresEachWay() {
        // A label at 0.3 m; its pin born on hits at 0.5 m, all from one spot: no parallax yet
        val label = Vec3(0.0, 0.0, -0.3)
        val b = PinBook()
        repeat(PIN_CONFIRM_COUNT) { b.see(seen("A", label, hit = Vec3(0.0, 0.0, -0.5)), it * 33 * ms) }
        val pin = b.pins.single()
        assertEquals(PriorSource.HITS, pin.est.priorSource)
        assertEquals(0.2, (pin.position - label).norm(), 0.01)
        assertFalse(pin.verified)
        // The camera slides 5 cm to the right, then 5 cm to the left, a read every 5 mm, each claimed
        var t = 100L
        val path = (1..10).map { it * 0.005 } + (9 downTo -10).map { it * 0.005 }
        for (x in path) {
            val placed = b.see(seen("A", label, from = Vec3(x, 0.0, 0.0)), t * ms)
            assertEquals("at x $x", pin, placed.claims.single().pin)
            t += 100
        }
        assertTrue((pin.position - label).norm() < 0.01)
        assertTrue(pin.verified)
        assertEquals(listOf(pin), b.pins)
    }

    /** [e] takes the ray from [from] through [at] of a read [widthPx] wide */
    private fun PinEstimator.seeSized(from: Vec3, at: Vec3, widthPx: Double) {
        val d = (at - from).unit()
        add(from.x, from.y, from.z, d.x, d.y, d.z, widthPx)
    }

    @Test fun aVerifiedPinOnAStillPhoneDoesNotSlidePastTwiceTheRangeItsSizeGives() {
        // 14:07: verified at 0.27-0.31 m over 2 cm of baseline, the phone then held still while ARCore's pose wandered
        // 2 cm, its rays meeting as if far away: 8 pins slid to 0.58-1.46 m. Here: verified at 0.3 m, the label 300 px
        // wide all along, then rays from a 2 cm wander that meet at 1.2 m
        val label = Vec3(0.0, 0.0, -0.3)
        val e = startedAt(Vec3(0.0, 0.0, -0.4), PIN_PRIOR_DEFAULT_M, PriorSource.DEFAULT)
        for (i in 0..20) e.seeSized(Vec3(-0.01 + 0.001 * i, 0.0, 0.0), label, 300.0)
        assertTrue(e.verified)
        assertEquals(0.3, e.point().norm(), 0.01)
        assertEquals(300.0 * 0.3 / k.fx, e.widthM, 0.001)
        val far = Vec3(0.0, 0.0, -1.2)
        for (i in 0..80) e.seeSized(Vec3(0.02 * kotlin.math.sin(i * 0.4), 0.0, 0.0), far, 300.0)
        assertTrue("${e.point().norm()} m", e.point().norm() <= 0.61) // 0.3 m · PIN_SIZE_RATIO 2
    }

    @Test fun theSizeGuardFollowsAPhoneThatBacksAway() {
        // Verified at 0.3 m, then the camera backs to 0.6 m while sliding sideways, the label shrinking from 300 px to 150
        val label = Vec3(0.0, 0.0, -0.3)
        val e = startedAt(Vec3(0.0, 0.0, -0.4), PIN_PRIOR_DEFAULT_M, PriorSource.DEFAULT)
        for (i in 0..20) e.seeSized(Vec3(-0.05 + 0.005 * i, 0.0, 0.0), label, 300.0)
        assertTrue(e.verified)
        for (i in 1..30) {
            val from = Vec3(0.05 * kotlin.math.sin(i * 0.3), 0.0, 0.01 * i)
            e.seeSized(from, label, 300.0 * 0.3 / (label - from).norm())
        }
        assertTrue("${e.point()}", (e.point() - label).norm() < 0.01)
    }

    @Test fun noisyRaysOverTenCentimetresOfBaselineVerifyTheDepthAndDropThePrior() {
        val label = Vec3(0.02, -0.01, -0.3)
        val e = startedAt(Vec3(0.0, 0.0, -0.5))
        val rnd = Random(3)
        for (i in 0..20) e.see(Vec3(-0.05 + 0.005 * i, 0.0, 0.0), label, 3.0, rnd)
        assertTrue(e.verified)
        assertTrue("${e.point()}", (e.point() - label).norm() < 0.005)
        assertTrue(e.sigmaZ < 0.02)
    }

    @Test fun thePriorBoundsTheErrorBeforeThereIsParallax() {
        // Rays from one spot (a phone that only turns) hold no depth: the point stays on them at the prior's depth,
        // 20 cm off and unverified
        val label = Vec3(0.0, 0.0, -0.3)
        val still = startedAt(Vec3(0.0, 0.0, -0.5))
        repeat(20) { still.see(Vec3.ZERO, label) }
        assertFalse(still.verified)
        assertEquals(0.0, (still.point() - Vec3(0.0, 0.0, -0.5)).norm(), 1e-9)
        // Their noise fans them out from the camera: it moves the point across them, never along them toward it
        val noisy = startedAt(Vec3(0.0, 0.0, -0.5))
        val rnd = Random(5)
        repeat(60) { noisy.see(Vec3.ZERO, label, 5.0, rnd) }
        assertFalse(noisy.verified)
        assertEquals("${noisy.point()}", 0.5, noisy.point().norm(), 0.005)
        assertTrue("${noisy.point()}", kotlin.math.hypot(noisy.x, noisy.y) < 0.002)
        assertEquals(PriorSource.HITS, noisy.priorSource)
    }

    @Test fun aStillPhoneKeepsEvenTheWeakestPriorsDepth() {
        // A mounted phone reads a label at 0.40 m: every ray from one spot, 3-5 px of noise, 22 claims (the boost's 2 s)
        // to 40. Those rays hold no depth, so each prior's holds, the default's (σ 25 cm) too
        val label = Vec3(0.0, 0.0, -0.4)
        val priors = listOf(PriorSource.DEFAULT to PIN_PRIOR_DEFAULT_M, PriorSource.WIDTH to PIN_PRIOR_WIDTH * 0.4, PriorSource.HITS to PIN_PRIOR_HITS_M)
        for ((source, sigmaM) in priors) for (noise in listOf(3.0, 4.0, 5.0)) for (n in listOf(22, 40)) for (seed in 0 until 6) {
            val e = startedAt(label, sigmaM, source)
            val rnd = Random(seed)
            repeat(n) { e.see(Vec3.ZERO, label, noise, rnd) }
            val what = "$source, $noise px, $n rays, seed $seed: ${e.point()}"
            assertFalse(what, e.verified)
            assertEquals(what, 0.4, e.point().norm(), 0.4 * 0.05)
        }
    }

    @Test fun aSolveOutsideTheDepthRangeOrBehindARayIsNotTaken() {
        // A prior at 1.4 m, then rays from 10 cm apart that meet 2 m away: the second pulls the point beyond 1.5 m, so
        // it keeps its place after the first
        val e = startedAt(Vec3(0.0, 0.0, -1.4), 0.25)
        e.see(Vec3(0.05, 0.0, 0.0), Vec3(0.0, 0.0, -2.0))
        val first = e.point()
        assertTrue(first.norm() < PIN_SOLVE_MAX_M)
        e.see(Vec3(-0.05, 0.0, 0.0), Vec3(0.0, 0.0, -2.0))
        assertEquals(first, e.point())
        // Behind a ray: a prior 0.3 m ahead of the camera, a ray from 0.5 m further along looking back past it
        val behind = startedAt(Vec3(0.0, 0.0, -0.3), 0.25)
        behind.see(Vec3(0.0, 0.0, -0.8), Vec3(0.0, 0.01, -1.2))
        assertEquals(Vec3(0.0, 0.0, -0.3), behind.point())
    }

    @Test fun theNewestTwentyRaysAndTenKeyRaysACentimetreApartAreKept() {
        val e = startedAt(Vec3(0.0, 0.0, -0.4))
        for (i in 0 until 60) e.see(Vec3(-0.15 + 0.005 * i, 0.0, 0.0), Vec3(0.0, 0.0, -0.4))
        assertEquals(PIN_NEW_RAYS, e.rayCount)
        assertEquals(PIN_KEY_RAYS, e.keyCount)
    }

    @Test fun theGateTestIsTheCoresTriangulationFit() {
        // RayGate is Depth.kt's fit without allocation: the same gate, point and σz on random rays, outliers among them
        val config = CountConfig()
        val gate = RayGate(PIN_NEW_RAYS + PIN_KEY_RAYS, config)
        val rnd = Random(11)
        var gated = 0
        repeat(400) { trial ->
            val n = rnd.nextInt(2, PIN_NEW_RAYS + PIN_KEY_RAYS + 1)
            val target = Vec3(rnd.nextDouble(-0.1, 0.1), rnd.nextDouble(-0.1, 0.1), -rnd.nextDouble(0.1, 1.6))
            val spread = rnd.nextDouble(0.0, 0.15)
            val rays = (0 until n).map {
                val o = Vec3(rnd.nextDouble(-spread, spread), rnd.nextDouble(-spread / 4, spread / 4), rnd.nextDouble(-0.02, 0.02))
                val px = if (rnd.nextDouble() < 0.1) 80.0 else rnd.nextDouble(0.0, 12.0)
                Ray(o, noisy(o, target, px, rnd))
            }
            val flat = DoubleArray(6 * n)
            rays.forEachIndexed { i, r -> doubleArrayOf(r.origin.x, r.origin.y, r.origin.z, r.dir.x, r.dir.y, r.dir.z).copyInto(flat, 6 * i) }
            val expected = Triangulation.fit(rays, sigma, config)
            val got = gate.fit(flat, n, sigma)
            assertEquals("trial $trial", expected?.gate, got)
            if (expected != null && got != null) {
                gated++
                assertEquals("trial $trial", 0.0, (expected.point - Vec3(gate.x[0], gate.x[1], gate.x[2])).norm(), 1e-9)
                assertEquals("trial $trial", expected.sigmaZ, gate.sigmaZ, 1e-9)
            }
        }
        assertTrue("gated $gated", gated > 40)
    }

    // --- rule 4: re-init ---

    @Test fun threeBadClaimsInARowReInitialiseOnTheirRaysAfterATenCentimetreShift() {
        val label = Vec3(0.0, 0.0, -0.4)
        val e = startedAt(label)
        for (i in 0..10) e.see(Vec3(-0.05 + 0.01 * i, 0.0, 0.0), label)
        assertTrue(e.verified)
        // The label is now read 10 cm aside (a map shift its anchor did not follow)
        val moved = Vec3(0.10, 0.0, -0.4)
        val rays = listOf(Vec3(0.06, 0.0, 0.0), Vec3(0.08, 0.0, 0.0), Vec3(0.10, 0.0, 0.0)).map { Ray(it, (moved - it).unit()) }
        assertFalse(e.bad(rays[0].origin.x, 0.0, 0.0, rays[0].dir.x, rays[0].dir.y, rays[0].dir.z))
        assertFalse(e.bad(rays[1].origin.x, 0.0, 0.0, rays[1].dir.x, rays[1].dir.y, rays[1].dir.z))
        assertEquals(2, e.badInRow)
        assertEquals(0.0, (e.point() - label).norm(), 1e-6) // held, not used
        assertTrue(e.bad(rays[2].origin.x, 0.0, 0.0, rays[2].dir.x, rays[2].dir.y, rays[2].dir.z))
        assertEquals(PriorSource.REINIT, e.priorSource)
        assertEquals(0, e.badInRow)
        assertEquals(3, e.rayCount)
        assertEquals(0, e.keyCount)
        assertFalse(e.verified) // no easing: a new estimate, its depth to verify again
        assertTrue("${e.point()}", (e.point() - moved).norm() < 0.01)
    }

    @Test fun aGoodClaimEndsTheBadRun() {
        val label = Vec3(0.0, 0.0, -0.4)
        val e = startedAt(label)
        e.see(Vec3.ZERO, label)
        assertFalse(e.bad(0.0, 0.0, 0.0, 0.25, 0.0, -0.97))
        assertFalse(e.bad(0.0, 0.0, 0.0, 0.25, 0.0, -0.97))
        e.see(Vec3.ZERO, label)
        assertEquals(0, e.badInRow)
        assertFalse(e.bad(0.0, 0.0, 0.0, 0.25, 0.0, -0.97))
        assertEquals(PriorSource.HITS, e.priorSource)
    }

    // --- rule 3 on a refined pin ---

    @Test fun aVoidedClaimLeavesThePinAsItWas() {
        val label = Vec3(0.0, 0.0, -0.4)
        val b = PinBook()
        repeat(PIN_CONFIRM_COUNT) { b.see(seen("A", label), it * 33 * ms) }
        val pin = b.pins.single()
        b.see(seen("A", label, from = Vec3(0.01, 0.0, 0.0)), 300 * ms)
        val rays = pin.est.rayCount
        val at = pin.position
        // Its twin 3.5 cm aside, read from between them half a second after the pin's good claim: a neighbour's read
        val placed = b.see(seen("A", Vec3(0.035, 0.0, -0.4), from = Vec3(0.0175, 0.0, 0.0)), 800 * ms)
        assertEquals(pin, placed.voided.single().pin)
        assertEquals(rays, pin.est.rayCount)
        assertEquals(0, pin.badInRow)
        assertEquals(at, pin.position)
    }

    // --- births: rule 1's prior (and rule 9's birth snapshot) ---

    @Test fun aNewbornTakesTheMedianOfItsValidHitsElseTheNominalWidthElseTheDefaultDepth() {
        val label = Vec3(0.0, 0.0, -0.4)
        fun born(vararg sightings: Sighting): Pin {
            val b = PinBook()
            sightings.forEachIndexed { i, s -> b.see(s, i * 33 * ms) }
            return b.pins.single()
        }
        // Two valid hits 5 cm too deep, one at the nominal width: their median
        val deep = Vec3(0.0, 0.0, -0.45)
        val hits = born(seen("A", label, hit = deep), seen("A", label, hit = deep), seen("A", label, source = HitSource.WIDTH))
        assertEquals(PriorSource.HITS, hits.est.priorSource)
        assertEquals(0.45, -hits.position.z, 1e-3)
        // Points at a verified neighbour's depth (no hit test) count as valid hits
        val twin = born(seen("A", label, hit = deep, source = HitSource.PIN), seen("A", label, hit = deep, source = HitSource.PIN), seen("A", label, source = HitSource.WIDTH))
        assertEquals(PriorSource.HITS, twin.est.priorSource)
        assertEquals(0.45, -twin.position.z, 1e-3)
        // An EAN-13 with no valid hit, its quad 227 px wide: 0.4 m
        val ean = (0 until 3).map { seen("A", label, hit = Vec3(0.0, 0.0, -0.9), source = HitSource.NEAREST, symbology = "ean13", w = 2896 * EAN13_WIDTH_M / 0.4) }
        val width = born(*ean.toTypedArray())
        assertEquals(PriorSource.WIDTH, width.est.priorSource)
        assertEquals(0.4, -width.position.z, 1e-3)
        // A Code 128 with none: 0.40 m, whatever its nearest hit
        val code128 = (0 until 3).map { seen("A", label, hit = Vec3(0.0, 0.0, -0.9), source = HitSource.NEAREST, symbology = "code128") }
        val default = born(*code128.toTypedArray())
        assertEquals(PriorSource.DEFAULT, default.est.priorSource)
        assertEquals(PIN_DEFAULT_DEPTH_M, -default.position.z, 1e-3)
        // Under iOS rules nothing is refined: the pin stays at its birth median until the Android rules first claim it
        val ios = PinBook().also { it.rules = PinRules.IOS }
        repeat(PIN_CONFIRM_COUNT) { ios.see(seen("A", label, hit = deep), it * 33 * ms) }
        val frozen = ios.pins.single()
        assertFalse(frozen.est.started)
        assertEquals(deep, frozen.position)
        ios.rules = PinRules.ANDROID
        ios.see(seen("A", label, from = Vec3(0.01, 0.0, 0.0)), 500 * ms)
        assertEquals(PriorSource.DEFAULT, frozen.est.priorSource)
        assertEquals(1, frozen.est.rayCount)
    }

    @Test fun theMergesSurvivorTakesTheOthersNewestRays() {
        val b = PinBook()
        val label = Vec3(0.0, 0.0, -0.4)
        repeat(PIN_CONFIRM_COUNT) { b.see(seen("A", label), it * 33 * ms) }
        val first = b.pins.single()
        val second = Pin(99, "A", Pose(Vec3(0.005, 0.0, -0.4), Quat.IDENTITY)).also {
            it.est.start(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.4, PIN_PRIOR_HITS_M, k.fx, PriorSource.HITS)
            repeat(5) { i -> val o = Vec3(0.002 * i, 0.0, 0.0) - Vec3(0.005, 0.0, -0.4); val d = (Vec3.ZERO - o).unit(); it.est.add(o.x, o.y, o.z, d.x, d.y, d.z) }
        }
        (b.pins as MutableList<Pin>) += second
        val rays = first.est.rayCount
        assertEquals(listOf(second), b.mergeSiblings())
        assertEquals(rays + PIN_ABSORB_RAYS, first.est.rayCount)
    }

    // --- rules 8 and 9: the anchor's frame ---

    private fun turned(deg: Double) = Quat.axisAngle(Vec3(0.3, 1.0, 0.2), Math.toRadians(deg))

    /** A pin anchored at [anchor], its point refined to [label] (world) from rays over ±5 cm of baseline captured at 0..10 ms */
    private fun refinedPin(anchor: Pose, label: Vec3): Pin {
        val pin = Pin(1, "A", anchor)
        val local = anchor.inverse()
        val p = local.apply(label)
        pin.est.start(p.x, p.y, p.z + 0.05, 0.0, 0.0, -1.0, 0.4, PIN_PRIOR_HITS_M, k.fx, PriorSource.HITS)
        val out = DoubleArray(6)
        for (i in 0..10) {
            val o = Vec3(-0.05 + 0.01 * i, 0.0, 0.0)
            pin.anchors.toFrame(pin.anchors.slotAt(i * ms), Ray(o, (label - o).unit()), out)
            pin.est.add(out[0], out[1], out[2], out[3], out[4], out[5])
        }
        return pin
    }

    @Test fun reAnchoringKeepsTheDrawnPointAndTheRays() {
        val label = Vec3(0.12, 0.03, -0.4)
        val anchor = Pose(Vec3(0.0, 0.0, -0.4), turned(30.0))
        val pin = refinedPin(anchor, label)
        val twin = refinedPin(anchor, label)
        assertTrue(pin.localNorm > PIN_REANCHOR_M)
        val before = pin.position
        assertEquals(0.0, (before - label).norm(), 1e-4)
        pin.reanchor(Pose(before, anchor.q))
        assertEquals(0.0, (pin.position - before).norm(), 1e-12)
        assertEquals(0.0, pin.localNorm, 1e-12)
        // A claim captured before the re-anchor and one after give the pin what they give its twin that kept its anchor
        val out = DoubleArray(6)
        for (p in listOf(pin, twin)) {
            for (from in listOf(Vec3(0.2, 0.0, 0.0), Vec3(-0.1, 0.05, 0.0))) {
                p.anchors.toFrame(p.anchors.slotAt(5 * ms), Ray(from, (label + Vec3(0.002, 0.0, 0.0) - from).unit()), out)
                p.est.add(out[0], out[1], out[2], out[3], out[4], out[5])
            }
        }
        assertEquals(0.0, (pin.position - twin.position).norm(), 1e-9)
    }

    @Test fun anAnchorCorrectionMovesThePinWithIt() {
        val label = Vec3(0.02, 0.0, -0.4)
        val anchor = Pose(Vec3(0.0, 0.0, -0.4), turned(-20.0))
        val pin = refinedPin(anchor, label)
        val local = Vec3(pin.est.x, pin.est.y, pin.est.z)
        // ARCore corrects its map at 20 ms: the anchor, and the label with it, move 3 cm and turn 2°
        val correction = Pose(Vec3(0.03, -0.01, 0.0), Quat.axisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(2.0)))
        val moved = correction * anchor
        val step = pin.anchored(20 * ms, moved.t.x, moved.t.y, moved.t.z, moved.q.x, moved.q.y, moved.q.z, moved.q.w)
        assertEquals((moved.t - anchor.t).norm(), step, 1e-12)
        assertEquals(0.0, (pin.position - moved.apply(local)).norm(), 1e-12)
        // Claims captured after it, of the label where it now is, keep the pin's point in its anchor's frame
        val now = correction.apply(label)
        val out = DoubleArray(6)
        for (from in listOf(Vec3(0.1, 0.0, 0.0), Vec3(-0.1, 0.02, 0.0))) {
            pin.anchors.toFrame(pin.anchors.slotAt(30 * ms), Ray(from, (now - from).unit()), out)
            pin.est.add(out[0], out[1], out[2], out[3], out[4], out[5])
        }
        assertEquals(0.0, (Vec3(pin.est.x, pin.est.y, pin.est.z) - local).norm(), 1e-6)
        assertEquals(0.0, (pin.position - now).norm(), 1e-4)
        // A claim captured before the correction still uses the anchor as it was then
        assertEquals(0.0, (pin.positionAt(10 * ms) - anchor.apply(local)).norm(), 1e-12)
    }

    @Test fun aCaptureBeforeTheOldestPoseKeptUsesTheOldest() {
        val ring = AnchorRing(4)
        for (i in 1..6) ring.add(i * 10 * ms, Pose(Vec3(i.toDouble(), 0.0, 0.0), Quat.IDENTITY))
        assertEquals(4, ring.count)
        assertEquals(3.0, ring.pose(ring.slotAt(5 * ms)).t.x, 0.0) // before every pose kept: the oldest
        assertEquals(4.0, ring.pose(ring.slotAt(45 * ms)).t.x, 0.0) // between frames: the newest before
        assertEquals(6.0, ring.pose(ring.slotAt(60 * ms)).t.x, 0.0)
        assertEquals(6.0, ring.pose(ring.newest).t.x, 0.0)
        assertEquals(1.0, ring.lastStep(), 0.0)
    }

    // --- cost ---

    /** The bytes this thread allocated so far, from the JVM's ThreadMXBean (reflection: android.jar has no java.lang.management); null when unknown */
    private fun allocatedBytes(): Long? = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val m = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        m.invoke(bean, Thread.currentThread().id) as Long
    }.getOrNull()?.takeIf { it >= 0 }

    @Test fun aClaimAllocatesNothing() {
        assumeTrue(allocatedBytes() != null)
        val label = Vec3(0.0, 0.0, -0.4)
        val pin = Pin(1, "A", Pose(label, turned(10.0)))
        val e = pin.est
        e.start(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.4, PIN_PRIOR_HITS_M, k.fx, PriorSource.HITS)
        val rays = (0 until 64).map { val o = Vec3(-0.1 + 0.003 * it, 0.01 * (it % 3), 0.0); Ray(o, (label - o).unit()) }
        val out = DoubleArray(6)
        fun claims(n: Int) {
            for (i in 0 until n) {
                val r = rays[i % rays.size]
                pin.anchors.add(i * ms, label.x, label.y, label.z, 0.0, 0.0, 0.0, 1.0)
                pin.anchors.toFrame(pin.anchors.slotAt(i * ms), r, out)
                if (i % 50 == 49) {
                    repeat(PIN_REINIT_BAD) { e.bad(out[0], out[1], out[2], out[3], out[4], out[5]) }
                } else {
                    e.add(out[0], out[1], out[2], out[3], out[4], out[5])
                }
            }
        }
        claims(5_000) // warm up (and the gate test's lazy parts)
        val before = allocatedBytes()!!
        claims(2_000)
        val bytes = allocatedBytes()!! - before
        assertTrue("$bytes bytes for 2000 claims", bytes < 2_000)
        assertTrue(e.verified || e.priorSource == PriorSource.REINIT)
    }

    @Test fun aNeverStartedEstimatorKeepsItsPinWhereItWasBorn() {
        val pin = Pin(1, "A", Pose(Vec3(0.1, 0.0, -0.4), Quat.IDENTITY))
        assertFalse(pin.est.started)
        assertEquals(Vec3(0.1, 0.0, -0.4), pin.position)
        assertNull(RayGate(4).fit(DoubleArray(24), 1, sigma))
    }
}
