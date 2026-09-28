package io.packagex.visiondemo.document

/**
 * The pages of the document being scanned (iOS `docPages` / `docExported`).
 * An exported document is done: the next page starts a new one. Retake (and
 * "Add page") keep the pages, so they are still being edited.
 */
class DocumentPages {
    private val list = mutableListOf<DocumentPage>()
    private var counter = 0

    val pages: List<DocumentPage> get() = list.toList()

    var exported = false

    /** Index for the next captured page. */
    fun nextIndex() = ++counter

    fun add(p: DocumentPage) {
        if (exported) { list.clear(); exported = false }
        list += p
    }

    /** iOS `rescanDocument`: Retake drops the last page; both keep the rest. */
    fun retake(dropLast: Boolean) {
        if (dropLast) list.removeLastOrNull()
        exported = false
    }

    /** Leaving Document Acquisition drops the pages (two large bitmaps each). */
    fun reset() {
        list.clear()
        exported = false
    }
}
