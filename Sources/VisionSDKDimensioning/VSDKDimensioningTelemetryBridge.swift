//
//  VSDKDimensioningTelemetryBridge.swift
//  VisionSDKDimensioning
//
//  Bridges MVDimensioning's DimensioningTelemetrySink callbacks into
//  VSDKDimensioningTelemetry.report(_:). The only telemetry sink ever
//  passed to MVDimensioning sessions from this module.
//

import Foundation
#if VSDK_DIMENSIONING_MONOLITH
import MVDimensioningCore
#else
@_spi(VSDKDimensioning) import VisionSDK
@_implementationOnly import MVDimensioningCore
#endif

internal final class VSDKDimensioningTelemetryBridge: NSObject, DimensioningTelemetrySink {
    internal static let shared = VSDKDimensioningTelemetryBridge()

    func receive(_ event: DimensioningEvent) {
        let translated: VSDKDimensioningTelemetryEvent
        switch event {
        case .measurementCaptured(let p):
            translated = Self.makeCapturedEvent(
                captureId:                  p.captureId,
                mode:                       p.mode,
                lengthCm:                   p.lengthCm,
                widthCm:                    p.widthCm,
                heightCm:                   p.heightCm,
                distanceM:                  p.distanceM,
                confidence:                 p.confidence,
                durationMs:                 p.durationMs,
                samFrameCount:              p.samFrameCount,
                cloudRequested:             p.cloudRequested,
                cloudLanded:                p.cloudLanded,
                trackingState:              p.trackingState,
                primaryResidualMm:          p.primaryResidualMm,
                consensusLevel:             p.consensusLevel,
                crossCheckAgreementCount:   p.crossCheckAgreementCount,
                topFaceInlierFraction:      p.topFaceInlierFraction,
                timestamp:                  Date()
            )
        case .measurementAborted(let p):
            translated = Self.makeAbortedEvent(
                captureId:      p.captureId,
                reason:         p.reason,
                durationMs:     p.durationMs,
                samFrameCount:  p.samFrameCount,
                cloudRequested: p.cloudRequested,
                timestamp:      Date()
            )
        }
        VSDKDimensioningTelemetry.report(translated)
    }

    // MARK: - Internal factories (testable without MV event types)

    internal static func makeCapturedEvent(
        captureId: UUID,
        mode: String,
        lengthCm: Float,
        widthCm: Float,
        heightCm: Float,
        distanceM: Float,
        confidence: Float,
        durationMs: Int,
        samFrameCount: Int,
        cloudRequested: Bool,
        cloudLanded: Bool,
        trackingState: String,
        primaryResidualMm: Float?,
        consensusLevel: String?,
        crossCheckAgreementCount: Int?,
        topFaceInlierFraction: Float?,
        timestamp: Date
    ) -> VSDKDimensioningTelemetryEvent {
        var props: [String: Any] = [
            "capture_id":      captureId.uuidString,
            "mode":            mode,
            "length_cm":       lengthCm,
            "width_cm":        widthCm,
            "height_cm":       heightCm,
            "distance_m":      distanceM,
            "confidence":      confidence,
            "duration_ms":     durationMs,
            "sam_frame_count": samFrameCount,
            "cloud_requested": cloudRequested,
            "cloud_landed":    cloudLanded,
            "tracking_state":  trackingState,
        ]
        if let v = primaryResidualMm        { props["primary_residual_mm"] = v }
        if let v = consensusLevel           { props["consensus_level"] = v }
        if let v = crossCheckAgreementCount { props["cross_check_agreement_count"] = v }
        if let v = topFaceInlierFraction    { props["top_face_inlier_fraction"] = v }

        return VSDKDimensioningTelemetryEvent(kind: .measurementCaptured, properties: props, timestamp: timestamp)
    }

    internal static func makeAbortedEvent(
        captureId: UUID,
        reason: String,
        durationMs: Int,
        samFrameCount: Int,
        cloudRequested: Bool,
        timestamp: Date
    ) -> VSDKDimensioningTelemetryEvent {
        let props: [String: Any] = [
            "capture_id":      captureId.uuidString,
            "reason":          reason,
            "duration_ms":     durationMs,
            "sam_frame_count": samFrameCount,
            "cloud_requested": cloudRequested,
        ]
        return VSDKDimensioningTelemetryEvent(kind: .measurementAborted, properties: props, timestamp: timestamp)
    }
}
