package io.packagex.texttemplates.camera


/**
 * Rolling log of per-frame ML Kit OCR passes, written by [FrameAnalyzer] on
 * every analyzed frame and rendered in the debug screen's "Per-frame OCR"
 * section. A single shared instance (owned by PXClient and shared across scan
 * sessions) rather than per-FrameAnalyzer state, so the debug screen sees the
 * same log the scanner wrote.
 */
internal class PerFrameOcrLog {

    companion object {
        /** Entries kept. A capture session analyzes a handful of frames per
         *  second for a few seconds, so 50 covers several sessions. */
        private const val MAX_ENTRIES = 50
    }

    private val lock = Any()
    private val entries = ArrayDeque<String>()

    fun add(entry: String) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    /** Newest first, for display. */
    fun snapshot(): List<String> = synchronized(lock) { entries.reversed() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
