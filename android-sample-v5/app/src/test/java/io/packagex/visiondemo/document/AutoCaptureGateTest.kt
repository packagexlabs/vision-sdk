package io.packagex.visiondemo.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureGateTest {
    private val page = listOf(10f to 10f, 90f to 10f, 90f to 90f, 10f to 90f)

    /** Frames until the gate fires (or -1 within [max]). */
    private fun AutoCaptureGate.framesToFire(max: Int = 40, valid: Boolean = true, auto: Boolean = true): Int {
        for (i in 1..max) if (frame(valid, inside = true, page, 100, 100, auto, capturing = false)) return i
        return -1
    }

    @Test fun steadyPageFiresAfterTheHold() {
        assertEquals(13, AutoCaptureGate().framesToFire())   // 1 frame sets the reference, then 12 held
    }

    @Test fun manualModeNeverFires() {
        assertEquals(-1, AutoCaptureGate().framesToFire(auto = false))
    }

    @Test fun afterACaptureThePageMustLeave() {
        val g = AutoCaptureGate(); g.captured()
        assertEquals(-1, g.framesToFire())
        repeat(5) { g.frame(false, false, emptyList(), 100, 100, auto = true, capturing = false) }
        assertTrue(g.armed); assertEquals(13, g.framesToFire())
    }

    @Test fun detectionTurningBackOnRearms() {
        val g = AutoCaptureGate(); g.captured(); assertFalse(g.armed)
        g.detectionOn()
        assertTrue(g.armed); assertEquals(13, g.framesToFire())
    }
}
