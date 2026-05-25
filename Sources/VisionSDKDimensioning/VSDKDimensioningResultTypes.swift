//
//  VSDKDimensioningResultTypes.swift
//  VisionSDKDimensioning
//
//  Public result/state types. NSObject-backed for ObjC visibility.
//

import Foundation
import CoreGraphics

@objc public final class VSDKDimensioningMeasurement: NSObject {
    @objc public let id: UUID
    @objc public let timestamp: Date
    @objc public let length: NSMeasurement
    @objc public let width: NSMeasurement
    @objc public let height: NSMeasurement
    @objc public let distanceFromCamera: NSMeasurement
    @objc public let confidence: Float
    @objc public let usedCloudSAM: Bool

    @objc public init(
        id: UUID,
        timestamp: Date,
        length: NSMeasurement,
        width: NSMeasurement,
        height: NSMeasurement,
        distanceFromCamera: NSMeasurement,
        confidence: Float,
        usedCloudSAM: Bool
    ) {
        self.id = id
        self.timestamp = timestamp
        self.length = length
        self.width = width
        self.height = height
        self.distanceFromCamera = distanceFromCamera
        self.confidence = confidence
        self.usedCloudSAM = usedCloudSAM
        super.init()
    }

    @objc public var volume: NSMeasurement {
        // Convert each side to meters, multiply, return cubic meters.
        // NSMeasurement loses the generic unit type when bridged, so rebuild
        // typed Measurement<UnitLength> values from the NSMeasurement fields.
        let lm = Self.toMeters(length)
        let wm = Self.toMeters(width)
        let hm = Self.toMeters(height)
        return NSMeasurement(doubleValue: lm * wm * hm, unit: UnitVolume.cubicMeters)
    }

    private static func toMeters(_ ns: NSMeasurement) -> Double {
        if let lengthUnit = ns.unit as? UnitLength {
            return Measurement(value: ns.doubleValue, unit: lengthUnit).converted(to: .meters).value
        }
        // Unit is not a UnitLength (shouldn't happen — initializer takes
        // UnitLength-typed NSMeasurements). Treat the value as already-meters
        // rather than crashing.
        return ns.doubleValue
    }
}

@objc public final class VSDKDimensioningTrack: NSObject {
    @objc public let id: UUID
    @objc public let measurement: VSDKDimensioningMeasurement?
    @objc public let isStable: Bool
    @objc public let normalizedScreenRect: CGRect

    @objc public init(
        id: UUID,
        measurement: VSDKDimensioningMeasurement?,
        isStable: Bool,
        normalizedScreenRect: CGRect
    ) {
        self.id = id
        self.measurement = measurement
        self.isStable = isStable
        self.normalizedScreenRect = normalizedScreenRect
        super.init()
    }
}

@objc public final class VSDKDimensioningCapabilities: NSObject {
    @objc public let lidar: Bool
    @objc public let arWorldTracking: Bool
    @objc public let sceneReconstruction: Bool

    @objc public init(lidar: Bool, arWorldTracking: Bool, sceneReconstruction: Bool) {
        self.lidar = lidar
        self.arWorldTracking = arWorldTracking
        self.sceneReconstruction = sceneReconstruction
        super.init()
    }
}

// MARK: - Phase and Error

public enum VSDKDimensioningPhase: Sendable, Equatable {
    case idle
    case initializing
    case scanning
    case detected(trackCount: Int)
    case capturing
    case captured(VSDKDimensioningMeasurement)

    public static func == (lhs: VSDKDimensioningPhase, rhs: VSDKDimensioningPhase) -> Bool {
        switch (lhs, rhs) {
        case (.idle, .idle), (.initializing, .initializing),
             (.scanning, .scanning), (.capturing, .capturing):
            return true
        case let (.detected(l), .detected(r)):
            return l == r
        case let (.captured(l), .captured(r)):
            return l === r       // identity compare on the wrapper class is sufficient here
        default:
            return false
        }
    }
}

public enum VSDKDimensioningError: Error, Sendable {
    case missingCredentials                    // ordinal 0
    case notConfigured                         // ordinal 1
    case lidarUnavailable                      // ordinal 2
    case arSessionFailed(reason: String)       // ordinal 3
    case noGroundPlane                         // ordinal 4
    case captureTimedOut                       // ordinal 5
    case userCancelled                         // ordinal 6

    /// Stable ObjC-bridgeable representation. Domain and code are part of
    /// the public ObjC API contract — never change them.
    public func toNSError() -> NSError {
        let domain = "io.packagex.visionsdk.dimensioning"
        let code: Int
        let description: String
        var userInfo: [String: Any] = [:]

        switch self {
        case .missingCredentials:
            code = 0
            description = "Dimensioning online mode requires VSDKConstants.apiKey to be set before starting the session."
        case .notConfigured:
            code = 1
            description = "VSDKDimensioningView used before configure(delegate:mode:maximumTrackCount:) was called."
        case .lidarUnavailable:
            code = 2
            description = "This device does not have LiDAR — dimensioning cannot run."
        case .arSessionFailed(let reason):
            code = 3
            description = "ARKit session failed: \(reason)"
            userInfo["reason"] = reason
        case .noGroundPlane:
            code = 4
            description = "No horizontal ground plane could be established. Aim the camera at a flat surface."
        case .captureTimedOut:
            code = 5
            description = "Dimensioning capture timed out before reaching a stable measurement."
        case .userCancelled:
            code = 6
            description = "Dimensioning capture was cancelled by the user."
        }
        userInfo[NSLocalizedDescriptionKey] = description
        return NSError(domain: domain, code: code, userInfo: userInfo)
    }
}
