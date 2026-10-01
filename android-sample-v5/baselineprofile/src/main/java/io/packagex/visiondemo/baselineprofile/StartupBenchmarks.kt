package io.packagex.visiondemo.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start time to the module cards (the v6 first screen), without and with the baseline profile:
 * `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark`.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun startupCompilationNone() = benchmark(CompilationMode.None())

    @Test fun startupCompilationBaselineProfiles() = benchmark(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun benchmark(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { grantCamera(); pressHome() },
    ) {
        startToHome()
    }
}
