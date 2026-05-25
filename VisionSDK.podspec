Pod::Spec.new do |s|
  s.name             = 'VisionSDK'
  s.version          = '2.2.5'
  s.summary          = "PackageX VisionSDK — barcode/OCR/price-tag scanning and optional 3D box dimensioning."
  s.description      = <<-DESC
    PackageX VisionSDK is a comprehensive scanning framework for iOS. It provides
    barcode and QR code detection with both manual and auto capturing modes,
    OCR for text detection in offline (without internet) and online
    (shipping-label scanning with Restful API) modes, and optional ARKit/LiDAR-based
    3D box dimensioning (iOS 17+, opt-in via the Dimensioning subspec).
  DESC
  s.license          = { :type => 'Proprietary' }
  s.homepage         = 'https://github.com/packagexlabs/vision-sdk'
  s.author           = { 'PackageX' => 'engineering@packagex.io' }
  s.swift_version    = '5.0'
  s.platform         = :ios, '13.0'
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
  # Dimensioning ships as its own Swift module (`VisionSDKDimensioning`)
  # built from source against the prebuilt Core xcframework. The wrapper
  # Swift files use `@_spi(VSDKDimensioning) import VisionSDK` to access
  # Core's SPI types. Consumers import the module explicitly:
  #   import VisionSDKDimensioning
  #
  # Sources land in Sources/VisionSDKDimensioning/ after publish.yml unpacks
  # VisionSDKDimensioning-bundle.zip from the vision-sdk-ios release.
  s.subspec 'Dimensioning' do |d|
    d.dependency 'VisionSDK/Core'
    d.dependency 'PostHog', '~> 3.0'
    d.ios.deployment_target = '17.0'
    d.module_name           = 'VisionSDKDimensioning'
    d.source_files          = 'Sources/VisionSDKDimensioning/*.swift'
    d.vendored_frameworks   = 'Sources/MVDimensioningCore.xcframework'
    d.resources             = 'Sources/MVDimensioning.mlmodelkey'
    d.frameworks            = ['ARKit', 'RealityKit', 'CoreML']
  end
end