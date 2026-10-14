package io.packagex.visiondemo.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift plan Phase 3's definition of done, with Phase 4's estimator and without it: the identical-unit scenarios on the
 * real [PinBook] ([TwinSim]), 2 units 5, 8 and 12 cm apart, read every 300 ms out of phase or every frame, with ARCore's
 * hits on depth or 15 cm too deep, 5 seeds each. Since Phase 4 each claim refines its pin, so a depth error is corrected
 * rather than re-born: the plan's "2 pins, 0 hops, 0 removals" holds for biased hits too (replay `rec`). With pins
 * frozen ([PinBook.refine] off, the arm Phase 4 is A/B tested against) the plan's Phase 3 numbers hold (`recFrozen`).
 */
class PinTwinTest {
    private val pitches = listOf(0.05, 0.08, 0.12)
    private val cadences = listOf(300, 0)
    private val seeds = 0L until 5L
    private val biases = listOf(0.0, 0.15)

    private fun runs(
        scenario: TwinScenario,
        biasM: Double,
        rules: PinRules = PinRules.ANDROID,
        refine: Boolean = true,
        check: (String, TwinRun) -> Unit,
    ) {
        for (pitch in pitches) for (cad in cadences) for (seed in seeds) {
            val r = TwinSim(rules, pitch, cad, scenario, biasM, seed, refine = refine).run()
            check("$rules refine $refine $scenario pitch $pitch cadence $cad bias $biasM seed $seed: $r", r)
        }
    }

    /** The 90th percentile of [v] (0 for none) */
    private fun p90(v: List<Double>): Double = v.sorted().let { if (it.isEmpty()) 0.0 else it[(it.size - 1) * 9 / 10] }

    @Test fun twinsKeepOnePinEachWithNoHopMergeOrRetirementWhereverTheHitsLie() {
        for (scenario in listOf(TwinScenario.BASE, TwinScenario.A, TwinScenario.B)) for (bias in biases) {
            runs(scenario, bias) { what, r ->
                assertEquals(what, 2, r.pins)
                assertEquals(what, 2, r.births)
                assertEquals(what, 0, r.hops)
                assertEquals(what, 0, r.merges)
                assertEquals(what, 0, r.retirements)
                assertEquals(what, 0.0, r.maxGhostS, 0.0)
                assertTrue(what, r.maxUnclaimedS < 1.5)
                assertTrue(what, r.farShare < 0.05) // reads more than 150 px from every pin: only before B's birth
                assertEquals(what, 2, r.verified) // the camera's ±12 cm slide verifies both depths
            }
        }
    }

    @Test fun iosRulesKeepOnePinForTwinsAndReBirthItAllTheTime() {
        // Root cause 4, which Phase 3 fixes: the neighbour's read starts a candidate, and the newborn evicts the pin
        val ios = TwinSim(PinRules.IOS, 0.08, 0).run()
        assertEquals(1, ios.pins)
        assertTrue("$ios", ios.merges > 100)
        assertTrue("$ios", ios.farShare > 0.4)
        assertEquals("$ios", 0, ios.verified) // iOS pins are never refined
    }

    @Test fun aCountedUnitUndecodableForThreeSecondsKeepsItsPin() {
        // A2: unit A not decoded from 8 s to 11 s while B is. A was counted long before (PIN_COUNTED_CLAIMS): its pin
        // stays through the gap, never retired and re-born
        for (bias in biases) {
            runs(TwinScenario.A2, bias) { what, r ->
                assertEquals(what, 2, r.pins)
                assertEquals(what, 2, r.births)
                assertEquals(what, 0, r.merges)
                assertEquals(what, 0, r.retirements)
                assertEquals(what, 0, r.hops)
            }
        }
    }

