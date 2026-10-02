package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTest {
    private val breaks = listOf(5_000_000_000L to BreakReason.WORLD_JUMP, 7_500_000_000L to BreakReason.SILENCE)

    @Test
    fun aResultCarriesTheCountAndItsRange() {
        val r = sectionResult("S1", "LABEL-1", setOf(GTIN14), SectionStatus.UNRESOLVED, Counts(7, 2, 1, 1), 3, 1, breaks, 4_567)
        assertEquals(7, r.counted)
        assertEquals(3, r.manualAdded)
        assertEquals(1, r.manualRemoved)
        assertEquals(1, r.tentative)
        assertEquals(1, r.ambiguous)
        assertEquals(9, r.countLow)
        assertEquals(11, r.countHigh)
        assertEquals(breaks, r.breaks)
    }

    @Test
    fun theJsonOfAResultHasEveryFieldInAFixedOrder() {
        val r = sectionResult("S1", "LA\"BEL\\1", setOf("b", "a"), SectionStatus.COMPLETE, Counts(10, 0, 0, 0), 0, 0, breaks, 4_567)
        val expected = """{"sectionId":"S1","labelPayload":"LA\"BEL\\1","gtins":["a","b"],"status":"COMPLETE",""" +
            """"counted":10,"manualAdded":0,"manualRemoved":0,"tentative":0,"ambiguous":0,"countLow":10,"countHigh":10,""" +
            """"breaks":[{"t":5000000000,"reason":"WORLD_JUMP"},{"t":7500000000,"reason":"SILENCE"}],"durationMs":4567}"""
        assertEquals(expected, r.toJson())
    }

    @Test
    fun anUnlabelledResultWritesNullAndControlCharactersAreEscaped() {
        val r = sectionResult("S2", null, setOf("x\ny"), SectionStatus.ABANDONED, Counts(0, 0, 0, 0), 0, 0, emptyList(), 0)
        val json = r.toJson()
        assertTrue(json.contains("\"labelPayload\":null"))
        assertTrue(json.contains("\"gtins\":[\"x\\ny\"]"))
        assertTrue(json.contains("\"breaks\":[]"))
        for (key in listOf("sectionId", "labelPayload", "gtins", "status", "counted", "manualAdded", "manualRemoved", "tentative", "ambiguous", "countLow", "countHigh", "breaks", "durationMs")) {
            assertTrue(key, json.contains("\"$key\":"))
        }
    }
}
