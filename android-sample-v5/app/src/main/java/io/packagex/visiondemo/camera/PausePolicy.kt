package io.packagex.visiondemo.camera

import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** True once a thermal reading at or above [PowerManager.THERMAL_STATUS_CRITICAL] is seen. */
    private var critical = false

    /** Resets the idle timer. Skipped while paused -- nothing to keep alive. */
    fun userActive() {
        idleJob?.cancel()
        if (_paused.value) return
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            _paused.value = true
        }
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
