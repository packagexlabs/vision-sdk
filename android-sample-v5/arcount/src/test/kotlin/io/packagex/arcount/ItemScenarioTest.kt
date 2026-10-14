package io.packagex.arcount

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * AR Item Count scenario sims (spec 5.10): the list {A, B}, a shelf of 4×A, 2×C and 3×B (C unlisted) with identical
 * units of a code next to each other at a pitch of 6–8 cm, 30 cm away. Every cell runs [SEEDS] seeds; every run must
 * show no definite count above the truth at any frame, and end with the truth inside each listed code's range.
 * Set `ARCOUNT_SIM_OUT=<dir>` to keep each violating run's events (`ARCOUNT_SIM_ALL=1`: every run's).
 *
 * "jumpbreak" is a one-pitch world jump the anchor does not follow, so T_ac jumps; the resume finds the jump's
 * correction (ruling R2). "longrow" is one row of three bays back to back, 1.8 m, walked in one pass: the section hands
 * its units over to a new anchor at the reach (ruling R1). "jumpsame" (only on request: `ARCOUNT_SIM_MOTIONS=jumpsame`)
 * makes the same jump over nine identical A units, where the pitch aliases tie: the section is abandoned and adds
 * [0, its high] to the count (ruling R3), and the cell shows how wide that makes the range.
 */
class ItemScenarioTest {
    private val b = "TP6056F32"
    private val list = linkedSetOf(GTIN14, b)

    /** One bay starting at [x0]; pitch per gap uniform in 6–8 cm */
    private fun bay(rnd: Random, x0: Double, idBase: Int): List<Symbol> {
        val codes = List(4) { GTIN } + List(2) { OTHER_GTIN } + List(3) { b }
        var x = x0
        return codes.mapIndexed { i, code ->
            if (i > 0) x += rnd.nextDouble(0.06, 0.08)
            Symbol(code, Vec3(x, 0.04, -0.30), idBase + i)
        }
    }

    private class Outcome(val violations: List<String>, val exact: Boolean, val sections: List<SectionStatus>, val width: Int, val events: List<String>)

    private fun run(motion: String, budget: Int, noise: Double, seed: Int): Outcome {
        val rnd = Random(seed * 7919 + budget * 31 + noise.toInt())
        val shelf = when (motion) {
            "walk2m" -> bay(rnd, 0.0, 1) + bay(rnd, 1.4, 101)
            // three bays back to back: one row longer than the reach
            "longrow" -> (0 until 3).fold(emptyList<Symbol>()) { acc, i -> acc + bay(rnd, (acc.maxOfOrNull { it.centre.x } ?: -0.07) + 0.07, 1 + 100 * i) }
            "jumpsame" -> List(9) { i -> Symbol(GTIN, Vec3(0.07 * i, 0.04, -0.30), i + 1) }
            else -> bay(rnd, 0.0, 1)
        }
        val truth = mapOf(GTIN14 to shelf.count { it.text == GTIN }, b to shelf.count { it.text == b })
        val core = CountingCore()
        core.setItems(list)
        val sim = Sim(shelf, CoreCounter(core), noisePx = noise, seed = seed)
        sim.readsPerFrame = budget
        val violations = ArrayList<String>()
        var last = cameraAt(0.0)
        fun go(path: List<Pose>) = sim.run(path.also { last = it.last() }) {
            for (it in core.view().items) if (it.countLow > truth.getValue(it.code)) violations += "t=${sim.frame}: ${it.code} definite ${it.countLow} > ${truth[it.code]}"
        }
        val end = shelf.maxOf { it.centre.x } + 0.06
        go(Paths.hold(cameraAt(0.0), 5.5))
        when (motion) {
            "oneway" -> go(Paths.move(Vec3.ZERO, Vec3(end, 0.0, 0.0), 0.03))
            "thereback" -> {
                go(Paths.move(Vec3.ZERO, Vec3(end, 0.0, 0.0), 0.03))
                go(Paths.move(Vec3(end, 0.0, 0.0), Vec3.ZERO, 0.03))
            }
            "walk2m", "longrow" -> go(Paths.move(Vec3.ZERO, Vec3(2.0, 0.0, 0.0), 0.03))
            "jumpanchored", "jumpbreak", "jumpsame" -> {
                go(Paths.move(Vec3.ZERO, Vec3(0.30, 0.0, 0.0), 0.03))
                // ARCore's world jumps one pitch along the row; with "jumpanchored" the anchor's world pose moves with
                // it, as ARCore updates anchors, so T_ac holds; with "jumpbreak" it stays and T_ac jumps
                sim.jumpCamera(Vec3(0.06, 0.0, 0.0))
                if (motion == "jumpanchored") sim.moveAnchor(Vec3(0.06, 0.0, 0.0))
                go(Paths.move(Vec3(0.30, 0.0, 0.0), Vec3(end, 0.0, 0.0), 0.03))
            }
        }
        go(Paths.hold(last, 1.0))
        sim.command(Command.Finish)
        val items = core.view().items
        for (it in items) {
            val t = truth.getValue(it.code)
            if (it.countLow > t || it.countHigh < t) violations += "end: ${it.code} ${it.countLow}..${it.countHigh}, truth $t"
        }
        val exact = items.all { it.countLow == truth[it.code] && it.countHigh == truth[it.code] }
        val events = core.machine.events + core.view().closed.map { it.toString() }
        return Outcome(violations, exact, core.view().closed.map { it.status }, items.sumOf { it.countHigh - it.countLow }, events)
    }

    @Test
    fun everyCellKeepsTheTruth() {
        val out = System.getenv("ARCOUNT_SIM_OUT")?.let { File(it).apply { mkdirs() } }
        val bad = ArrayList<String>()
        println("motion       budget noise  runs exact  COMPLETE/sections  meanWidth maxWidth violations  statuses")
        for (motion in MOTIONS) for (budget in listOf(2, 3, Int.MAX_VALUE)) for (noise in listOf(1.0, 15.0)) {
            val runs = (1..SEEDS).map { seed -> seed to run(motion, budget, noise, seed) }
            val sections = runs.flatMap { it.second.sections }
            val violating = runs.filter { it.second.violations.isNotEmpty() }
            println(
                "%-12s %6s %5.0f %5d %5d %8d/%-8d %9.2f %8d %10d  %s".format(
                    motion, if (budget == Int.MAX_VALUE) "all" else budget.toString(), noise, runs.size, runs.count { it.second.exact },
                    sections.count { it == SectionStatus.COMPLETE }, sections.size, runs.map { it.second.width }.average(), runs.maxOf { it.second.width }, violating.size,
                    sections.groupingBy { it }.eachCount(),
                ),
            )
            if (out != null && System.getenv("ARCOUNT_SIM_ALL") != null) {
                for ((seed, o) in runs) File(out, "all-$motion-b$budget-n${noise.toInt()}-s$seed.txt").writeText(o.events.joinToString("\n"))
            }
            for ((seed, o) in violating) {
                val id = "$motion-b$budget-n${noise.toInt()}-s$seed"
                bad += "$id: ${o.violations.first()} (${o.violations.size} frames)"
                out?.let { File(it, "$id.txt").writeText((o.violations.take(20) + listOf("--- events") + o.events).joinToString("\n")) }
            }
        }
        bad.forEach { println("VIOLATION $it") }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    private companion object {
        val MOTIONS = (System.getenv("ARCOUNT_SIM_MOTIONS") ?: "oneway,thereback,walk2m,jumpanchored,jumpbreak,longrow").split(',')
        val SEEDS = System.getenv("ARCOUNT_SIM_SEEDS")?.toInt() ?: 5
    }
}
