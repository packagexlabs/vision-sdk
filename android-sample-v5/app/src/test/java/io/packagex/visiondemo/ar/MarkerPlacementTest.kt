package io.packagex.visiondemo.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AR marker presentation: no animation or smoothing (AR_MARKER_SMOOTHING is
 * off), and a re-detection of an already-marked code never moves or
 * recreates its marker.
 */
class MarkerPlacementTest {
    @Test fun smoothingIsOff() {
        assertFalse(AR_MARKER_SMOOTHING)
    }

    // A raw hit maps straight to the drawn (birth) position: no median/EMA
    // averaging of the accumulated sightings.
    @Test fun birthPositionIsTheRawLatestHitNotAnAverage() {
        val consensusOfHits = floatArrayOf(1f, 1f, 1f) // e.g. median of several samples
        val latestHit = floatArrayOf(2f, 3f, 4f)
        assertEquals(consensusOfHits, birthPosition(smoothing = true, consensusOfHits, latestHit))
        assertEquals(latestHit, birthPosition(smoothing = false, consensusOfHits, latestHit))
    }

    // No snap-back: with smoothing off, a sub-threshold move never holds the
    // previously drawn screen position, regardless of camera stillness.
    @Test fun noSnapBackWhenSmoothingIsOff() {
        assertFalse(holdScreenPosition(smoothing = false, cameraNearStill = true, hasScreen = true, dxPx = 0.1f, dyPx = 0.1f, deadbandPx = 4f))
        assertFalse(holdScreenPosition(smoothing = false, cameraNearStill = true, hasScreen = true, dxPx = 0f, dyPx = 0f, deadbandPx = 4f))
    }

    @Test fun snapBackStillAvailableWithSmoothingOnForComparison() {
        assertTrue(holdScreenPosition(smoothing = true, cameraNearStill = true, hasScreen = true, dxPx = 0.1f, dyPx = 0.1f, deadbandPx = 4f))
        assertFalse(holdScreenPosition(smoothing = true, cameraNearStill = false, hasScreen = true, dxPx = 0.1f, dyPx = 0.1f, deadbandPx = 4f))
    }

    // No birth pop/grow/fade: with smoothing off there is never a pulsing
    // ring, at any age.
    @Test fun noBirthPulseWhenSmoothingIsOff() {
        val (pulseR, pulseAlpha) = pulseState(smoothing = false, ageMs = 0L, pulseMs = 700L, ringR = 11f, growth = 28f)
        assertEquals(-1f, pulseR)
        assertEquals(0f, pulseAlpha)
        val (pulseR2, pulseAlpha2) = pulseState(smoothing = false, ageMs = 350L, pulseMs = 700L, ringR = 11f, growth = 28f)
        assertEquals(-1f, pulseR2)
        assertEquals(0f, pulseAlpha2)
    }

    @Test fun pulseStillAnimatesWithSmoothingOnForComparison() {
        val (pulseR, pulseAlpha) = pulseState(smoothing = true, ageMs = 0L, pulseMs = 700L, ringR = 11f, growth = 28f)
        assertEquals(11f, pulseR)
        assertEquals(1f, pulseAlpha)
    }

    // The redraw/move fix: a same-payload marker read again at a nearby hit
    // (within the assignment radius) is the SAME marker — assignAndPlace
    // pairs it and only refreshes lastSeenMs, and mergeSiblingMarkers must
    // treat it as one marker, not replace it with a rival.
    @Test fun sameCodeReadAgainAtANearbyHitIsTheSameMarker() {
        val existing = floatArrayOf(1f, 0f, 1f)
        val nearbyReRead = floatArrayOf(1.10f, 0f, 1f) // 10cm away
        assertTrue(sameMarkerPosition(existing, nearbyReRead, radiusM = 0.15f))
    }

    // A hit clearly beyond the assignment radius (> 15cm) is a different
    // physical copy of the same barcode text and must get its own marker,
    // not silently replace the existing one.
    @Test fun aHitFarFromAnExistingMarkerIsADifferentPhysicalCopy() {
        val existing = floatArrayOf(0f, 0f, 0f)
        val farAway = floatArrayOf(0.30f, 0f, 0f) // 30cm away
        assertFalse(sameMarkerPosition(existing, farAway, radiusM = 0.15f))
    }

    @Test fun sameMarkerPositionIsExactlyAtTheRadiusBoundary() {
        val existing = floatArrayOf(0f, 0f, 0f)
        val atBoundary = floatArrayOf(0.15f, 0f, 0f)
        assertTrue(sameMarkerPosition(existing, atBoundary, radiusM = 0.15f))
    }
}
