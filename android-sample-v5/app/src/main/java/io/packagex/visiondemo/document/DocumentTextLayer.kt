package io.packagex.visiondemo.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** One recognised text line with its box normalised to 0…1, origin top-left. */
class RecognizedLine(
    val text: String,
    val box: RectF,
)

/**
 * On-device text recognition (ML Kit) for the Document mode. The lines are
 * drawn into the exported PDF effectively invisibly, which makes the file
 * searchable and selectable while the visible page stays the captured image.
 * Port of iOS DocumentTextLayer (Vision).
 */
object DocumentTextLayer {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun recognize(page: Bitmap): List<RecognizedLine> =
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(page, 0)), 60, TimeUnit.SECONDS)
            val w = page.width.toFloat()
            val h = page.height.toFloat()
            result.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line ->
                    val b = line.boundingBox ?: return@mapNotNull null
                    if (line.text.isBlank()) return@mapNotNull null
                    RecognizedLine(line.text, RectF(b.left / w, b.top / h, b.right / w, b.bottom / h))
                }
        } catch (e: Exception) {
            Log.w("DocumentTextLayer", "recognition failed", e)
            emptyList()
        }

    /**
     * Draws the lines over a page of `pageW × pageH` points, each string scaled
     * to span the box it was recognised in. Skia's PDF backend drops fully
     * transparent paint, so the text is drawn at alpha 1/255 instead — invisible
     * on screen, present in the content stream for search and selection.
     */
    fun draw(
        lines: List<RecognizedLine>,
        pageW: Float,
        pageH: Float,
        canvas: Canvas,
    ) {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(1, 0, 0, 0)
                textSize = 12f
            }
        for (line in lines) {
            val left = line.box.left * pageW
            val top = line.box.top * pageH
            val width = line.box.width() * pageW
            val height = line.box.height() * pageH
            if (width <= 1 || height <= 1) continue
            paint.textSize = 12f
            val probe = paint.measureText(line.text)
            if (probe <= 0) continue
            paint.textSize = (12f * width / probe).coerceIn(1f, height * 1.6f)
            canvas.drawText(line.text, left, top + height - paint.descent(), paint)
        }
    }
}
