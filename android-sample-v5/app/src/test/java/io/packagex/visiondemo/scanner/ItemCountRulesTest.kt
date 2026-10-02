package io.packagex.visiondemo.scanner

import io.packagex.arcount.CountView
import io.packagex.arcount.ItemCount
import io.packagex.arcount.Prompt
import io.packagex.visiondemo.model.RetrievalRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** AR Item Count's hint, drawer rows and seen rows (spec 5.10). */
class ItemCountRulesTest {
    private val counts = CountView.EMPTY.copy(items = listOf(ItemCount("A", 2, 2, true), ItemCount("B", 3, 4, false), ItemCount("C", 0, 0, false)))

    @Test fun theHintWithAnEmptyListSaysWhatToDo() {
        assertEquals("Add item codes to find", retrievalHint(emptyList(), emptyList(), CountView.EMPTY))
        assertEquals("1 code in view · add it from Item list", retrievalHint(emptyList(), listOf("X"), CountView.EMPTY))
        assertEquals("3 codes in view · add them from Item list", retrievalHint(emptyList(), listOf("X", "Y", "Z"), CountView.EMPTY))
    }

    @Test fun theHintSaysWhatIsInViewAndCounted() {
        val items = listOf("A", "B", "C")
        assertEquals("Pan across the shelf", retrievalHint(items, emptyList(), counts))
        assertEquals("No listed items in view · 5 counted", retrievalHint(items, listOf("Z"), counts))
        assertEquals("1 listed item in view · 5 counted", retrievalHint(items, listOf("A", "Z"), counts))
        assertEquals("2 listed items in view · 0 counted", retrievalHint(items, listOf("A", "B"), CountView.EMPTY))
    }

    @Test fun theCountersPromptTakesTheHintLine() {
        val items = listOf("A")
        assertEquals("Slow down", retrievalHint(items, listOf("A"), counts.copy(prompt = Prompt.SLOW_DOWN)))
        assertEquals("Hold still a moment", retrievalHint(items, emptyList(), counts.copy(prompt = Prompt.HOLD_STILL_A_MOMENT)))
        assertEquals("Point at items you counted to continue", retrievalHint(items, listOf("A"), counts.copy(prompt = Prompt.REREAD_COUNTED_ITEMS)))
        // With no list there is nothing to count: the hint asks for codes, whatever the counter says
        assertEquals("Add item codes to find", retrievalHint(emptyList(), emptyList(), counts.copy(prompt = Prompt.SLOW_DOWN)))
    }

    @Test fun rowsAreTheCodesInViewThenTheListedCodesCounted() {
        assertEquals(
            listOf(
                RetrievalRow("Z", inList = false, countLow = null, countHigh = null),
                RetrievalRow("C", inList = true, countLow = 0, countHigh = 0),
                RetrievalRow("A", inList = true, countLow = 2, countHigh = 2),
                RetrievalRow("B", inList = true, countLow = 3, countHigh = 4),
            ),
            retrievalRows(listOf("Z", "C"), listOf("A", "B", "C"), counts.items),
        )
        // A listed code in view the counter has no count for yet: × 0
        assertEquals(listOf(RetrievalRow("D", true, 0, 0)), retrievalRows(listOf("D"), listOf("D"), emptyList()))
    }

    @Test fun countsReadAsTimesNOrARange() {
        assertEquals("× 2", RetrievalRow("A", true, 2, 2).countText())
        assertEquals("× 3–4", RetrievalRow("B", true, 3, 4).countText())
        assertEquals(null, RetrievalRow("Z", false, null, null).countText())
    }

    @Test fun seenRowsCarryTheInListBadge() {
        assertEquals(listOf("C" to false, "A" to true), seenRows(listOf("C", "A"), listOf("A", "B")))
    }
}
