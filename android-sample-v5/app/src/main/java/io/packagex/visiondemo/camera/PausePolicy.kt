package io.packagex.visiondemo.camera

import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * When the camera should be paused: idle timeout, thermal throttling, and app-lifecycle
 * background/foreground. Takes its [CoroutineScope] (rather than owning one) so tests can drive
 * it with virtual time via `runTest`'s `backgroundScope`.
 */
class PausePolicy(
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long = 90_000L,
) {
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private var idleJob: Job? = null

    /** Whether the latest [thermal] reading was at or above [PowerManager.THERMAL_STATUS_CRITICAL]. Tracks
     *  the current status, not a one-way latch: it clears again as soon as a cooler reading arrives. */
    private var critical = false

    /** A capture or extraction is in flight: the idle timeout waits for it (iOS `phase == .idle` guard). */
    private var busy = false

    /** Resets the idle timer. Skipped while paused -- nothing to keep alive -- or [busy]. */
    fun userActive() {
        idleJob?.cancel()
        if (_paused.value || busy) return
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            ensureActive()   // don't act on a stale delay that raced a cancel() (e.g. from a resume() in between)
            _paused.value = true
        }
    }

    /** Busy stops the idle timer; going idle again restarts it (heat and background still pause). */
    fun setBusy(busy: Boolean) {
        if (busy == this.busy) return
        this.busy = busy
        userActive()
    }

    /** [status] is a `PowerManager.THERMAL_STATUS_*` value. */
    fun thermal(status: Int) {
        critical = status >= PowerManager.THERMAL_STATUS_CRITICAL
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            idleJob?.cancel()
            _paused.value = true
        }
    }

    /** `false` (background/backgrounding) always pauses; `true` always resumes, even from an idle pause. */
    fun lifecycle(foreground: Boolean) {
        if (foreground) {
            resume()
        } else {
            idleJob?.cancel()
            _paused.value = true
        }
    }

    /** Returns false (and stays paused) while the device is critically hot. */
    fun resume(): Boolean {
        if (critical) return false
        _paused.value = false
        userActive()
        return true
    }
}
