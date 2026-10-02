package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The view: markers, gaps, bracket, prompts */
class CountingCoreTest {
    private val home = cameraAt(0.0)
    private val four = row(4, 0.06, x0 = -0.09)

    private fun session(symbols: List<Symbol>): Pair<Sim, CountingCore> {
        val core = CountingCore(hostConfig)
        return Sim(symbols, CoreCounter(core)) to core
    }

    private fun counting(symbols: List<Symbol> = four + label(), at: Pose = home): Pair<Sim, CountingCore> {
        val (sim, core) = session(symbols)
        sim.run(Paths.hold(at, 5.5))
        assertEquals(SectionState.COUNTING, core.view().state)
        return sim to core
    }

    @Test
    fun duringTheStartUpGuardTheViewAsksToHoldStill() {
        val (sim, core) = session(four + label())
        assertEquals(CountView.EMPTY, core.view())
        sim.run(Paths.hold(home, 1.0))
        assertEquals(SectionState.IDLE, core.view().state)
        assertEquals(Prompt.HOLD_STILL_A_MOMENT, core.view().prompt)
    }

    @Test
    fun inIdleAfterTheGuardTheViewAsksForTheShelfLabel() {
        val (sim, core) = session(four)
        sim.run(Paths.hold(home, 5.5))
        assertEquals(Prompt.SCAN_SHELF_LABEL, core.view().prompt)
    }

    @Test
    fun markersSitOnTheUnitsWhileTheirReadsAreFreshAndCertain() {
        val (_, core) = counting()
        val markers = core.view().markers
        assertEquals(4, markers.size)
        for (s in four) {
            val (u, v) = K4K.project(s.centre)!!
            assertTrue(markers.any { kotlin.math.abs(it.u * 3840 - u) < 2 && kotlin.math.abs(it.v * 2160 - v) < 2 && it.state == UnitState.COUNTED })
        }
        assertEquals(0.031 * 2896 / 0.30 / 3840, markers.first().sizeU, 1e-3)
    }

    @Test
    fun markersGoOneSecondAfterTheLastRead() {
        val (sim, core) = counting()
        sim.hidden = { it.text == GTIN }
        sim.run(Paths.hold(home, 0.8))
        assertEquals(4, core.view().markers.size)
        sim.run(Paths.hold(home, 0.3))
        assertTrue(core.view().markers.isEmpty())
    }

