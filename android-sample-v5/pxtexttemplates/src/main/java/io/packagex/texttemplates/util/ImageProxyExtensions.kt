package io.packagex.texttemplates.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import java.io.ByteArrayOutputStream

/**
 * Convert ImageProxy to ML Kit InputImage.
 */
@androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
internal fun ImageProxy.toInputImage(): InputImage {
    return InputImage.fromMediaImage(
        this.image!!,
        this.imageInfo.rotationDegrees
    )
}

/**
 * The actual image buffer width from the underlying Image.
 * This matches what ML Kit sees (not the ImageProxy crop rect).
 */
@get:androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
internal val ImageProxy.bufferWidth: Int get() = this.image!!.width

/**
 * The actual image buffer height from the underlying Image.
 * This matches what ML Kit sees (not the ImageProxy crop rect).
 */
@get:androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
internal val ImageProxy.bufferHeight: Int get() = this.image!!.height

/**
 * Rotate a packed Y800 grayscale image by [degrees] (must be 0, 90, 180, or 270).
 * Returns Triple(rotatedBytes, newWidth, newHeight).
 */
internal fun rotateY800(src: ByteArray, w: Int, h: Int, degrees: Int): Triple<ByteArray, Int, Int> {
    return when (degrees % 360) {
        0 -> Triple(src, w, h)
        90 -> {
            // 90° clockwise: dst(x,y) = src(y, w-1-x), new dims = h x w
            val dst = ByteArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    dst[x * h + (h - 1 - y)] = src[y * w + x]
                }
            }
            Triple(dst, h, w)
        }
        180 -> {
            val dst = ByteArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    dst[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x]
                }
            }
            Triple(dst, w, h)
        }
        270 -> {
            // 270° clockwise (= 90° CCW): dst(x,y) = src(h-1-y, x), new dims = h x w
            val dst = ByteArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    dst[(w - 1 - x) * h + y] = src[y * w + x]
                }
            }
            Triple(dst, h, w)
        }
        else -> Triple(src, w, h) // unsupported angle, return as-is
    }
}

/**
 * Convert packed Y-plane grayscale bytes to a rotated ARGB Bitmap.
 */
internal fun yPlaneToBitmap(yBytes: ByteArray, width: Int, height: Int, rotationDegrees: Int): Bitmap {
    val nv21 = ByteArray(width * height * 3 / 2)
    System.arraycopy(yBytes, 0, nv21, 0, width * height)
    for (i in width * height until nv21.size) nv21[i] = 128.toByte()

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    var bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())

    if (rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    return bitmap
}

/**
 * Extract the full Y-plane bytes from the ImageProxy (YUV_420_888 format).
 * Uses the underlying Image's actual buffer dimensions (NOT the crop rect)
 * so the result matches ML Kit's coordinate space.
 * Strips row-stride padding so the result is packed [bufferWidth x bufferHeight].
 */
@androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
internal fun ImageProxy.yPlaneBytes(): ByteArray {
    val yPlane = this.planes[0]
    val yBuffer = yPlane.buffer.duplicate()
    val rowStride = yPlane.rowStride
    val w = this.image!!.width
    val h = this.image!!.height

    yBuffer.rewind()
    val raw = ByteArray(yBuffer.remaining())
    yBuffer.get(raw)

    // If row stride == width, the buffer is already packed
    if (rowStride == w) return raw.copyOf(w * h)

    // Strip row-stride padding
    val packed = ByteArray(w * h)
    for (row in 0 until h) {
        val srcOffset = row * rowStride
        val copyLen = minOf(w, raw.size - srcOffset)
        if (copyLen <= 0) break
        System.arraycopy(raw, srcOffset, packed, row * w, copyLen)
    }
    return packed
}
