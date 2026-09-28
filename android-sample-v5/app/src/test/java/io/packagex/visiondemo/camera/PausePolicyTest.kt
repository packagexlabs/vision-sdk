package io.packagex.visiondemo.camera

import android.os.PowerManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PausePolicyTest {
    @Test fun idlePausesAfter90s() = runTest {
        val p = PausePolicy(backgroundScope, 90_000)
        p.userActive()
        advanceTimeBy(89_000); assertFalse(p.paused.value)
        advanceTimeBy(2_000); assertTrue(p.paused.value)
    }

    @Test fun activityResetsIdle() = runTest {
        val p = PausePolicy(backgroundScope, 90_000)
        p.userActive(); advanceTimeBy(60_000); p.userActive(); advanceTimeBy(60_000)
        assertFalse(p.paused.value)
    }

    @Test fun severeHeatPauses() = runTest {
        val p = PausePolicy(backgroundScope)
        p.thermal(PowerManager.THERMAL_STATUS_SEVERE)
        assertTrue(p.paused.value)
    }

    @Test fun criticalHeatRefusesResume() = runTest {
        val p = PausePolicy(backgroundScope)
        p.thermal(PowerManager.THERMAL_STATUS_CRITICAL)
        assertFalse(p.resume()); assertTrue(p.paused.value)
    }

    @Test fun foregroundResumesFromAnyPause() = runTest {
        val p = PausePolicy(backgroundScope, 90_000)
        p.userActive(); advanceTimeBy(91_000)   // idle-paused
        p.lifecycle(foreground = false); p.lifecycle(foreground = true)
        assertFalse(p.paused.value)
    }

    @Test fun backgroundPauses() = runTest {
        val p = PausePolicy(backgroundScope)
        p.lifecycle(foreground = false)
        assertTrue(p.paused.value)
    }
}
