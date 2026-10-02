package io.packagex.texttemplates.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Continuous IMU rotation-rate monitor. The capture pipeline queries this on
 * each camera frame to decide whether the device has been steady enough during
 * the recent window for the frame to be worth processing.
 *
 * Signal: gyroscope rotation rate magnitude in rad/s, sampled at ~50 Hz. The
 * buffer holds a 250 ms sliding window; the query returns the maximum
 * magnitude over that window — so a brief jitter anywhere in the window fails
 * the gate, not just the instantaneous reading.
 *
 * Always-on while the scanner screen is presented (`start()` on appear,
 * `stop()` on disappear), independent of the camera/analyzer lifecycle. That
 * way the first camera frame already has a populated history.
 *
 * Two thresholds are exposed because the pipeline uses the IMU at two
 * different decision points:
 *  - `loose` (0.6 rad/s): per-frame quality gate; also lock release.
 *  - `strict` (0.3 rad/s): arms AE/AF/AWB lock when paired with streak ≥ 1.
 */
internal class IMUStabilityMonitor constructor(
    context: Context,
) : SensorEventListener {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val lock = Any()
    /** (timestampNs, magnitude) pairs in chronological order. */
    private val samples = ArrayDeque<Pair<Long, Double>>()
    @Volatile
    private var running: Boolean = false

    val isRunning: Boolean get() = running

    fun start() {
        if (running || gyroscope == null) return
        running = true
        // SENSOR_DELAY_GAME ≈ 20 ms (50 Hz), matching iOS.
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        synchronized(lock) { samples.clear() }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val mag = sqrt(x * x + y * y + z * z)
        // event.timestamp is in nanoseconds since boot.
        synchronized(lock) {
            samples.addLast(event.timestamp to mag)
            val cutoff = event.timestamp - WINDOW_DURATION_NS
            while (samples.isNotEmpty() && samples.first().first < cutoff) {
                samples.removeFirst()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Max rotation-rate magnitude over the most recent [WINDOW_DURATION_NS].
     * Returns 0 when no samples are available yet (treat as "still" so the
     * pipeline doesn't false-fail before IMU has warmed up).
     */
    fun maxMagnitudeInWindow(): Double {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val cutoff = nowNs - WINDOW_DURATION_NS
        var maxMag = 0.0
        synchronized(lock) {
            for (i in samples.indices.reversed()) {
                val s = samples[i]
                if (s.first < cutoff) break
                if (s.second > maxMag) maxMag = s.second
            }
        }
        return maxMag
    }

    /** True when the recent window is below the loose threshold. */
    fun passesLooseGate(): Boolean = maxMagnitudeInWindow() < LOOSE_THRESHOLD

    /** True when the recent window is below the strict threshold. */
    fun passesStrictGate(): Boolean = maxMagnitudeInWindow() < STRICT_THRESHOLD

    companion object {
        // Initial iOS calibration was 0.3/0.15, but max-over-window aggregation
        // catches every tremor spike and typical hand tremor lives in
        // 0.05-0.2 rad/s. Relaxed to 0.6/0.3 so the gate only fires on
        // deliberate motion, not steady-hand tremor.
        const val LOOSE_THRESHOLD: Double = 0.6
        const val STRICT_THRESHOLD: Double = 0.3
        const val WINDOW_DURATION_NS: Long = 250_000_000L // 250 ms
    }
}
