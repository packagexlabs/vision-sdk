package io.packagex.visiondemo.ar

import io.packagex.arcount.AnchorRequest
import io.packagex.arcount.ArCounter
import io.packagex.arcount.Bracket
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import io.packagex.arcount.Marker
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Read
import io.packagex.arcount.SectionState
import io.packagex.arcount.UnitState

/** Makes the counter of each AR session; Hilt binds it in [ArModule]. */
fun interface ArCounterFactory {
    fun create(): ArCounter
}

/**
 * The counter until the counting core lands (wave 1), to see the plumbing work: no sections, no anchor, no prompt.
 * Every read is a marker at its centre for [markerNs] of camera time; the bracket (not in the image) shows how many
 * distinct texts were read. Commands are ignored.
 */
class DebugCounter(private val markerNs: Long = 500_000_000L) : ArCounter {
    private var width = 0
    private var height = 0
    private var nowNs = Long.MIN_VALUE
    private val texts = HashSet<String>()
    private val shown = ArrayDeque<Pair<Long, Marker>>()
    private var nextId = 0

    override fun onResume(timestampNs: Long) {}

    override fun onFrame(frame: PoseRecord) {
        width = frame.intrinsics.width
        height = frame.intrinsics.height
        nowNs = maxOf(nowNs, frame.timestampNs)
    }

    override fun onReads(timestampNs: Long, reads: List<Read>) {
        nowNs = maxOf(nowNs, timestampNs)
        for (r in reads) {
            texts += r.text
            // Normalized to the stream size of the frames seen so far; a read before any frame has none
            if (width > 0 && height > 0) {
                shown.addLast(timestampNs to Marker(nextId++, UnitState.TENTATIVE, r.centreU / width, r.centreV / height, r.widthPx / width))
            }
        }
    }

    override fun onCommand(command: Command, timestampNs: Long) {}

    override fun anchorRequest(): AnchorRequest? = null

    override fun onAnchorCreated(ok: Boolean) {}

    override fun view(): CountView {
        while (shown.isNotEmpty() && nowNs - shown.first().first > markerNs) shown.removeFirst()
        val n = texts.size
        return CountView(
            state = SectionState.IDLE,
            prompt = null,
            markers = shown.map { it.second },
            gaps = emptyList(),
            bracket = if (n == 0) null else Bracket(0.5, 0.5, inImage = false, gtin = null, countLow = n, countHigh = n, frozen = false),
            closed = emptyList(),
        )
    }
}
