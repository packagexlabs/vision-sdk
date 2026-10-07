package io.packagex.visiondemo.ar

import com.example.barcodescanner.FrameBarcode
import com.example.barcodescanner.FrameStats
import com.example.barcodescanner.ScanFrame
import com.example.barcodescanner.Symbology
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadsTest {
    // A quad in a 1215 x 2160 upright frame (4K turned 90 degrees), corners 0..1 (the spike's UprightQuadTest)
    private val quad = floatArrayOf(0.1f, 0.2f, 0.3f, 0.2f, 0.3f, 0.25f, 0.1f, 0.25f)

    @Test fun mapsEveryCornerAsTheCentreIsMapped() {
        val raw = uprightQuadToRaw(quad, 1215, 2160, 90, 3840, 2160)
        // Upright centre (0.2 * 1215, 0.225 * 2160) = (243, 486) -> raw (v, rawHeight - u) = (486, 1917)
        assertArrayEquals(doubleArrayOf(486.0, 1917.0), doubleArrayOf((raw[0] + raw[2] + raw[4] + raw[6]) / 4, (raw[1] + raw[3] + raw[5] + raw[7]) / 4), 1e-3)
    }

    @Test fun turnsAQuarterTurnBack() {
        // Upright (u, v) = (0.1 * 1215, 0.2 * 2160) = (121.5, 432) -> raw (v, rawHeight - u) = (432, 2038.5)
        val raw = uprightQuadToRaw(quad, 1215, 2160, 90, 3840, 2160)
        assertArrayEquals(doubleArrayOf(432.0, 2038.5), doubleArrayOf(raw[0], raw[1]), 1e-3)
    }

    @Test fun mapsEachRotationBack() {
        val box = floatArrayOf(0.2f, 0.45f, 0.3f, 0.45f, 0.3f, 0.55f, 0.2f, 0.55f)   // tl at (0.2, 0.45) of the upright frame
        // 0: upright == raw 1920 x 1080
        assertArrayEquals(doubleArrayOf(384.0, 486.0), uprightQuadToRaw(box, 1920, 1080, 0, 1920, 1080).copyOf(2), 1e-3)
        // 180: (1920 - 384, 1080 - 486)
        assertArrayEquals(doubleArrayOf(1536.0, 594.0), uprightQuadToRaw(box, 1920, 1080, 180, 1920, 1080).copyOf(2), 1e-3)
        // 270: upright 1080 x 1920, tl (216, 864) -> (rawWidth - v, u) = (1056, 216)
        assertArrayEquals(doubleArrayOf(1056.0, 216.0), uprightQuadToRaw(box, 1080, 1920, 270, 1920, 1080).copyOf(2), 1e-3)
    }

    @Test fun onlyWhatTheFrameDecodedIsARead() {
        val box = floatArrayOf(0.4f, 0.4f, 0.6f, 0.4f, 0.6f, 0.5f, 0.4f, 0.5f)
        val frame = ScanFrame(
            listOf(
                FrameBarcode(1, null, null, box),                                           // a detector box
                FrameBarcode(2, "TRACK", Symbology.CODE_128, box),                          // a track this frame did not decode
                FrameBarcode(3, "B", Symbology.EAN_13, box, raw = box, rawText = "A"),      // shows B, decoded A here
                FrameBarcode(4, null, null, box, raw = box, rawText = null),
                FrameBarcode(5, "", null, box, raw = box, rawText = ""),
            ),
            2160, 3840, FrameStats(30f, 5, 1, 1f, 10f, 5f),
        )
        val reads = readsOf(frame, rotationDegrees = 90, rawWidth = 3840, rawHeight = 2160, timestampNs = 77L)
        assertEquals(1, reads.size)
        val r = reads.single()
        assertEquals("A", r.text)   // rawText, never the track's text
        assertEquals(3, r.engineId); assertEquals("ean13", r.symbology); assertEquals(77L, r.timestampNs)
        // Upright tl (0.4 * 2160, 0.4 * 3840) = (864, 1536) -> raw (v, rawHeight - u) = (1536, 1296)
        assertEquals(1536.0, r.corners[0], 1e-3); assertEquals(1296.0, r.corners[1], 1e-3)
        assertFalse(r.touchesBorder)
        // The boxes this frame did not decode are tracked boxes, at the same pixels: the detector box with no text, the track with its own
        val tracked = trackedOf(frame, rotationDegrees = 90, rawWidth = 3840, rawHeight = 2160, timestampNs = 77L)
        assertEquals(2, tracked.size)
        val (d, t) = tracked
        assertEquals("", d.text); assertEquals(1, d.engineId); assertEquals(r.corners, d.corners)
        assertEquals("TRACK", t.text); assertEquals(2, t.engineId); assertEquals(r.corners, t.corners)
    }

    @Test fun aQuadAtTheBorderIsFlagged() {
        val cut = floatArrayOf(0.0003f, 0.4f, 0.1f, 0.4f, 0.1f, 0.5f, 0.0003f, 0.5f)   // tl x = 0.0003 * 3840 = 1.2 px
        val frame = ScanFrame(listOf(FrameBarcode(1, "A", Symbology.CODE_128, cut, raw = cut, rawText = "A")), 3840, 2160, FrameStats(30f, 1, 1, 1f, 1f, 1f))
        assertTrue(readsOf(frame, 0, 3840, 2160, 1L).single().touchesBorder)
    }

    @Test fun borderIsTwoPixels() {
        fun quadAt(x: Double, y: Double) = listOf(x, y, 100.0, y, 100.0, 100.0, x, 100.0)
        assertTrue(touchesBorder(quadAt(2.0, 50.0), 3840, 2160))
        assertFalse(touchesBorder(quadAt(2.01, 50.0), 3840, 2160))
        assertTrue(touchesBorder(quadAt(50.0, 0.0), 3840, 2160))
        assertTrue(touchesBorder(listOf(3000.0, 50.0, 3838.0, 50.0, 3838.0, 90.0, 3000.0, 90.0), 3840, 2160))
        assertTrue(touchesBorder(listOf(3000.0, 2100.0, 3100.0, 2100.0, 3100.0, 2158.0, 3000.0, 2158.0), 3840, 2160))
        assertFalse(touchesBorder(listOf(3000.0, 2100.0, 3100.0, 2100.0, 3100.0, 2157.9, 3000.0, 2157.9), 3840, 2160))
    }

    @Test fun engineStatsCarryTheFrameStats() {
        assertEquals(EngineStats(12f, 5f, 20f, 18f, 4, 3, 47f, 9L), FrameStats(12f, 4, 3, 5f, 20f, 18f).toEngineStats(47f, 9L))
    }
}
