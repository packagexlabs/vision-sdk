package io.packagex.visiondemo.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every device run of 2026-10-07 and 2026-10-08 with a trace ([TraceReplay]) through the current pin rules. Page 2 of the test sheet
 * holds EAN 02000000000107 x3, UNIT-A x4, UNIT-C x2, UNIT-Q x3 and UNIT-T x5 at 6, 4.5, 12, 5.5 and 3.2 cm pitches; on
 * page 1 every code is one unit. Each run's expected pins are the units it read: every page-2 run read every unit of
 * every listed code (each code was read that many times in one image, but UNIT-T: 4 at once at most in 12:34, 14:18,
 * 14:47, 15:03 and 15:54, whose fifth unit was read on its own); 12:03 is judged at 135 s, before its map drifted (below).
 *
 * A run is replayed faithfully ([FAITHFUL]) when every read's point can be had again and its table kept its height
 * within 3 cm (the anchors stay put in a replay). A read the app gave a verified pin's depth had no hit test; the replay
 * takes the table under it, which is that depth for a label on the table, so a run with a table is faithful (15:54:
 * 17 % of its reads); with no plane it takes the default depth, which the app did not: 14:07 (12 % of its reads), 14:10
 * (25 %) and 14:46 (9 %) are not faithful, nor 12:03 after 135 s (its table sank 6.7 cm by 200 s). Those are printed,
 * not judged, nor 155426-replay2, the second of three replays on the Memor of 15:54's recording (16:31): ARCore found
 * the table 21 s in and met UNIT-T's row only off its polygon, with no depth point, so the gate held three of its units
 * unborn (UNIT-T 2); the replay of that trace does not redo the device's own pins (UNIT-A 6, UNIT-Q 6 to its 4 and 3).
 * Nor 165439, 165536 and 165721, three more replays of it on the Memor (16:54-16:57, 24, 23 and 19 pins): ARCore put
 * the table at 2-4 heights and moved the world by up to 40 cm in each (the pins' anchors followed, which a trace does
 * not keep), so here their pins stay where they were born and the replay ends at 33, 16 and 17. 2026-10-08 12:20:27 is
 * judged at 14.3 s for the same reason: its section anchor stepped 3.9 cm at 14.4 s (28 and 2.2 cm later), which the
 * pins followed on the Memor (17, every unit) and not here (UNIT-A's four are born again 3-4 cm off: 8).
 */
class TraceReplayTest {
    private fun load(run: String) = TraceReplay(run, javaClass.classLoader!!.getResourceAsStream("traces/$run.ndjson.gz")!!)

    @Test
    fun everyFaithfulRunEndsWithOnePinPerUnitAndNoCountedPinLost() {
        val failures = ArrayList<String>()
        for (run in RUNS) {
            val t = load(run)
            val r = t.replay(PITCH_M, END_S[run] ?: Double.MAX_VALUE)
            val (livePins, births, retired) = t.recorded()
            println(
                "$run replay pins ${r.pins} births ${r.births} retired ${r.retired} (counted ${r.retiredCounted}, lost ${r.countedLost}) " +
                    "merged ${r.merged} dups ${r.duplicates} M1 %.0f/%.0f px stand-ins ${r.fallbacks}/${r.placedReads} | recorded pins $livePins births $births retired $retired"
                        .format(r.m1MedianPx, r.m1P90Px),
            )
            val expected = EXPECTED[run] ?: continue
            if (r.pins != expected) failures += "$run: pins ${r.pins}, expected $expected"
            if (r.countedLost > 0) failures += "$run: ${r.countedLost} counted pins removed with none in their place"
            if (r.duplicates > 0) failures += "$run: ${r.duplicates} duplicate pins at the end"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun theReplayRedoesARunRecordedUnderTheseRules() {
        // 15:42 (rec-20261007-154251) ran the rules of 15:41, which differ from these only in what it never met
        val t = load("154252")
        val r = t.replay(PITCH_M)
        val (livePins, births, retired) = t.recorded()
        assertEquals(livePins, r.pins)
        assertEquals(births, r.births)
        assertEquals(retired, r.retired)
    }

    @Test
    fun debug() {
        val run = System.getenv("TRACE_RUN") ?: return
        val code = System.getenv("TRACE_CODE")
        load(run).replay(PITCH_M, END_S[run] ?: Double.MAX_VALUE) { if (code == null || code in it || it.startsWith("end")) println("DBG $it") }
    }

    companion object {
        val RUNS = listOf(
            "115335", "115522", "120307", "123437", "140144", "140750", "141011", "141805", "142320", "144643", "144715",
            "145613", "150344", "153935", "154252", "155426", "155426-replay2", "165439", "165536", "165721",
            "1008-121622", "1008-121751", "1008-121908", "1008-122027",
        )
        val PITCH_M = mapOf(
            "02000000000107" to 0.060, "UNIT-A" to 0.045, "UNIT-C" to 0.120, "UNIT-Q" to 0.055, "UNIT-T" to 0.032,
        )
        private val PAGE2 = sortedMapOf("02000000000107" to 3, "UNIT-A" to 4, "UNIT-C" to 2, "UNIT-Q" to 3, "UNIT-T" to 5)

        /** Where a run is judged before its end: 12:03's table sank 6.7 cm after it, 2026-10-08 12:20:27's map stepped 3.9 cm */
        val END_S = mapOf("120307" to 135.0, "1008-122027" to 14.3)

        /** The faithful runs and their units read (see the class comment) */
        val EXPECTED: Map<String, Map<String, Int>> = mapOf(
            "115335" to sortedMapOf("02000000000022" to 1, "PX-0001-2026-ARCOUNT" to 1),
            "115522" to sortedMapOf("UNIT-A" to 4),
            "120307" to sortedMapOf("02000000000107" to 3, "UNIT-A" to 4, "UNIT-Q" to 3),
            "123437" to PAGE2,
            "140144" to sortedMapOf("02000000000022" to 1),
            "141805" to PAGE2,
            "142320" to PAGE2,
            "144715" to PAGE2,
            "145613" to PAGE2,
            "150344" to PAGE2,
            "153935" to PAGE2,
            "154252" to PAGE2,
            "155426" to PAGE2,
            // 2026-10-08 (the 11:56 build, recorded): every unit of every code read in each, its reads' rays meeting the
            // table on 3/4/2/3/5 clusters; 12:20:27 by 14.3 s (END_S) had read UNIT-A's four
            "1008-121622" to PAGE2,
            "1008-121751" to PAGE2,
            "1008-121908" to PAGE2,
            "1008-122027" to sortedMapOf("UNIT-A" to 4),
        )
        val FAITHFUL get() = EXPECTED.keys
    }
}
