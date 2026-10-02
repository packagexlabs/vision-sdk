package io.packagex.visiondemo.ar

import io.packagex.arcount.Bracket
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import io.packagex.arcount.Marker
import io.packagex.arcount.Prompt
import io.packagex.arcount.SectionResult
import io.packagex.arcount.SectionState
import io.packagex.arcount.SectionStatus
import io.packagex.arcount.Gap
import io.packagex.arcount.UnitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun theBracketShowsGtinTimesCountOrARange() {
        assertEquals("04006381333931 × 13", bracketText(bracket(13, 13)))
        assertEquals("12–14 ?", bracketText(bracket(12, 14)))
        assertEquals("3", bracketText(bracket(3, 3, gtin = null)))   // the debug counter's distinct texts
    }

    @Test fun buttonsFollowTheSectionState() {
        fun view(state: SectionState, b: Bracket?) = CountView(state, null, emptyList(), emptyList(), b, emptyList())
        assertEquals(emptyList<Command>(), arButtons(view(SectionState.IDLE, null)))
        assertEquals(emptyList<Command>(), arButtons(view(SectionState.IDLE, bracket(3, 3, gtin = null))))
        assertEquals(emptyList<Command>(), arButtons(view(SectionState.OPEN, bracket(0, 0))))
        assertEquals(listOf(Command.Finish), arButtons(view(SectionState.COUNTING, bracket(5, 5))))
        assertEquals(listOf(Command.Finish, Command.Restart, Command.AcceptRange), arButtons(view(SectionState.COUNTING, bracket(5, 7))))
        assertEquals(listOf(Command.Finish, Command.Restart), arButtons(view(SectionState.FROZEN, bracket(5, 5, frozen = true))))
        assertEquals(listOf("Finish", "Restart", "Accept range"), listOf(Command.Finish, Command.Restart, Command.AcceptRange).map(::buttonLabel))
    }

    @Test fun closedSectionsReadAsCountsAndStatuses() {
        val s = SectionResult("s1", "LBL", setOf("1"), SectionStatus.UNRESOLVED, 10, 0, 0, 1, 1, 10, 12, emptyList(), 1)
        assertEquals("10–12", countText(s)); assertEquals("10", countText(s.copy(countHigh = 10)))
        assertEquals(listOf("Complete", "Unresolved", "Closed while frozen", "Abandoned"), SectionStatus.entries.map(::statusText))
    }

    @Test fun theHintSaysTheStateAndTheWorkingDistance() {
        val counting = CountView.EMPTY.copy(state = SectionState.COUNTING)
        assertEquals("Counting · within 40 cm", arHint(counting, AppStream.UHD))
        assertEquals("No section open · within 30 cm", arHint(CountView.EMPTY, AppStream.QHD))
        assertEquals("Count frozen", arHint(CountView.EMPTY.copy(state = SectionState.FROZEN), null))
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

    @Test fun aTapNearAGapFillsTheNearestOne() {
        val screen = ArScreen(null, listOf(ScreenGap(1, 100f, 100f), ScreenGap(2, 130f, 100f)))
        assertEquals(2, screen.gapAt(125f, 105f, radius = 40f))
        assertEquals(1, screen.gapAt(90f, 100f, radius = 40f))
        assertNull(screen.gapAt(300f, 300f, radius = 40f))
    }
}
