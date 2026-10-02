package io.packagex.visiondemo.ar

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.util.Log
import com.example.barcodescanner.BarcodeScanner
import com.example.barcodescanner.ScannerSettings
import io.packagex.arcount.Read
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The engine's refreshAfterMs while an AR Count session runs: 0 re-reads every shown barcode in every frame, as far as
 * the frame's decode budget goes. Spec 5.3 says 100 while counting, but at 30 fps 100 re-reads only one shown barcode
 * per frame (engine investigation, 2026-10-02). One constant, for the thermal fallback (spec 6) to raise later.
 */
internal const val AR_REFRESH_AFTER_MS = 0L

/** Waits until every task queued on single-thread [worker] so far has run, at most [timeoutMs]; false on timeout.
 *  A shut-down worker has nothing left to wait for. */
internal fun awaitDrained(worker: ExecutorService, timeoutMs: Long): Boolean = try {
    worker.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    true
} catch (e: TimeoutException) {
    false
} catch (e: RejectedExecutionException) {
    true
}

/**
 * The engine worker (spec 5.8), the spike's: images of the app stream go to BarcodeScannerApp's engine
 * ([BarcodeScanner.scanAll] of the planes, the whole frame, turned upright by the camera's SENSOR_ORIENTATION) on one
 * worker thread, single-flight: an image that comes while one is decoded is closed unread. The engine runs with
 * [ScannerSettings.repeatedPayloads] (a shelf of identical units) and [AR_REFRESH_AFTER_MS]. Every decoded image's
 * reads ([readsOf]) and what it took go to the callback, empty ones too: the counter learns which frames were decoded.
 */
class BarcodeProcessor(
    private val context: Context,
    private val refreshAfterMs: Long = AR_REFRESH_AFTER_MS,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ArEngine") }
    private val busy = AtomicBoolean(false)

    @Volatile
    private var closed = false

    // Of the worker: loading the detector takes a moment, which the main thread does not have.
    // Null until it is made, and for good if it cannot be (then nothing is read).
    private var scanner: BarcodeScanner? = null

    /** Clockwise turn that makes the camera's image upright; 90 (the Memor 35's) until [configureRotation]. */
    @Volatile
    private var rotationDegrees = 90

    val isBusy: Boolean get() = busy.get()

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

    /** Takes ownership of [image] and closes it when done; [onReads] runs on the worker with the image's timestamp. */
    fun process(
        image: Image,
        onReads: (timestampNs: Long, reads: List<Read>, stats: EngineStats) -> Unit,
    ) {
        if (closed || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        // close() may have shut the worker down after the closed check above.
        try {
            worker.execute {
                try {
                    val scanner = scanner ?: return@execute
                    val w = image.width
                    val h = image.height
                    val ts = image.timestamp
                    val rotation = rotationDegrees
                    val p = image.planes
                    val started = System.nanoTime()
                    val frame = scanner.scanAll(
                        p[0].buffer, p[1].buffer, p[2].buffer, w, h, p[0].rowStride, p[1].rowStride, p[1].pixelStride,
                        Rect(0, 0, w, h), rotation, ts,
                    )
                    val scanMs = (System.nanoTime() - started) / 1e6f
                    onReads(ts, readsOf(frame, rotation, w, h, ts), frame.stats.toEngineStats(scanMs))
                } catch (t: Throwable) {
                    Log.w(TAG, "decode failed", t)
                } finally {
                    image.close()
                    busy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            image.close()
            busy.set(false)
        }
    }

    /**
     * Blocks until the decode in flight (if any) has finished, at most [timeoutMs]: its [Image] belongs to the app
     * stream's ImageReader, which must not close under it. The single worker runs FIFO, so a no-op queued behind the
     * decode completes once the decode has.
     */
    fun awaitIdle(timeoutMs: Long) {
        if (!awaitDrained(worker, timeoutMs)) Log.w(TAG, "decode still running after $timeoutMs ms")
    }

    fun close() {
        if (closed) return
        closed = true
        // After any frame still queued: BarcodeScanner is closed only when no scan is running.
        worker.execute {
            scanner?.close()
            scanner = null
        }
        worker.shutdown()
    }

    private companion object {
        const val TAG = "ArEngine"
    }
}
