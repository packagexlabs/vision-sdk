package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountConfigTest {
    private val c = CountConfig()

    @Test
    fun pairingKeepsTwoSecondsOfPosesAndWaitsATenthOfASecond() {
        assertEquals(2_000_000_000L, c.poseRingNs)
        assertEquals(100_000_000L, c.pairWaitNs)
    }

    @Test
    fun depthUsesFifteenPixelRaysAndBothGates() {
        assertEquals(15.0, c.sigmaRayPx, 0.0)
        assertEquals(30, c.maxRays)
        assertEquals(3.0, c.rejectSigmas, 0.0)
        assertEquals(5, c.wideMinInliers)
        assertEquals(3.0, c.wideSpanDeg, 0.0)
        assertEquals(1.5, c.wideBaselineHalfAngleDeg, 0.0)
        assertEquals(8, c.denseMinInliers)
        assertEquals(2.0, c.denseSpanDeg, 0.0)
        assertEquals(0.02, c.denseMaxSigmaZ, 0.0)
        assertEquals(0.08, c.minDepth, 0.0)
        assertEquals(1.5, c.maxDepth, 0.0)
        assertEquals(0.15, c.priorSigmaAlongRay, 0.0)
    }

    @Test
    fun predictionPitchAndAssociationUseTheSpecsNumbers() {
        assertEquals(24.0, c.sigmaTPx, 0.0)
        assertEquals(0.0, c.sigmaTSlopePxPerM, 0.0)
        assertEquals(0.06, c.defaultPitch, 0.0)
        assertEquals(3, c.pitchMinCounted)
        assertEquals(0.25, c.pitchPercentile, 0.0)
        assertEquals(0.04, c.minPitch, 0.0)
        assertEquals(0.12, c.maxPitch, 0.0)
        assertEquals(0.25, c.gateCost, 0.0)
        assertEquals(0.25, c.gateMaxSigmaFraction, 0.0)
        assertEquals(2.0, c.gateSigmas, 0.0)
        assertEquals(0.5, c.gateMaxCost, 0.0)
        assertEquals(0.1, c.engineIdPenalty, 0.0)
        assertEquals(0.5, c.duplicateFraction, 0.0)
        assertEquals(0.75, c.ambiguityPitchFraction, 0.0)
        assertEquals(2.0, c.ambiguitySigmas, 0.0)
        assertEquals(3, c.mergeFrames)
    }

    @Test
    fun theSectionTimersAndBandsAreTheSpecs() {
        assertEquals(0.40, c.anchorDepth, 0.0)
        assertEquals(0.04, c.railHalfHeight, 0.0)
        assertEquals(1.0, c.maxExtentFromAnchor, 0.0)
        assertEquals(0.025, c.labelMaxWidth, 0.0)
        assertEquals(2_000_000_000L, c.guardTrackingNs)
        assertEquals(5_000_000_000L, c.guardSinceResumeNs)
        assertEquals(10_000_000_000L, c.openTimeoutNs)
        assertEquals(5_000_000_000L, c.resumeWindowNs)
        assertEquals(2, c.resumeMinUnits)
        assertEquals(0.10, c.jumpStep, 0.0)
        assertEquals(0.5, c.jumpSpeed, 0.0)
        assertEquals(0.01, c.jumpMinStep, 0.0)
        assertEquals(50.0, c.jumpStillPx, 0.0)
        assertEquals(1_000_000_000L, c.leftSectionNs)
        assertEquals(10_000_000_000L, c.silenceNs)
    }

    @Test
    fun markersGapsAndPromptsUseTheSpecsNumbers() {
        assertEquals(0.1, c.markerSigmaPitchFraction, 0.0)
        assertEquals(1_000_000_000L, c.markerMaxAgeNs)
        assertEquals(1.75, c.gapPitches, 0.0)
        assertEquals(1.0, c.blurMaxModules, 0.0)
        assertEquals(1_000_000_000L, c.blurHoldNs)
        assertEquals(95.0, c.modulesPerSymbol, 0.0)
        assertEquals(1_000_000_000L, c.noDepthPromptNs)
    }

    @Test
    fun theHostHooksAreUnsetByDefault() {
        assertNull(c.isLabel)
        assertNull(c.gtinsOfLabel)
    }
}
