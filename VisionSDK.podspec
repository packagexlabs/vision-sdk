Pod::Spec.new do |s|
  s.name             = 'VisionSDK'
  s.version          = '2.2.4'
  s.summary          = "PackageX VisionSDK — barcode/OCR/price-tag scanning and optional 3D box dimensioning."
  s.description      = <<-DESC
    Barcode and QR Code scanner framework for iOS. VisionSDK provides a way to
    detect barcodes and QR codes with both manual and auto capturing modes. It
    also provides OCR for text detection in offline (without internet) and online
    (label scanning with Restful API) modes. Optional ARKit/LiDAR-based box
    dimensioning is available via the Dimensioning subspec (iOS 17+).
  DESC
  s.license          = { :type => 'Proprietary' }
  s.homepage         = 'https://github.com/packagexlabs/vision-sdk'
  s.author           = { 'PackageX' => 'engineering@packagex.io' }
  s.swift_version    = '5.0'
  s.source           = { :git => 'https://github.com/packagexlabs/vision-sdk.git', :tag => s.version.to_s }
  s.default_subspecs = 'Core'

  # --- Core subspec ---
  # Prebuilt binary. No source_files -- VisionSDK.xcframework is the module.
  s.subspec 'Core' do |c|
    c.ios.deployment_target = '13.0'
    c.vendored_frameworks   = [
      'Sources/VisionSDK.xcframework',
      'Sources/TensorFlowLiteC.xcframework'
    ]
    c.pod_target_xcconfig   = { 'OTHER_LDFLAGS' => '-lc++' }
    c.user_target_xcconfig  = { 'OTHER_LDFLAGS' => '-lc++' }
  end

  # --- Dimensioning subspec ---
  # Compiles the dimensioning wrapper Swift sources into the *VisionSDK* module
  # (no module_name override -- inherits parent spec name). Consumers use
  #   import VisionSDK
  # and get VSDKDimensioning, VSDKDimensioningView, etc. directly.
  #
  # Sources land in Sources/VisionSDKDimensioning/ after publish.yml unpacks
  # VisionSDKDimensioning-bundle.zip from the vision-sdk-ios release.
  # MVDimensioningCore.xcframework (226 MB) is stored via Git LFS.
  s.subspec 'Dimensioning' do |d|
    d.dependency 'VisionSDK/Core'
    d.dependency 'PostHog', '~> 3.0'
    d.ios.deployment_target = '17.0'
    d.source_files          = 'Sources/VisionSDKDimensioning/*.swift'
    d.vendored_frameworks   = 'Sources/MVDimensioningCore.xcframework'
    d.resources             = 'Sources/MVDimensioning.mlmodelkey'
    d.frameworks            = ['ARKit', 'RealityKit', 'CoreML']
    d.pod_target_xcconfig   = {
      'OTHER_SWIFT_FLAGS' => '$(inherited) -DVSDK_DIMENSIONING_MONOLITH'
    }
  end
end