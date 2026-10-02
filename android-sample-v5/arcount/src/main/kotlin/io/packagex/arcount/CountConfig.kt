package io.packagex.arcount

/**
 * Every number the counting core decides with. The defaults are the spec's values (spec v2.1, sections 5.1, 5.4, 5.5,
 * 5.6, and the P2 decision draft of 2026-10-02). Times are nanoseconds of the camera clock, lengths metres.
 */
data class CountConfig(
    // Pairing (5.2, 5.8)
    val poseRingNs: Long = 2_000_000_000L,
    val pairWaitNs: Long = 100_000_000L,
    // Rays and depth (5.4): ray noise in pixels at the stream's f, the gates (a) "wide" and (b) "dense"
    val sigmaRayPx: Double = 15.0,
    val maxRays: Int = 30,
    val rejectSigmas: Double = 3.0,
    val wideMinInliers: Int = 5,
    val wideSpanDeg: Double = 3.0,
    val wideBaselineHalfAngleDeg: Double = 1.5,
    val denseMinInliers: Int = 8,
    val denseSpanDeg: Double = 2.0,
    val denseMaxSigmaZ: Double = 0.02,
    val minDepth: Double = 0.08,
    val maxDepth: Double = 1.5,
    val priorSigmaAlongRay: Double = 0.15,
    // Prediction (5.4): sigmaT = sigmaTPx + slope * travel; phase B found no drift inside a segment, so the slope is 0
    val sigmaTPx: Double = 24.0,
    val sigmaTSlopePxPerM: Double = 0.0,
    // Pitch (5.4)
    val defaultPitch: Double = 0.06,
    val pitchMinCounted: Int = 3,
    val pitchPercentile: Double = 0.25,
    val minPitch: Double = 0.04,
    val maxPitch: Double = 0.12,
    // Association (5.4)
    val gateCost: Double = 0.25,
    val engineIdPenalty: Double = 0.1,
    val duplicateFraction: Double = 0.5,
    val ambiguityPitchFraction: Double = 0.75,
    val ambiguitySigmas: Double = 2.0,
    // An AMBIGUOUS unit merges into its linked unit after this many frames that read the linked unit and every other
    // unit of the GTIN in view but nothing in its gate (Units: settleAmbiguous; coverage rule, not the spec's literal one)
    val mergeFrames: Int = 3,
    // Section (5.1)
    val anchorDepth: Double = 0.40,
    val railHalfHeight: Double = 0.04,
    val maxExtentFromAnchor: Double = 1.0,
    val labelMaxWidth: Double = 0.025,
    val labelAssumedDepth: Double = 0.40,
    // Not in the spec: a label opens or switches a section only when aimed at, its centre in this middle fraction of
    // the image width and nearer the centre than the open section's own label, so two labels in view cannot ping-pong
    val labelAimFraction: Double = 1.0 / 3,
    val guardTrackingNs: Long = 2_000_000_000L,
    val guardSinceResumeNs: Long = 5_000_000_000L,
    val openTimeoutNs: Long = 10_000_000_000L,
    val resumeWindowNs: Long = 5_000_000_000L,
    val resumeMinUnits: Int = 2,
    val resumeLabelGate: Double = 0.25,
    val jumpStep: Double = 0.10,
    val jumpSpeed: Double = 0.5,
    val jumpMinStep: Double = 0.01,
    val jumpStillPx: Double = 50.0,
    val leftSectionNs: Long = 1_000_000_000L,
    val silenceNs: Long = 10_000_000_000L,
    // Markers and gaps (5.5)
    val markerSigmaPitchFraction: Double = 0.1,
    val markerMaxAgeNs: Long = 1_000_000_000L,
    val gapPitches: Double = 1.75,
    val gapMinCounted: Int = 3,
    // Prompts (5.4, 5.5, 5.6)
    val blurMaxModules: Double = 1.0,
    val blurHoldNs: Long = 1_000_000_000L,
    val modulesPerSymbol: Double = 95.0,
    val noDepthPromptNs: Long = 1_000_000_000L,
    val minModulePx: Double = 2.0,
    val fullResolutionWidth: Int = 3840,
    // Host hooks (5.1): label recognition, and the label payload's GTINs (pack hierarchy included)
    val isLabel: ((Read) -> Boolean)? = null,
    val gtinsOfLabel: ((String) -> Set<String>?)? = null,
)
