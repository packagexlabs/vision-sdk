package io.packagex.visiondemo.ar

import io.packagex.arcount.Bracket
import io.packagex.arcount.CountView
import io.packagex.arcount.Gap
import io.packagex.arcount.Marker
import io.packagex.arcount.Prompt
import io.packagex.arcount.SectionState
import io.packagex.arcount.UnitState
import org.junit.Assert.assertEquals
import org.junit.Test

class ArCountTextTest {
    private fun bracket(low: Int, high: Int, gtin: String? = "04006381333931", frozen: Boolean = false) = Bracket(0.4, 0.6, true, gtin, low, high, frozen)

    @Test fun everyPromptHasTheSpecsWording() {
        val range = bracket(12, 14)
        assertEquals(
            listOf(
                "Hold still a moment", "Scan the shelf label", "Scan the shelf label to continue", "Range: 12–14. Rescan from the label?",
                "Too dark: hold still and press the trigger", "Slow down", "Slide a little", "Move closer", "Hold within 30 cm", "Device hot",
                "Point at items you counted to continue",
            ),
            Prompt.entries.map { promptText(it, range) },
        )
    }

    @Test fun theUiCopyDropsThePerFrameGeometry() {
        val v = CountView(
            SectionState.COUNTING, Prompt.SLOW_DOWN, listOf(Marker(1, UnitState.COUNTED, 0.5, 0.5, 0.05)), listOf(Gap(1, 0.6, 0.5)),
            bracket(4, 4), emptyList(),
        )
        assertEquals(CountView(SectionState.COUNTING, Prompt.SLOW_DOWN, emptyList(), emptyList(), Bracket(0.0, 0.0, true, "04006381333931", 4, 4, false), emptyList()), v.forUi())
        // Moving the camera changes only the geometry: the UI copy stays equal, so the UI state does not change
        assertEquals(v.forUi(), v.copy(markers = listOf(Marker(1, UnitState.COUNTED, 0.4, 0.5, 0.05)), bracket = bracket(4, 4).copy(u = 0.1)).forUi())
    }
}
