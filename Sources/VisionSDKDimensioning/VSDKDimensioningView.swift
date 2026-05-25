//
//  VSDKDimensioningView.swift
//  VisionSDKDimensioning
//

import UIKit
import SwiftUI
#if VSDK_DIMENSIONING_MONOLITH
import MVDimensioningCore
#else
@_implementationOnly import MVDimensioningCore
#endif

@objc public protocol VSDKDimensioningViewDelegate: AnyObject {
    @objc func dimensioningView(_ view: VSDKDimensioningView,
                                didCapture measurement: VSDKDimensioningMeasurement)
    @objc optional func dimensioningView(_ view: VSDKDimensioningView,
                                         didFailWithError error: NSError)
}

@objc public final class VSDKDimensioningView: UIView {

    @objc public weak var delegate: VSDKDimensioningViewDelegate?

    private var configuration: VSDKDimensioningConfiguration?
    private var hostingController: UIHostingController<AnyView>?

    @objc public override init(frame: CGRect) {
        super.init(frame: frame)
    }
    @objc public required init?(coder: NSCoder) {
        super.init(coder: coder)
    }
    @objc public convenience init() {
        self.init(frame: .zero)
    }

    @objc public func configure(
        delegate: VSDKDimensioningViewDelegate,
        mode: VSDKDimensioningMode = .offline,
        maximumTrackCount: Int = 5
    ) {
        self.delegate = delegate
        self.configuration = VSDKDimensioningConfiguration(
            mode: mode,
            measurementUnit: .centimeters,
            maximumTrackCount: maximumTrackCount
        )
    }

    @objc public func startRunning() {
        guard let config = configuration else {
            delegate?.dimensioningView?(self, didFailWithError: VSDKDimensioningError.notConfigured.toNSError())
            return
        }
        let mvConfig: DimensioningConfiguration
        do {
            mvConfig = try VSDKDimensioningCredentialResolver.makeMVConfiguration(for: config)
        } catch let err as VSDKDimensioningError {
            delegate?.dimensioningView?(self, didFailWithError: err.toNSError())
            return
        } catch {
            delegate?.dimensioningView?(self, didFailWithError: NSError(
                domain: "io.packagex.visionsdk.dimensioning",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: error.localizedDescription]
            ))
            return
        }

        let mvView = DimensioningView(
            configuration: mvConfig,
            telemetry: VSDKDimensioningTelemetryBridge.shared,
            onCapture: { [weak self] mv in
                guard let self else { return }
                let wrapped = VSDKDimensioningSession.mapMeasurement(mv)
                self.delegate?.dimensioningView(self, didCapture: wrapped)
            }
        )

        let host = UIHostingController(rootView: AnyView(mvView))
        host.view.translatesAutoresizingMaskIntoConstraints = false
        host.view.backgroundColor = .clear
        addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: trailingAnchor),
            host.view.topAnchor.constraint(equalTo: topAnchor),
            host.view.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        hostingController = host
    }

    @objc public func stopRunning() {
        hostingController?.view.removeFromSuperview()
        hostingController = nil
    }

    @objc public func deConfigure() {
        stopRunning()
        delegate = nil
        configuration = nil
    }
}
