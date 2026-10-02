package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The core with the patch tracker wired (spec 5.3, 5.5, 5.9): markers for the app's frame, the published refresh */
class TrackingCoreTest {
    private val home = cameraAt(0.0)
    private val four = row(4, 0.06, x0 = -0.09)

    private fun session(symbols: List<Symbol>, luma: Boolean = true): Pair<Sim, CountingCore> {
        val core = CountingCore(hostConfig)
        val sim = Sim(symbols, CoreCounter(core))
        if (luma) sim.feedLuma(core, LumaScene({ sim.symbols }))
        return sim to core
    }

    /** The marker's anchor point and metric size, projected as the app does with the latest frame's T_ac */
    private fun projected(m: Marker, r: PoseRecord): Triple<Double, Double, Double> {
        val tac = r.cameraInAnchor()
        val (u, v) = Prediction.pixel(m.anchorPoint!!, tac, r.intrinsics)!!
        val size = r.intrinsics.fx * m.sizeM / Prediction.cameraDepth(m.anchorPoint!!, tac)
        return Triple(u, v, size)
    }

    @Test
    fun aTrackedUnitsMarkerIsThePointOnItsTrackedRayAtItsDepthWithTheSymbolsWidth() {
        val (sim, core) = session(four + label())
        sim.run(Paths.hold(home, 5.5))
        assertEquals(4, core.view().bracket!!.countLow)
        // walk 3 cm at 6 cm/s with no decode of the units: the tracker carries them and gives them depth
        sim.hidden = { it.text == GTIN }
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.03, 0.0, 0.0), 0.06))
        val r = sim.lastRecord!!
        val anchor = sim.anchor!!
        val markers = core.view().markers
        assertEquals(4, markers.size)
        for (m in markers) {
            val unit = core.units.single { it.id == m.unitId }
            assertTrue(unit.hasDepth)
            assertTrue(core.patchTracks.isTracked(unit.id))
            val nearest = four.minBy { (anchor.inverse().apply(it.centre) - m.anchorPoint!!).norm() }
            assertTrue("marker ${m.unitId} ${(anchor.inverse().apply(nearest.centre) - m.anchorPoint!!).norm()} m off", (anchor.inverse().apply(nearest.centre) - m.anchorPoint!!).norm() < 0.004)
            assertEquals(0.031, m.sizeM, 0.002)
            val (u, v, size) = projected(m, r)
            assertEquals(u, m.u * 3840, 1e-6)
            assertEquals(v, m.v * 2160, 1e-6)
            assertEquals(size, m.sizeU * 3840, 3.0)
            val (tu, tv) = K4K.project(r.camera.inverse().apply(nearest.centre))!!
            assertTrue(hypot(u - tu, v - tv) < 6.0)
        }
    }

    @Test
    fun aDecodedOnlyUnitsMarkerIsItsPointAndAUnitAddedByHandItsPlanePointAtOnePitch() {
        val (sim, core) = session(four + label(), luma = false)
        sim.run(Paths.hold(home, 5.5))
        sim.command(Command.AddUnit)
        val r = sim.lastRecord!!
        val markers = core.view().markers
        assertEquals(5, markers.size)
        for (m in markers) {
            val unit = core.units.single { it.id == m.unitId }
            assertEquals(unit.point, m.anchorPoint)
            val (u, v, size) = projected(m, r)
            if (unit.state == UnitState.MANUAL) {
                assertEquals(core.machine.section!!.table!!.pitch, m.sizeM, 0.0)
                assertEquals(u, m.u * 3840, 1e-6)
            } else {
                // a still camera: the marker's quad and its point project to the same place and size
                assertEquals(u, m.u * 3840, 2.0)
                assertEquals(v, m.v * 2160, 2.0)
            }
            assertEquals(size, m.sizeU * 3840, 2.0)
        }
    }

    @Test
    fun theViewPublishesTheRefreshScheduleOnceTheTrackerIsFed() {
        val (still, unfed) = session(four + label(), luma = false)
        still.run(Paths.hold(home, 7.0))
        assertEquals(SectionState.COUNTING, unfed.view().state)
        assertEquals(0, unfed.view().desiredRefreshMs)
        val (sim, core) = session(four + label())
        val seen = ArrayList<Int>()
        sim.run(Paths.hold(home, 5.5)) { seen += core.view().desiredRefreshMs }
        assertTrue(seen.all { it == 0 })
        sim.run(Paths.hold(home, 1.0))
        assertEquals(300, core.view().desiredRefreshMs)
        sim.hidden = { it.text == GTIN }
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.03, 0.0, 0.0), 0.06))
        assertEquals(300, core.view().desiredRefreshMs)
        // settling: 0 for the next 8 engine frames (every third frame here)
        seen.clear()
        sim.run(Paths.hold(cameraAt(0.03), 1.0)) { seen += core.view().desiredRefreshMs }
        assertEquals(listOf(300), seen.take(1))
        assertTrue(seen.drop(1).take(20).all { it == 0 })
        assertEquals(300, seen.last())
        sim.run(Paths.hold(cameraAt(0.03), 3.0))
        assertEquals(450, core.view().desiredRefreshMs)
    }

    /**
     * Counting four units, then 3 cm at 6 cm/s with no decode of them; each luma copy comes at the start of the frame
     * [late] frames after its own, so after its own pose record and the next late − 1 ones
     */
    private fun walkWithLuma(late: Int): CountingCore {
        val core = CountingCore(hostConfig)
        val sim = Sim(four + label(), CoreCounter(core))
        sim.feedLuma(core, LumaScene({ sim.symbols }), late)
        sim.run(Paths.hold(home, 5.5))
        assertEquals(4, core.view().bracket!!.countLow)
        sim.hidden = { it.text == GTIN }
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.03, 0.0, 0.0), 0.06))
        return core
    }

    @Test
    fun aLumaCopyIsTrackedWhetherItComesBeforeOrAfterItsPoseRecord() {
        val early = walkWithLuma(0)
        for (late in listOf(1, 2, 4)) {
            val core = walkWithLuma(late)
            assertEquals(0, core.lateLumas)
            // every frame of the walk is tracked for its four units, but the last [late]: their copies are on the way
            assertEquals("$late late", early.patchTracks.trackCalls - 4 * late, core.patchTracks.trackCalls)
            assertTrue(core.units.all { core.patchTracks.hasPatch(it.id) && it.hasDepth })
            assertEquals(4, core.view().markers.size)
        }
    }

    @Test
    fun aLumaCopyMoreThanATenthOfASecondBehindTheNewestPoseRecordIsDropped() {
        // each copy comes after the pose record 4 frames (133 ms) newer: every copy is dropped, nothing tracked or captured
        val core = walkWithLuma(5)
        assertTrue(core.lateLumas > 0)
        assertEquals(0, core.trackFrames)
        assertTrue(core.units.none { core.patchTracks.hasPatch(it.id) })
        assertEquals(0, core.view().desiredRefreshMs)
    }

    @Test
    fun aLumaCopyOlderThanAFrameAlreadyTrackedIsNotTracked() {
        val core = CountingCore(hostConfig)
        val sim = Sim(four + label(), CoreCounter(core))
        val scene = LumaScene({ sim.symbols })
        val held = ArrayList<Pair<Long, LumaImage>>()
        var holdBack = false
        sim.luma = { ts, camera ->
            val img = scene.render(ts, camera)
            if (holdBack) held += ts to img else core.onLuma(ts, img, scene.scale)
        }
        sim.run(Paths.hold(home, 5.5))
        sim.hidden = { it.text == GTIN }
        val walk = Paths.move(Vec3.ZERO, Vec3(0.03, 0.0, 0.0), 0.06)
        sim.run(walk.take(3))
        // frame A's copy is held back; frame B's comes first and is tracked; then A's comes, in time but out of order
        holdBack = true
        sim.step(walk[3])
        holdBack = false
        val before = core.trackFrames
        sim.step(walk[4])
        assertEquals(before + 1, core.trackFrames)
        val (ts, img) = held.single()
        core.onLuma(ts, img, scene.scale)
        assertEquals(before + 1, core.trackFrames)
        assertEquals(0, core.lateLumas)
    }

    @Test
    fun meanTrackingTimePerFrameForTenTrackedUnits() {
        val ten = row(10, 0.035, x0 = -0.16)
        val (sim, core) = session(ten + label())
        sim.run(Paths.hold(home, 5.5))
        assertEquals(10, core.view().bracket!!.countLow)
        sim.hidden = { it.text == GTIN }
        // 3 cm at 3 cm/s to warm up, then the same back, timed; no decode of the units, under 3 s since the last
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.03, 0.0, 0.0), 0.03))
        var frames = 0
        var trackedSum = 0
        val start = core.trackNanos to core.trackFrames
        sim.run(Paths.move(Vec3(0.03, 0.0, 0.0), Vec3.ZERO, 0.03)) {
            frames++
            trackedSum += core.units.count { core.patchTracks.isTracked(it.id) }
        }
        val ms = (core.trackNanos - start.first) / 1e6 / (core.trackFrames - start.second)
        println("tracking: %.3f ms per frame, %.1f of 10 units tracked per frame, %d frames".format(ms, trackedSum.toDouble() / frames, frames))
    }
}
