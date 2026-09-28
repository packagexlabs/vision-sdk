package io.packagex.visiondemo.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArSessionRulesTest {
    @Test fun warmUpResetsOnNewSession() {
        val g = WarmUpGate(minFrames = 60); repeat(60) { g.onTrackedFrame() }; assertTrue(g.ready)
        g.onSessionStart(); assertFalse(g.ready)
    }

    @Test fun catalogReadIsASnapshot() {
        val c = CatalogSnapshot(); c.set(mapOf("A" to "Apple")); val seen = c.get(); c.set(emptyMap()); assertEquals("Apple", seen["A"])
    }

    @Test fun warmUpNeedsMinFrames() {
        val g = WarmUpGate(minFrames = 3); repeat(2) { g.onTrackedFrame() }; assertFalse(g.ready)
        g.onTrackedFrame(); assertTrue(g.ready)
    }

    // CPU image sizes (w x h) as ARCore reports them, all already filtered to 30 fps.
    @Test fun cameraConfigIsTheSmallestAtLeast1280Wide() {
        assertEquals(1, pickCameraConfig(listOf(640 to 480, 1280 to 720, 1920 to 1080, 3840 to 2160)))
        assertEquals(1, pickCameraConfig(listOf(640 to 480, 1440 to 1080, 1920 to 1080)))
    }

    @Test fun cameraConfigFallsBackToTheLargestWhenNoneIs1280Wide() {
        assertEquals(1, pickCameraConfig(listOf(640 to 480, 960 to 720)))
        assertEquals(0, pickCameraConfig(listOf(2560 to 1440, 3840 to 2160)))
        assertNull(pickCameraConfig(emptyList()))
    }

    @Test fun payloadCountsToRowsCarryNames() {
        val rows = arRows(listOf(PayloadCount("A1", "EAN-13", 2), PayloadCount("B2", "QR", 1)), mapOf("A1" to "Apple"))
        assertEquals(listOf(ArRow("A1", "EAN-13", 2, "Apple"), ArRow("B2", "QR", 1, null)), rows)
    }
}
