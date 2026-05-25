//
//  VSDKDimensioningConfiguration.swift
//  VisionSDKDimensioning
//

import Foundation

/// Public configuration for a dimensioning session.
///
/// Deliberately omits credentials, URLs, telemetry sinks, and the underlying
/// segmentation backend choice — those are sourced from `VSDKConstants` at
/// session-start time. Consumers control only the knobs that make sense at
/// the SDK boundary.
public struct VSDKDimensioningConfiguration: Sendable {
    public var mode: VSDKDimensioningMode
    public var measurementUnit: UnitLength
    public var maximumTrackCount: Int

    public init(
        mode: VSDKDimensioningMode = .offline,
        measurementUnit: UnitLength = .centimeters,
        maximumTrackCount: Int = 5
    ) {
        self.mode = mode
        self.measurementUnit = measurementUnit
        self.maximumTrackCount = maximumTrackCount
    }
}
