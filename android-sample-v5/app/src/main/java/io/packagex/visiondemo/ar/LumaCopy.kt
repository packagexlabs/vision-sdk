package io.packagex.visiondemo.ar

import io.packagex.arcount.LumaImage
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Stream pixels per pixel of the luma copy (spec 5.9): 3840x2160 -> 960x540 */
internal const val LUMA_SCALE = 4

/**
 * The 4x4 box average of the [width] x [height] luma plane in [src] ([rowStride] bytes a row): a
 * (width / 4) x (height / 4) image whose every pixel is the rounded mean of its 16. Columns and rows past the last
 * whole block are left out.
 */
fun downscaleLuma4(src: ByteArray, width: Int, height: Int, rowStride: Int): LumaImage {
    val ow = width / LUMA_SCALE
    val oh = height / LUMA_SCALE
    require(ow > 0 && oh > 0) { "a ${width}x$height plane has no 4x4 block" }
    require(rowStride >= width && src.size >= (height - 1).toLong() * rowStride + width) { "too few bytes for ${width}x$height, row stride $rowStride" }
    val out = ByteArray(ow * oh)
    val sums = IntArray(ow)
    for (oy in 0 until oh) {
        sums.fill(0)
        for (r in 0 until LUMA_SCALE) {
            var i = (oy * LUMA_SCALE + r) * rowStride
            for (ox in 0 until ow) {
                sums[ox] += (src[i].toInt() and 0xFF) + (src[i + 1].toInt() and 0xFF) + (src[i + 2].toInt() and 0xFF) + (src[i + 3].toInt() and 0xFF)
                i += LUMA_SCALE
            }
        }
        val base = oy * ow
        for (ox in 0 until ow) out[base + ox] = ((sums[ox] + 8) shr 4).toByte()
    }
    return LumaImage(ow, oh, out)
}

/**
 * The luma copy of spec 5.8 step 1 for the patch tracker (5.9). [offer] (camera thread) copies an app-stream image's
 * Y plane into a buffer kept for it, one bulk copy, so the image goes on to the engine at once and is never held for
 * the copy; the 4x4 downscale runs on a thread of its own, one copy in work and at most one waiting, latest wins (the
 * one replaced is counted in [dropped]). Each copy goes to [post] with its image's timestamp, in order.
 *
 * [afterLuma] (engine worker) runs what it is given at once, unless the copy of that timestamp still waits or is in
 * work: then right after that copy is posted (or dropped). So the counter gets a frame's luma before its reads.
 *
 * Two buffers of the plane's size at most (one in work, one waiting or being filled).
 */
class LumaCopier internal constructor(
    private val post: (timestampNs: Long, img: LumaImage) -> Unit,
    private val downscale: (src: ByteArray, width: Int, height: Int, rowStride: Int) -> LumaImage,
) {
    constructor(post: (timestampNs: Long, img: LumaImage) -> Unit) : this(post, ::downscaleLuma4)

    private class Slot(var bytes: ByteArray) {
        var timestampNs = 0L
        var width = 0
        var height = 0
        var rowStride = 0
    }

    private val lock = Any()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ArLuma") }
    private var working: Slot? = null
    private var pending: Slot? = null
    private var spare: Slot? = null
    private var closed = false
    private val held = ArrayList<Pair<Long, () -> Unit>>()

    /** Copies offered */
    @Volatile var frames = 0L
        private set

    /** Of them, replaced in the waiting slot by a newer one before the downscale took them */
    @Volatile var dropped = 0L
        private set

    /** Time spent copying the Y plane on the camera thread, all copies together */
    @Volatile var copyNs = 0L
        private set

    /** Time spent downscaling on the luma thread, all copies together */
    @Volatile var scaleNs = 0L
        private set

    /**
     * Camera thread: the [width] x [height] Y plane [y] ([rowStride] bytes a row, pixel stride 1) of the image taken at
     * [timestampNs], copied before this returns; [y] is not held.
     */
    fun offer(timestampNs: Long, y: ByteBuffer, width: Int, height: Int, rowStride: Int) {
        val started = System.nanoTime()
        val n = ((height - 1).toLong() * rowStride + width).toInt()
        if (width < LUMA_SCALE || height < LUMA_SCALE || rowStride < width || y.capacity() < n) return // not a plane of this size
        val slot = synchronized(lock) {
            if (closed) return
            spare?.also { spare = null } ?: pending?.also { replaced(it) } ?: Slot(ByteArray(n))
        }
        if (slot.bytes.size < n) slot.bytes = ByteArray(n)
        val src = y.duplicate()
        src.clear()
        src.get(slot.bytes, 0, n)
        slot.timestampNs = timestampNs
        slot.width = width
        slot.height = height
        slot.rowStride = rowStride
        copyNs += System.nanoTime() - started
        frames++
        synchronized(lock) {
            if (closed) return
            if (working == null) {
                working = slot
                submit(slot)
            } else {
                pending = slot
            }
        }
    }

    /** Engine worker: [action] now, or right after the copy of [timestampNs] is posted if it waits or is in work. */
    fun afterLuma(timestampNs: Long, action: () -> Unit) {
        synchronized(lock) {
            if (working?.timestampNs == timestampNs || pending?.timestampNs == timestampNs) {
                held += timestampNs to action
                return
            }
        }
        action()
    }

    /** Lets the thread go; a copy in work is not posted. What waits for a copy runs now. */
    fun close() {
        val waiting = synchronized(lock) {
            closed = true
            pending = null
            held.map { it.second }.also { held.clear() }
        }
        worker.shutdown()
        waiting.forEach { it() }
    }

    // Under the lock: [slot] leaves the waiting slot unread
    private fun replaced(slot: Slot) {
        pending = null
        dropped++
        runHeld(slot.timestampNs)
    }

    // Under the lock
    private fun runHeld(timestampNs: Long) {
        if (held.isEmpty()) return
        val it = held.iterator()
        while (it.hasNext()) {
            val (ts, action) = it.next()
            if (ts == timestampNs) {
                it.remove()
                action()
            }
        }
    }

    // Under the lock
    private fun submit(first: Slot) {
        try {
            worker.execute { drain(first) }
        } catch (e: RejectedExecutionException) { // closed meanwhile
            working = null
        }
    }

    /** Luma thread: [first], then whatever waits, until nothing does. */
    private fun drain(first: Slot) {
        var slot = first
        while (true) {
            val started = System.nanoTime()
            val img = downscale(slot.bytes, slot.width, slot.height, slot.rowStride)
            scaleNs += System.nanoTime() - started
            synchronized(lock) {
                // Posted under the lock: a reads batch of this timestamp that comes now waits for it in afterLuma
                if (!closed) post(slot.timestampNs, img)
                runHeld(slot.timestampNs)
                spare = slot
                val next = pending
                pending = null
                working = next
                if (next == null || closed) {
                    working = null
                    return
                }
                slot = next
            }
        }
    }
}
