//
//  VSDKDimensioningMode.swift
//  VisionSDKDimensioning
//

import Foundation

/// Binary toggle for dimensioning processing location — mirrors VSDK's `OCRMode`.
///
/// - `offline`: on-device only. No network, no apiKey required.
/// - `online`: cloud-augmented segmentation. Requires `VSDKConstants.apiKey`
///   to be set before the session is started; otherwise the session throws
///   `VSDKDimensioningError.missingCredentials`.
@objc public enum VSDKDimensioningMode: Int, Sendable {
    case offline = 0
    case online = 1
}
