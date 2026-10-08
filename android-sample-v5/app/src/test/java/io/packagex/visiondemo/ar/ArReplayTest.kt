package io.packagex.visiondemo.ar

import io.packagex.arcount.Read
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ArReplayTest {
    private fun read(ts: Long, text: String, symbology: String? = "code128", border: Boolean = false) =
        Read(ts, text, listOf(1.5, 2.25, 300.0, 4.0, 301.0, 80.5, 2.0, 79.0), 7, symbology, border)

    private val stats = EngineStats(
        fps = 23.5f, prepareMs = 9.1f, detectMs = 24.6f, decodeMs = 13.2f, barcodes = 14, decoded = 12, scanMs = 41.0f,
        droppedImages = 3, refreshAfterMs = 250, pipe = PipeCounters(100, 2_000_000, 3_000_000, 4, 5),
    )
    private val meta = CaptureMeta(1_000L, 33_000_000L, 800, 32_540_676L, 0, "[30, 30]", 2, 0.76f)

    @Test fun aSampleComesBackAsItWasWritten() {
        val sample = TrackSample(
            frameCameraNs = 2_000L,
            entries = listOf(
                TrackEntry.Items(setOf("UNIT-A", "02000000000107", "UNIT %T")),
                TrackEntry.Reads(ArEvent.Reads(1_000L, listOf(read(1_000L, "UNIT-A"), read(1_000L, "ünït", null, true)), stats, listOf(read(1_000L, ""))), meta),
                TrackEntry.Reads(ArEvent.Reads(1_033L, emptyList()), null),
            ),
        )
        assertEquals(sample, decodeSample(ByteBuffer.wrap(encodeSample(sample))))
    }

    @Test fun aSampleFromAnotherVersionIsRefused() {
        val bytes = encodeSample(TrackSample(1L, emptyList())).also { it[0] = 9 }
        val error = runCatching { decodeSample(ByteBuffer.wrap(bytes)) }.exceptionOrNull()
        assertTrue("$error", error is IllegalArgumentException)
    }

    @Test fun theQueueHandsEachFrameWhatCameSinceTheLastOne() {
        val q = TrackQueue()
        assertNull(q.drain(1L)) // nothing waited: no sample on this frame
        val first = TrackEntry.Reads(ArEvent.Reads(10L, listOf(read(10L, "A"))), meta)
        val second = TrackEntry.Items(setOf("A", "B"))
        q.offer(first)
        q.offer(second)
        val sample = decodeSample(ByteBuffer.wrap(q.drain(33L)!!))
        assertEquals(TrackSample(33L, listOf(first, second)), sample) // in the order they came, on the frame that took them
        assertNull(q.drain(66L)) // each entry once
        val third = TrackEntry.Reads(ArEvent.Reads(40L, emptyList()), null)
        q.offer(third)
        assertEquals(listOf<TrackEntry>(third), decodeSample(ByteBuffer.wrap(q.drain(99L)!!)).entries)
    }

    @Test fun theSidecarRoundTrips() {
        val info = RecordingInfo(AppStream.UHD, setOf("UNIT-A", "02000000000107"), """{"t":"cam","id":"0"}""")
        assertEquals(info, RecordingInfo.decode(info.encode()))
        assertEquals(RecordingInfo(AppStream.QHD, emptySet(), null), RecordingInfo.decode(RecordingInfo(AppStream.QHD, emptySet(), null).encode()))
        val mp4 = java.io.File("/sdcard/x/rec-20261007-150000.mp4")
        assertEquals("rec-20261007-150000.txt", RecordingInfo.sidecarOf(mp4).name)
    }

    /** 2026-10-08 12:21:05: ARCore's recorder crashed on a recording started 2.6 s after the last one stopped */
    @Test fun aRecordingStartsOnly3sAfterTheLastOneStopped() {
        assertEquals(0L, recordWaitMs(null, 500L)) // none stopped yet in this process
        assertEquals(400L, recordWaitMs(10_000L, 12_600L)) // the crash's gap: this run is not recorded
        assertEquals(0L, recordWaitMs(10_000L, 13_000L))
        assertEquals(0L, recordWaitMs(10_000L, 13_110L)) // the shortest gap that day that did not crash
    }
}
