package io.packagex.visiondemo.document

import android.graphics.Bitmap

/**
 * One captured page through the pipeline. [original] is the perspective-cropped
 * capture; [page] is it straightened by UVDoc when curled — what gets exported.
 * Once a page is straightened the raw crop is never shown again, so [original]
 * then points at [page] and the crop's memory goes.
 * Fields fill in as processing completes; [lines] stays null until recognition,
 * which runs at PDF export (as iOS). [index] numbers the captures (1, 2, …).
 */
class DocumentPage(
    original: Bitmap,
    val index: Int = 0,
) {
    @Volatile var original: Bitmap = original
        internal set

    @Volatile var page: Bitmap? = null

    @Volatile var enhanced: Bitmap? = null

    @Volatile var wasDewarped = false

    @Volatile var deviation = 0.0

    @Volatile var quality: DocumentQuality? = null

    @Volatile var lines: List<RecognizedLine>? = null

    @Volatile var modelMs = 0L

    @Volatile var resampleMs = 0L

    @Volatile var enhanceMs = 0L

    @Volatile var recognizeMs = 0L

    @Volatile var failed = false

    val isProcessed: Boolean get() = enhanced != null || failed
}
