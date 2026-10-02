package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Stop-and-read (ScenarioTest's) with the patch tracker fed a luma copy of every frame (spec 5.9) */
class TrackingScenarioTest {
    class Run(val core: CountingCore, val views: List<CountView>)

    companion object {
        /**
         * 12 cm walks at 15 cm/s without decodes; at each stop 1.5 s still, 1.5 s of ±5.5 mm sway, or a 5 cm slide at
         * 5 cm/s; [budget] codes decoded per engine frame; [tracking]: the core gets a luma copy of every frame.
         */
        fun stopAndRead(budget: Int, seed: Int, stop: String, back: Boolean, noisePx: Double, tracking: Boolean, lumaFramesLate: Int = 0): Run {
            val views = ArrayList<CountView>()
            val core = CountingCore(hostConfig)
            val sim = Sim(row(10, 0.06) + label(), CoreCounter(core), noisePx = noisePx, seed = seed)
            if (tracking) sim.feedLuma(core, LumaScene({ sim.symbols }, seed = seed), lumaFramesLate)
            sim.readsPerFrame = budget
            fun go(path: List<Pose>) = sim.run(path) { views += core.view() }
            go(Paths.hold(cameraAt(0.0), 5.5))
            var x = 0.0
            val out = listOf(0.12, 0.24, 0.36, 0.48, 0.60)
            for (next in if (back) out + listOf(0.48, 0.36, 0.24, 0.12, 0.0) else out) {
                sim.readsEnabled = false
                go(Paths.move(Vec3(x, 0.0, 0.0), Vec3(next, 0.0, 0.0), 0.15))
                sim.readsEnabled = true
                x = next
                when (stop) {
                    "still" -> go(Paths.hold(cameraAt(x), 1.5))
                    "sway" -> go(Paths.sway(Vec3(x, 0.0, 0.0), 0.0055, 1.0, 1.5))
                    else -> {
                        go(Paths.move(Vec3(x, 0.0, 0.0), Vec3(x + 0.05, 0.0, 0.0), 0.05))
                        x += 0.05
                    }
                }
            }
            sim.command(Command.Finish)
            return Run(core, views)
        }

        /** No definite count above 10 in any view or COMPLETE result, and every section not abandoned holds 10 in its range */
        fun truthKept(run: Run): Boolean {
            if (run.views.any { v -> v.bracket != null && v.bracket!!.countLow > 10 }) return false
            val closed = run.core.view().closed
            if (closed.any { it.status == SectionStatus.COMPLETE && it.counted > 10 }) return false
            return closed.filter { it.status != SectionStatus.ABANDONED }.all { it.countLow <= 10 && it.countHigh >= 10 }
        }
    }

    @Test
    fun aOneWaySlideAtTwoDecodesPerFrameWithTrackingCountsTenComplete() {
        val run = stopAndRead(2, seed = 1, stop = "slide", back = false, noisePx = 15.0, tracking = true)
        val result = run.core.view().closed.single()
        assertTrue(truthKept(run))
        assertEquals(SectionStatus.COMPLETE, result.status)
        assertEquals(10, result.counted)
    }

    @Test
    fun withEachLumaCopyAfterItsPoseRecordTheSlideStillCountsTenComplete() {
        // 1: right after its own record; 2: after the next frame's record too (a frame late); 3: two frames late
        for (late in 1..3) {
            val run = stopAndRead(2, seed = 1, stop = "slide", back = false, noisePx = 15.0, tracking = true, lumaFramesLate = late)
            val result = run.core.view().closed.single()
            assertTrue(truthKept(run))
            assertEquals("$late frames late", SectionStatus.COMPLETE, result.status)
            assertEquals(10, result.counted)
            assertTrue(run.core.patchTracks.trackedRays > 0)
            assertEquals(0, run.core.lateLumas)
        }
    }

    /** The full table, off and on; run with ARCOUNT_TABLE=1 (several minutes) */
    @Test
    fun stopAndReadTable() {
        assumeTrue(System.getenv("ARCOUNT_TABLE") != null)
        val seeds = (System.getenv("ARCOUNT_SEEDS") ?: "5").toInt()
        val budgets = listOf(1, 2, 3, Int.MAX_VALUE)
        val failures = ArrayList<String>()
        val sb = StringBuilder("| Path | Stop | Noise | Tracking | budget 1 | budget 2 | budget 3 | all codes |\n|---|---|---|---|---|---|---|---|\n")
        for (back in listOf(false, true)) for (stop in listOf("still", "sway", "slide")) for (noise in listOf(1.0, 15.0)) for (tracking in if (System.getenv("ARCOUNT_ON_ONLY") != null) listOf(true) else listOf(false, true)) {
            val cells = budgets.map { budget ->
                val late = (System.getenv("ARCOUNT_LUMA_LATE") ?: "0").toInt()
                val runs = (1..seeds).map { seed -> stopAndRead(budget, seed, stop, back, noise, tracking, late) }
                runs.forEachIndexed { i, r ->
                    if (!truthKept(r)) failures += "back=$back stop=$stop noise=$noise tracking=$tracking budget=$budget seed=${i + 1}: ${r.core.view().closed}"
                }
                val results = runs.map { it.core.view().closed.last() }
                val complete = results.count { it.status == SectionStatus.COMPLETE && it.counted == 10 }
                val widths = results.map { it.countHigh - it.countLow }
                val lows = results.map { it.countLow }
                val highs = results.map { it.countHigh }
                "$complete/$seeds C; [${lows.min()}–${lows.max()}, ${highs.min()}–${highs.max()}] w ${"%.1f".format(widths.average())}"
            }
            val b = cells.joinToString(" | ")
            sb.append("| ${if (back) "there-and-back" else "one-way"} | $stop | ${noise.toInt()} px | ${if (tracking) "on" else "off"} | $b |\n")
            println(sb.lines().dropLast(1).last())
        }
        println(sb)
        println("FAILURES ${failures.size}")
        failures.forEach { println(it) }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
