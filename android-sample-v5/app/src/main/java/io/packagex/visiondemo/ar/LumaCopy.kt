package io.packagex.visiondemo.ar

import io.packagex.arcount.LumaImage
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Stream pixels per pixel of the luma copy (spec 5.9): 3840x2160 -> 960x540 */
internal const val LUMA_SCALE = 4

/**
 * The row of each 4-row block the camera thread copies: of rows 4·j .. 4·j+3 (box centre 4·j+1.5), row 4·j+2, so a
 * luma pixel's vertical centre is half a stream pixel below the box centre the core assumes ([LumaFrame]); the bias is
 * the same in the frame a patch is captured in and the frames it is tracked in.
 */
internal const val LUMA_ROW = 2

/**
 * The 4x1 average of [rows] luma rows of [width] in [src] ([rowStride] bytes a row), the rows [LumaCopier] kept, one in
 * four: a (width / 4) x [rows] image whose every pixel is the rounded mean of 4 neighbours in its row. Written into
 * [into] when it is that size; [onFree] gets the pixels back when the image is freed ([LumaImage.free]).
 */
fun downscaleRows4(src: ByteArray, width: Int, rows: Int, rowStride: Int, into: ByteArray? = null, onFree: ((ByteArray) -> Unit)? = null): LumaImage {
    val ow = width / LUMA_SCALE
    require(ow > 0 && rows > 0) { "$rows rows of $width have no 4x1 block" }
    require(rowStride >= width && src.size >= (rows - 1).toLong() * rowStride + width) { "too few bytes for $rows rows of $width, row stride $rowStride" }
    val out = into?.takeIf { it.size == ow * rows } ?: ByteArray(ow * rows)
    for (oy in 0 until rows) {
        var i = oy * rowStride
        val base = oy * ow
        for (ox in 0 until ow) {
            val sum = (src[i].toInt() and 0xFF) + (src[i + 1].toInt() and 0xFF) + (src[i + 2].toInt() and 0xFF) + (src[i + 3].toInt() and 0xFF)
            out[base + ox] = ((sum + 2) shr 2).toByte()
            i += LUMA_SCALE
        }
    }
    return LumaImage(ow, rows, out, ow, onFree)
}

/**
 * The luma copies' pixels: [downscale] writes into a freed copy's array ([LumaImage.free]) before it makes a new one.
 * 2026-10-08: a new 960x540 array a frame was 14 MB/s of large objects for the GC, a collection every second or two.
 */
internal class LumaPool {
    private val spare = ArrayList<ByteArray>()

    fun downscale(src: ByteArray, width: Int, rows: Int, rowStride: Int): LumaImage =
        downscaleRows4(src, width, rows, rowStride, synchronized(spare) { spare.removeLastOrNull() }) { synchronized(spare) { spare.add(it) } }
}

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
 * The luma copy of spec 5.8 step 1 for the patch tracker (5.9). [offer] (camera thread) copies one row in four of an
 * app-stream image's Y plane ([LUMA_ROW], a bulk copy per row: a quarter of the plane's bytes, which cost 6-8 ms whole on
 * the Memor) into a buffer kept for it, so the image goes on to the engine at once and is never held for the copy; the
 * 4x1 downscale ([downscaleRows4]) runs on a thread of its own, one copy in work and at most one waiting, latest wins (the
 * one replaced is counted in [dropped]). Each copy goes to [post] with its image's timestamp, in order.
 *
 * [afterLuma] (engine worker) runs what it is given at once, unless the copy of that timestamp still waits or is in
 * work: then right after that copy is posted (or dropped). So the counter gets a frame's luma before its reads.
 *
 * Two buffers of a quarter of the plane's size at most (one in work, one waiting or being filled); the copies' pixels
 * are reused once freed ([LumaPool]).
 */
class LumaCopier internal constructor(
    private val post: (timestampNs: Long, img: LumaImage) -> Unit,
    private val downscale: (src: ByteArray, width: Int, height: Int, rowStride: Int) -> LumaImage,
) {
    constructor(post: (timestampNs: Long, img: LumaImage) -> Unit) : this(post, LumaPool()::downscale)

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
     * [timestampNs]: its rows 4·j + [LUMA_ROW] copied before this returns; [y] is not held.
     */
    fun offer(timestampNs: Long, y: ByteBuffer, width: Int, height: Int, rowStride: Int) {
        val started = System.nanoTime()
        val n = ((height - 1).toLong() * rowStride + width).toInt()
        if (width < LUMA_SCALE || height < LUMA_SCALE || rowStride < width || y.capacity() < n) return // not a plane of this size
        val rows = height / LUMA_SCALE
        val kept = rows * width
        val slot = synchronized(lock) {
            if (closed) return
            spare?.also { spare = null } ?: pending?.also { replaced(it) } ?: Slot(ByteArray(kept))
        }
        if (slot.bytes.size < kept) slot.bytes = ByteArray(kept)
        val src = y.duplicate()
        src.clear()
        for (j in 0 until rows) {
            src.position((j * LUMA_SCALE + LUMA_ROW) * rowStride)
            src.get(slot.bytes, j * width, width)
        }
        slot.timestampNs = timestampNs
        slot.width = width
        slot.height = rows
        slot.rowStride = width
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
