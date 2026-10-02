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
 * `scanAll` call on the worker.
 */
data class EngineStats(
    val fps: Float,
    val prepareMs: Float,
    val detectMs: Float,
    val decodeMs: Float,
    val barcodes: Int,
    val decoded: Int,
    val scanMs: Float,
)

internal fun FrameStats.toEngineStats(scanMs: Float) = EngineStats(framesPerSecond, prepareMs, detectMs, decodeMs, barcodes, decoded, scanMs)

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
