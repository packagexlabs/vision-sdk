package io.packagex.arcount

import kotlin.math.abs
import kotlin.math.floor

/** The row's pitch (spec 5.4): metric on the section plane, and in pixels per unit */
object Pitch {
    /**
     * The metric pitch from the COUNTED units' positions along the shelf axis: [CountConfig.defaultPitch] until
     * [CountConfig.pitchMinCounted] exist, then the [CountConfig.pitchPercentile] percentile of their
     * nearest-neighbour distances, clamped to [CountConfig.minPitch]..[CountConfig.maxPitch].
     */
    fun metric(countedAlong: List<Double>, config: CountConfig): Double {
        if (countedAlong.size < config.pitchMinCounted) return config.defaultPitch
        val nearest = countedAlong.mapIndexed { i, a ->
            countedAlong.indices.filter { it != i }.minOf { abs(countedAlong[it] - a) }
        }.sorted()
        val pos = config.pitchPercentile * (nearest.size - 1)
        val lo = floor(pos).toInt()
        val hi = minOf(lo + 1, nearest.size - 1)
        val p = nearest[lo] + (nearest[hi] - nearest[lo]) * (pos - lo)
        return p.coerceIn(config.minPitch, config.maxPitch)
    }

    /** pitch_px = f · p / z_cam */
    fun px(f: Double, pitch: Double, z: Double) = f * pitch / z
}
