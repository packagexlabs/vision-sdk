package io.packagex.visiondemo.ar

import android.util.Log
import io.packagex.arcount.AnchorRequest
import io.packagex.arcount.ArCounter
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
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
    data class Reads(val timestampNs: Long, val reads: List<Read>, val stats: EngineStats? = null) : ArEvent

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
    private val log: (String, Throwable) -> Unit = { what, t -> Log.e(THREAD, what, t) },
) {
    private val lock = Object()
    private val queue = ArrayDeque<ArEvent>()
    private var queuedReads = 0
    private var stopped = false
    private val dropped = AtomicLong()
    private val latest = AtomicReference(CountView.EMPTY)
    private var worker: Thread? = null

    /** For the GL thread, in order */
    val anchorOps = ConcurrentLinkedQueue<AnchorOp>()

    /** Reads batches dropped because [readsCapacity] were already waiting */
    val droppedReads: Long get() = dropped.get()

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

    /** The newest view (GL thread) */
    fun latestView(): CountView = latest.get()

    fun post(event: ArEvent) {
        synchronized(lock) {
            if (stopped) return
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
                view = handle(e)
            } catch (t: Throwable) {
                log("the counter failed on ${e::class.simpleName}", t)
            }
        }
        view?.let { latest.set(it); onView(it) }
    }

    private fun handle(e: ArEvent): CountView {
        when (e) {
            is ArEvent.Frame -> {
                lastFrameNs = e.record.timestampNs
                counter.onFrame(e.record)
                recorder?.frame(e.record)
            }
            is ArEvent.Reads -> {
                counter.onReads(e.timestampNs, e.reads)
                recorder?.run {
                    reads(e.timestampNs, e.reads)
                    e.stats?.let { engine(e.timestampNs, it, e.reads.size, droppedReads) }
                }
            }
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
                counter.onResume(lastFrameNs)
            }
            is ArEvent.Trace -> {
                recorder?.close()
                recorder = e.recorder
            }
        }
        return afterCall()
    }

    private fun afterCall(): CountView {
        val view = counter.view()
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
    }
}
