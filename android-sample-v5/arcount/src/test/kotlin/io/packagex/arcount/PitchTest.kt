package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Test

class PitchTest {
    private val config = CountConfig()

    @Test
    fun sixCentimetresUntilThreeUnitsAreCounted() {
        assertEquals(0.06, Pitch.metric(emptyList(), config), 0.0)
        assertEquals(0.06, Pitch.metric(listOf(0.0, 0.08), config), 0.0)
    }

    @Test
    fun thenTheQuarterPercentileOfNearestNeighbourDistancesAlongTheShelf() {
        assertEquals(0.08, Pitch.metric(listOf(0.24, 0.0, 0.16, 0.08), config), 1e-12)
        // nearest-neighbour distances 0.05, 0.05, 0.10, 0.10, 0.10: the 25th percentile is 0.05
        assertEquals(0.05, Pitch.metric(listOf(0.0, 0.05, 0.15, 0.25, 0.35), config), 1e-12)
    }

    @Test
    fun theMetricPitchIsClampedToFourToTwelveCentimetres() {
        assertEquals(0.04, Pitch.metric(listOf(0.0, 0.02, 0.04), config), 0.0)
        assertEquals(0.12, Pitch.metric(listOf(0.0, 0.3, 0.6), config), 0.0)
    }

    @Test
    fun inPixelsItIsFocalLengthTimesPitchOverCameraDepth() {
        assertEquals(434.4, Pitch.px(2896.0, 0.06, 0.4), 1e-9)
    }
}
