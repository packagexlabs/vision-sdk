package io.packagex.visiondemo.document

import kotlin.math.hypot

/**
 * When a live quad should auto-capture, from the original DocumentCaptureActivity: every corner within
 * [stillFraction] of the frame diagonal of where it was when the hold started, for [stillFrames] frames.
 * After a capture the page must leave the frame ([rearmAfterInvalid] frames without one) before the next
 * one arms, or detection must restart ([detectionOn]: Add page / Retake / closing the drawer).
 * Pure and synchronized: frames arrive on the analysis thread, [detectionOn] on the main thread.
 */
class AutoCaptureGate(
    private val stillFrames: Int = 12,
    private val stillFraction: Float = 0.01f,
    private val rearmAfterInvalid: Int = 5,
) {
    var armed = true
        @Synchronized get
        private set
    private var invalidFrames = 0
    private var held = 0
    private var ref: List<Pair<Float, Float>>? = null

    /** Detection (re)started: a fresh hold, armed. */
    @Synchronized fun detectionOn() {
        armed = true
        invalidFrames = 0
        held = 0
        ref = null
    }

    /** A capture produced a page: wait for the page to leave (or detection to restart). */
    @Synchronized fun captured() { armed = false }

    /**
     * One analysis frame. [inside]: no corner within the edge margin (the page isn't clipped).
     * Returns true when auto capture should fire now.
     */
    @Synchronized fun frame(
        valid: Boolean,
        inside: Boolean,
        corners: List<Pair<Float, Float>>,
        frameWidth: Int,
        frameHeight: Int,
        auto: Boolean,
        capturing: Boolean,
    ): Boolean {
        if (!valid) {
            if (++invalidFrames >= rearmAfterInvalid) armed = true
        } else {
            invalidFrames = 0
        }
        if (!auto || capturing || !valid || !inside || !armed) {
            held = 0
            ref = null
            return false
        }
        val r = ref
        val tol = stillFraction * hypot(frameWidth.toFloat(), frameHeight.toFloat())
        if (r == null || r.size != corners.size || corners.indices.any { hypot(corners[it].first - r[it].first, corners[it].second - r[it].second) > tol }) {
            ref = corners
            held = 0
            return false
        }
        return ++held >= stillFrames
    }
}
