package io.packagex.visiondemo.ar

import android.util.Log
import io.packagex.arcount.AnchorRequest
import io.packagex.arcount.ArCounter
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import io.packagex.arcount.LumaImage
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Read
import io.packagex.arcount.SectionState
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** What the mapper hands the counter, in the order it was posted. */
sealed interface ArEvent {
    /** An ARCore frame (GL thread) */
    data class Frame(val record: PoseRecord) : ArEvent

    /** The reads of the app-stream image taken at [timestampNs] (engine worker), empty when it had none, and what it took */
    data class Reads(val timestampNs: Long, val reads: List<Read>, val stats: EngineStats? = null, val tracked: List<Read> = emptyList()) : ArEvent

    /** The quarter-scale luma copy of the app-stream image taken at [timestampNs] (luma thread), before its reads */
    class Luma(val timestampNs: Long, val img: LumaImage) : ArEvent

    /** A command of the worker (main thread); it takes the time of the newest frame */
    data class Cmd(val command: Command) : ArEvent

    /** ARCore resumed; [timestampNs] is the first frame's after `Session.resume()` (GL thread) */
    data class Resumed(val timestampNs: Long) : ArEvent

    /** The answer to [AnchorOp.Create] number [id] (GL thread) */
    data class AnchorCreated(val ok: Boolean, val id: Int) : ArEvent

    /** "New Scan": [counter] takes over; the old one's sections go with it (main thread) */
    class Reset(val counter: ArCounter) : ArEvent

    /** Trace to [recorder] from now on, null to stop; the old one is closed (main thread) */
    class Trace(val recorder: SessionRecorder?) : ArEvent

    /** AR Item Count's list: the codes the counter counts, this one and every later one (main thread) */
    data class Items(val codes: Set<String>) : ArEvent

    /** One line for the open trace, from the GL or camera thread ([ArMapper.diag]); the counter never sees it */
    class Diag(val line: String) : ArEvent
}

/** What the GL thread does with the section anchor, in order. It holds one anchor at most. */
sealed interface AnchorOp {
    /** Let go of the anchor held now, then create one at [request]; answer with [ArEvent.AnchorCreated] of [id]. */
    data class Create(val request: AnchorRequest, val id: Int) : AnchorOp

    /** Let go of the anchor held now, if any. */
    data object Detach : AnchorOp
}

/**
 * The mapper thread (spec 5.8): the single caller of the [ArCounter]. Frames, reads, commands and anchor answers reach
 * the counter in the order they were posted, from whichever thread; reads wait in a bounded queue ([readsCapacity]
 * batches, the oldest dropped and counted in [droppedReads]) so a slow counter never holds the engine up, while
 * frames are never dropped. After each call it asks the counter for an anchor and hands the GL thread [anchorOps]:
 * an anchor is created once per request and never moved or re-created (spec 5.1), and let go when its section ends
 * (closed, back to IDLE) or a new request replaces it. The newest view goes to [latestView] (GL thread) and to
 * [onView] (once per batch of events). An event the counter throws on goes to [log] and is lost, the session is not.
 */
