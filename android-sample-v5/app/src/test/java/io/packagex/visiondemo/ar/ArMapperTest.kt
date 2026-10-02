package io.packagex.visiondemo.ar

import io.packagex.arcount.AnchorRequest
import io.packagex.arcount.ArCounter
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import io.packagex.arcount.Intrinsics
import io.packagex.arcount.LumaImage
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Read
import io.packagex.arcount.SectionResult
import io.packagex.arcount.SectionState
import io.packagex.arcount.SectionStatus
import io.packagex.arcount.Tracking
import io.packagex.arcount.Vec3
import io.packagex.arcount.Quat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread

class ArMapperTest {
    /** Logs every counter call and the thread it came on; answers [request] until an anchor is created. */
    private class RecordingCounter(var request: AnchorRequest? = null, var view: CountView = CountView.EMPTY) : ArCounter {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val threads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        private fun log(call: String) {
            calls += call
            threads += Thread.currentThread().name
        }

        override fun onResume(timestampNs: Long) = log("resume $timestampNs")
        override fun onFrame(frame: PoseRecord) = log("frame ${frame.timestampNs}")
        override fun onReads(timestampNs: Long, reads: List<Read>) = log("reads $timestampNs")
        override fun onCommand(command: Command, timestampNs: Long) = log("command $command $timestampNs")
        override fun onLuma(timestampNs: Long, img: LumaImage, streamPxPerLumaPx: Double) = log("luma $timestampNs x$streamPxPerLumaPx")
        override fun onAnchorCreated(ok: Boolean) {
            log("anchor $ok")
            if (ok) request = null
        }
        override fun anchorRequest(): AnchorRequest? = request.also { threads += Thread.currentThread().name }
        override fun view(): CountView = view.also { threads += Thread.currentThread().name }
    }

