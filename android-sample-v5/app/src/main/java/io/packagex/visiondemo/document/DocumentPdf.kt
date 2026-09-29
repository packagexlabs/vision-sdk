package io.packagex.visiondemo.document

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-page PDF: the page image on top, the recognised text under it
 * (effectively invisible) so the file is searchable. Port of iOS DocumentPDF.
 */
object DocumentPdf {
    /** US Letter in points; pages are aspect-fitted so a PDF point stays a point. */
    private const val BOX_W = 612f
    private const val BOX_H = 792f

    fun pageSize(image: Bitmap): Pair<Int, Int> {
        val scale = min(BOX_W / image.width, BOX_H / image.height)
        return max(1, (image.width * scale).roundToInt()) to max(1, (image.height * scale).roundToInt())
    }

    /** Writes [pages] to [file]; returns false if nothing was written. */
    fun write(
        pages: List<DocumentPage>,
        useEnhanced: Boolean,
        file: File,
    ): Boolean {
        if (pages.isEmpty()) return false
        val doc = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            pages.forEachIndexed { i, page ->
                val bitmap = (if (useEnhanced) page.enhanced else page.page) ?: page.original   // a page whose processing failed exports as captured
                val (w, h) = pageSize(bitmap)
                val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                val canvas = pdfPage.canvas
                canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
                page.lines?.let { DocumentTextLayer.draw(it, w.toFloat(), h.toFloat(), canvas) }
                doc.finishPage(pdfPage)
            }
            file.outputStream().use { doc.writeTo(it) }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            doc.close()
        }
    }
}
