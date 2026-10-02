package io.packagex.visiondemo.ar

import io.packagex.arcount.Read

/**
 * What AR Item Count shows of the reads themselves (spec 5.10), kept on the mapper thread, the clock being the reads'
 * own timestamps: the reads of the last [windowNs] ([reads], for the neutral markers), the distinct codes among them
 * ([inView]: the hint, the shutter's rows, "Add Item"), and every distinct code read since [clearSeen], the most
 * recently first read first, at most [seenCap] ([seen]: the item list's "Seen" section). One writer.
 */
class RecentReads(private val windowNs: Long = IN_VIEW_NS, private val seenCap: Int = SEEN_CAP) {
    private var newestNs = Long.MIN_VALUE

    /** The reads of the last [windowNs] before the newest timestamp added, oldest first */
    var reads: List<Read> = emptyList()
        private set

    /** Newest first */
    var seen: List<String> = emptyList()
        private set

    /** The distinct texts of [reads], in the order they were first read */
    val inView: List<String> get() = reads.map { it.text }.distinct()

    /** The reads of the app-stream image taken at [timestampNs], empty when it had none */
    fun add(timestampNs: Long, batch: List<Read>) {
        if (timestampNs > newestNs) newestNs = timestampNs
        reads = (reads + batch).filter { it.timestampNs >= newestNs - windowNs }
        val new = batch.map { it.text }.distinct().filter { it !in seen }
        if (new.isNotEmpty()) seen = (new.reversed() + seen).take(seenCap)
    }

    /** "New Scan" */
    fun clearSeen() {
        seen = emptyList()
    }

    companion object {
        /** A code is in view while read within the last second (spec 5.10) */
        const val IN_VIEW_NS = 1_000_000_000L
        const val SEEN_CAP = 30

        /** The codes in view and the seen list go to the UI at most this often */
        const val PUBLISH_EVERY_NS = 250_000_000L
    }
}

/**
 * The reads that get a neutral marker (white outline, no fill) in the frame taken at [nowNs]: codes not in [listed]
 * (listed ones have the counter's unit markers), read [windowNs] before it or later; per code and engine track the newest
 * read only, so a code read in every frame of a pan has one marker, where it was read last.
 */
fun unlistedReads(reads: List<Read>, listed: Set<String>, nowNs: Long, windowNs: Long = NEUTRAL_WINDOW_NS): List<Read> =
    reads.filter { it.text !in listed && it.timestampNs >= nowNs - windowNs }
        .groupBy { it.text to it.engineId }
        .map { (_, same) -> same.maxBy { it.timestampNs } }

const val NEUTRAL_WINDOW_NS = 500_000_000L