    @Test fun moreOrFewerUnitsWithHitsFifteenCentimetresTooDeepNeverHopOrMerge() {
        // One unit, and three in a row: each pin's depth converges from its rays. Of three units read every 300 ms a
        // neighbour's read may now and then seed a short-lived pin that retires; still fewer reads lie far from every pin
        // than under iOS
        for (units in listOf(1, 3)) for (pitch in pitches) for (cad in cadences) {
            val android = seeds.map { TwinSim(PinRules.ANDROID, pitch, cad, TwinScenario.BASE, 0.15, it, units).run() }
            val ios = seeds.map { TwinSim(PinRules.IOS, pitch, cad, TwinScenario.BASE, 0.15, it, units).run() }
            val config = "units $units pitch $pitch cadence $cad"
            for (r in android) {
                val what = "$config: $r"
                assertEquals(what, units, r.pins)
                assertEquals(what, 0, r.hops)
                assertEquals(what, 0, r.merges)
                assertTrue(what, r.retirements <= 1)
                assertTrue(what, r.maxGhostS <= 1.0)
                assertTrue(what, r.farShare < 0.01)
                assertEquals(what, units, r.verified)
            }
            if (units > 1) {
                val far = android.sumOf { it.farShare } - ios.sumOf { it.farShare }
                assertTrue("$config: far ${android.map { it.farShare }} vs iOS ${ios.map { it.farShare }}", far < 0)
            }
        }
    }

    @Test fun twinsWithNoHitAtAllAreBornAtTheirNominalWidth() {
        for (pitch in pitches) for (cad in cadences) for (seed in seeds) {
            val r = TwinSim(PinRules.ANDROID, pitch, cad, seed = seed, hits = false).run()
            val what = "pitch $pitch cadence $cad seed $seed: $r"
            assertEquals(what, 2, r.pins)
            assertEquals(what, 2, r.births)
            assertTrue(what, r.farShare < 0.01)
        }
    }

    @Test fun mapShiftsUpToTwentyCentimetresAreRecoveredWithinTwoAndAHalfSeconds() {
        // Every unit moves at 10 s, as the pins see it (a correction their anchors did not follow, undetected). Each pin
        // is then off its unit, by 3 cm as by 20: its claims are voided for a second (rule 3) and their reads seed a
        // candidate; after it its third bad claim in a row re-initialises it on its unit (rule 4). Whichever comes first
        // wins: a re-birth, and the stale pin retires 2 s after its first miss, or the re-init, and nothing goes. A 20 cm
        // shift moves a unit out of the image for part of each sweep, and a pin retires only while its code is read.
        var reinitOnly = 0
        for (units in 1..3) for (shift in listOf(0.03, 0.10, 0.20)) for (detected in listOf(true, false)) for (cad in cadences) for (seed in seeds) {
            val r = TwinSim(PinRules.ANDROID, 0.08, cad, seed = seed, units = units, shiftM = shift, shiftDetected = detected).run()
            val what = "units $units shift $shift detected $detected cadence $cad seed $seed: $r"
            if (!detected || shift > 0.08) {
                // A counted item is never removed (the user's rule): undetected or past one pitch, its stale pin stays
                assertTrue(what, (r.recoveredS ?: Double.MAX_VALUE) <= 2.5)
                assertTrue(what, r.ghosts <= units)
                continue
            }
            assertEquals(what, units, r.pins)
            assertEquals(what, 0, r.ghosts)
            assertEquals(what, 0, r.merges)
            assertTrue(what, r.retirements <= units) // no hop count: a shift past the pitch leaves a stale pin by the other unit
            assertEquals(what, units + r.retirements, r.births)
            assertTrue(what, (r.recoveredS ?: Double.MAX_VALUE) <= 2.5)
            assertTrue(what, r.farShare < 0.12)
            assertTrue(what, r.maxGhostS <= if (shift <= 0.10) 3.1 else 6.0)
            if (r.retirements < units) reinitOnly++
        }
        assertTrue("runs where a re-init alone recovered a unit: $reinitOnly", reinitOnly > 0)
    }

    // --- Pins frozen (Phase 3): what the estimator is measured against ---

