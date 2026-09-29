package io.packagex.visiondemo.scanner

import io.packagex.visiondemo.model.Box
import io.packagex.visiondemo.model.DetectedCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerUiStateTest {
    private fun code(left: Int) = DetectedCode("123", "Code128", Box(left, 0, left + 10, 10))

    @Test fun withoutBoxesDropsOnlyTheBoxes() {
        val s = ScannerUiState(codeInFrame = true, boxes = listOf(code(0)))
        val derived = s.withoutBoxes()
        assertTrue(derived.boxes.isEmpty())
        assertEquals(s.copy(boxes = emptyList()), derived)
    }

    /** What lets the screen skip recomposing while only the boxes move. */
    @Test fun statesDifferingOnlyInBoxesDeriveEqual() {
        val a = ScannerUiState(codeInFrame = true, boxes = listOf(code(0)))
        val b = a.copy(boxes = listOf(code(40), code(80)))
        assertNotEquals(a, b)
        assertEquals(a.withoutBoxes(), b.withoutBoxes())
        assertNotEquals(a.withoutBoxes(), b.copy(codeInFrame = false).withoutBoxes())
    }

    @Test fun noBoxesIsTheSameInstance() {
        val s = ScannerUiState()
        assertSame(s, s.withoutBoxes())
    }
}
