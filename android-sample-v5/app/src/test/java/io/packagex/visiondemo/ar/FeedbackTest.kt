package io.packagex.visiondemo.ar

import io.packagex.arcount.Bracket
import io.packagex.arcount.CountView
import io.packagex.arcount.ItemCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {
    private fun bracket(low: Int) = Bracket(0.5, 0.5, true, "04006381333931", low, low + 1, false)

    @Test fun theTotalIsTheItemsCountLowOrTheBracketsWithoutItems() {
        assertEquals(0, countedTotal(CountView.EMPTY))
        assertEquals(4, countedTotal(CountView.EMPTY.copy(bracket = bracket(4))))
        val items = listOf(ItemCount("a", 2, 3, true), ItemCount("b", 5, 5, false))
        assertEquals(7, countedTotal(CountView.EMPTY.copy(bracket = bracket(4), items = items)))
    }

    @Test fun aRiseTicksOnceAndABurstInsideHalfASecondTicksOnceMore() {
        val tick = CountTick()
        assertFalse(tick.onTotal(0, 0))
        assertTrue(tick.onTotal(1, 100))    // first unit
        assertFalse(tick.onTotal(2, 200))   // 100 ms later: owed
        assertFalse(tick.onTotal(3, 300))   // still owed, once
        assertFalse(tick.onTotal(3, 599))
        assertTrue(tick.onTotal(3, 600))    // 500 ms after the last tick: the one owed
        assertFalse(tick.onTotal(3, 2_000)) // nothing new
        assertTrue(tick.onTotal(4, 2_001))
    }

    @Test fun aFallOwesNothingAndTheNextRiseTicks() {
        val tick = CountTick()
        assertTrue(tick.onTotal(5, 0))
        assertFalse(tick.onTotal(6, 100))  // owed
        assertFalse(tick.onTotal(0, 200))  // new scan: the owed tick is dropped
        assertFalse(tick.onTotal(0, 900))
        assertTrue(tick.onTotal(1, 1_000))
    }

    @Test fun theSameTotalNeverTicks() {
        val tick = CountTick()
        val view = CountView.EMPTY.copy(bracket = bracket(3))
        assertTrue(tick.onView(view, 0))
        for (t in 1..20) assertFalse(tick.onView(view, t * 100L))
    }
}
