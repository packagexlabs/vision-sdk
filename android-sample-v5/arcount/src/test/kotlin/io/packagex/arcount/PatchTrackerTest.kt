package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class PatchTrackerTest {
    /** The box the bars are drawn in: x0 until x1, y0 until y1 */
    private class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /** An image as 0..255 values, before they are rounded to bytes */
    private class Raster(val w: Int, val h: Int, val v: DoubleArray) {
        /** The value at (x, y), read bilinearly; coordinates outside the raster read its edge */
        fun at(x: Double, y: Double): Double {
            val cx = x.coerceIn(0.0, w - 1.0)
            val cy = y.coerceIn(0.0, h - 1.0)
            val x0 = min(cx.toInt(), w - 2)
            val y0 = min(cy.toInt(), h - 2)
            val fx = cx - x0
            val fy = cy - y0
            val i = y0 * w + x0
            val upper = (1 - fx) * v[i] + fx * v[i + 1]
            val lower = (1 - fx) * v[i + w] + fx * v[i + w + 1]
            return (1 - fy) * upper + fy * lower
        }

        /** The raster moved by (dx, dy): pixel (x, y) of the result is this one read at (x - dx, y - dy) */
        fun shifted(dx: Double, dy: Double) = Raster(w, h, DoubleArray(w * h) { k -> at(k % w - dx, k / w - dy) })

        /** A horizontal box blur of [length] px (odd), the edges repeated */
        fun blurredX(length: Int) = Raster(
            w,
            h,
            DoubleArray(w * h) { k ->
                var sum = 0.0
                for (i in -(length / 2)..length / 2) sum += v[k - k % w + (k % w + i).coerceIn(0, w - 1)]
                sum / length
            },
        )

        /** As bytes; the padding of each row, if any, is filled with a value no pixel should ever read */
        fun luma(rowStride: Int = w): LumaImage {
            val data = ByteArray(rowStride * h) { 0x55 }
            for (y in 0 until h) {
                for (x in 0 until w) data[y * rowStride + x] = v[y * w + x].roundToInt().coerceIn(0, 255).toByte()
            }
            return LumaImage(w, h, data, rowStride)
        }
    }

    /** A smooth random surface around 128: random values on a 10 px grid, interpolated: texture without sharp edges */
    private fun texture(rng: Random, w: Int, h: Int): Raster {
        val cell = 10
        val grid = DoubleArray((w / cell + 2) * (h / cell + 2)) { 128.0 + rng.nextDouble(-45.0, 45.0) }
        val coarse = Raster(w / cell + 2, h / cell + 2, grid)
        return Raster(w, h, DoubleArray(w * h) { k -> coarse.at((k % w).toDouble() / cell, (k / w).toDouble() / cell) })
    }

    /** One value per column: vertical bars of random widths 2..8 px (times [scale]), alternately about 30 and 220 */
    private fun bars(rng: Random, width: Int, scale: Int = 1): DoubleArray {
        val columns = DoubleArray(width)
        var x = 0
        var dark = rng.nextBoolean()
        while (x < width) {
            val value = (if (dark) 30.0 else 220.0) + rng.nextInt(-10, 11)
            val end = min(width, x + scale * rng.nextInt(2, 9))
            while (x < end) columns[x++] = value
            dark = !dark
        }
        return columns
    }

    /** A barcode on a textured surface: the bars of [barSeed] filling [box] */
    private fun barcode(box: Box, barSeed: Int, scale: Int = 1): Raster {
        val bars = bars(Random(barSeed), box.x1 - box.x0, scale)
        val v = background.v.copyOf()
        for (y in box.y0 until box.y1) {
            for (x in box.x0 until box.x1) v[y * W + x] = bars[x - box.x0]
        }
        return Raster(W, H, v)
    }

    private val background = texture(Random(3), W, H)

    /** Where the patch of [before] at (120, 80) is found in [after], searched from the seed */
    private fun track(before: Raster, after: Raster, seedX: Double, seedY: Double, half: Int = 20): Tracked? {
        val patch = PatchTracker.capture(before.luma(), 120.0, 80.0, half)!!
        return PatchTracker.track(patch, after.luma(), seedX, seedY)
    }

    @Test
    fun followsASubPixelShift() {
        val before = barcode(SHORT_BOX, 11)
        val found = track(before, before.shifted(3.4, -2.1), 120.0, 80.0)!!
        assertEquals(123.4, found.x, 0.1)
        assertEquals(77.9, found.y, 0.1)
        assertFalse(found.oneDimensional)
    }

    @Test
    fun aLargerShiftConvergesThroughThePyramid() {
        // A close-up: bars 4 times wider (8..32 px). Gradient descent reaches about as far as the bars are wide, so on
        // the 2..8 px bars of the other tests a 9 px shift is out of reach, pyramid or not. With this seed level 0
        // alone stays where it started.
        val before = barcode(SHORT_BOX, 8, scale = 4)
        val found = track(before, before.shifted(9.0, 5.0), 120.0, 80.0)!!
        assertEquals(129.0, found.x, 0.3)
        assertEquals(85.0, found.y, 0.3)
    }

    @Test
    fun toleratesMotionBlur() {
        val before = barcode(SHORT_BOX, 11)
        val found = track(before, before.shifted(2.0, 1.0).blurredX(7), 120.0, 80.0)!!
        assertEquals(122.0, found.x, 0.6)
        assertEquals(81.0, found.y, 0.6)
        assertTrue("ncc ${found.ncc}", found.ncc >= 0.6)
    }

    @Test
    fun anotherBarcodeIsNotAccepted() {
        // 61 px wide, all bars: two unrelated bar patterns still reach a correlation of 0.6 now and then (about 2 in
        // 100 here, 10 in 100 for a 41 px patch), once the search has fitted one to the other
        val a = barcode(TALL_BOX, 8)
        val patch = PatchTracker.capture(a.luma(), 120.0, 80.0, 30)!!
        assertNotNull("the same barcode is found", PatchTracker.track(patch, a.luma(), 120.0, 80.0))
        assertNull(PatchTracker.track(patch, barcode(TALL_BOX, 9).luma(), 120.0, 80.0))
    }

    @Test
    fun alongTheBarsOnlyTheSeedDecides() {
        val before = barcode(TALL_BOX, 11)
        val found = track(before, before.shifted(2.6, 1.3), 120.0, 83.0)!!
        assertTrue(found.oneDimensional)
        assertEquals(83.0, found.y, 1e-9)
        assertEquals(122.6, found.x, 0.1)
    }

    @Test
    fun alongTiltedBarsTheSeedStillDecides() {
        // Bars at 20 degrees, smooth so that the image has no structure along them. The patch's strong direction is
        // then about, not exactly, (cos, sin): the coordinate along the bars must stay on the seed all the same
        val angle = 0.35
        val sharp = bars(Random(21), 1000)
        val pattern = DoubleArray(sharp.size) { i -> (-1..1).sumOf { sharp[(i + it).coerceIn(0, sharp.size - 1)] } / 3 }
        val line = Raster(pattern.size, 2, pattern + pattern)
        fun tilted(offset: Double) = Raster(
            W,
            H,
            DoubleArray(W * H) { k -> line.at((k % W) * cos(angle) + (k / W) * sin(angle) - offset + 500, 0.0) },
        )
        val seedX = 120.0 - 3 * sin(angle)
        val seedY = 80.0 + 3 * cos(angle)
        val patch = PatchTracker.capture(tilted(0.0).luma(), 120.0, 80.0, 20)!!
        val found = PatchTracker.track(patch, tilted(3.0).luma(), seedX, seedY)!!
        assertTrue(found.oneDimensional)
        assertEquals(seedX + 3 * cos(angle), found.x, 0.1)
        assertEquals(seedY + 3 * sin(angle), found.y, 0.1)
        val axis = patch.fine.axes.single()
        assertEquals(0.0, (found.x - seedX) * -axis.y + (found.y - seedY) * axis.x, 1e-9)
    }

    @Test
    fun aFlatPatchCannotBeCaptured() {
        val flat = Raster(W, H, DoubleArray(W * H) { 100.0 })
        assertNull(PatchTracker.capture(flat.luma(), 120.0, 80.0, 20))
    }

    @Test
    fun theFlatLimitSeparatesNoiseFromFaintBars() {
        val rng = Random(5)
        val ramp = Raster(W, H, DoubleArray(W * H) { k -> 100.0 + 0.5 * (k % W) })
        val noise = Raster(W, H, DoubleArray(W * H) { 128.0 + rng.nextDouble(-3.0, 3.0) })
        val sharp = barcode(SHORT_BOX, 11)
        val faint = Raster(W, H, DoubleArray(W * H) { k -> 128.0 + (sharp.v[k] - 128.0) * 0.2 })
        assertNull("a ramp of half a grey level per pixel", PatchTracker.capture(ramp.luma(), 120.0, 80.0, 20))
        assertNull("noise of 1.7 grey levels rms", PatchTracker.capture(noise.luma(), 120.0, 80.0, 20))
        assertNotNull("bars of contrast 38", PatchTracker.capture(faint.luma(), 120.0, 80.0, 20))
    }

    @Test
    fun leavingTheImageIsLost() {
        val img = barcode(SHORT_BOX, 11).luma()
        assertNull(PatchTracker.capture(img, 15.0, 80.0, 20))
        assertNull(PatchTracker.capture(img, 120.0, 150.0, 20))
        val patch = PatchTracker.capture(img, 120.0, 80.0, 20)!!
        assertNotNull("inside the image it is found", PatchTracker.track(patch, img, 120.0, 80.0))
        assertNull(PatchTracker.track(patch, img, 225.0, 80.0))
        assertNull(PatchTracker.track(patch, img, 120.0, 150.0))
    }

    @Test
    fun aPaddedRowStrideReadsTheSame() {
        val before = barcode(SHORT_BOX, 11)
        val after = before.shifted(3.4, -2.1)
        val tight = PatchTracker.capture(before.luma(), 120.0, 80.0, 20)!!
        val padded = PatchTracker.capture(before.luma(W + 37), 120.0, 80.0, 20)!!
        val onTight = PatchTracker.track(tight, after.luma(), 120.0, 80.0)!!
        val onPadded = PatchTracker.track(padded, after.luma(W + 37), 120.0, 80.0)!!
        assertEquals(onTight.x, onPadded.x, 0.0)
        assertEquals(onTight.y, onPadded.y, 0.0)
        assertEquals(onTight.ncc, onPadded.ncc, 0.0)
    }

    @Test
    fun timing() {
        val w = 960
        val h = 540
        val surface = texture(Random(3), w, h).v
        val bars = bars(Random(11), 240)
        for (y in 258 until 282) {
            for (x in 360 until 600) surface[y * w + x] = bars[x - 360]
        }
        val before = Raster(w, h, surface)
        val patch = PatchTracker.capture(before.luma(), 480.0, 270.0, 20)!!
        val after = before.shifted(3.4, -2.1).luma()
        repeat(500) { PatchTracker.track(patch, after, 480.0, 270.0)!! }
        var checksum = 0.0
        val start = System.nanoTime()
        repeat(2000) { checksum += PatchTracker.track(patch, after, 480.0, 270.0)!!.x / 2000 }
        val micros = (System.nanoTime() - start) / 2000 / 1000.0
        println("PatchTracker.track, 41x41 patch on ${w}x$h: ${"%.1f".format(micros)} us per call (x $checksum)")
    }

    private companion object {
        const val W = 240
        const val H = 160

        /** 24 px tall: a patch of half 20 at (120, 80) holds its top and bottom edges, so y is measurable */
        val SHORT_BOX = Box(40, 68, 200, 92)

        /** Taller than any patch used here: the bars have no structure along y */
        val TALL_BOX = Box(40, 20, 200, 140)
    }
}
