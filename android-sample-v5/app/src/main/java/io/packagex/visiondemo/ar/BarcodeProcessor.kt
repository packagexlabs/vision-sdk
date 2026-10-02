package io.packagex.visiondemo.ar

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.util.Log
import com.example.barcodescanner.BarcodeScanner
import com.example.barcodescanner.ScannerSettings
import io.packagex.arcount.Read
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The engine's refreshAfterMs while an AR Count session runs, until the counter's first view says otherwise
 * ([BarcodeProcessor.desiredRefresh], spec 5.3 schedule): 0 re-reads every shown barcode in every frame, as far as
 * the frame's decode budget goes. Spec 5.3 says 100 while counting, but at 30 fps 100 re-reads only one shown barcode
 * per frame (engine investigation, 2026-10-02). One constant, for the thermal fallback (spec 6) to raise later.
 */
internal const val AR_REFRESH_AFTER_MS = 0L

/** Runs [task] on single-thread [worker] after every task queued so far; on the caller's thread when it is shut down. */
internal fun runBehind(worker: ExecutorService, task: () -> Unit) {
    try {
        worker.execute(task)
    } catch (e: RejectedExecutionException) {
        task()
    }
}

/**
 * The engine worker (spec 5.8), the spike's: images of the app stream go to BarcodeScannerApp's engine
 * ([BarcodeScanner.scanAll] of the planes, the whole frame, turned upright by the camera's SENSOR_ORIENTATION) on one
 * worker thread, one at a time. An image that comes while one is decoded waits for the engine, the newest one only
 * ([LatestWins]), so a decode a little longer than a frame (4K: about 39 ms, frames every 33 ms) does not leave the
 * worker idle until the next frame. The engine runs with [ScannerSettings.repeatedPayloads] (a shelf of identical units)
 * and the counter's refresh, set before every scan ([desiredRefresh]; [AR_REFRESH_AFTER_MS] until its first view).
 * Every decoded image's reads ([readsOf]) and what it took go to the callback, empty ones too: the counter learns
 * which frames were decoded.
 */
