package io.packagex.arcount

import io.packagex.arcount.SectionState.CLOSED
import io.packagex.arcount.SectionState.COUNTING
import io.packagex.arcount.SectionState.FROZEN
import io.packagex.arcount.SectionState.IDLE
import io.packagex.arcount.SectionState.OPEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class SectionTest {
    private val y = Vec3(0.0, 1.0, 0.0)
    private val home = cameraAt(0.0)

    private fun sim(symbols: List<Symbol> = row(4, 0.06, x0 = -0.09) + label(), config: CountConfig = hostConfig, engineEvery: Int = 3): Pair<Sim, SectionMachine> {
        val m = SectionMachine(config)
        return Sim(symbols, MachineCounter(m), engineEvery = engineEvery) to m
    }

    /** Through the start-up guard to a section counting its four units */
    private fun counting(engineEvery: Int = 3, symbols: List<Symbol> = row(4, 0.06, x0 = -0.09) + label()): Pair<Sim, SectionMachine> {
        val (sim, m) = sim(symbols, engineEvery = engineEvery)
        sim.run(Paths.hold(home, 5.5))
        assertEquals(COUNTING, m.state)
        assertEquals(4, m.section!!.table!!.counts().counted)
        return sim to m
    }

    private fun frozen(): Pair<Sim, SectionMachine> {
        val (sim, m) = counting()
        sim.tracking = Tracking.PAUSED
        sim.step(home)
        sim.tracking = Tracking.TRACKING
        assertEquals(FROZEN, m.state)
        return sim to m
    }

    @Test
    fun noSectionOpensBeforeFiveSecondsAfterResumeAndTwoSecondsOfTracking() {
        val (sim, m) = sim()
        sim.run(Paths.hold(home, 4.99))
        assertEquals(IDLE, m.state)
        assertTrue(m.guardHolds(sim.ts(sim.frame - 1)))
        sim.run(Paths.hold(home, 0.2))
        assertEquals(OPEN, m.state)
    }

    @Test
    fun afterAPauseTrackingMustHoldTwoSecondsBeforeASectionOpens() {
        val (sim, m) = sim()
        sim.tracking = Tracking.PAUSED
        sim.run(Paths.hold(home, 4.0))
        sim.tracking = Tracking.TRACKING
        sim.run(Paths.hold(home, 1.99))
        assertEquals(IDLE, m.state)
        sim.run(Paths.hold(home, 0.2))
        assertEquals(OPEN, m.state)
    }

    @Test
    fun theAnchorIsGravityAlignedFortyCentimetresAlongTheLabelsRayFacingTheCamera() {
        val (sim, _) = sim(listOf(label()))
        val camera = Pose(Vec3(0.01, 0.02, 0.0), Quat.axisAngle(y, 0.1))
        sim.run(Paths.hold(camera, 5.2))
        val anchor = sim.anchorRequests.single().world
        val toLabel = (label().centre - camera.t).unit()
        val expected = camera.t + toLabel * 0.40
        assertEquals(0.0, (anchor.t - expected).norm(), 1e-3)
        assertEquals(0.0, (anchor.rotate(y) - y).norm(), 1e-9)
        val view = camera.rotate(Vec3(0.0, 0.0, -1.0))
        val facing = -Vec3(view.x, 0.0, view.z).unit()
        assertEquals(0.0, (anchor.rotate(Vec3(0.0, 0.0, 1.0)) - facing).norm(), 1e-9)
    }

    @Test
    fun anOpenSectionWithoutAUnitGoesBackToIdleAfterTenSeconds() {
        val (sim, m) = sim(listOf(label()))
        sim.run(Paths.hold(home, 5.2))
        assertEquals(OPEN, m.state)
        sim.hidden = { it.text == LABEL }
        sim.run(Paths.hold(home, 9.9))
        assertEquals(OPEN, m.state)
        sim.run(Paths.hold(home, 0.2))
        assertEquals(IDLE, m.state)
        assertTrue(m.closed.isEmpty())
    }

    @Test
    fun theFirstReadOfTheSectionsGtinStartsCountingAndOtherGtinsDoNot() {
        val (sim, m) = sim(row(4, 0.06, x0 = -0.09, text = OTHER_GTIN) + label())
        sim.run(Paths.hold(home, 6.0))
        assertEquals(OPEN, m.state)
        counting()
    }

    @Test
    fun aFrameNotTrackingFreezesTheCount() {
        val (_, m) = frozen()
        assertEquals(listOf(BreakReason.FRAME_NOT_TRACKING), m.section!!.breaks.map { it.second })
        assertEquals(4, m.section!!.table!!.counts().counted)
    }

    @Test
    fun anAnchorNotTrackingFreezesTheCount() {
        val (sim, m) = counting()
        sim.anchorTracking = Tracking.PAUSED
        sim.step(home)
        assertEquals(FROZEN, m.state)
        assertEquals(BreakReason.ANCHOR_NOT_TRACKING, m.section!!.breaks.single().second)
    }

    @Test
    fun aStoppedAnchorAbandonsTheSectionAndOpensItAgain() {
        val (sim, m) = counting()
        sim.anchorTracking = Tracking.STOPPED
        sim.step(home)
        assertEquals(OPEN, m.state)
        assertEquals(SectionStatus.ABANDONED, m.closed.single().status)
        assertEquals(4, m.closed.single().counted)
    }

    @Test
    fun aStepOfMoreThanTenCentimetresInTheAnchorFrameIsAWorldJump() {
        val (sim, m) = counting()
        sim.hidden = { it.text == LABEL }
        sim.jumpCamera(Vec3(0.0, 0.11, 0.0))
        sim.step(home)
        assertEquals(FROZEN, m.state)
        assertEquals(BreakReason.WORLD_JUMP, m.section!!.breaks.single().second)
    }

    @Test
    fun aStepFasterThanHalfAMetrePerSecondIsAWorldJumpWhenThePictureStoodStill() {
        val (sim, m) = counting(engineEvery = 1)
        sim.hidden = { it.text == LABEL }
        sim.jumpCamera(Vec3(0.03, 0.0, 0.0))
        sim.step(home)
        assertEquals(FROZEN, m.state)
        assertEquals(BreakReason.WORLD_JUMP, m.section!!.breaks.single().second)
    }

    @Test
    fun theSameStepIsNoJumpWhenTheUnitsMovedWithIt() {
        val (sim, m) = counting(engineEvery = 1)
        sim.run(Paths.hold(cameraAt(0.03), 1.0))
        assertEquals(COUNTING, m.state)
        sim.run(Paths.move(Vec3(0.03, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), 0.05))
        assertEquals(COUNTING, m.state)
    }

    @Test
    fun noSectionElementInTheImageForMoreThanASecondFreezes() {
        val (sim, m) = counting()
        val away = Pose(Vec3.ZERO, Quat.axisAngle(y, PI / 2))
        sim.run(Paths.hold(away, 1.0))
        assertEquals(COUNTING, m.state)
        sim.step(away)
        assertEquals(BreakReason.LEFT_SECTION, m.section!!.breaks.single().second)
    }

    @Test
    fun anAmbiguousUnitInViewKeepsTheSectionInView() {
        val v = Symbol(GTIN, Vec3(0.0, 0.04, -0.30), 1)
        val u = Symbol(GTIN, Vec3(0.036, 0.04, -0.30), 2)
        val (sim, m) = sim(listOf(v, u, label()))
        sim.hidden = { it == u }
        sim.run(Paths.hold(home, 5.5))
        sim.hidden = { it == v }
        sim.run(Paths.hold(home, 0.2))
        assertEquals(listOf(UnitState.COUNTED, UnitState.AMBIGUOUS), m.section!!.table!!.units.map { it.state })
        // turned right: the label and the counted unit leave the image, the ambiguous one stays in it
        val turned = Pose(Vec3.ZERO, Quat.axisAngle(y, -0.62))
        assertNull(K4K.project(turned.inverse().apply(v.centre))?.takeIf { it.first >= 0 })
        sim.run(Paths.hold(turned, 1.5))
        assertEquals(COUNTING, m.state)
    }

    @Test
    fun tenSecondsWithoutAReadWhileUnitsAreInViewFreezes() {
        val (sim, m) = counting()
        sim.readsEnabled = false
        sim.run(Paths.hold(home, 9.9))
        assertEquals(COUNTING, m.state)
        sim.run(Paths.hold(home, 0.2))
        assertEquals(BreakReason.SILENCE, m.section!!.breaks.single().second)
    }

    @Test
    fun theLabelWhereItWasAndTwoCountedUnitsResumeTheCount() {
        val (sim, m) = frozen()
        sim.run(Paths.hold(home, 1.9))
        assertEquals(FROZEN, m.state)
        sim.run(Paths.hold(home, 0.3))
        assertEquals(COUNTING, m.state)
        assertEquals(4, m.section!!.table!!.counts().counted)
        assertEquals(2, m.section!!.segment)
        assertTrue(m.closed.isEmpty())
    }

    @Test
    fun aLabelReadAPitchFromWhereItWasFailsTheResumeAndTheSectionStartsAgain() {
        val (sim, m) = counting()
        sim.hidden = { it.text == LABEL }
        sim.jumpCamera(Vec3(0.06, 0.0, 0.0))
        sim.step(home)
        assertEquals(FROZEN, m.state)
        sim.hidden = { false }
        sim.run(Paths.hold(home, 0.2))
        assertEquals(SectionStatus.ABANDONED, m.closed.single().status)
        assertEquals(4, m.closed.single().counted)
        assertEquals(listOf(BreakReason.WORLD_JUMP), m.closed.single().breaks.map { it.second })
        assertTrue(m.state == OPEN || m.state == COUNTING)
        assertEquals(LABEL, m.section!!.labelPayload)
    }

    @Test
    fun withoutTwoCountedUnitsWithinFiveSecondsOfTheLabelTheResumeFails() {
        val (sim, m) = frozen()
        sim.hidden = { it.text == GTIN }
        sim.run(Paths.hold(home, 2.1))
        assertEquals(FROZEN, m.state)
        sim.run(Paths.hold(home, 5.1))
        assertEquals(SectionStatus.ABANDONED, m.closed.single().status)
        assertEquals(OPEN, m.state)
    }

    @Test
    fun finishClosesACountingSectionCompleteOrUnresolvedAndAFrozenOneClosedFrozen() {
        val (sim, m) = counting()
        sim.command(Command.Finish)
        assertEquals(CLOSED, m.state)
        assertEquals(SectionStatus.COMPLETE, m.closed.single().status)

        val (sim2, m2) = counting()
        sim2.symbols = sim2.symbols + Symbol(GTIN, Vec3(0.15, 0.04, -0.30), 9)
        sim2.run(Paths.hold(home, 0.1))
        sim2.command(Command.Finish)
        val unresolved = m2.closed.single()
        assertEquals(SectionStatus.UNRESOLVED, unresolved.status)
        assertEquals(4, unresolved.countLow)
        assertEquals(5, unresolved.countHigh)

        val (sim3, m3) = frozen()
        sim3.command(Command.Finish)
        assertEquals(SectionStatus.CLOSED_FROZEN, m3.closed.single().status)
    }

    @Test
    fun theNextLabelAimedAtClosesTheSectionAndOpensTheNext() {
        val second = label(x = 0.12, text = "LABEL-2")
        val (sim, m) = counting(symbols = row(4, 0.06, x0 = -0.09) + label() + second)
        sim.run(Paths.hold(home, 1.0))
        assertEquals(COUNTING, m.state)
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.12, 0.0, 0.0), 0.1))
        sim.run(Paths.hold(cameraAt(0.12), 1.0))
        assertEquals(SectionStatus.COMPLETE, m.closed.single().status)
        assertEquals(LABEL, m.closed.single().labelPayload)
        assertEquals("LABEL-2", m.section!!.labelPayload)
    }

    @Test
    fun aTriggerInIdleOpensAnUnlabelledSectionAnchoredOnTheFirstUnit() {
        val (sim, m) = sim(row(4, 0.06, x0 = -0.09))
        sim.run(Paths.hold(home, 5.1))
        assertEquals(IDLE, m.state)
        sim.command(Command.TriggerShort)
        assertEquals(OPEN, m.state)
        sim.run(Paths.hold(home, 0.5))
        assertEquals(COUNTING, m.state)
        assertNull(m.section!!.labelPayload)
        assertEquals(setOf(GTIN14), m.section!!.gtins)
        val first = row(4, 0.06, x0 = -0.09).first().centre
        val expected = home.t + (first - home.t).unit() * 0.40
        assertEquals(0.0, (sim.anchorRequests.single().world.t - expected).norm(), 1e-3)
    }

    @Test
    fun anUnlabelledSectionHasNoResumeAndClosesFrozen() {
        val (sim, m) = sim(row(4, 0.06, x0 = -0.09))
        sim.run(Paths.hold(home, 5.1))
        sim.command(Command.TriggerShort)
        sim.run(Paths.hold(home, 0.5))
        sim.tracking = Tracking.PAUSED
        sim.step(home)
        sim.tracking = Tracking.TRACKING
        sim.run(Paths.hold(home, 6.0))
        assertEquals(FROZEN, m.state)
        sim.command(Command.Finish)
        assertEquals(SectionStatus.CLOSED_FROZEN, m.closed.single().status)
    }

    @Test
    fun theTriggerIsIgnoredWhileFrozen() {
        val (sim, m) = frozen()
        sim.command(Command.TriggerShort)
        sim.command(Command.TriggerLong)
        assertEquals(FROZEN, m.state)
        assertTrue(m.closed.isEmpty())
    }

    @Test
    fun aLongTriggerWhileCountingClosesTheSectionAndOpensTheNextUnlabelledOne() {
        val (sim, m) = counting()
        sim.command(Command.TriggerLong)
        assertEquals(SectionStatus.COMPLETE, m.closed.single().status)
        assertEquals(OPEN, m.state)
        assertNull(m.section!!.labelPayload)
    }

    @Test
    fun restartFromFrozenAbandonsTheCountAndStartsAgainAtTheLabel() {
        val (sim, m) = frozen()
        sim.command(Command.Restart)
        assertEquals(SectionStatus.ABANDONED, m.closed.single().status)
        assertEquals(OPEN, m.state)
        // tracking came back with the restart's frame: no new anchor until the start-up guard has passed
        sim.run(Paths.hold(home, 1.9))
        assertTrue(sim.anchorRequests.size == 1)
        sim.run(Paths.hold(home, 0.6))
        assertEquals(COUNTING, m.state)
        assertEquals(4, m.section!!.table!!.counts().low + m.section!!.table!!.counts().tentative)
    }

    @Test
    fun withoutHostHooksALabelIsANarrowSymbolAndTheFirstUnitNearItGivesTheGtin() {
        val symbols = row(4, 0.06, x0 = -0.09, depth = 0.40) + label(depth = 0.40)
        val (sim, m) = sim(symbols, config = CountConfig())
        sim.run(Paths.hold(home, 5.6))
        assertEquals(COUNTING, m.state)
        assertEquals(LABEL, m.section!!.labelPayload)
        assertEquals(setOf(GTIN14), m.section!!.gtins)
        assertEquals(4, m.section!!.table!!.counts().counted)
    }
}
