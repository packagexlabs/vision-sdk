package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole core as the simulator's counter */
class CoreCounter(val core: CountingCore) : Counter {
    override fun resume(ts: Long) = core.onResume(ts)

    override fun frame(r: PoseRecord) = core.onFrame(r)

    override fun reads(ts: Long, reads: List<Read>) = core.onReads(ts, reads)

    override fun command(c: Command, ts: Long) = core.onCommand(c, ts)

    override fun anchorRequest() = core.anchorRequest()

    override fun anchorCreated(ok: Boolean) = core.onAnchorCreated(ok)
}

/** The brief's end-to-end scenarios on synthetic scenes: 4K, f = 2896 px, 30 fps, reads every third frame */
class ScenarioTest {
    private val home = cameraAt(0.0)
    private val views = ArrayList<CountView>()

    private fun session(symbols: List<Symbol>, noisePx: Double = 1.0, seed: Int = 1, readsFirst: Boolean = true): Pair<Sim, CountingCore> {
        val core = CountingCore(hostConfig)
        return Sim(symbols, CoreCounter(core), noisePx = noisePx, seed = seed, readsFirst = readsFirst) to core
    }

    private fun Sim.go(path: List<Pose>) = run(path) { views += (counter as CoreCounter).core.view() }

    /** No view ever showed more than [n] as a definite count, and no closed section claimed more */
    private fun neverMoreThan(n: Int, core: CountingCore) {
        assertTrue(views.all { v -> v.bracket == null || v.bracket!!.countLow <= n })
        assertTrue(core.view().closed.filter { it.status == SectionStatus.COMPLETE }.all { it.counted <= n })
    }

    private fun onePass(pitch: Double, seed: Int) {
        val (sim, core) = session(row(10, pitch) + label(), seed = seed)
        val end = 9 * pitch + 0.06
        sim.go(Paths.hold(home, 5.5))
        sim.go(Paths.move(Vec3.ZERO, Vec3(end, 0.0, 0.0), 0.03))
        sim.go(Paths.hold(cameraAt(end), 1.0))
        sim.command(Command.Finish)
        val result = core.view().closed.single()
        assertEquals(SectionStatus.COMPLETE, result.status)
        assertEquals(10, result.counted)
        assertEquals(10, result.countLow)
        assertEquals(10, result.countHigh)
        neverMoreThan(10, core)
    }

    @Test
    fun aOneDirectionPassOverTenIdenticalUnitsAtSixCentimetresCountsTen() = onePass(0.06, seed = 1)

    @Test
    fun aOneDirectionPassOverTenIdenticalUnitsAtEightCentimetresCountsTen() = onePass(0.08, seed = 2)

    @Test
    fun startEndStartStillCountsTenWithNoAmbiguousLeft() {
        val (sim, core) = session(row(10, 0.06) + label(), seed = 3)
        sim.go(Paths.hold(home, 5.5))
        sim.go(Paths.move(Vec3.ZERO, Vec3(0.6, 0.0, 0.0), 0.03))
        sim.go(Paths.move(Vec3(0.6, 0.0, 0.0), Vec3.ZERO, 0.03))
        sim.go(Paths.hold(home, 1.0))
        assertEquals(0, core.units.count { it.state == UnitState.AMBIGUOUS })
        sim.command(Command.Finish)
        val result = core.view().closed.single()
        assertEquals(SectionStatus.COMPLETE, result.status)
        assertEquals(10, result.counted)
        assertEquals(0, result.ambiguous)
        neverMoreThan(10, core)
    }

    /** The pass to x = 0.27 m, then a world jump of one pitch along the row; the section freezes on it */
    private fun jumped(seed: Int): Pair<Sim, CountingCore> {
        val (sim, core) = session(row(10, 0.06) + label(), seed = seed)
        sim.go(Paths.hold(home, 5.5))
        sim.go(Paths.move(Vec3.ZERO, Vec3(0.27, 0.0, 0.0), 0.03))
        val before = core.view().bracket!!.countLow
        sim.jumpCamera(Vec3(0.06, 0.0, 0.0))
        sim.go(Paths.hold(cameraAt(0.27), 0.1))
        assertEquals(SectionState.FROZEN, core.view().state)
        assertEquals(BreakReason.WORLD_JUMP, core.machine.section!!.breaks.single().second)
        assertTrue(core.view().bracket!!.frozen)
        assertEquals(before, core.view().bracket!!.countLow)
        assertTrue(core.view().markers.isEmpty())
        return sim to core
    }

