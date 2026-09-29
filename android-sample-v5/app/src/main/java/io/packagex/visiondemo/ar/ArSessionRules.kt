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
