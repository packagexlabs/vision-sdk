package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ruling R2: after a world jump the anchor did not follow, the resume tries the jump's correction and its pitch aliases */
class JumpResumeTest {
    private val b = "TP6056F32"

    private fun record(ts: Long, camera: Vec3, anchor: Vec3 = Vec3(0.0, 0.0, -0.4)) =
        PoseRecord(ts, Pose(camera, Quat.IDENTITY), Pose(anchor, Quat.IDENTITY), Tracking.TRACKING, Tracking.TRACKING, K4K, EXPOSURE_NS)

    @Test
    fun theCorrectionUndoesTheApparentStepAndTheAliasesMoveItAlongTheShelf() {
        val prev = record(0, Vec3(0.30, 0.0, 0.0))
        val r = record(FRAME_NS, Vec3(0.36, 0.0, 0.0))
        val d = JumpResume.step(prev, r)
        assertEquals(0.0, (JumpResume.corrected(r, d).cameraInAnchor().t - prev.cameraInAnchor().t).norm(), 1e-12)
        val hs = JumpResume.hypotheses(d, 0.07, Vec3(1.0, 0.0, 0.0))
        assertEquals(listOf(0, -1, 1, -2, 2), hs.map { it.pitches })
        for (h in hs) {
            val t = JumpResume.corrected(r, h.correction).cameraInAnchor().t
            assertEquals(0.30 + 0.07 * h.pitches, t.x, 1e-12)
        }
        // a second jump while frozen, seen on the same records, composes onto the first
        val r2 = record(2 * FRAME_NS, Vec3(0.40, 0.0, 0.0))
        val d2 = JumpResume.compose(d, JumpResume.step(r, r2))
        assertEquals(0.30, JumpResume.corrected(r2, d2).cameraInAnchor().t.x, 1e-12)
    }

    /** A, A, A, A, C, C, B, B, B at 7 cm, 30 cm away; C is not listed */
    private val shelf = (List(4) { GTIN } + List(2) { OTHER_GTIN } + List(3) { b }).mapIndexed { i, code -> Symbol(code, Vec3(0.07 * i, 0.04, -0.30), i + 1) }

    /** A one-way pass with a one-pitch world jump at x = 0.30 m that the anchor does not follow */
    private fun jumpedPass(symbols: List<Symbol>, noisePx: Double = 1.0, seed: Int = 1): Pair<Sim, CountingCore> {
        val core = CountingCore()
        core.setItems(linkedSetOf(GTIN14, b))
        val sim = Sim(symbols, CoreCounter(core), noisePx = noisePx, seed = seed)
        sim.run(Paths.hold(cameraAt(0.0), 5.5))
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.30, 0.0, 0.0), 0.03))
        sim.jumpCamera(Vec3(0.07, 0.0, 0.0))
        return sim to core
    }

    @Test
    fun aJumpTheAnchorDoesNotFollowResumesWithItsCorrectionAndCountsOnce() {
        val (sim, core) = jumpedPass(shelf)
        sim.step(cameraAt(0.30))
        assertTrue(core.events.any { it.contains("FROZEN, WORLD_JUMP") })
        sim.run(Paths.move(Vec3(0.30, 0.0, 0.0), Vec3(0.66, 0.0, 0.0), 0.03)) {
            assertTrue(core.view().items.all { it.countLow <= if (it.code == b) 3 else 4 })
        }
        assertTrue(core.events.any { it.contains("resumed with the jump's correction") })
        assertEquals(SectionState.COUNTING, core.view().state)
        // the markers sit on the barcodes again: the correction holds for every later pose
        val view = core.view()
        assertTrue(view.markers.isNotEmpty())
        val truth = shelf.mapNotNull { K4K.project(cameraAt(0.66).inverse().apply(it.centre)) }
        for (m in view.markers) assertTrue(truth.any { (u, v) -> kotlin.math.hypot(m.u * 3840 - u, m.v * 2160 - v) < 20 })
        sim.run(Paths.hold(cameraAt(0.66), 1.0))
        sim.command(Command.Finish)
        assertTrue(core.view().closed.none { it.status == SectionStatus.ABANDONED })
        assertEquals(listOf(ItemCount(GTIN14, 4, 4, false), ItemCount(b, 3, 3, true)), core.view().items)
    }

    @Test
    fun overIdenticalUnitsTheAliasesTieSoTheSectionNeverResumesAndTheRangeHoldsTheTruth() {
        val (sim, core) = jumpedPass(row(12, 0.07))
        sim.run(Paths.move(Vec3(0.30, 0.0, 0.0), Vec3(0.80, 0.0, 0.0), 0.03)) {
            assertTrue(core.view().items.first().countLow <= 12)
        }
        sim.run(Paths.hold(cameraAt(0.80), 1.0))
        sim.command(Command.Finish)
        assertTrue(core.events.any { it.contains("tie") })
        assertFalse(core.events.any { it.contains("resumed") })
        val a = core.view().items.first()
        assertTrue("${a.countLow}..${a.countHigh}", a.countLow <= 12 && a.countHigh >= 12)
    }

    /**
     * The jumpsame sim's run b2-n15-s9 (fix round): the aliases tied, the measured correction was then ruled out and
     * alias -2, left alone, resumed and undercounted. After a tie only the measured correction may resume.
     */
    @Test
    fun afterATieNoAliasResumesEvenWhenItIsLeftAlone() {
        val core = CountingCore()
        core.setItems(linkedSetOf(GTIN14, b))
        val sim = Sim(List(9) { i -> Symbol(GTIN, Vec3(0.07 * i, 0.04, -0.30), i + 1) }, CoreCounter(core), noisePx = 15.0, seed = 9)
        sim.readsPerFrame = 2
        sim.run(Paths.hold(cameraAt(0.0), 5.5))
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.30, 0.0, 0.0), 0.03))
        sim.jumpCamera(Vec3(0.06, 0.0, 0.0))
        sim.run(Paths.move(Vec3(0.30, 0.0, 0.0), Vec3(0.62, 0.0, 0.0), 0.03))
        sim.run(Paths.hold(cameraAt(0.62), 1.0))
        sim.command(Command.Finish)
        assertTrue(core.events.any { it.contains("tie") })
        assertFalse(core.events.any { Regex("alias -?[12]").containsMatchIn(it) })
        val a = core.view().items.first()
        assertTrue("${a.countLow}..${a.countHigh}", a.countLow <= 9 && a.countHigh >= 9)
    }

    @Test
    fun aTrackingLossWithoutAMeasuredJumpTriesNoAliases() {
        val core = CountingCore()
        core.setItems(setOf(GTIN14))
        val sim = Sim(row(4, 0.07, x0 = -0.105), CoreCounter(core), noisePx = 1.0)
        sim.run(Paths.hold(cameraAt(0.0), 5.5))
        sim.tracking = Tracking.PAUSED
        sim.step(cameraAt(0.0))
        sim.tracking = Tracking.TRACKING
        sim.run(Paths.hold(cameraAt(0.0), 2.5))
        assertEquals(SectionState.COUNTING, core.view().state)
        assertTrue(core.events.none { it.contains("tie") })
    }
}
