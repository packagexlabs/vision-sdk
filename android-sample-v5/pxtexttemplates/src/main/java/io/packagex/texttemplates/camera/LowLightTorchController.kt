package io.packagex.texttemplates.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraControl

/**
 * Auto-torch driver fed by per-frame mean luma of the Y plane (computed in
 * [FrameAnalyzer], where the plane bytes are already in hand — the sample
 * costs nothing extra). Low ambient light degrades both OCR and barcode
 * decoding more than any gate can recover, so below [LUMA_ON] the torch is
 * enabled through the bound [CameraControl].
 *
 * Oscillation guards — the torch itself raises scene luma, so a symmetric
 * threshold would toggle forever:
 *  - Asymmetric thresholds: ON below [LUMA_ON], OFF only above [LUMA_OFF].
 *    The gap is sized so typical torch gain on a near-field label lands
 *    between the two.
 *  - Minimum dwell [MIN_TOGGLE_INTERVAL_MS] between toggles.
 *  - EMA smoothing so a single dark/bright frame (hand shadow, glare) can't
 *    flip the state.
 */
internal class LowLightTorchController constructor() {

    companion object {
        private const val TAG = "[LowLightTorch]"

        /** Master switch for auto-torch. Disabled: low-light frames are still
         *  sampled (harmless), but the torch is never auto-engaged. Flip to
         *  true to restore the luma-driven behavior below. */
        private const val ENABLED = false

        /** Smoothed mean luma (0-255) below which the torch turns on. */
        private const val LUMA_ON = 60.0

        /** Smoothed mean luma above which the torch turns back off. Must sit
         *  well above [LUMA_ON] + typical torch gain so enabling the torch
         *  doesn't immediately disqualify itself. */
        private const val LUMA_OFF = 170.0

        /** EMA weight of the newest sample. */
        private const val EMA_ALPHA = 0.3

        private const val MIN_TOGGLE_INTERVAL_MS = 3_000L
    }

    private var cameraControl: CameraControl? = null
    private var ema: Double? = null
    private var torchOn = false
    private var lastToggleAt = 0L

    /** Bind the active camera. Called from CameraPreview after
     *  `bindToLifecycle`; cleared in [detach] on dispose. */
    @Synchronized
    fun attach(control: CameraControl) {
        cameraControl = control
        // Fresh session: CameraX unbind turned any previous torch off.
        ema = null
        torchOn = false
        lastToggleAt = 0L
    }

    @Synchronized
    fun detach() {
        cameraControl = null
        ema = null
        torchOn = false
    }

    /** Feed one frame's mean Y-plane luma (0-255). */
    @Synchronized
    fun onLumaSample(meanLuma: Double) {
        if (!ENABLED) return
        val control = cameraControl ?: return
        val smoothed = ema?.let { it + EMA_ALPHA * (meanLuma - it) } ?: meanLuma
        ema = smoothed

        val now = SystemClock.elapsedRealtime()
        if (now - lastToggleAt < MIN_TOGGLE_INTERVAL_MS) return

        val wantOn = if (torchOn) smoothed < LUMA_OFF else smoothed < LUMA_ON
        if (wantOn == torchOn) return

        torchOn = wantOn
        lastToggleAt = now
        Log.d(TAG, "torch ${if (wantOn) "ON" else "OFF"} (ema luma %.0f)".format(smoothed))
        control.enableTorch(wantOn)
    }
}
