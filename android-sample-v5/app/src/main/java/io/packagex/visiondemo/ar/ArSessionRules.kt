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
 * motion). It is reset on every session start: reusing a warmed-up gate after a pause or a new session
 * placed markers before tracking had settled (iOS fix d9fb1d1). Read and written on the GL thread only.
 */
class WarmUpGate(private val minFrames: Int) {
    private var frames = 0

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