class BarcodeProcessor(
    private val context: Context,
    private val refreshAfterMs: Long = AR_REFRESH_AFTER_MS,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ArEngine") }
    private val intake = LatestWins<Decode>()

    @Volatile
    private var closed = false

    // Of the worker: loading the detector takes a moment, which the main thread does not have.
    // Null until it is made, and for good if it cannot be (then nothing is read).
    private var scanner: BarcodeScanner? = null

    /**
     * The counter's refresh for the next frames ([io.packagex.arcount.CountView.desiredRefreshMs], spec 5.3), read on
     * the worker before every scan; null (until the counter's first view) keeps [refreshAfterMs].
     */
    @Volatile
    var desiredRefresh: () -> Int? = { null }

    /** Clockwise turn that makes the camera's image upright; 90 (the Memor 35's) until [configureRotation]. */
    @Volatile
    private var rotationDegrees = 90

    init {
        worker.execute {
            try {
                scanner = BarcodeScanner.create(context.applicationContext, ScannerSettings(repeatedPayloads = true))
                    .also { it.refreshAfterMs = refreshAfterMs }
            } catch (t: Throwable) {
                Log.e(TAG, "the barcode scanner could not be made; AR Count reads no barcodes", t)
            }
        }
    }

    /** Reads SENSOR_ORIENTATION of [cameraId], the camera ARCore runs on. */
    fun configureRotation(cameraId: String) {
        runCatching {
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION)
        }.onSuccess { orientation ->
            if (orientation != null) rotationDegrees = orientation
        }.onFailure { t ->
            Log.w(TAG, "SENSOR_ORIENTATION lookup failed for camera $cameraId, keeping $rotationDegrees", t)
        }
    }

    /**
     * Takes ownership of [image] and closes it when done: decoded now, or after the decode in flight unless a newer
     * image comes first; [onReads] runs on the worker with the image's timestamp.
     */
    fun process(
        image: Image,
        onReads: (timestampNs: Long, reads: List<Read>, stats: EngineStats) -> Unit,
    ) {
        val d = Decode(image, onReads)
        if (closed) return d.close()
        if (intake.offer(d)) submit(d)
    }

    /** Closes the image waiting for the engine, unread: the capture it belongs to has stopped. */
    fun dropPending() = intake.clear()

    /**
     * Closes [reader] on the worker, after the decode in flight: that decode reads the planes of one of its images,
     * and `ImageReader.close()` frees every image it handed out. The image waiting is closed at once, and one of its
     * images decoded later is closed by then and throws before the engine reads it.
     */
    fun closeAfterDecode(reader: ImageReader) {
        intake.clear()
        runBehind(worker) { reader.close() }
    }

    fun close() {
        if (closed) return
        closed = true
        intake.clear()
        // After any frame still queued: BarcodeScanner is closed only when no scan is running.
        worker.execute {
            scanner?.close()
            scanner = null
        }
        worker.shutdown()
    }

    // Queued on the worker, behind anything queued meanwhile: a reader's close (closeAfterDecode) runs before the next decode.
    private fun submit(d: Decode) {
        try {
            worker.execute { decode(d) }
        } catch (e: RejectedExecutionException) { // closed: nothing is decoded any more
            d.close()
            while (true) (intake.next() ?: break).close()
        }
    }

    /** Worker: [d] through the engine, then the image that came meanwhile, the newest one, at once. */
    private fun decode(d: Decode) {
        try {
            scanner?.let { scan(it, d) }
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed", t)
        } finally {
            d.close()
        }
        // The blur pre-skip (spec 5.6) runs before the intake, on the camera thread: ArSessionController.onImage
        intake.next()?.let(::submit)
    }

    private fun scan(scanner: BarcodeScanner, d: Decode) {
        val image = d.image
        val w = image.width
        val h = image.height
        val ts = image.timestamp
        val rotation = rotationDegrees
        val p = image.planes
        val refresh = refreshFor(desiredRefresh(), refreshAfterMs)
        scanner.refreshAfterMs = refresh
        val started = System.nanoTime()
        val frame = scanner.scanAll(
            p[0].buffer, p[1].buffer, p[2].buffer, w, h, p[0].rowStride, p[1].rowStride, p[1].pixelStride,
            Rect(0, 0, w, h), rotation, ts,
        )
        val scanMs = (System.nanoTime() - started) / 1e6f
        d.onReads(ts, readsOf(frame, rotation, w, h, ts), frame.stats.toEngineStats(scanMs, intake.dropped, refresh))
    }

    /** An app-stream image for the engine, and where its reads go */
    private class Decode(val image: Image, val onReads: (Long, List<Read>, EngineStats) -> Unit) : AutoCloseable {
        override fun close() = image.close()
    }

    private companion object {
        const val TAG = "ArEngine"
    }
}

/** The engine's refresh for the next scan: the counter's [desired] one (none below 0), or [fallback] before it has one. */
internal fun refreshFor(desired: Int?, fallback: Long): Long = desired?.toLong()?.coerceAtLeast(0L) ?: fallback

/**
 * The engine's intake (spec 5.8 step 1): one item in work and at most one waiting, latest wins. An item that comes
 * while one is in work waits in the slot; a newer one takes its place, and the one it replaces is closed unread and
 * counted in [dropped]. Offered on the camera thread, taken on the worker, cleared on teardown.
 */
internal class LatestWins<T : AutoCloseable> {
    private var working = false
    private var pending: T? = null

    /** Items closed unread because a newer one replaced them in the slot */
    @Volatile
    var dropped = 0L
        private set

    /** [item] came: true when it is to be worked on now (busy until [next] returns null), false when it waits. */
    @Synchronized
    fun offer(item: T): Boolean {
        if (!working) {
            working = true
            return true
        }
        pending?.let {
            it.close()
            dropped++
        }
        pending = item
        return false
    }

    /** The item in work is done: the waiting one, in work from now on, or null (idle) when none waits. */
    @Synchronized
    fun next(): T? = pending.also {
        pending = null
        if (it == null) working = false
    }

    /** Teardown: the waiting item, if any, is closed unread (not counted: nothing newer took its place). */
    @Synchronized
    fun clear() {
        pending?.close()
        pending = null
    }
}
