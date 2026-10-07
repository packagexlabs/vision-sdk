package io.packagex.visiondemo.ar

import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord

/*
 * Drift plan P2c, the read-rate boost: the counter's schedule re-reads a shown code every 300 ms while the camera moves
 * and up to every second once it is still (RefreshSchedule), which leaves a new pin few rays to verify its depth from.
 * While a pin needs them, the engine reads at full rate (refresh 0) instead, at most [PIN_BOOST_NS] per pin. Pure, for
 * the JVM tests: ArPins feeds it each tracked frame and publishes [ReadBoost.on] to the engine worker.
 */

/** P2c: a pin keeps the boost on for at most this much frame time in all */
const val PIN_BOOST_NS = 2_000_000_000L

/** P2c: a listed read that went unclaimed keeps the boost on for this long after its capture */
const val PIN_BOOST_UNCLAIMED_NS = 1_000_000_000L

/** A frame counts at most this long toward a pin's boost (a pause between frames is no boost) */
private const val MAX_FRAME_NS = 100_000_000L

/**
 * P2c on the GL thread: [on] while a listed pin not yet [Pin.verified] lies [wellInside] the newest frame (at least 10%
 * inside the image, in front within 1.5 m), each such pin for at most [PIN_BOOST_NS] of frames ([Pin.boostNs]); or
 * while a listed read in view went unclaimed (to the candidates, a voided one too) less than [PIN_BOOST_UNCLAIMED_NS]
 * before that frame. [frames], [forPins] and [forReads] count the frames and why they boosted, for the 3 s line.
 */
class ReadBoost {
    @Volatile
    var on = false
        private set

    var frames = 0
        private set
    var forPins = 0
        private set
    var forReads = 0
        private set

    private var lastFrameNs = Long.MIN_VALUE
    private var unclaimedNs = Long.MIN_VALUE

    /** A listed read in view, captured at [captureNs], was not claimed */
    fun unclaimed(captureNs: Long) {
        if (captureNs > unclaimedNs) unclaimedNs = captureNs
    }

    /**
     * A tracked frame [rec], with the [pins] and the listed code [keys]; [enabled]: the Android rules, refining their
     * pins, with the boost switched on. Whether the engine should read at full rate now. [toCamera] is the inverse of
     * [rec]'s camera, for a caller that has it (every frame: one inverse, not one per pin).
     */
    fun frame(rec: PoseRecord, pins: List<Pin>, keys: Set<String>, enabled: Boolean, toCamera: Pose = rec.camera.inverse()): Boolean {
        val ts = rec.timestampNs
        val dt = if (lastFrameNs == Long.MIN_VALUE) 0L else (ts - lastFrameNs).coerceIn(0L, MAX_FRAME_NS)
        lastFrameNs = ts
        frames++
        if (!enabled) {
            on = false
            return false
        }
        var forPin = false
        for (i in pins.indices) {
            val p = pins[i]
            if (p.verified || p.boostNs >= PIN_BOOST_NS || p.code !in keys) continue
            if (!wellInside(p.position, rec.camera, rec.intrinsics, toCamera)) continue
            p.boostNs += dt
            forPin = true
        }
        val forRead = unclaimedNs != Long.MIN_VALUE && ts - unclaimedNs < PIN_BOOST_UNCLAIMED_NS
        if (forPin) forPins++
        if (forRead) forReads++
        on = forPin || forRead
        return on
    }

    /** A frame not tracked: the pins are hidden, and no boost helps them */
    fun paused() {
        on = false
    }

    /** New Scan: the reads before it no longer count */
    fun clear() {
        unclaimedNs = Long.MIN_VALUE
        on = false
    }

    /** The 3 s line took the counts */
    fun resetCounts() {
        frames = 0
        forPins = 0
        forReads = 0
    }
}
