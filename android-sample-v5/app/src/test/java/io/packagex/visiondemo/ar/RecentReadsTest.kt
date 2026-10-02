package io.packagex.visiondemo.ar

import io.packagex.arcount.ArCounter
import io.packagex.arcount.AnchorRequest
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Read
import org.junit.Assert.assertEquals
import org.junit.Test

/** AR Item Count's view of the reads (spec 5.10): codes in view, the seen list, and the neutral markers. */
class RecentReadsTest {
    private val ms = 1_000_000L

    private fun read(text: String, tsMs: Long, engineId: Int = 1, u: Double = 100.0) =
        Read(tsMs * ms, text, listOf(u, 10.0, u + 20, 10.0, u + 20, 30.0, u, 30.0), engineId)

    private fun RecentReads.add(tsMs: Long, vararg texts: String) = add(tsMs * ms, texts.map { read(it, tsMs) })

    @Test fun codesStayInViewForASecondAfterTheirLastRead() {
        val r = RecentReads()
        r.add(0, "A", "B")
        r.add(600, "B", "C")
        assertEquals(listOf("A", "B", "C"), r.inView)
        r.add(1_100)   // an image with no reads moves the clock on: A was last read 1.1 s ago
        assertEquals(listOf("B", "C"), r.inView)
        r.add(1_700)
        assertEquals(emptyList<String>(), r.inView)
    }

    @Test fun seenIsNewestFirstDistinctAndCappedAt30() {
        val r = RecentReads()
        r.add(0, "A")
        r.add(100, "B", "A")
        r.add(200, "C")
        r.add(300, "A")   // read again: keeps its place, the list does not reshuffle under the worker's finger
        assertEquals(listOf("C", "B", "A"), r.seen)
        (1..40).forEach { r.add(300L + it, "X$it") }
        assertEquals(30, r.seen.size)
        assertEquals("X40", r.seen.first()); assertEquals("X11", r.seen.last())
        r.add(5_000)   // nothing in view any more: seen stays for the whole session
        assertEquals(30, r.seen.size)
    }

    @Test fun clearSeenEmptiesTheSeenListOnly() {
        val r = RecentReads()
        r.add(0, "A")
        r.clearSeen()
        assertEquals(emptyList<String>(), r.seen)
        assertEquals(listOf("A"), r.inView)
        r.add(100, "A")
        assertEquals(listOf("A"), r.seen)
    }

    @Test fun neutralMarkersAreForUnlistedCodesReadInTheLastHalfSecond() {
        val reads = listOf(
            read("OLD", 0), read("L", 900), read("U", 600), read("U", 900, u = 300.0),
            read("U", 950, engineId = 2, u = 900.0), read("V", 1_000),
        )
        val marks = unlistedReads(reads, listed = setOf("L"), nowNs = 1_000 * ms)
        // OLD is 1 s old; L is listed (the counter marks it); U's track 1 is marked once, where it was read last
        assertEquals(listOf("U" to 300.0, "U" to 900.0, "V" to 100.0), marks.map { it.text to it.corners[0] })
        assertEquals(emptyList<Read>(), unlistedReads(reads, setOf("U", "V"), nowNs = 1_600 * ms))
    }

    @Test fun theMapperSendsTheListToEveryCounterAndPublishesTheCodesAtMostEvery250Ms() {
        val first = ItemsCounter()
        val published = mutableListOf<Pair<List<String>, List<String>>>()
        val m = ArMapper(first, onCodes = { inView, seen -> published += inView to seen })
        m.post(ArEvent.Items(setOf("A", "B"))); m.drain()
        assertEquals(listOf(setOf("A", "B")), first.items); assertEquals(setOf("A", "B"), m.items)

        m.post(ArEvent.Reads(1_000 * ms, listOf(read("A", 1_000)))); m.drain()
        m.post(ArEvent.Reads(1_100 * ms, listOf(read("Z", 1_100)))); m.drain()   // within 250 ms: not published yet
        m.post(ArEvent.Reads(1_300 * ms, emptyList())); m.drain()
        assertEquals(listOf(listOf("A") to listOf("A"), listOf("A", "Z") to listOf("Z", "A")), published)
        assertEquals(listOf("A", "Z"), m.recentReads().map { it.text })   // the GL thread's copy is not throttled

        val second = ItemsCounter()
        m.post(ArEvent.Reset(second)); m.drain()   // New Scan: the new counter gets the list, the seen list empties
        assertEquals(listOf(setOf("A", "B")), second.items)
        assertEquals(listOf("A", "Z") to emptyList<String>(), published.last())
    }

    /** Records the lists it was given; counts nothing. */
    private class ItemsCounter : ArCounter {
        val items = mutableListOf<Set<String>>()
        override fun setItems(codes: Set<String>) { items += codes }
        override fun onResume(timestampNs: Long) {}
        override fun onFrame(frame: PoseRecord) {}
        override fun onReads(timestampNs: Long, reads: List<Read>) {}
        override fun onCommand(command: Command, timestampNs: Long) {}
        override fun anchorRequest(): AnchorRequest? = null
        override fun onAnchorCreated(ok: Boolean) {}
        override fun view(): CountView = CountView.EMPTY
    }
}
