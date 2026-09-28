package io.packagex.visiondemo.document

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * Per-page processing, ported from the original demo's DocumentSession: dewarp →
 * quality → enhance, run once per captured page (never per live frame). The
 * page list lives in [DocumentPages]; text recognition runs at PDF export, as iOS
 * DocumentPipeline defers it. The UVDoc model stays warm for the next page.
 */
class DocumentSession(private val context: Context) {
    private var model: DocumentDewarpModel? = null

    @Synchronized
    private fun model(): DocumentDewarpModel = model ?: DocumentDewarpModel(context).also { model = it }

    /** Blocking; call off the main thread. A failure leaves [DocumentPage.failed] set. */
    fun process(page: DocumentPage) {
        try {
            val r = DocumentDewarp.dewarp(model(), page.original)
            page.page = r.bitmap
            page.wasDewarped = r.corrected
            page.deviation = r.deviation
            page.modelMs = r.modelMs
            page.resampleMs = r.resampleMs
            // Quality on the straightened page, before tone (glare is meaningless after).
            page.quality = DocumentQualityChecker.analyze(r.bitmap)
            val t = System.nanoTime()
            val w = r.bitmap.width
            val h = r.bitmap.height
            val px = IntArray(w * h)
            r.bitmap.getPixels(px, 0, w, 0, 0, w, h)
            page.enhanced = Bitmap.createBitmap(DocumentEnhancer.enhance(px, w, h), w, h, Bitmap.Config.ARGB_8888)
            page.enhanceMs = (System.nanoTime() - t) / 1_000_000
            Log.i(TAG, "page ${page.index}: ${w}x$h dewarped=${r.corrected} model=${r.modelMs}ms resample=${r.resampleMs}ms enhance=${page.enhanceMs}ms (${DocumentEnhancer.lastPath})")
        } catch (e: Exception) {
            Log.e(TAG, "page ${page.index} failed", e)
            page.failed = true
        }
    }

    /** Blocking text recognition for the PDF's text layer; a no-op once done. */
    fun recognize(page: DocumentPage) {
        if (page.lines != null) return
        val t = System.nanoTime()
        page.lines = DocumentTextLayer.recognize(page.page ?: page.original)
        page.recognizeMs = (System.nanoTime() - t) / 1_000_000
    }

    private companion object {
        const val TAG = "DocumentSession"
    }
}
