package io.packagex.visiondemo.camera

import io.packagex.visionsdk.camera.core.CameraError
import io.packagex.visionsdk.camera.core.CameraStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraReclaimTest {
    @Test fun lossRules() {
        assertTrue(isCameraLoss(CameraStatus.INTERRUPTED, null))
        assertTrue(isCameraLoss(CameraStatus.ERROR, CameraError.ConfigurationFailed(null)))
        assertTrue(isCameraLoss(CameraStatus.ERROR, null))
        assertFalse(isCameraLoss(CameraStatus.ERROR, CameraError.PermissionDenied))
        assertFalse(isCameraLoss(CameraStatus.RUNNING, null))
        assertFalse(isCameraLoss(CameraStatus.IDLE, null))
    }

    @Test fun backoffDoublesUpTo30s() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..6).map(CameraReclaim::backoffMs))
    }

    @Test fun lostRetriesWithBackoffUntilItRunsStably() = runTest {
        var reconnects = 0
        val r = CameraReclaim(backgroundScope, stableMs = 1_500) { reconnects++ }
        r.onLost()
        assertTrue(r.lost.value)
        advanceTimeBy(999); runCurrent(); assertEquals(0, reconnects)
        advanceTimeBy(2); assertEquals(1, reconnects)
        r.onLost()                       // the reconnect was evicted again: next step is 2 s
        advanceTimeBy(1_999); runCurrent(); assertEquals(1, reconnects)
        advanceTimeBy(2); assertEquals(2, reconnects)
        r.onRunning()
        advanceTimeBy(1_000); assertTrue(r.lost.value)   // not yet stable
        advanceTimeBy(600); assertFalse(r.lost.value)
        assertEquals(2, reconnects)
    }

    @Test fun availabilityAndTapReconnectSoon() = runTest {
        var reconnects = 0
        val r = CameraReclaim(backgroundScope) { reconnects++ }
        r.onAvailable(); r.retryNow(); advanceTimeBy(5_000)
        assertEquals(0, reconnects)      // nothing lost: nothing to do
        r.onLost()
        r.onAvailable(); advanceTimeBy(CameraReclaim.AVAILABLE_DELAY_MS + 1)
        assertEquals(1, reconnects)
        r.onLost(); r.retryNow(); runCurrent()
        assertEquals(2, reconnects)
    }

    @Test fun noReconnectWhileRunningOrAfterReset() = runTest {
        var reconnects = 0
        val r = CameraReclaim(backgroundScope) { reconnects++ }
        r.onLost(); r.onRunning()
        r.onAvailable(); r.retryNow(); advanceTimeBy(500)
        assertEquals(0, reconnects)
        r.onLost(); r.reset(); advanceTimeBy(60_000)
        assertEquals(0, reconnects)
        assertFalse(r.lost.value)
    }
}
