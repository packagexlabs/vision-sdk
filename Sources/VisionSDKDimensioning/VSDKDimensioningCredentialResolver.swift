//
//  VSDKDimensioningCredentialResolver.swift
//  VisionSDKDimensioning
//
//  Maps the locked-down VSDKDimensioningConfiguration into an MVDimensioning
//  DimensioningConfiguration, pulling apiKey/sdkID/URL from VSDKConstants.
//  Read at session-start time so the host app can configure VSDK late.
//

import Foundation
#if VSDK_DIMENSIONING_MONOLITH
import MVDimensioningCore
#else
@_spi(VSDKDimensioning) import VisionSDK
@_implementationOnly import MVDimensioningCore
#endif

internal enum VSDKDimensioningResolvedBackend: Equatable {
    case localOnly
    case cloud(url: URL, apiKey: String, sdkID: String)
}

internal enum VSDKDimensioningCredentialResolver {

    /// Resolve the locked-down config into a backend description. Throws
    /// VSDKDimensioningError.missingCredentials when online mode is requested
    /// but VSDKConstants.apiKey hasn't been set.
    internal static func resolve(for config: VSDKDimensioningConfiguration) throws -> VSDKDimensioningResolvedBackend {
        switch config.mode {
        case .offline:
            return .localOnly
        case .online:
            let creds = VSDKDimensioningCredentials.current
            guard !creds.apiKey.isEmpty else {
                throw VSDKDimensioningError.missingCredentials
            }
            return .cloud(url: creds.cloudURL, apiKey: creds.apiKey, sdkID: creds.sdkID)
        }
    }

    /// Build the MVDimensioning configuration that the underlying session
    /// will consume. enableTelemetry is forced to false — VSDK telemetry
    /// bridge owns event routing.
    internal static func makeMVConfiguration(for config: VSDKDimensioningConfiguration) throws -> DimensioningConfiguration {
        let resolved = try resolve(for: config)
        let backend: DimensioningConfiguration.SegmentationBackend
        switch resolved {
        case .localOnly:
            backend = .localOnly
        case let .cloud(url, apiKey, sdkID):
            backend = .cloud(url: url, apiKey: apiKey, sdkID: sdkID)
        }
        return DimensioningConfiguration(
            segmentationBackend: backend,
            measurementUnit: config.measurementUnit,
            maximumTrackCount: config.maximumTrackCount,
            enableTelemetry: false
        )
    }
}

#if DEBUG
internal struct VSDKDimensioningResolvedSnapshot {
    let measurementUnitSymbol: String
    let maximumTrackCount: Int
    let enableTelemetry: Bool
}

internal extension VSDKDimensioningCredentialResolver {
    /// Test-only helper that flattens the MV configuration's tunable fields
    /// into a struct that does not depend on MVDimensioning types.
    /// Allows the test target to assert against the resolver's outputs.
    static func makeResolvedSnapshot(for config: VSDKDimensioningConfiguration) throws -> VSDKDimensioningResolvedSnapshot {
        let mv = try makeMVConfiguration(for: config)
        return VSDKDimensioningResolvedSnapshot(
            measurementUnitSymbol: mv.measurementUnit.symbol,
            maximumTrackCount: mv.maximumTrackCount,
            enableTelemetry: mv.enableTelemetry
        )
    }
}
#endif