    @Test
    fun markersGoWhenTheWarpIsNoLongerCertainToATenthOfAPitch() {
        val (sim, core) = counting()
        assertTrue(core.units.none { it.hasDepth })
        sim.hidden = { it.text == GTIN }
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.02, 0.0, 0.0), 0.067))
        assertTrue(core.units.all { sim.lastRecord!!.timestampNs - it.lastReadNs < 1_000_000_000L })
        assertTrue(core.view().markers.isEmpty())
    }

    @Test
    fun aGapOpensBetweenCountedUnitsAndATapAddsAUnitByHandThere() {
        // six units 6 cm apart, the fourth unreadable
        val centre = cameraAt(0.15)
        val (sim, core) = session(row(6, 0.06) + label(x = 0.15))
        sim.hidden = { it.engineId == 4 }
        sim.run(Paths.hold(centre, 6.5))
        assertEquals(5, core.view().bracket!!.countLow)
        val gap = core.view().gaps.single()
        val (gu, _) = K4K.project(centre.inverse().apply(Vec3(0.18, 0.04, -0.30)))!!
        assertEquals(gu / 3840, gap.u, 0.02)
        sim.command(Command.FillGap(gap.gapId))
        assertEquals(6, core.view().bracket!!.countLow)
        assertTrue(core.view().gaps.isEmpty())
        assertTrue(core.view().markers.any { it.state == UnitState.MANUAL })
        sim.command(Command.RemoveManualUnit)
        assertEquals(5, core.view().bracket!!.countLow)
        assertEquals(gap.gapId, core.view().gaps.single().gapId)
        sim.command(Command.AddUnit)
        assertEquals(6, core.view().bracket!!.countLow)
        sim.command(Command.Finish)
        val result = core.view().closed.single()
        assertEquals(2, result.manualAdded)
        assertEquals(1, result.manualRemoved)
        assertEquals(6, result.countLow)
    }

    @Test
    fun theBracketSitsAtTheAnchorWithTheCountAndALockWhenFrozen() {
        val (sim, core) = counting()
        val b = core.view().bracket!!
        assertEquals(GTIN14, b.gtin)
        assertEquals(4, b.countLow)
        assertEquals(4, b.countHigh)
        assertTrue(b.inImage)
        val (lu, lv) = K4K.project(label().centre)!!
        assertEquals(lu / 3840, b.u, 2e-3)
        assertEquals(lv / 2160, b.v, 2e-3)
        sim.hidden = { it.text == LABEL }
        sim.tracking = Tracking.PAUSED
        sim.step(home)
        assertTrue(core.view().bracket!!.frozen)
        assertTrue(core.view().markers.isEmpty())
        assertEquals(Prompt.HOLD_STILL_A_MOMENT, core.view().prompt)
        sim.tracking = Tracking.TRACKING
        sim.run(Paths.hold(home, 2.1))
        assertEquals(Prompt.SCAN_LABEL_TO_CONTINUE, core.view().prompt)
    }

    @Test
    fun aTentativeUnitWidensTheRangeAndTheViewOffersARescanUntilTheRangeIsAccepted() {
        val (sim, core) = counting()
        sim.symbols = sim.symbols + Symbol(GTIN, Vec3(0.15, 0.04, -0.30), 9)
        sim.hidden = { it.engineId == 9 && sim.frame > 166 }
        sim.run(Paths.hold(home, 0.1))
        val b = core.view().bracket!!
        assertEquals(4, b.countLow)
        assertEquals(5, b.countHigh)
        assertEquals(Prompt.RANGE_RESCAN, core.view().prompt)
        sim.command(Command.AcceptRange)
        assertNotEquals(Prompt.RANGE_RESCAN, core.view().prompt)
    }

    @Test
    fun movingFastWithUnitsInViewAsksToSlowDown() {
        val (sim, core) = counting()
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.12, 0.0, 0.0), 0.1))
        assertEquals(Prompt.SLOW_DOWN, core.view().prompt)
    }

    @Test
    fun aReadWithANonFiniteCornerIsDroppedAtTheBoundaryNotThrown() {
        val (sim, core) = counting()
        val ts = sim.lastRecord!!.timestampNs
        val good = shoot(home, four, ts).first()
        val nan = good.copy(corners = good.corners.toMutableList().also { it[0] = Double.NaN })
        val inf = good.copy(corners = good.corners.toMutableList().also { it[3] = Double.POSITIVE_INFINITY })
        core.onReads(ts, listOf(nan, inf))
        assertEquals(2, core.nonFiniteReads)
        assertEquals(4, core.units.size)
        assertEquals(SectionState.COUNTING, core.view().state)
    }

    @Test
    fun aReadWithAnInfiniteCornerCannotPlaceAnUnlabelledSectionsAnchor() {
        val (sim, core) = session(four)
        sim.run(Paths.hold(home, 5.5))
        sim.command(Command.TriggerShort)
        assertEquals(SectionState.OPEN, core.view().state)
        val ts = sim.lastRecord!!.timestampNs
        val good = shoot(home, four, ts).first()
        core.onReads(ts, listOf(good.copy(corners = good.corners.toMutableList().also { it[2] = Double.POSITIVE_INFINITY })))
        assertEquals(null, core.anchorRequest())
        core.onReads(ts, listOf(good))
        val t = core.anchorRequest()!!.world.t
        assertTrue(t.x.isFinite() && t.y.isFinite() && t.z.isFinite())
    }

    @Test
    fun aFinishedSectionIsInTheViewAndTheNextLabelIsAskedFor() {
        val (sim, core) = counting()
        sim.command(Command.Finish)
        assertEquals(SectionState.CLOSED, core.view().state)
        assertEquals(Prompt.SCAN_SHELF_LABEL, core.view().prompt)
        assertEquals(4, core.view().closed.single().counted)
        assertEquals(null, core.view().bracket)
    }
}
