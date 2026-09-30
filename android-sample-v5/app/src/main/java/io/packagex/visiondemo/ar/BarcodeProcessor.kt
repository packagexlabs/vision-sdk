package io.packagex.visiondemo.ar

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.util.Log
import com.example.barcodescanner.BarcodeScanner
import com.example.barcodescanner.ScanFrame
import io.packagex.visiondemo.BuildConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
 * A barcode decoded from an ARCore CPU frame, with its centre expressed in the
 * *raw* (unrotated) image's pixel coordinates — the space ARCore's
 * `Coordinates2d.IMAGE_PIXELS` expects.
 */
data class Detection(
    val payload: String,
    val format: String,
    val rawX: Float,
    val rawY: Float,
)

/**
 * Feeds ARCore CPU images to the barcode engine VisionSDK reads with (`com.packagexlabs:barcode-scanner`,
 * BarcodeScannerApp's [BarcodeScanner.scanAll] of the planes, the whole frame), on one worker thread,
 * single-flight. Only barcodes whose text shows are reported: the engine shows a text once two frames
 * agree and reports only what it sees in the frame, so no predicted position reaches the AR placement.
 */
class BarcodeProcessor(
    private val context: Context,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ArBarcodeDecode") }
    private val busy = AtomicBoolean(false)

    @Volatile
    private var closed = false

    // Of the worker: loading the detector takes a moment, which the main thread does not have.
    // Null until it is made, and for good if it cannot be (then AR reads no barcode).
    private var scanner: BarcodeScanner? = null

    val isBusy: Boolean get() = busy.get()

    // Fallback until configureRotation() runs; matches the old hardcoded
    // value, verified 90 on the Datalogic Memor 35 via `dumpsys media.camera`
    // (android.sensor.orientation). Devices vary, so this is replaced with
    // the real SENSOR_ORIENTATION for ARCore's own camera once the session
    // picks one.
    @Volatile
    private var rotationDegrees = 90

    init {
        worker.execute {
            try {
                scanner = BarcodeScanner.create(context.applicationContext)
            } catch (t: Throwable) {
                Log.e(TAG, "the barcode scanner could not be made; AR reads no barcodes", t)
            }
        }
    }

    /**
     * Reads SENSOR_ORIENTATION for [cameraId] (ARCore's chosen camera) and
     * uses it for both the scanner's rotation and [uprightCentreToRaw].
     * Call once the session's camera config is known, after `Session.configure`.
     */
    fun configureRotation(cameraId: String) {
        runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION)
        }.onSuccess { orientation ->
            if (orientation != null) rotationDegrees = orientation
        }.onFailure { t ->
            Log.w(TAG, "SENSOR_ORIENTATION lookup failed for camera $cameraId, keeping $rotationDegrees", t)
        }
    }

    /** Takes ownership of [image] and closes it when done. */
    fun process(
        image: Image,
        onResult: (List<Detection>) -> Unit,
    ) {
        if (closed || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        worker.execute {
            try {
                val scanner = scanner ?: return@execute
                val rawW = image.width
                val rawH = image.height
                val rotation = rotationDegrees
                val p = image.planes
                val frame =
                    scanner.scanAll(
                        p[0].buffer,
                        p[1].buffer,
                        p[2].buffer,
                        rawW,
                        rawH,
                        p[0].rowStride,
                        p[1].rowStride,
                        p[1].pixelStride,
                        Rect(0, 0, rawW, rawH),
                        rotation,
                        image.timestamp,
                    )
                val detections = detectionsOf(frame, rotation, rawW, rawH)
                if (detections.isNotEmpty()) {
                    if (BuildConfig.DEBUG) Log.d("MarkerDiag", "BATCH dets=${detections.size}")
                    onResult(detections)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "decode failed", t)
            } finally {
                image.close()
                busy.set(false)
            }
        }
    }

    /**
     * Blocks until the decode in flight (if any) has finished, at most [timeoutMs]: its [Image] belongs to
     * the ARCore session, so the session must not close under it. The single worker runs FIFO, so a no-op
     * queued behind the decode completes once the decode has.
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
        const val TAG = "ArBarcodeProcessor"
    }
}

/**
 * The barcodes of [frame] whose text shows, with their centres in pixels of the raw
 * [rawWidth] x [rawHeight] frame that [rotationDegrees] clockwise turned upright. A box the
 * engine has not read (or read as nothing) is left out; a read barcode whose symbology the
 * SDK has no name for is kept as "Barcode".
 */
internal fun detectionsOf(
    frame: ScanFrame,
    rotationDegrees: Int,
    rawWidth: Int,
    rawHeight: Int,
): List<Detection> =
    frame.barcodes.mapNotNull { barcode ->
        val payload = barcode.text
        if (payload.isNullOrEmpty()) return@mapNotNull null
        val (x, y) = uprightCentreToRaw(barcode.corners, frame.cropWidth, frame.cropHeight, rotationDegrees, rawWidth, rawHeight)
        Detection(payload, barcode.symbology?.id ?: "Barcode", x, y)
    }

/**
 * The centre of a barcode whose [corners] are 0..1 of a [uprightWidth] x [uprightHeight] frame
 * turned upright by [rotationDegrees] clockwise, in pixels of the raw [rawWidth] x [rawHeight]
 * frame.
 */
internal fun uprightCentreToRaw(
    corners: FloatArray,
    uprightWidth: Int,
    uprightHeight: Int,
    rotationDegrees: Int,
    rawWidth: Int,
    rawHeight: Int,
): Pair<Float, Float> {
    val u = (corners[0] + corners[2] + corners[4] + corners[6]) / 4f * uprightWidth
    val v = (corners[1] + corners[3] + corners[5] + corners[7]) / 4f * uprightHeight
    return when (rotationDegrees) {
        90 -> Pair(v, rawHeight - u)
        180 -> Pair(rawWidth - u, rawHeight - v)
        270 -> Pair(rawWidth - v, u)
        else -> Pair(u, v)
    }
}