    @Test
    fun aWorldJumpOfOnePitchFreezesAndTheLabelWithTwoCountedUnitsRestoresTheCount() {
        val (sim, core) = jumped(seed = 4)
        sim.go(Paths.hold(cameraAt(0.27), 0.3))
        sim.moveAnchor(Vec3(0.06, 0.0, 0.0))
        sim.go(Paths.move(Vec3(0.27, 0.0, 0.0), Vec3.ZERO, 0.1))
        sim.go(Paths.hold(home, 1.0))
        assertEquals(SectionState.COUNTING, core.view().state)
        assertEquals(2, core.machine.section!!.segment)
        sim.go(Paths.move(Vec3.ZERO, Vec3(0.6, 0.0, 0.0), 0.03))
        sim.go(Paths.hold(cameraAt(0.6), 1.0))
        sim.command(Command.Finish)
        val result = core.view().closed.single()
        assertEquals(SectionStatus.COMPLETE, result.status)
        assertEquals(10, result.counted)
        assertEquals(listOf(BreakReason.WORLD_JUMP), result.breaks.map { it.second })
        neverMoreThan(10, core)
    }

    @Test
    fun aWorldJumpThatStaysMakesTheLabelMissAndTheSectionStartsAgainNeverASilentEleven() {
        val (sim, core) = jumped(seed = 5)
        sim.go(Paths.move(Vec3(0.27, 0.0, 0.0), Vec3.ZERO, 0.1))
        sim.go(Paths.hold(home, 1.0))
        val abandoned = core.view().closed.single()
        assertEquals(SectionStatus.ABANDONED, abandoned.status)
        assertTrue(abandoned.counted in 1..10)
        sim.go(Paths.move(Vec3.ZERO, Vec3(0.6, 0.0, 0.0), 0.03))
        sim.go(Paths.hold(cameraAt(0.6), 1.0))
        sim.command(Command.Finish)
        val again = core.view().closed.last()
        assertEquals(SectionStatus.COMPLETE, again.status)
        assertEquals(10, again.counted)
        neverMoreThan(10, core)
    }

    @Test
    fun withoutTwoCountedUnitsWithinFiveSecondsOfTheLabelTheSectionStartsAgain() {
        val (sim, core) = jumped(seed = 6)
        sim.moveAnchor(Vec3(0.06, 0.0, 0.0))
        sim.hidden = { it.text == GTIN }
        // the label comes into view 0.8 s into the walk back: its window closes 5 s later
        sim.go(Paths.move(Vec3(0.27, 0.0, 0.0), Vec3.ZERO, 0.1))
        sim.go(Paths.hold(home, 2.0))
        assertEquals(SectionState.FROZEN, core.view().state)
        sim.go(Paths.hold(home, 2.0))
        assertEquals(SectionStatus.ABANDONED, core.view().closed.single().status)
        assertEquals(SectionState.OPEN, core.view().state)
        neverMoreThan(10, core)
    }

    private val stop = row(3, 0.06, depth = 0.25, x0 = -0.06) + label(depth = 0.25)

    @Test
    fun turningAboutAFixedCameraCentreGivesNoDepthAndAsksToSlideALittle() {
        val (sim, core) = session(stop)
        sim.go(Paths.hold(home, 5.5))
        sim.go(Paths.yaw(Vec3.ZERO, 0.0218, 1.0, 3.0))
        assertEquals(3, core.units.count { it.state == UnitState.COUNTED })
        assertTrue(core.units.none { it.hasDepth })
        assertEquals(Prompt.SLIDE_A_LITTLE, core.view().prompt)
    }

    @Test
    fun handSwayOfTwoAndAHalfDegreesGivesDepthByTheDenseGateNotTheWideOne() {
        val (sim, core) = session(stop)
        sim.go(Paths.hold(home, 5.5))
        sim.go(Paths.sway(Vec3.ZERO, 0.0055, 1.0, 4.0))
        assertTrue(core.units.all { it.depthGate == DepthGate.DENSE })
        val accepted = core.events.filter { it.contains(": depth ") }
        assertEquals(3, accepted.size)
        assertTrue(accepted.all { it.contains("DENSE") })
        assertTrue(accepted.all { Regex("over (\\d+) rays").find(it)!!.groupValues[1].toInt() >= 8 })
        val tac = sim.lastRecord!!.cameraInAnchor()
        assertTrue(core.units.all { kotlin.math.abs(Prediction.cameraDepth(it.point, tac) - 0.25) < 0.02 })
    }

