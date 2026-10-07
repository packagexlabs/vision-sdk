package io.packagex.visiondemo.ar

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6 on the JVM: what [PinBook.place] (the pins' identity rules, and with them [PinEstimator]'s solves, rule 1's gate
 * and the births' priors) costs per claim over a realistic minute of [TwinSim] reads, after the JIT has warmed up on the
 * same runs. The device's budget is 2 ms of pin work a frame; on the JVM the bound here is a generous 0.5 ms a claim on
 * average, so only a gross regression fails it. The numbers are printed for the record.
 */
class PinBookTimingTest {
    private class Workload(val what: String, val sim: (PlaceTiming) -> TwinSim)

    private val workloads = listOf(
        // P2c's full rate: both units read every frame, ARCore's hits 15 cm too deep, so every claim refines its pin
        Workload("2 units 8 cm apart read every frame, hits 15 cm deep") { TwinSim(pitch = 0.08, cadenceMs = 0, biasM = 0.15, seconds = 60.0, timing = it) },
        // The schedule's rate, and a unit unreadable for 3 s: claims, voids and a re-birth's candidates
        Workload("2 units 5 cm apart read every 300 ms, A unreadable 8-11 s") {
            TwinSim(pitch = 0.05, cadenceMs = 300, scenario = TwinScenario.A2, seconds = 60.0, timing = it)
        },
        // No hits at all: every birth at the nominal width's depth, then a map shift the pins must recover from
        Workload("3 units 12 cm apart read every frame, no hits, the map shifted 3 cm at 30 s") {
            TwinSim(pitch = 0.12, cadenceMs = 0, units = 3, hits = false, shiftM = 0.03, shiftAtS = 30.0, seconds = 60.0, timing = it)
        },
    )

    @Test fun placingAMinuteOfTwinReadsCostsWellUnderHalfAMillisecondAClaim() {
        for (w in workloads) {
            repeat(WARM_UP_RUNS) { w.sim(PlaceTiming()).run() }
            val timing = PlaceTiming()
            val run = w.sim(timing).run()
            println("PinBook.place, ${w.what}: $timing; $run")
            assertTrue("${w.what}: $timing", timing.claims > 200)
            assertTrue("${w.what}: $timing", timing.ns / 1e6 / timing.claims < 0.5)
        }
    }

    private companion object {
        const val WARM_UP_RUNS = 3
    }
}
