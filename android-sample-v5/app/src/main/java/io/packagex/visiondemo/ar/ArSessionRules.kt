package io.packagex.visiondemo.ar

/** One barcode payload and how many physical instances of it are marked. */
data class PayloadCount(
    val payload: String,
    val format: String,
    val count: Int,
)

/** An AR result row (iOS `ScanResult.ar` rows), with the catalog name the code had when the result was shown. */
data class ArRow(val value: String, val symbology: String, val count: Int, val name: String?)

internal fun arRows(counts: List<PayloadCount>, names: Map<String, String>): List<ArRow> =
    counts.map { ArRow(it.payload, it.format, it.count, names[it.payload]) }

/**
 * Marker placement waits for [minFrames] tracked frames (VIO scale converges over the first 1-2 s of
 * motion). A new session needs the warm-up again (iOS fix d9fb1d1, `runSession`'s resetTracking);
 * pausing and resuming the same session keeps it (iOS resumes without reset options). Read and written
 * on the GL thread only.
 */
class WarmUpGate(private val minFrames: Int) {
    /** Tracked frames seen so far, capped at [minFrames]. */
    var frames = 0
        private set

    val ready: Boolean get() = frames >= minFrames

    fun onTrackedFrame() { if (!ready) frames++ }

    fun onSessionStart() { frames = 0 }
}

/** SKU -> name, set on the main thread and read on the GL thread. Readers get an immutable snapshot. */
class CatalogSnapshot {
    @Volatile private var map: Map<String, String> = emptyMap()

    fun get(): Map<String, String> = map

    fun set(names: Map<String, String>) { map = names.toMap() }
}

/** iOS `displayText`: labels longer than 24 characters show the first 21 and an ellipsis. */
internal fun markerText(label: String): String = if (label.length > 24) label.take(21) + "…" else label

/**
 * Index of the camera config to use, from each config's CPU image size (the image the decoder reads),
 * already filtered to 30 fps. For heat: the smallest image at least [MIN_WIDTH] px wide, as close to
 * iOS v5's "at most 1920 px, 30 fps" as ARCore's list allows; if none is that wide, the largest one.
 */
internal fun pickCameraConfig(sizes: List<Pair<Int, Int>>): Int? {
    if (sizes.isEmpty()) return null
    val area = { i: Int -> sizes[i].first.toLong() * sizes[i].second }
    val wide = sizes.indices.filter { sizes[it].first >= MIN_WIDTH }
    return wide.minByOrNull(area) ?: sizes.indices.maxBy(area)
}

private const val MIN_WIDTH = 1280

// --- AR marker presentation: no animation or smoothing --------------------
//
// Markers are drawn exactly where the latest data places them: no easing,
// fades, pops, gliding, position/depth smoothing (median/EMA/Kalman
// averaging of hits), snap-back/hysteresis thresholds, or held screen
// positions redrawn between reads. Flip to true only to A/B against the old
// smoothed/animated behaviour (birth-position median of hits, screen
// dead-band hold, birth pulse ring).
internal const val AR_MARKER_SMOOTHING = false

/**
 * A candidate barcode's birth position: the raw [latest] agreeing hit when
 * smoothing is off, the median of all agreeing hits ([consensus]) when on.
 */
internal fun birthPosition(
    smoothing: Boolean,
    consensus: FloatArray,
    latest: FloatArray,
): FloatArray = if (smoothing) consensus else latest

/**
 * Whether a freshly projected screen position should be replaced by the
 * previously drawn one instead (the screen dead-band / snap-back). With
 * smoothing off this is always false: every frame draws the fresh
 * projection, never a held one.
 */
internal fun holdScreenPosition(
    smoothing: Boolean,
    cameraNearStill: Boolean,
    hasScreen: Boolean,
    dxPx: Float,
    dyPx: Float,
    deadbandPx: Float,
): Boolean =
    smoothing && cameraNearStill && hasScreen &&
        kotlin.math.abs(dxPx) < deadbandPx && kotlin.math.abs(dyPx) < deadbandPx

/**
 * Birth-pulse ring radius (or -1f when not pulsing) and alpha for a marker
 * of [ageMs]. With smoothing off, always the non-pulsing (-1f, 0f) pair — no
 * grow/fade animation on marker birth.
 */
internal fun pulseState(
    smoothing: Boolean,
    ageMs: Long,
    pulseMs: Long,
    ringR: Float,
    growth: Float,
): Pair<Float, Float> {
    val pulsing = smoothing && ageMs in 0 until pulseMs
    val t = ageMs / pulseMs.toFloat()
    val pulseR = if (pulsing) ringR + growth * t else -1f
    val pulseAlpha = if (pulsing) 1f - t else 0f
    return pulseR to pulseAlpha
}

/**
 * Whether two same-payload marker positions are the same physical marker (a
 * birth-race duplicate, e.g. two candidate clusters maturing the same frame)
 * rather than a different physical copy of the same barcode text — which
 * must get, and keep, its own marker rather than replacing this one.
 */
internal fun sameMarkerPosition(
    a: FloatArray,
    b: FloatArray,
    radiusM: Float,
): Boolean {
    val dx = a[0] - b[0]
    val dy = a[1] - b[1]
    val dz = a[2] - b[2]
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) <= radiusM
}
