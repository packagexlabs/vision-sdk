package io.packagex.visiondemo.camera

import io.packagex.visionsdk.camera.core.CameraError
import io.packagex.visionsdk.camera.core.CameraStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The SDK camera was taken away from under the scanner: another client opened the sensor (e.g. a device's
 * system scanner service evicting our camera client), so the SDK session stopped (INTERRUPTED), or a rebind
 * failed while the camera was busy (ERROR, ConfigurationFailed). Permission and missing-lens errors are not
 * losses; they keep their own handling.
 */
internal fun isCameraLoss(status: CameraStatus, error: CameraError?): Boolean = when (status) {
    CameraStatus.INTERRUPTED -> true
    CameraStatus.ERROR -> error == null || error is CameraError.ConfigurationFailed
    else -> false
}

/**
 * Gets the scanner camera back after [isCameraLoss]: [lost] is true from the loss until the camera has run
 * again for [stableMs] (so a reconnect that is evicted again at once doesn't flicker the UI).
 *
 * Reconnect attempts are spaced by a backoff (1 s doubling up to 30 s) counted from the previous attempt; the
 * system reporting a camera free again ([onAvailable]) only brings the next attempt forward to that spacing (and
 * at least [AVAILABLE_DELAY_MS] out), and a tap ([retryNow]) runs it at once. No attempt starts while one is in
 * flight (the camera reported running, or [stillLost] false: the SDK is already rebinding). After
 * [GIVE_UP_ATTEMPTS] attempts that ended in a genuine failure (not a plain interruption) [onPersistentFailure]
 * runs once per loss, so the user hears about a camera that never comes back; retries go on.
 *
 * Takes its [CoroutineScope] (main thread in the app) and [now] so tests can drive it with virtual time.
 */
class CameraReclaim(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val stableMs: Long = 1_500L,
    /** The SDK still reports a loss: a reconnect is needed. */
    private val stillLost: () -> Boolean,
    private val onPersistentFailure: () -> Unit = {},
    private val reconnect: () -> Unit,
) {
    private val _lost = MutableStateFlow(false)
    val lost: StateFlow<Boolean> = _lost.asStateFlow()

    private var attempts = 0
    private var lastAttemptAt = Long.MIN_VALUE
    private var failureReported = false
    private var job: Job? = null
    /** Running again since the loss, waiting out [stableMs]: no reconnect needed. */
    private var running = false

    /** The camera was lost (again, if a reconnect didn't hold). [failed]: a failed rebind, not a plain interruption. */
    fun onLost(failed: Boolean = false) {
        _lost.value = true
        running = false
        if (failed && attempts >= GIVE_UP_ATTEMPTS && !failureReported) {
            failureReported = true
            onPersistentFailure()
        }
        scheduleAt(nextAllowed())
    }

    /** The SDK reports the camera running: recovered once it stays up for [stableMs]. */
    fun onRunning() {
        if (!_lost.value) return
        running = true
        job?.cancel()
        job = scope.launch {
            delay(stableMs)
            clear()
        }
    }

    /** The system reports the scanner's camera free again (CameraManager availability): reconnect soon. */
    fun onAvailable() {
        if (!_lost.value || running) return
        scheduleAt(maxOf(now() + AVAILABLE_DELAY_MS, nextAllowed()))
    }

    /** The user tapped to resume. */
    fun retryNow() { if (_lost.value && !running) scheduleAt(now()) }

    /** The camera was released on purpose (paused, handed to another owner): nothing to get back. */
    fun reset() {
        job?.cancel()
        clear()
    }

    private fun clear() {
        running = false
        attempts = 0
        lastAttemptAt = Long.MIN_VALUE
        failureReported = false
        _lost.value = false
    }

    private fun nextAllowed(): Long =
        if (lastAttemptAt == Long.MIN_VALUE) now() + backoffMs(0) else maxOf(now(), lastAttemptAt + backoffMs(attempts))

    /** Replaces any pending attempt with one at [at]. */
    private fun scheduleAt(at: Long) {
        job?.cancel()
        job = scope.launch {
            delay((at - now()).coerceAtLeast(0))
            if (running || !stillLost()) return@launch   // recovered, or a rebind is already under way
            attempts++
            lastAttemptAt = now()
            reconnect()
        }
    }

    internal companion object {
        const val AVAILABLE_DELAY_MS = 300L
        const val GIVE_UP_ATTEMPTS = 5
        /** Spacing after [attempts] attempts: 1 s, 2 s, 4 s ... capped at 30 s. */
        fun backoffMs(attempts: Int): Long = minOf(30_000L, 1_000L shl minOf(attempts, 5))
    }
}
