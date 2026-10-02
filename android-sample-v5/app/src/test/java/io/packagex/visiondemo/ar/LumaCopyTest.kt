package io.packagex.visiondemo.ar

import io.packagex.arcount.LumaImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LumaCopyTest {
    private fun px(img: LumaImage, x: Int, y: Int) = img.data[y * img.rowStride + x].toInt() and 0xFF

    @Test fun eachPixelIsTheRoundedMeanOfItsFourByFourBlockAndRowPaddingIsSkipped() {
        val w = 8
        val h = 4
        val stride = 10 // two bytes of padding a row, which must not be read
        val src = ByteArray(stride * h) { 0xEE.toByte() }
        for (y in 0 until h) for (x in 0 until w) src[y * stride + x] = (if (x < 4) 200 + y else x * 30).toByte()
        val img = downscaleLuma4(src, w, h, stride)
        assertEquals(2, img.width); assertEquals(1, img.height); assertEquals(2, img.rowStride)
        assertEquals(202, px(img, 0, 0)) // mean of 200..203, four of each: 201.5, rounded up
        assertEquals(165, px(img, 1, 0)) // 120, 150, 180, 210 over four rows: 165
    }

    @Test fun bytesAboveOneTwentySevenAreUnsignedAndPartialBlocksAreLeftOut() {
        val src = ByteArray(9 * 7) { 255.toByte() }
        val img = downscaleLuma4(src, 9, 7, 9)
        assertEquals(2, img.width); assertEquals(1, img.height)
        assertEquals(255, px(img, 0, 0)); assertEquals(255, px(img, 1, 0))
    }

    @Test fun aFourKPlaneBecomesNineSixtyByFiveFortyAndKeepsAGradient() {
        val w = 3840
        val h = 2160
        val src = ByteArray(w * h) { i -> ((i % w) / 16).toByte() }
        val img = downscaleLuma4(src, w, h, w)
        assertEquals(960, img.width); assertEquals(540, img.height)
        assertEquals(0, px(img, 0, 0)); assertEquals(239, px(img, 959, 539)); assertEquals(px(img, 500, 0), px(img, 500, 300))
    }

    @Test fun theCopyIsMadeBeforeOfferReturnsSoThePlaneCanBeReusedAtOnce() {
        val got = Collections.synchronizedList(mutableListOf<Pair<Long, LumaImage>>())
        val done = CountDownLatch(1)
        val copier = LumaCopier { ts, img -> got += ts to img; done.countDown() }
        val plane = ByteBuffer.allocateDirect(16 * 8)
        repeat(16 * 8) { plane.put(it, 100) }
        copier.offer(7L, plane, 16, 8, 16)
        repeat(16 * 8) { plane.put(it, 0) } // the camera's next image in the same buffer
        assertTrue(done.await(2, TimeUnit.SECONDS))
        copier.close()
        assertEquals(7L, got[0].first)
        assertArrayEquals(ByteArray(8) { 100 }, got[0].second.data)
        assertTrue(copier.copyNs > 0); assertEquals(1L, copier.frames)
    }

    @Test fun latestWinsWhileOneIsInWorkAndReadsOfAWaitingCopyGoAfterIt() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val inWork = CountDownLatch(1)
        val release = CountDownLatch(1)
        val last = CountDownLatch(1)
        var first = true
        val copier = LumaCopier(
            post = { ts, _ -> events += "luma $ts" },
            downscale = { src, w, h, stride ->
                if (first) { first = false; inWork.countDown(); release.await(2, TimeUnit.SECONDS) }
                downscaleLuma4(src, w, h, stride)
            },
        )
        val plane = ByteBuffer.allocateDirect(64)
        copier.offer(1L, plane, 8, 8, 8)
        assertTrue(inWork.await(2, TimeUnit.SECONDS)) // 1 is in work
        copier.offer(2L, plane, 8, 8, 8) // waits
        copier.offer(3L, plane, 8, 8, 8) // replaces 2
        copier.afterLuma(2L) { events += "reads 2" } // 2 was dropped: no luma comes, the reads go now
        copier.afterLuma(3L) { events += "reads 3"; last.countDown() } // waits for luma 3
        copier.afterLuma(1L) { events += "reads 1" } // waits for luma 1
        assertEquals(listOf("reads 2"), events.toList())
        release.countDown()
        assertTrue(last.await(2, TimeUnit.SECONDS))
        copier.close()
        assertEquals(listOf("reads 2", "luma 1", "reads 1", "luma 3", "reads 3"), events.toList())
        assertEquals(1L, copier.dropped); assertEquals(3L, copier.frames)
    }

    @Test fun afterCloseWaitingReadsRunAndOffersAreIgnored() {
        val copier = LumaCopier { _, _ -> }
        copier.close()
        var ran = false
        copier.afterLuma(5L) { ran = true }
        copier.offer(6L, ByteBuffer.allocateDirect(64), 8, 8, 8)
        assertTrue(ran); assertEquals(0L, copier.frames)
    }

    /** JVM cost of the two halves at 4K, for the report: the camera thread's bulk copy and the luma thread's downscale. */
    @Test fun costAtFourK() {
        val w = 3840
        val h = 2160
        val plane = ByteBuffer.allocateDirect(w * h)
        for (i in 0 until w * h) plane.put(i, (i * 31).toByte())
        val dst = ByteArray(w * h)
        fun copyMs(): Double {
            val t = System.nanoTime()
            val src = plane.duplicate()
            src.clear()
            src.get(dst, 0, w * h)
            return (System.nanoTime() - t) / 1e6
        }
        fun scaleMs(): Double {
            val t = System.nanoTime()
            downscaleLuma4(dst, w, h, w)
            return (System.nanoTime() - t) / 1e6
        }
        repeat(20) { copyMs(); scaleMs() } // warm-up: the JIT
        val copies = List(30) { copyMs() }.sorted()
        val scales = List(30) { scaleMs() }.sorted()
        println("luma copy 4K on the JVM: copy median %.2f ms p90 %.2f ms; downscale median %.2f ms p90 %.2f ms".format(copies[15], copies[27], scales[15], scales[27]))
    }
}
