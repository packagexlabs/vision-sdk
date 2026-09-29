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
 * again for [stableMs] (so a reconnect that is evicted again at once doesn't flicker the UI), and [reconnect]
 * is retried meanwhile -- soon after the system reports a camera free again ([onAvailable]), otherwise with
 * backoff (1 s doubling up to 30 s), or at once on [retryNow]. Takes its [CoroutineScope] (main thread in the
 * app) so tests can drive it with virtual time, as [PausePolicy].
 */
class CameraReclaim(
    private val scope: CoroutineScope,
    private val stableMs: Long = 1_500L,
    private val reconnect: () -> Unit,
) {
    private val _lost = MutableStateFlow(false)
    val lost: StateFlow<Boolean> = _lost.asStateFlow()

    private var attempts = 0
    private var job: Job? = null
    /** Running again since the loss, waiting out [stableMs]: no reconnect needed. */
    private var running = false

    /** The camera was lost (again, if a reconnect didn't hold): retry with the next backoff step. */
    fun onLost() {
        _lost.value = true
        running = false
        schedule(backoffMs(attempts))
    }

    /** The SDK reports the camera running: recovered once it stays up for [stableMs]. */
    fun onRunning() {
        if (!_lost.value) return
        running = true
        job?.cancel()
        job = scope.launch {
            delay(stableMs)
            attempts = 0
            _lost.value = false
        }
    }

    /** The system reports a camera free again (CameraManager availability): reconnect shortly. */
    fun onAvailable() { if (_lost.value && !running) schedule(AVAILABLE_DELAY_MS) }

    /** The user tapped to resume. */
    fun retryNow() { if (_lost.value && !running) schedule(0) }

    /** The camera was released on purpose (paused, handed to another owner): nothing to get back. */
    fun reset() {
        job?.cancel()
        running = false
        attempts = 0
        _lost.value = false
    }

    private fun schedule(delayMs: Long) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            attempts++
            reconnect()
        }
    }

    internal companion object {
        const val AVAILABLE_DELAY_MS = 300L
        fun backoffMs(attempts: Int): Long = minOf(30_000L, 1_000L shl minOf(attempts, 5))
    }
}
