package io.packagex.arcount

/**
 * One downscaled luma copy of an app-stream frame (spec 5.8, step 1). The copy is a [scale] × [scale] box average,
 * so its pixel (i, j) is centred on stream pixel (scale · i + (scale − 1) / 2, ...).
 */
class LumaFrame(val timestampNs: Long, val img: LumaImage, val scale: Double) {
    fun toLuma(streamPx: Double) = (streamPx - (scale - 1) / 2) / scale

    fun toStream(lumaPx: Double) = lumaPx * scale + (scale - 1) / 2
}

/** The last [capacity] luma frames, by timestamp: a read's patch is captured from the frame of its own capture */
class LumaRing(private val capacity: Int = 8) {
    private val frames = ArrayDeque<LumaFrame>()

    /** Whether any luma frame has come this session: until one does, the tracker is not fed (spec 5.3) */
    var fed = false
        private set

    fun add(frame: LumaFrame) {
        fed = true
        if (frames.any { it.timestampNs == frame.timestampNs }) return
        val at = frames.indexOfFirst { it.timestampNs > frame.timestampNs }
        if (at < 0) frames.addLast(frame) else frames.add(at, frame)
        while (frames.size > capacity) frames.removeFirst()
    }

    /** The frame captured at [timestampNs]; null when it never came or is gone */
    fun at(timestampNs: Long): LumaFrame? = frames.lastOrNull { it.timestampNs == timestampNs }

    /** Whether the frame at [timestampNs] may still come: nothing at or after it has come yet */
    fun mayStillCome(timestampNs: Long) = frames.lastOrNull().let { it == null || it.timestampNs < timestampNs }
}
