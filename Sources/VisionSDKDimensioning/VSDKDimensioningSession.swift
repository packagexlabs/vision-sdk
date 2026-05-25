//
//  VSDKDimensioningSession.swift
//  VisionSDKDimensioning
//

import Foundation
import Combine
#if VSDK_DIMENSIONING_MONOLITH
import MVDimensioningCore
#else
@_implementationOnly import MVDimensioningCore
#endif

/// Swift-only programmatic dimensioning session.
///
/// Wraps MVDimensioning's `DimensioningSession`, hides credential/telemetry
/// plumbing, and forwards `@Published` state through VSDK-prefixed types.
@MainActor
public final class VSDKDimensioningSession: ObservableObject {

    @Published public private(set) var phase: VSDKDimensioningPhase = .idle
    @Published public private(set) var tracks: [VSDKDimensioningTrack] = []

    /// Mutable configuration. Changes take effect at the next `start()`.
    public var configuration: VSDKDimensioningConfiguration

    private var underlying: DimensioningSession?
    private var cancellables: Set<AnyCancellable> = []

    public init(configuration: VSDKDimensioningConfiguration = .init()) {
        self.configuration = configuration
    }

    public func start() async throws {
        let mvConfig: DimensioningConfiguration
        do {
            mvConfig = try VSDKDimensioningCredentialResolver.makeMVConfiguration(for: configuration)
        } catch let err as VSDKDimensioningError {
            throw err
        }

        let session = DimensioningSession(
            configuration: mvConfig,
            telemetry: VSDKDimensioningTelemetryBridge.shared
        )
        underlying = session
        wireForwarding(from: session)

        do {
            try await session.start()
        } catch let err as DimensioningError {
            throw Self.mapMVError(err)
        }
    }

    public func pause() { underlying?.pause() }
    public func resume() { underlying?.resume() }

    public func capture() async throws -> VSDKDimensioningMeasurement {
        guard let underlying else { throw VSDKDimensioningError.notConfigured }
        do {
            let mv = try await underlying.capture()
            return Self.mapMeasurement(mv)
        } catch let err as DimensioningError {
            throw Self.mapMVError(err)
        }
    }

    public func clearCaptured() { underlying?.clearCaptured() }

    // MARK: - Internal mapping

    private func wireForwarding(from session: DimensioningSession) {
        cancellables.removeAll()
        session.$phase
            .map { Self.mapPhase($0) }
            .receive(on: RunLoop.main)
            .assign(to: &$phase)
        session.$tracks
            .map { $0.map(Self.mapTrack) }
            .receive(on: RunLoop.main)
            .assign(to: &$tracks)
    }

    internal static func mapPhase(_ mv: DimensioningPhase) -> VSDKDimensioningPhase {
        switch mv {
        case .idle: return .idle
        case .initializing: return .initializing
        case .scanning: return .scanning
        case .detected(let count): return .detected(trackCount: count)
        case .capturing: return .capturing
        case .captured(let m): return .captured(mapMeasurement(m))
        }
    }

    internal static func mapTrack(_ mv: DimensioningTrack) -> VSDKDimensioningTrack {
        VSDKDimensioningTrack(
            id: mv.id,
            measurement: mv.measurement.map(mapMeasurement),
            isStable: mv.isStable,
            normalizedScreenRect: mv.normalizedScreenRect
        )
    }

    internal static func mapMeasurement(_ mv: DimensioningMeasurement) -> VSDKDimensioningMeasurement {
        VSDKDimensioningMeasurement(
            id: mv.id,
            timestamp: mv.timestamp,
            length: NSMeasurement(doubleValue: mv.length.value, unit: mv.length.unit),
            width:  NSMeasurement(doubleValue: mv.width.value,  unit: mv.width.unit),
            height: NSMeasurement(doubleValue: mv.height.value, unit: mv.height.unit),
            distanceFromCamera: NSMeasurement(doubleValue: mv.distanceFromCamera.value, unit: mv.distanceFromCamera.unit),
            confidence: mv.confidence,
            usedCloudSAM: mv.usedCloudSAM
        )
    }

    internal static func mapMVError(_ mv: DimensioningError) -> VSDKDimensioningError {
        switch mv {
        case .lidarUnavailable:                 return .lidarUnavailable
        case .arSessionFailed(let r):           return .arSessionFailed(reason: r)
        case .noGroundPlane:                    return .noGroundPlane
        case .captureTimedOut:                  return .captureTimedOut
        case .userCancelled:                    return .userCancelled
        }
    }
}
