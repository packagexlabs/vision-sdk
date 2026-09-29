package io.packagex.visiondemo.camera

import io.packagex.visionsdk.camera.core.CameraError
import io.packagex.visionsdk.camera.core.CameraStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraReclaimTest {
    /** The SDK's loss state: a reconnect (rescan) moves it to STARTING, i.e. not lost. */
    private var sdkLost = true
    private var sdkStarting = false
    private val attemptsAt = mutableListOf<Long>()
    private var failures = 0

    private fun TestScope.reclaim(scope: CoroutineScope = backgroundScope) = CameraReclaim(
        scope = scope,
        now = { testScheduler.currentTime },
        stableMs = 1_500,
        startTimeoutMs = 10_000,
        stillLost = { sdkLost },
        starting = { sdkStarting },
        onPersistentFailure = { failures++ },
    ) { attemptsAt += testScheduler.currentTime; sdkLost = false }

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

    @Test fun retriesWithBackoffUntilItRunsStably() = runTest {
        val r = reclaim()
        r.onLost()
        assertTrue(r.lost.value)
        advanceTimeBy(1_001); assertEquals(listOf(1_000L), attemptsAt)
        sdkLost = true; r.onLost()                   // the reconnect was evicted again: next one 2 s after the last
        advanceTimeBy(1_998); runCurrent(); assertEquals(1, attemptsAt.size)
        advanceTimeBy(2); assertEquals(listOf(1_000L, 3_000L), attemptsAt)
        r.onRunning()
        advanceTimeBy(1_000); assertTrue(r.lost.value)   // not yet stable
        advanceTimeBy(600); assertFalse(r.lost.value)
    }

    /** Availability used to reschedule a 300 ms rebind on every callback, tearing down the one in flight. */
    @Test fun availabilityRespectsBackoffAndNeverInterruptsARebind() = runTest {
        val r = reclaim()
        r.onLost()
        advanceTimeBy(1_001); assertEquals(listOf(1_000L), attemptsAt)   // rebinding: the SDK no longer reports a loss
        repeat(5) { r.onAvailable(); advanceTimeBy(100) }
        assertEquals(1, attemptsAt.size)                          // no second rescan while the first is under way
        sdkLost = true; r.onLost()                                // that rebind was evicted too (at 1.5 s)
        r.onAvailable()                                           // free again at once: still waits for 2 s after the last attempt
        advanceTimeBy(1_400); runCurrent(); assertEquals(1, attemptsAt.size)
        advanceTimeBy(200); assertEquals(listOf(1_000L, 3_000L), attemptsAt)
    }

    @Test fun availabilityBringsTheAttemptForwardButNotInsideTheSpacing() = runTest {
        val r = reclaim()
        r.onLost(); advanceTimeBy(1_001)                          // attempt 1 at 1 s
        sdkLost = true; r.onLost()                                // next allowed at 3 s
        advanceTimeBy(1_500)                                      // 2.5 s
        r.onAvailable()                                           // 2.8 s would be sooner, but the spacing wins: 3 s
        advanceTimeBy(400); runCurrent(); assertEquals(1, attemptsAt.size)
        advanceTimeBy(200); assertEquals(listOf(1_000L, 3_000L), attemptsAt)
        sdkLost = true; r.onLost()                                // next allowed at 7 s (4 s spacing)
        advanceTimeBy(5_000)                                      // the backoff alone brings it at 7 s
        assertEquals(listOf(1_000L, 3_000L, 7_000L), attemptsAt)
    }

    @Test fun tapRetriesAtOnce() = runTest {
        val r = reclaim()
        r.onLost(); r.retryNow(); runCurrent()
        assertEquals(listOf(0L), attemptsAt)
    }

    @Test fun nothingWhenNotLostRunningOrReset() = runTest {
        val r = reclaim()
        r.onAvailable(); r.retryNow(); advanceTimeBy(5_000)
        assertTrue(attemptsAt.isEmpty())
        r.onLost(); r.onRunning(); r.onAvailable(); r.retryNow(); advanceTimeBy(500)
        assertTrue(attemptsAt.isEmpty())
        r.onLost(); r.reset(); advanceTimeBy(60_000)
        assertTrue(attemptsAt.isEmpty())
        assertFalse(r.lost.value)
    }

    @Test fun persistentFailureReportedOnceAfterFiveFailedAttempts() = runTest {
        val r = reclaim()
        repeat(8) { sdkLost = true; r.onLost(failed = true); advanceTimeBy(40_000) }
        assertEquals(1, failures)
        assertTrue(attemptsAt.size >= 8)                          // keeps trying
        r.reset(); sdkLost = true
        repeat(5) { sdkLost = true; r.onLost(failed = false); advanceTimeBy(40_000) }
        sdkLost = true; r.onLost(failed = false)
        assertEquals(1, failures)                                 // plain interruptions never alert
    }

    @Test fun tapReconnectsEvenWhenTheSdkNoLongerReportsTheLossButNotWhileStarting() = runTest {
        val r = reclaim()
        r.onLost(); sdkLost = false                   // e.g. the SDK went IDLE: scheduled attempts would skip
        advanceTimeBy(5_000); assertTrue(attemptsAt.isEmpty())
        r.retryNow(); runCurrent()
        assertEquals(1, attemptsAt.size)
        sdkStarting = true
        r.retryNow(); runCurrent()
        assertEquals(1, attemptsAt.size)              // a rebind is starting: the tap doesn't tear it down
    }

    @Test fun aStartStuckFor10sCountsAsAFailedAttemptAndIsReplaced() = runTest {
        val r = reclaim()
        r.onLost(); advanceTimeBy(1_001)
        assertEquals(1, attemptsAt.size)
        sdkStarting = true                            // the rescan never gets past STARTING
        advanceTimeBy(10_000)                         // watchdog: a failed attempt, next one 2 s after the last
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, attemptsAt.size)              // replaces the stuck start although the SDK still says STARTING
    }

    @Test fun onlyFailedAttemptsCountTowardsTheAlert() = runTest {
        val r = reclaim()
        repeat(10) { sdkLost = true; r.onLost(failed = false); advanceTimeBy(40_000) }   // interruptions only
        assertEquals(0, failures)
        repeat(4) { sdkLost = true; r.onLost(failed = true); advanceTimeBy(40_000) }
        assertEquals(0, failures)                     // 4 failed despite 14 attempts
        sdkLost = true; r.onLost(failed = true)
        assertEquals(1, failures)
    }

    @Test fun stuckStartsCountAsFailures() = runTest {
        val r = reclaim()
        r.onLost(); advanceTimeBy(1_001)
        sdkStarting = true                            // every rescan from now on hangs in STARTING
        advanceTimeBy(300_000)
        assertTrue(attemptsAt.size >= 6)              // each stuck start was replaced
        assertEquals(1, failures)                     // and after five of them the alert showed once
    }
}