class ArMapper(
    counter: ArCounter,
    private val onView: (CountView) -> Unit = {},
    private val readsCapacity: Int = 4,
    /** Luma copies waiting at most: the oldest is dropped (counted in [droppedLumas]), so a slow counter holds little memory */
    private val lumaCapacity: Int = 2,
    private val log: (String, Throwable) -> Unit = { what, t -> Log.e(THREAD, what, t) },
    /** AR Item Count: the codes in view and the seen list ([RecentReads]), at most every 250 ms of read time */
    private val onCodes: (inView: List<String>, seen: List<String>) -> Unit = { _, _ -> },
) {
    private val lock = Object()
    private val queue = ArrayDeque<ArEvent>()
    private var queuedReads = 0
    private var stopped = false
    private val dropped = AtomicLong()
    private val droppedLuma = AtomicLong()
    private val latest = AtomicReference(CountView.EMPTY)
    private var worker: Thread? = null

    /** For the GL thread, in order */
    val anchorOps = ConcurrentLinkedQueue<AnchorOp>()

    /** Every non-empty reads batch as posted, for the GL thread's pins ([ArPins]); the oldest go past [PIN_READS_CAP] */
    val pinReads = ConcurrentLinkedQueue<ArEvent.Reads>()

    /** Reads batches dropped because [readsCapacity] were already waiting */
    val droppedReads: Long get() = dropped.get()

    /** Luma copies dropped because [lumaCapacity] were already waiting */
    val droppedLumas: Long get() = droppedLuma.get()

    /** The counter's refresh for the engine's next frames (spec 5.3), after each event; null until its first view */
    @Volatile
    var desiredRefreshMs: Int? = null
        private set

    // The mapper thread's own
    private var counter = counter
    private var lastFrameNs = 0L
    private var closedSeen = 0
    private var nextCreateId = 0
    private var lastCreateId = -1
    /** The first [AnchorOp.Create] id of the current counter: answers to older ones were for a counter that is gone */
    private var counterFirstId = 0
    /** The request acted on last, so a request the counter repeats is not created twice; cleared when it failed */
    private var anchorWanted: AnchorRequest? = null
    /** The GL thread holds an anchor, or is about to: a Create was sent and no Detach since */
    private var anchorHeld = false
    private var recorder: SessionRecorder? = null
    private val recent = RecentReads()
    private var codesPublishedNs: Long? = null
    private val latestReads = AtomicReference<List<Read>>(emptyList())

    /** A trace is open: the mapper thread writes it, the GL and camera threads read it before building a line */
    @Volatile
    var tracing = false
        private set

    /** A diagnostic line for the open trace, built only while one is open, written after what was posted before it */
    inline fun diag(line: () -> String) {
        if (tracing) post(ArEvent.Diag(line()))
    }

    /** The item list (GL thread: listed codes get no neutral marker) */
    @Volatile
    var items: Set<String> = emptySet()
        private set

    /** The newest view (GL thread) */
    fun latestView(): CountView = latest.get()

    /** The reads of the last second, oldest first (GL thread: the neutral markers) */
    fun recentReads(): List<Read> = latestReads.get()

    fun post(event: ArEvent) {
        synchronized(lock) {
            if (stopped) return
            if (event is ArEvent.Reads && event.reads.isNotEmpty()) {
                pinReads.add(event)
                while (pinReads.size > PIN_READS_CAP) pinReads.poll()
            }
            if (event is ArEvent.Reads) {
                if (queuedReads == readsCapacity) {
                    val it = queue.iterator()
                    while (it.hasNext()) {
                        if (it.next() is ArEvent.Reads) {
                            it.remove()
                            break
                        }
                    }
                    queuedReads--
                    dropped.incrementAndGet()
                }
                queuedReads++
            }
            if (event is ArEvent.Luma && queue.count { it is ArEvent.Luma } >= lumaCapacity) {
                val it = queue.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (e is ArEvent.Luma) {
                        it.remove()
                        e.img.free()
                        break
                    }
                }
                droppedLuma.incrementAndGet()
            }
            queue.addLast(event)
            lock.notifyAll()
        }
    }

    fun start() {
        worker = thread(name = THREAD, isDaemon = true) {
            while (true) handle(take() ?: break)
        }
    }

    /** Stops the thread after the batch in hand (waiting at most [timeoutMs]) and closes the trace; queued events are dropped. */
    fun close(timeoutMs: Long = 500) {
        synchronized(lock) {
            stopped = true
            queue.clear()
            lock.notifyAll()
        }
        worker?.join(timeoutMs)
        recorder?.close()
        recorder = null
        tracing = false
    }

    /** For tests that run the mapper without its thread: handles what is queued, on the caller's thread. */
    internal fun drain() = handle(synchronized(lock) { queue.toList().also { queue.clear(); queuedReads = 0 } })

    private fun take(): List<ArEvent>? = synchronized(lock) {
        while (queue.isEmpty() && !stopped) lock.wait()
        if (stopped) null else queue.toList().also { queue.clear(); queuedReads = 0 }
    }

    private fun handle(batch: List<ArEvent>) {
        if (batch.isEmpty()) return
        var view: CountView? = null
        for (e in batch) {
            // A counter bug must not take the app down: this thread has no other handler
            try {
                handle(e)?.let { view = it }
            } catch (t: Throwable) {
                log("the counter failed on ${e::class.simpleName}", t)
            }
        }
        view?.let { latest.set(it); onView(it) }
    }

    /** The view after [e]; null for a trace line, which the counter never sees */
    private fun handle(e: ArEvent): CountView? {
        when (e) {
            is ArEvent.Frame -> {
                lastFrameNs = e.record.timestampNs
                counter.onFrame(e.record)
                recorder?.frame(e.record)
            }
            is ArEvent.Reads -> {
                addRecent(e.timestampNs, e.reads)
                counter.onReads(e.timestampNs, e.reads)
                recorder?.run {
                    reads(e.timestampNs, e.reads)
                    e.stats?.let { engine(e.timestampNs, it, e.reads.size, droppedReads, counter.trackStats()) }
                }
            }
            is ArEvent.Luma -> counter.onLuma(e.timestampNs, e.img, LUMA_SCALE.toDouble())
            is ArEvent.Cmd -> counter.onCommand(e.command, lastFrameNs)
            is ArEvent.Resumed -> counter.onResume(e.timestampNs)
            is ArEvent.AnchorCreated -> if (e.id >= counterFirstId) {
                if (!e.ok && e.id == lastCreateId) {
                    anchorHeld = false
                    anchorWanted = null
                }
                counter.onAnchorCreated(e.ok)
            }
            is ArEvent.Reset -> {
                detach()
                anchorWanted = null
                closedSeen = 0
                counterFirstId = nextCreateId
                counter = e.counter
                counter.setItems(items)
                counter.onResume(lastFrameNs)
                recent.clearSeen()
                onCodes(recent.inView, recent.seen)
            }
            is ArEvent.Trace -> {
                recorder?.close()
                recorder = e.recorder
                tracing = e.recorder != null
            }
            is ArEvent.Items -> {
                items = e.codes
                counter.setItems(e.codes)
            }
            is ArEvent.Diag -> {
                recorder?.diag(e.line)
                return null
            }
        }
        return afterCall()
    }

    private fun addRecent(timestampNs: Long, reads: List<Read>) {
        recent.add(timestampNs, reads)
        latestReads.set(recent.reads)
        val last = codesPublishedNs
        if (last == null || timestampNs < last || timestampNs - last >= RecentReads.PUBLISH_EVERY_NS) {
            codesPublishedNs = timestampNs
            onCodes(recent.inView, recent.seen)
        }
    }

    private fun afterCall(): CountView {
        val view = counter.view()
        desiredRefreshMs = view.desiredRefreshMs
        if (view.closed.size > closedSeen || view.state == SectionState.IDLE || view.state == SectionState.CLOSED) detach()
        closedSeen = view.closed.size
        val request = counter.anchorRequest()
        if (request != null && request != anchorWanted) {
            detach()
            lastCreateId = nextCreateId++
            anchorOps.add(AnchorOp.Create(request, lastCreateId))
            anchorWanted = request
            anchorHeld = true
        }
        return view
    }

    private fun detach() {
        if (!anchorHeld) return
        anchorOps.add(AnchorOp.Detach)
        anchorHeld = false
    }

    private companion object {
        const val THREAD = "ArMapper"
        const val PIN_READS_CAP = 8
    }
}
