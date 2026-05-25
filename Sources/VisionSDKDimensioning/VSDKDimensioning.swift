//
//  VSDKDimensioning.swift
//  VisionSDKDimensioning
//
//  Public namespace for SDK metadata and capability probes.
//

import Foundation
import CoreML
#if VSDK_DIMENSIONING_MONOLITH
import MVDimensioningCore
#else
@_implementationOnly import MVDimensioningCore
#endif

public enum VSDKDimensioning {
    /// Bundled MVDimensioning build version (publish date, e.g. "2026.05.13").
    /// Useful for support / diagnostics. Mirrors the upstream binary's
    /// `Dimensioning.version` constant.
    public static let version: String = Dimensioning.version

    /// Reports whether the current device can run dimensioning. Returns
    /// all-false on simulator (ARKit + LiDAR are device-only).
    public static func deviceCapabilities() -> VSDKDimensioningCapabilities {
        let mv = Dimensioning.deviceCapabilities()
        return VSDKDimensioningCapabilities(
            lidar:                mv.lidar,
            arWorldTracking:      mv.arWorldTracking,
            sceneReconstruction:  mv.sceneReconstruction
        )
    }

    /// Pre-warm Core ML decryption keys for the bundled dimensioning models.
    ///
    /// MVDimensioning ships its YOLO + SAM2 models as Apple-CoreML-encrypted
    /// .mlmodelc bundles. The first time the app runs on a device, CoreML has
    /// to reach Apple's servers to fetch the per-model decryption key (scoped
    /// to the SDK publisher's Apple Developer team) before the model can be
    /// instantiated. The key is then cached for all subsequent launches.
    ///
    /// If you don't pre-warm, the first dimensioning session typically starts
    /// the AR view *before* the keys arrive, so the underlying pipeline logs
    /// "SAM2 not available" and "YOLO MISSING" and falls back to LiDAR-only
    /// point-cloud measurement (still functional, but lower accuracy).
    ///
    /// Call this from your app launch path — e.g. `App.init`, the SwiftUI
    /// `.task { … }` of your root view, or as soon as `VSDKConstants.apiKey`
    /// is set. It returns once every bundled model has either been decrypted
    /// + cached or has produced a load error. Cheap and idempotent on
    /// subsequent launches (keys come from the cache).
    /// Compile and warm the bundled CoreML models so the first capture session
    /// doesn't pay the JIT-compile cost. Cheap to call on app launch; safe to
    /// call multiple times.
    public static func prefetchModels() async {
        let framework = Bundle(for: DimensioningSession.self)
        let resourceBundleURL = framework.bundleURL
            .appendingPathComponent("MVDimensioningCore_MVDimensioningCore.bundle")
        guard FileManager.default.fileExists(atPath: resourceBundleURL.path) else { return }

        let modelURLs = (try? FileManager.default.contentsOfDirectory(
            at: resourceBundleURL,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ))?.filter { $0.pathExtension == "mlmodelc" } ?? []

        await withTaskGroup(of: Void.self) { group in
            for url in modelURLs {
                group.addTask { _ = try? await MLModel.load(contentsOf: url) }
            }
        }
    }
}
