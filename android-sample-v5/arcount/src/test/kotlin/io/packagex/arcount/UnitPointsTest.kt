package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AR Item Count's pins (CountView.unitPoints) and the patch tracker's counters (CountingCore.trackStats) */
class UnitPointsTest {
    private fun sym(text: String, x: Double, id: Int) = Symbol(text, Vec3(x, 0.04, -0.30), id)

    private val shelf = listOf(sym(GTIN, -0.06, 1), sym(GTIN, 0.0, 2), sym(OTHER_GTIN, 0.06, 3), sym(GTIN, 0.12, 4))

    private fun run(): Pair<CountingCore, Sim> {
        val core = CountingCore()
        core.setItems(setOf(GTIN14))
        val sim = Sim(shelf, CoreCounter(core), noisePx = 1.0)
        sim.feedLuma(core, LumaScene({ sim.symbols }))
        sim.run(Paths.hold(cameraAt(0.0), 5.5))
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.15, 0.0, 0.0), 0.03))
        return core to sim
    }

    @Test fun listedUnitsWithDepthComeInTheWorldNearTheirSymbols() {
        val (core, _) = run()
        val v = core.view()
        assertEquals(SectionState.COUNTING, v.state)
        val points = v.unitPoints
        assertTrue("no unit points", points.isNotEmpty())
        assertTrue(points.all { it.code == GTIN14 }) // the unlisted code has none
        assertEquals(points.size, points.map { it.key }.toSet().size)
        val listed = shelf.filter { it.text == GTIN }
        for (p in points) {
            val nearest = listed.minOf { (it.centre - p.world).norm() }
            assertTrue("unit ${p.key} at ${p.world} is %.3f m from its symbol".format(nearest), nearest < 0.02)
        }
    }

    @Test fun trackStatsCountTheTrackerAndTheDepths() {
        val (core, _) = run()
        val s = core.trackStats()
        assertTrue(s.trackingFrames > 0)
        assertTrue(s.unitsTracked > 0)
        assertEquals(core.view().unitPoints.size, s.unitsWithDepth - core.units.count { it.hasDepth && it.gtin != GTIN14 })
        assertEquals(core.units.count { !it.hasDepth && it.state != UnitState.MANUAL }, s.unitsPriorOnly)
    }
}
