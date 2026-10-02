package io.packagex.visiondemo.ar

import io.packagex.arcount.Command
import io.packagex.arcount.Intrinsics
import io.packagex.arcount.Marker
import io.packagex.arcount.Pose
import io.packagex.arcount.PoseRecord
import io.packagex.arcount.Read
import io.packagex.arcount.SectionState
import io.packagex.arcount.Tracking
import io.packagex.arcount.UnitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCounterTest {
    private val ms = 1_000_000L

    private fun frame(ts: Long) = PoseRecord(ts, Pose.IDENTITY, null, Tracking.TRACKING, null, Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160))

    /** A [w] px wide symbol centred at ([cx], [cy]) of the 4K image */
    private fun read(ts: Long, text: String, cx: Double, cy: Double, w: Double = 200.0) =
        Read(ts, text, listOf(cx - w / 2, cy - 20, cx + w / 2, cy - 20, cx + w / 2, cy + 20, cx - w / 2, cy + 20), engineId = 1)

    @Test fun everyReadIsAMarkerAtItsCentreForHalfASecond() {
        val c = DebugCounter()
        c.onFrame(frame(0))
        c.onReads(10 * ms, listOf(read(10 * ms, "A", 1920.0, 1080.0), read(10 * ms, "B", 960.0, 540.0)))
        assertEquals(
            listOf(Marker(0, UnitState.TENTATIVE, 0.5, 0.5, 200.0 / 3840), Marker(1, UnitState.TENTATIVE, 0.25, 0.25, 200.0 / 3840)),
            c.view().markers,
        )
        c.onFrame(frame(510 * ms)); assertEquals(2, c.view().markers.size)   // 500 ms old
        c.onFrame(frame(511 * ms)); assertTrue(c.view().markers.isEmpty())
    }

    @Test fun theBracketCountsDistinctTexts() {
        val c = DebugCounter()
        assertNull(c.view().bracket)
        c.onFrame(frame(0))
        c.onReads(1 * ms, listOf(read(1 * ms, "A", 100.0, 100.0), read(1 * ms, "A", 900.0, 100.0)))
        c.onReads(2 * ms, listOf(read(2 * ms, "B", 100.0, 100.0)))
        val b = c.view().bracket!!
        assertEquals(2, b.countLow); assertEquals(2, b.countHigh)
        assertEquals(false, b.inImage); assertNull(b.gtin); assertEquals(false, b.frozen)
    }

    @Test fun noSectionsNoPromptNoAnchorAndCommandsDoNothing() {
        val c = DebugCounter()
        c.onResume(0); c.onFrame(frame(0)); c.onReads(1 * ms, listOf(read(1 * ms, "A", 100.0, 100.0)))
        val before = c.view()
        listOf(Command.Finish, Command.Restart, Command.AcceptRange, Command.AddUnit, Command.RemoveManualUnit, Command.TriggerShort).forEach { c.onCommand(it, 2 * ms) }
        assertEquals(before, c.view())
        assertEquals(SectionState.IDLE, before.state); assertNull(before.prompt)
        assertTrue(before.gaps.isEmpty()); assertTrue(before.closed.isEmpty()); assertNull(c.anchorRequest())
    }

    @Test fun aReadBeforeAnyFrameCountsButHasNoMarker() {
        val c = DebugCounter()
        c.onReads(1 * ms, listOf(read(1 * ms, "A", 100.0, 100.0)))
        assertTrue(c.view().markers.isEmpty()); assertEquals(1, c.view().bracket?.countLow)
    }
}
