package io.packagex.visiondemo.ar

import com.example.barcodescanner.FrameStats
import com.example.barcodescanner.ScanFrame
import io.packagex.arcount.Read

/** A corner this close to the image border, in pixels, marks a cut symbol: its corners are guessed (spec 5.3). */
internal const val BORDER_PX = 2.0

/**
 * What the engine took for one app-stream image, to explain slow 4K frames: its [ScanFrame.stats] ([fps] the engine's
 * frame rate; [prepareMs] picture motion and handing the frame to the detector, [detectMs] the detector's last look,
 * [decodeMs] localizer and decoder; [barcodes] boxes, [decoded] of them with a text shown) and [scanMs], the whole
 * `scanAll` call on the worker; [droppedImages], the images so far that waited for the engine and were replaced by a
 * newer one, unread; [refreshAfterMs], the engine's refresh for this image (-1: not told); [pipe], the app's own work
 * on the images so far.
 */
data class EngineStats(
    val fps: Float,
    val prepareMs: Float,
    val detectMs: Float,
    val decodeMs: Float,
    val barcodes: Int,
    val decoded: Int,
    val scanMs: Float,
    val droppedImages: Long,
    val refreshAfterMs: Long = -1L,
    val pipe: PipeCounters = PipeCounters(),
)

/**
 * The app's own work on the app-stream images, all of it so far (the 2 s line takes differences): the luma copies of
 * spec 5.9 ([lumaFrames] made, [lumaCopyNs] copying on the camera thread, [lumaScaleNs] downscaling on the luma
 * thread, [lumaDropped] replaced before the downscale took them) and [blurSkipped], the images kept from the engine
 * for predicted blur (5.6).
 */
data class PipeCounters(
    val lumaFrames: Long = 0,
    val lumaCopyNs: Long = 0,
    val lumaScaleNs: Long = 0,
    val lumaDropped: Long = 0,
    val blurSkipped: Long = 0,
)

internal fun FrameStats.toEngineStats(scanMs: Float, droppedImages: Long, refreshAfterMs: Long = -1L) =
    EngineStats(framesPerSecond, prepareMs, detectMs, decodeMs, barcodes, decoded, scanMs, droppedImages, refreshAfterMs)

/**
 * Every corner of [corners] (x0, y0, ... x3, y3, 0..1 of a [uprightWidth] x [uprightHeight] frame that [rotationDegrees]
 * clockwise turned upright) in pixels of the raw [rawWidth] x [rawHeight] frame.
 */
internal fun uprightQuadToRaw(
    corners: FloatArray,
    uprightWidth: Int,
    uprightHeight: Int,
    rotationDegrees: Int,
    rawWidth: Int,
    rawHeight: Int,
): DoubleArray {
    val out = DoubleArray(8)
    for (i in 0 until 4) {
        val u = corners[2 * i].toDouble() * uprightWidth
        val v = corners[2 * i + 1].toDouble() * uprightHeight
        val (x, y) = when (rotationDegrees) {
            90 -> v to rawHeight - u
            180 -> rawWidth - u to rawHeight - v
            270 -> rawWidth - v to u
            else -> u to v
        }
        out[2 * i] = x
        out[2 * i + 1] = y
    }
    return out
}

/** Whether a corner of [corners] (pixels of a [width] x [height] image) lies within [BORDER_PX] of its border. */
internal fun touchesBorder(corners: List<Double>, width: Int, height: Int): Boolean =
    (0 until 4).any { i ->
        val x = corners[2 * i]
        val y = corners[2 * i + 1]
        x <= BORDER_PX || y <= BORDER_PX || x >= width - BORDER_PX || y >= height - BORDER_PX
    }

/**
 * The counter's reads of one engine frame (spec 5.3): only what this frame decoded ([com.example.barcodescanner.FrameBarcode.raw]
 * and its `rawText`, engine 0.2.18), with the decoded corners in pixels of the unrotated [rawWidth] x [rawHeight] frame and
 * the text read there, which may differ from the text the engine's track shows. Detector and localizer boxes (no `raw`) are
 * left out; a read whose corners touch the border is kept and flagged, for the counter to leave out.
 */
internal fun readsOf(frame: ScanFrame, rotationDegrees: Int, rawWidth: Int, rawHeight: Int, timestampNs: Long): List<Read> =
    frame.barcodes.mapNotNull { b ->
        val raw = b.raw ?: return@mapNotNull null
        val text = b.rawText?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val corners = uprightQuadToRaw(raw, frame.cropWidth, frame.cropHeight, rotationDegrees, rawWidth, rawHeight).toList()
        Read(
            timestampNs = timestampNs,
            text = text,
            corners = corners,
            engineId = b.id,
            symbology = b.symbology?.id,
            touchesBorder = touchesBorder(corners, rawWidth, rawHeight),
        )
    }

/**
 * The boxes of one engine frame it did not decode in it (no `raw`): tracked with a text (a known code it skipped or ran
 * out of time for) or not read at all (a detector or localizer box, text ""), with the smoothed corners in the same
 * pixels as [readsOf]'s. For rule 7 only: a pin in one is seen, not missed (a batch reads 1 of 4 identical units, the
 * other 3 are still there; small tight codes are boxed before they are read).
 */
internal fun trackedOf(frame: ScanFrame, rotationDegrees: Int, rawWidth: Int, rawHeight: Int, timestampNs: Long): List<Read> =
    frame.barcodes.mapNotNull { b ->
        if (b.raw != null) return@mapNotNull null
        val corners = uprightQuadToRaw(b.corners, frame.cropWidth, frame.cropHeight, rotationDegrees, rawWidth, rawHeight).toList()
        Read(timestampNs, b.text.orEmpty(), corners, b.id, b.symbology?.id)
    }
