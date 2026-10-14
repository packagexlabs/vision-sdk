package io.packagex.visiondemo.ar

import io.packagex.arcount.LumaImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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

    @Test fun rowsAreAveragedFourAcrossAndRoundedAndRowPaddingIsSkipped() {
        val src = byteArrayOf(10, 11, 12, 14, 200.toByte(), 200.toByte(), 201.toByte(), 201.toByte(), 99, /* padding */ 1, 2, 3, 4, 5, 6, 7, 8, 9, 99)
        val img = downscaleRows4(src, 8, 2, 9)
        assertEquals(2, img.width); assertEquals(2, img.height)
        assertEquals(12, px(img, 0, 0)) // 47 / 4 = 11.75
        assertEquals(201, px(img, 1, 0)) // 200.5, rounded up
        assertEquals(3, px(img, 0, 1)); assertEquals(7, px(img, 1, 1)) // 2.5 and 6.5, rounded up
    }

    @Test fun theCameraThreadKeepsRowTwoOfEachFourAndTheImageIsAQuarterEachWay() {
        val got = Collections.synchronizedList(mutableListOf<LumaImage>())
        val done = CountDownLatch(1)
        val copier = LumaCopier { _, img -> got += img; done.countDown() }
        val w = 16
        val h = 8
        val stride = 20
        val plane = ByteBuffer.allocateDirect(stride * h)
        for (y in 0 until h) for (x in 0 until stride) plane.put(y * stride + x, (if (x < w) y * 10 else 255).toByte())
        copier.offer(1L, plane, w, h, stride)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        copier.close()
        val img = got[0]
        assertEquals(4, img.width); assertEquals(2, img.height)
        assertArrayEquals(byteArrayOf(20, 20, 20, 20, 60, 60, 60, 60), img.data) // rows 2 and 6
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
                downscaleRows4(src, w, h, stride)
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

    /** 2026-10-08: a new 960x540 array a frame was 14 MB/s of large objects for the GC */
    @Test fun aFreedCopysPixelsGoToTheNextCopyOnceAndAHeldOnesNever() {
        val pool = LumaPool()
        val src = ByteArray(64) { 40 }
        val a = pool.downscale(src, 8, 8, 8)
        assertNotSame(a.data, pool.downscale(src, 8, 8, 8).data) // a is held
        a.free(); a.free()
        val c = pool.downscale(src, 8, 8, 8)
        assertSame(a.data, c.data)
        assertArrayEquals(ByteArray(16) { 40 }, c.data)
        assertNotSame(a.data, pool.downscale(src, 8, 8, 8).data) // freed twice, reused once
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
        // What LumaCopier does now: one row in four on the camera thread, then the 4x1 average
        val rows = h / LUMA_SCALE
        val kept = ByteArray(rows * w)
        fun rowCopyMs(): Double {
            val t = System.nanoTime()
            val src = plane.duplicate()
            src.clear()
            for (j in 0 until rows) {
                src.position((j * LUMA_SCALE + LUMA_ROW) * w)
                src.get(kept, j * w, w)
            }
            return (System.nanoTime() - t) / 1e6
        }
        fun rowScaleMs(): Double {
            val t = System.nanoTime()
            downscaleRows4(kept, w, rows, w)
            return (System.nanoTime() - t) / 1e6
        }
        repeat(20) { copyMs(); scaleMs(); rowCopyMs(); rowScaleMs() } // warm-up: the JIT
        val copies = List(30) { copyMs() }.sorted()
        val scales = List(30) { scaleMs() }.sorted()
        val rowCopies = List(30) { rowCopyMs() }.sorted()
        val rowScales = List(30) { rowScaleMs() }.sorted()
        println("luma copy 4K on the JVM: whole plane copy median %.2f ms p90 %.2f ms, 4x4 downscale median %.2f ms p90 %.2f ms".format(copies[15], copies[27], scales[15], scales[27]))
        println("luma copy 4K on the JVM: 1-in-4 rows copy median %.2f ms p90 %.2f ms, 4x1 downscale median %.2f ms p90 %.2f ms".format(rowCopies[15], rowCopies[27], rowScales[15], rowScales[27]))
    }
}
