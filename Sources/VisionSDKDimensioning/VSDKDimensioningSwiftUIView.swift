//
//  VSDKDimensioningSwiftUIView.swift
//  VisionSDKDimensioning
//

import SwiftUI
#if VSDK_DIMENSIONING_MONOLITH
import MVDimensioningCore
#else
@_implementationOnly import MVDimensioningCore
#endif

/// SwiftUI view that runs a dimensioning session with VSDK-resolved credentials
/// and telemetry routing. Modern-Swift surface; for ObjC/UIKit consumers, use
/// `VSDKDimensioningView` (UIKit) instead.
public struct VSDKDimensioningSwiftUIView: View {

    private let configuration: VSDKDimensioningConfiguration
    private let onCapture: ((VSDKDimensioningMeasurement) -> Void)?

    public init(
        configuration: VSDKDimensioningConfiguration = .init(),
        onCapture: ((VSDKDimensioningMeasurement) -> Void)? = nil
    ) {
        self.configuration = configuration
        self.onCapture = onCapture
    }

    public var body: some View {
        InnerView(configuration: configuration, onCapture: onCapture)
    }

    // MARK: - Private inner that handles MV configuration build (throwable).
    private struct InnerView: View {
        let configuration: VSDKDimensioningConfiguration
        let onCapture: ((VSDKDimensioningMeasurement) -> Void)?

        var body: some View {
            // If credential resolution throws, render an empty view + log.
            // The host can pre-check via VSDKDimensioning.deviceCapabilities()
            // or by setting VSDKConstants.apiKey before showing this view.
            if let mvConfig = try? VSDKDimensioningCredentialResolver.makeMVConfiguration(for: configuration) {
                DimensioningView(
                    configuration: mvConfig,
                    telemetry: VSDKDimensioningTelemetryBridge.shared,
                    onCapture: onCapture.map { cb in
                        return { mv in cb(VSDKDimensioningSession.mapMeasurement(mv)) }
                    }
                )
            } else {
                // Missing credentials in online mode, or other config error.
                // Render nothing — host should ensure VSDKConstants is set first.
                Color.clear
            }
        }
    }
}
