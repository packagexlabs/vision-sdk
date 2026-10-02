package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private val k = Intrinsics(2896.0, 2896.0, 1920.0, 1080.0, 3840, 2160)
    private val frameNs = 33_333_333L

    private fun record(ts: Long, x: Double = 0.0, tracking: Tracking = Tracking.TRACKING) =
        PoseRecord(ts, Pose(Vec3(x, 0.0, 0.0), Quat.IDENTITY), null, tracking, null, k, 10_000_000L)

    private fun read(ts: Long) = Read(ts, "4006381333931", listOf(1.0, 1.0, 3.0, 1.0, 3.0, 3.0, 1.0, 3.0), 7)

    @Test
    fun aReadWhoseFrameIsAlreadyHerePairsAtOnce() {
        val p = Pairing()
        p.addRecord(record(1_000))
        val paired = p.addReads(1_000, listOf(read(1_000)))!!
        assertEquals(1_000L, paired.record.timestampNs)
        assertEquals(1, paired.reads.size)
    }

    @Test
    fun aReadThatComesBeforeItsFrameWaitsAndPairsWhenTheFrameComes() {
        val p = Pairing()
        p.addRecord(record(0))
        assertNull(p.addReads(frameNs, listOf(read(frameNs))))
        val paired = p.addRecord(record(frameNs))
        assertEquals(1, paired.size)
        assertEquals(frameNs, paired[0].record.timestampNs)
        assertEquals(0, p.droppedReads)
    }

    @Test
    fun waitingReadsPairInTimestampOrder() {
        val p = Pairing()
        assertNull(p.addReads(2 * frameNs, listOf(read(2 * frameNs))))
        assertNull(p.addReads(frameNs, listOf(read(frameNs))))
        assertTrue(p.addRecord(record(0)).isEmpty())
        assertEquals(listOf(frameNs), p.addRecord(record(frameNs)).map { it.record.timestampNs })
        assertEquals(listOf(2 * frameNs), p.addRecord(record(2 * frameNs)).map { it.record.timestampNs })
    }

    @Test
    fun aReadWithNoFrameWithin100msOfRecordTimeIsDroppedAndCounted() {
        val p = Pairing()
        p.addRecord(record(0))
        assertNull(p.addReads(frameNs, listOf(read(frameNs), read(frameNs))))
        // ARCore stalls: the next record comes 150 ms after the read's capture, too late to pair or interpolate
        val paired = p.addRecord(record(frameNs + 150_000_000L))
        assertTrue(paired.isEmpty())
        assertEquals(2, p.droppedReads)
    }

    @Test
    fun aReadOfACaptureArcoreSkippedPairsWithThePoseBetweenItsNeighbours() {
        val p = Pairing()
        p.addRecord(record(0, x = 0.0))
        assertNull(p.addReads(frameNs, listOf(read(frameNs))))
        // the record of frameNs never comes: ARCore skipped that capture (spec 5.2: interpolate between neighbours)
        val paired = p.addRecord(record(2 * frameNs, x = 0.002))
        assertEquals(1, paired.size)
        assertTrue(paired[0].interpolated)
        assertEquals(frameNs, paired[0].record.timestampNs)
        assertEquals(0.001, paired[0].record.camera.t.x, 1e-9)
    }

    @Test
    fun noInterpolationAcrossAJump() {
        val p = Pairing()
        p.addRecord(record(0, x = 0.0))
        assertNull(p.addReads(frameNs, listOf(read(frameNs))))
        assertTrue(p.addRecord(record(2 * frameNs, x = 0.06)).isEmpty())
        assertEquals(1, p.droppedReads)
    }

    @Test
    fun noInterpolationWhenANeighbourIsNotTracking() {
        val p = Pairing()
        p.addRecord(record(0, tracking = Tracking.PAUSED))
        assertNull(p.addReads(frameNs, listOf(read(frameNs))))
        assertTrue(p.addRecord(record(2 * frameNs)).isEmpty())
        assertEquals(1, p.droppedReads)
    }

    @Test
    fun aReadOlderThanTheTwoSecondRingIsDropped() {
        val p = Pairing()
        p.addRecord(record(0))
        p.addRecord(record(3_000_000_000L))
        assertNull(p.addReads(frameNs, listOf(read(frameNs))))
        assertEquals(1, p.droppedReads)
    }
}