    private fun frame(ts: Long) = ArEvent.Frame(PoseRecord(ts, Pose.IDENTITY, null, Tracking.TRACKING, null, Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)))
    private fun reads(ts: Long) = ArEvent.Reads(ts, emptyList())
    private fun request(z: Double) = AnchorRequest(Pose(Vec3(0.0, 0.0, z), Quat.IDENTITY))
    private val open = CountView.EMPTY.copy(state = SectionState.OPEN)
    private val result = SectionResult("s1", null, setOf("1"), SectionStatus.COMPLETE, 3, 0, 0, 0, 0, 3, 3, emptyList(), 1000)

    private fun awaitCalls(c: RecordingCounter, n: Int) {
        val until = System.nanoTime() + 5_000_000_000L
        while (c.calls.size < n && System.nanoTime() < until) Thread.sleep(2)
    }

    @Test fun theCounterGetsEveryEventInPostedOrderOnTheMapperThread() {
        val c = RecordingCounter()
        val m = ArMapper(c).apply { start() }
        listOf(ArEvent.Resumed(1), frame(1), reads(1), frame(2), ArEvent.Cmd(Command.Finish), reads(2), frame(3)).forEach(m::post)
        awaitCalls(c, 7)
        m.close()
        assertEquals(listOf("resume 1", "frame 1", "reads 1", "frame 2", "command Finish 2", "reads 2", "frame 3"), c.calls)
        assertEquals(setOf("ArMapper"), c.threads)
    }

    @Test fun framesAndReadsFromTwoThreadsStayInOrder() {
        val c = RecordingCounter()
        val m = ArMapper(c).apply { start() }
        val gl = thread(name = "GL") { for (ts in 1L..300L) m.post(frame(ts)) }
        val engine = thread(name = "Engine") { for (ts in 1L..100L) { m.post(reads(ts)); if (ts % 10 == 0L) Thread.sleep(1) } }
        gl.join(); engine.join()
        awaitCalls(c, 300 + 100 - m.droppedReads.toInt())
        m.close()
        val frames = c.calls.filter { it.startsWith("frame") }.map { it.removePrefix("frame ").toLong() }
        val reads = c.calls.filter { it.startsWith("reads") }.map { it.removePrefix("reads ").toLong() }
        assertEquals((1L..300L).toList(), frames)
        assertEquals(reads.sorted(), reads)
        assertEquals(100L, reads.size + m.droppedReads)
        assertEquals(setOf("ArMapper"), c.threads)
    }

    @Test fun aCounterThatThrowsLosesThatEventNotTheMapperThread() {
        val c = RecordingCounter(view = open)
        val failing = object : ArCounter by c {
            override fun onReads(timestampNs: Long, reads: List<Read>) {
                c.onReads(timestampNs, reads)
                error("counter bug")
            }
        }
        val logged = Collections.synchronizedList(mutableListOf<Throwable>())
        val m = ArMapper(failing, log = { _, t -> logged += t }).apply { start() }
        listOf(frame(1), reads(1), frame(2), reads(2), frame(3)).forEach(m::post)
        awaitCalls(c, 5)
        m.close()
        assertEquals(listOf("frame 1", "reads 1", "frame 2", "reads 2", "frame 3"), c.calls)
        assertEquals(listOf("counter bug", "counter bug"), logged.map { it.message })
        assertEquals(open, m.latestView())
    }

    @Test fun readsBeyondFourWaitingDropTheOldestButFramesNever() {
        val c = RecordingCounter()
        val m = ArMapper(c)
        m.post(frame(1)); (1L..6L).forEach { m.post(reads(it)) }; m.post(frame(2))
        m.drain()
        assertEquals(listOf("frame 1", "reads 3", "reads 4", "reads 5", "reads 6", "frame 2"), c.calls)
        assertEquals(2L, m.droppedReads)
    }

    private fun luma(ts: Long) = ArEvent.Luma(ts, LumaImage(2, 2, ByteArray(4)))

    @Test fun aLumaCopyReachesTheCounterInOrderAtFourStreamPixelsAPixelAndOnlyTwoWait() {
        val c = RecordingCounter()
        val m = ArMapper(c)
        m.post(frame(1)); m.post(luma(1)); m.post(reads(1))
        m.drain()
        assertEquals(listOf("frame 1", "luma 1 x4.0", "reads 1"), c.calls)
        c.calls.clear()
        (2L..5L).forEach { m.post(luma(it)) }; m.post(reads(5))
        m.drain()
        assertEquals(listOf("luma 4 x4.0", "luma 5 x4.0", "reads 5"), c.calls)
        assertEquals(2L, m.droppedLumas)
    }

    @Test fun theRefreshIsTheNewestViewsAfterEachEventAndNullBeforeAny() {
        val c = RecordingCounter()
        val m = ArMapper(c)
        assertEquals(null, m.desiredRefreshMs)
        m.post(frame(1)); m.drain()
        assertEquals(0, m.desiredRefreshMs)
        c.view = CountView.EMPTY.copy(desiredRefreshMs = 300)
        m.post(reads(1)); m.drain()
        assertEquals(300, m.desiredRefreshMs)
    }

    @Test fun anAnchorIsCreatedOncePerRequestAndLetGoWhenItsSectionCloses() {
        val c = RecordingCounter(request = request(-0.4), view = open)
        val m = ArMapper(c)
        m.post(frame(1)); m.drain()
        assertEquals(listOf<AnchorOp>(AnchorOp.Create(request(-0.4), 0)), m.anchorOps.toList()); m.anchorOps.clear()
        m.post(frame(2)); m.drain()   // still asked for, still in flight
        assertTrue(m.anchorOps.isEmpty())
        m.post(ArEvent.AnchorCreated(true, 0)); m.post(frame(3)); m.drain()
        assertTrue("anchor true" in c.calls); assertTrue(m.anchorOps.isEmpty())
        c.view = open.copy(state = SectionState.COUNTING, closed = listOf(result))
        m.post(frame(4)); m.drain()
        assertEquals(listOf<AnchorOp>(AnchorOp.Detach), m.anchorOps.toList())
    }

    @Test fun aNewRequestReplacesTheHeldAnchor() {
        val c = RecordingCounter(request = request(-0.4), view = open)
        val m = ArMapper(c)
        m.post(frame(1)); m.post(ArEvent.AnchorCreated(true, 0)); m.drain()
        m.anchorOps.clear()
        c.request = request(-0.5)   // a restart opens the section again
        m.post(frame(2)); m.drain()
        assertEquals(listOf(AnchorOp.Detach, AnchorOp.Create(request(-0.5), 1)), m.anchorOps.toList())
    }

    @Test fun aFailedAnchorMayBeAskedForAgain() {
        val c = RecordingCounter(request = request(-0.4), view = open)
        val m = ArMapper(c)
        m.post(frame(1)); m.drain(); m.anchorOps.clear()
        m.post(ArEvent.AnchorCreated(false, 0)); m.drain()
        assertTrue("anchor false" in c.calls)
        assertEquals(listOf<AnchorOp>(AnchorOp.Create(request(-0.4), 1)), m.anchorOps.toList())
    }

    @Test fun noAnchorIsHeldOutsideASection() {
        val c = RecordingCounter(request = request(-0.4), view = open)
        val m = ArMapper(c)
        m.post(frame(1)); m.post(ArEvent.AnchorCreated(true, 0)); m.drain()
        m.anchorOps.clear()
        c.view = CountView.EMPTY   // IDLE: the open section timed out
        m.post(frame(2)); m.drain()
        assertEquals(listOf<AnchorOp>(AnchorOp.Detach), m.anchorOps.toList())
    }

    @Test fun aResetSwapsTheCounterLetsTheAnchorGoAndStartsTheGuard() {
        val old = RecordingCounter(request = request(-0.4), view = open)
        val m = ArMapper(old)
        m.post(frame(7)); m.drain()   // Create 0, in flight
        m.anchorOps.clear()
        val new = RecordingCounter()
        m.post(ArEvent.Reset(new)); m.post(ArEvent.AnchorCreated(true, 0)); m.post(frame(8)); m.drain()
        assertEquals(listOf<AnchorOp>(AnchorOp.Detach), m.anchorOps.toList())
        assertEquals(listOf("resume 7", "frame 8"), new.calls)   // the old counter's anchor answer is not the new one's
        assertTrue("anchor true" !in old.calls)
    }

    @Test fun theNewestViewIsPublishedOncePerBatch() {
        val c = RecordingCounter(view = open)
        val seen = mutableListOf<CountView>()
        val m = ArMapper(c, onView = { seen += it })
        m.post(frame(1)); m.post(reads(1)); m.post(frame(2)); m.drain()
        assertEquals(listOf(open), seen)
        assertEquals(open, m.latestView())
    }
}