    @Test fun frozenTwinsWithHitsOnDepthKeepOnePinEachWithNoHopOrRemoval() {
        for (scenario in listOf(TwinScenario.BASE, TwinScenario.A, TwinScenario.B)) {
            runs(scenario, 0.0, refine = false) { what, r ->
                assertEquals(what, 2, r.pins)
                assertEquals(what, 2, r.births)
                assertEquals(what, 0, r.hops)
                assertEquals(what, 0, r.merges)
                assertEquals(what, 0, r.retirements)
                assertEquals(what, 0.0, r.maxGhostS, 0.0)
                assertEquals(what, 0, r.verified) // frozen pins are never refined
            }
        }
    }

    @Test fun frozenPinsOfHitsFifteenCentimetresTooDeepNeverHopAndTheirStaleOnesRetire() {
        // A frozen pin that slides off its unit is re-born and the stale one retired: per unit per 20 s at most 7
        // retirements read every 300 ms and 12 read every frame; at the end at most 3 stale pins beside the units; ghost
        // lifetime p90 ≤ 3.2 s. The plan's "up to 4 pins per unit at once" is the three units' 12; one unit reaches 5
        for (units in 1..3) {
            val scenarios = if (units == 2) listOf(TwinScenario.BASE, TwinScenario.A, TwinScenario.B, TwinScenario.A2) else listOf(TwinScenario.BASE)
            for (scenario in scenarios) for (pitch in pitches) for (cad in cadences) {
                val frozen = seeds.map { TwinSim(PinRules.ANDROID, pitch, cad, scenario, 0.15, it, units, refine = false).run() }
                val config = "units $units $scenario pitch $pitch cadence $cad"
                for (r in frozen) {
                    val what = "$config: $r"
                    assertEquals(what, 0, r.hops)
                    assertTrue(what, r.retirements <= units * if (cad == 0) 12 else 7)
                    assertTrue(what, r.pins in units..units + 3)
                    assertTrue(what, r.maxPins <= 5 * units)
                }
                assertTrue("$config: ghost lives ${frozen.map { it.ghostLivesS }}", p90(frozen.flatMap { it.ghostLivesS }) <= 3.2)
                if (units > 1 && scenario == TwinScenario.BASE) {
                    val ios = seeds.map { TwinSim(PinRules.IOS, pitch, cad, scenario, 0.15, it, units).run() }
                    assertTrue("$config: far ${frozen.map { it.farShare }} vs iOS ${ios.map { it.farShare }}", frozen.sumOf { it.farShare } < ios.sumOf { it.farShare })
                }
            }
        }
    }

    @Test fun aFrozenUnitUndecodableForThreeSecondsIsRetiredOnceThenReBorn() {
        // Refinement off keeps the old retirement for counted pins too (PIN_COUNTED_CLAIMS)
        runs(TwinScenario.A2, 0.0, refine = false) { what, r ->
            assertEquals(what, 2, r.pins)
            assertEquals(what, 3, r.births)
            assertEquals(what, 1, r.retirements)
            assertTrue(what, r.bornAtS.last() > 11.0)
            assertEquals(what, 0, r.hops)
        }
    }

    @Test fun frozenPinsRecoverMapShiftsUpToTwentyCentimetresWithinTwoAndAHalfSecondsByReBirth() {
        // A frozen pin cannot re-initialise: from its third bad claim in a row its reads seed a candidate, the new pin is
        // born on its unit and the stale one retires. A 20 cm shift's ghost lives up to 6 s while its unit is out of view
        for (units in 1..3) for (shift in listOf(0.03, 0.10, 0.20)) for (cad in cadences) for (seed in seeds) {
            val r = TwinSim(PinRules.ANDROID, 0.08, cad, seed = seed, units = units, shiftM = shift, refine = false).run()
            val what = "units $units shift $shift cadence $cad seed $seed: $r"
            assertEquals(what, units, r.pins)
            assertEquals(what, 0, r.ghosts)
            assertEquals(what, 0, r.merges)
            assertEquals(what, units, r.retirements)
            assertEquals(what, 2 * units, r.births)
            assertTrue(what, (r.recoveredS ?: Double.MAX_VALUE) <= 2.5)
            assertTrue(what, r.maxGhostS <= if (shift <= 0.10) 3.1 else 6.0)
        }
    }
}
