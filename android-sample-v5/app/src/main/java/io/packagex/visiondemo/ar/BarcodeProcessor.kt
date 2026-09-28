package io.packagex.visiondemo.ar

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.util.Log
import com.packagexlabs.visionbarcodescanner.ScannerCallback
import com.packagexlabs.visionbarcodescanner.VisionBarcodeScanner
import com.packagexlabs.visionbarcodescanner.model.BarcodeState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
 * Feeds ARCore CPU images to the SDK's own [VisionBarcodeScanner] via its
 * raw-plane `processFrame`, on one worker thread, single-flight.
 *
 * CV-only (ML detection off): ZBar/ZXing decode in ~5-40 ms from frame one,
 * whereas ML detection needs the GMS TfLite client initialised first — the
 * SDK's `BarcodeScanAnalyzer` defers that for exactly this reason. The AR
 * placement pipeline wants low latency and does its own multi-sighting
 * confirmation, so it also asks the tracker to surface every decode at once.
 */
class BarcodeProcessor(
    private val context: Context,
) {
    private val scanner =
        VisionBarcodeScanner.create(
            assetManager = context.assets,
            callback = object : ScannerCallback {},
        ) {
            mlDetection { enabled = false }
            tracking {
                minHitStreak = 1
                stabilityFramesRequired = 1
                maxMissedFrames = 0
                skipFrameInterval = 1
                roiEnabled = false
            }
        }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ArBarcodeDecode") }
    private val busy = AtomicBoolean(false)

    @Volatile
    private var closed = false

    val isBusy: Boolean get() = busy.get()

    // Fallback until configureRotation() runs; matches the old hardcoded
    // value, verified 90 on the Datalogic Memor 35 via `dumpsys media.camera`
    // (android.sensor.orientation). Devices vary, so this is replaced with
    // the real SENSOR_ORIENTATION for ARCore's own camera once the session
    // picks one.
    @Volatile
    private var rotationDegrees = 90

    /**
     * Reads SENSOR_ORIENTATION for [cameraId] (ARCore's chosen camera) and
     * uses it for both the scanner's decode rotation and [uprightToRaw].
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
                val rawW = image.width
                val rawH = image.height
                val p = image.planes
                val tracked =
                    scanner.processFrame(
                        p[0].buffer,
                        rawW,
                        rawH,
                        p[0].rowStride,
                        rotationDegrees,
                        p[1].buffer,
                        p[1].rowStride,
                        p[1].pixelStride,
                        p[2].buffer,
                        p[2].rowStride,
                        p[2].pixelStride,
                    )
                val detections =
                    tracked.mapNotNull { tb ->
                        if (tb.state == BarcodeState.LOST) return@mapNotNull null
                        // Coasting tracks (missed this frame, Kalman-predicted
                        // position) still report non-LOST states, so filtering
                        // on state alone lets a stale, predicted box through.
                        if (!tb.measuredThisFrame) return@mapNotNull null
                        val payload = tb.content
                        if (payload.isNullOrEmpty()) return@mapNotNull null
                        // Boxes come back in the rotated (upright) image, like the
                        // SDK's own consumer assumes (it swaps w/h for 90/270).
                        // Use the raw (unsmoothed) box: the Kalman + One-Euro
                        // smoothed boundingBoxF lags a moving barcode in the pan
                        // direction, which is what placed AR markers off-target.
                        val box = tb.rawBoundingBoxF
                        val (rx, ry) = uprightToRaw(box.centerX(), box.centerY(), rotationDegrees, rawW, rawH)
                        Detection(payload, tb.formatName ?: "Barcode", rx, ry)
                    }
                if (detections.isNotEmpty()) {
                    Log.d("MarkerDiag", "BATCH dets=${detections.size}")
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

    /** Map a point in the upright (rotated) image back to raw sensor pixels. */
    private fun uprightToRaw(
        u: Float,
        v: Float,
        rotationDegrees: Int,
        rawW: Int,
        rawH: Int,
    ): Pair<Float, Float> =
        when (rotationDegrees) {
            90 -> Pair(v, rawH - u)
            180 -> Pair(rawW - u, rawH - v)
            270 -> Pair(rawW - v, u)
            else -> Pair(u, v)
        }

    fun close() {
        closed = true
        worker.shutdown()
        scanner.close()
    }

    private companion object {
        const val TAG = "ArBarcodeProcessor"
    }
}