    @Test
    fun theSameCodeDecodedTwiceAThirdOfAPitchApartIsOneUnit() {
        val unit = Symbol(GTIN, Vec3(0.0, 0.04, -0.30), 1)
        val twice = Symbol(GTIN, Vec3(0.02, 0.04, -0.30), 2)
        val (sim, core) = session(listOf(unit, twice, label()))
        sim.go(Paths.hold(home, 6.0))
        assertEquals(1, core.units.size)
        assertEquals(1, core.view().bracket!!.countHigh)
    }

    @Test
    fun anUpsideDownSymbolNextToAnUprightOneIsTwoUnits() {
        val upright = Symbol(GTIN, Vec3(0.0, 0.04, -0.30), 1)
        val flipped = Symbol(GTIN, Vec3(0.06, 0.04, -0.30), 2, upsideDown = true)
        val (sim, core) = session(listOf(upright, flipped, label()))
        sim.go(Paths.hold(home, 6.0))
        assertEquals(2, core.view().bracket!!.countLow)
        assertTrue(core.events.any { it.contains("opposite reading directions") })
    }

    @Test
    fun aReadTouchingTheBorderInTheRailBandOrOfAnotherGtinIsNotAUnit() {
        val counted = Symbol(GTIN, Vec3(0.0, 0.04, -0.30), 1)
        val cut = Symbol(GTIN, Vec3(0.19, 0.04, -0.30), 2)
        val onTheRail = Symbol(GTIN, Vec3(0.08, -0.08, -0.30), 3)
        val other = Symbol(OTHER_GTIN, Vec3(-0.08, 0.04, -0.30), 4)
        val (sim, core) = session(listOf(counted, cut, onTheRail, other, label()))
        sim.go(Paths.hold(home, 6.0))
        assertEquals(1, core.units.size)
        assertEquals(1, core.view().bracket!!.countHigh)
    }

    /**
     * Stop-and-read (spec 5.5, phase B): 12 cm walks at 15 cm/s without decodes, 1.5 s still stops, the engine's
     * measured budget of one code per frame, 15 px of ray noise. The spec's merge rule then folds distinct
     * neighbours together (a miss, reported), but a count above the truth is never shown as a number.
     */
    @Test
    fun stopAndReadWithOneDecodePerFrameNeverShowsMoreThanTheTruth() {
        for (seed in 1..3) {
            views.clear()
            val (sim, core) = session(row(10, 0.06) + label(), noisePx = 15.0, seed = seed)
            sim.readsPerFrame = 1
            sim.go(Paths.hold(home, 5.5))
            var x = 0.0
            for (next in listOf(0.12, 0.24, 0.36, 0.48, 0.60)) {
                sim.readsEnabled = false
                sim.go(Paths.move(Vec3(x, 0.0, 0.0), Vec3(next, 0.0, 0.0), 0.15))
                sim.readsEnabled = true
                sim.go(Paths.hold(cameraAt(next), 1.5))
                x = next
            }
            sim.command(Command.Finish)
            val result = core.view().closed.single()
            neverMoreThan(10, core)
            assertTrue(result.status != SectionStatus.COMPLETE || result.counted == 10)
        }
    }

    @Test
    fun readsThatComeAfterTheirFrameCountTheSame() {
        val (sim, core) = session(row(10, 0.06) + label(), seed = 7, readsFirst = false)
        sim.go(Paths.hold(home, 5.5))
        sim.go(Paths.move(Vec3.ZERO, Vec3(0.6, 0.0, 0.0), 0.03))
        sim.command(Command.Finish)
        assertEquals(10, core.view().closed.single().counted)
        assertEquals(0, core.droppedReads)
    }

    @Test
    fun readsWaitForTheirFrameAndAreDroppedWhenNoPoseComesWithinAHundredMilliseconds() {
        val core = CountingCore(hostConfig)
        val r0 = PoseRecord(0, home, null, Tracking.TRACKING, null, K4K, EXPOSURE_NS)
        core.onResume(0)
        core.onFrame(r0)
        core.onReads(FRAME_NS, shoot(home, listOf(label()), FRAME_NS))
        assertEquals(0, core.droppedReads)
        core.onFrame(r0.copy(timestampNs = FRAME_NS))
        assertEquals(0, core.droppedReads)
        // ARCore stalls: the capture at 2 frames has no record, and the next comes 150 ms after it
        core.onReads(2 * FRAME_NS, shoot(home, listOf(label()), 2 * FRAME_NS))
        core.onFrame(r0.copy(timestampNs = 2 * FRAME_NS + 150_000_000))
        assertEquals(1, core.droppedReads)
        assertNotNull(core.view())
    }
}
