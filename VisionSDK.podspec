Pod::Spec.new do |s|
  s.name             = 'VisionSDK'
  s.version          = '2.6.0'
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
  s.platform         = :ios, '16.0'
  s.source           = { :git => 'https://github.com/packagexlabs/vision-sdk.git', :tag => s.version.to_s }
  s.default_subspecs = 'Core'

  # --- Core subspec ---
  # Prebuilt binary. No source_files -- VisionSDK.xcframework is the module.
  s.subspec 'Core' do |c|
    c.ios.deployment_target = '16.0'
    c.vendored_frameworks   = [
      'Sources/VisionSDK.xcframework',
      'Sources/TensorFlowLiteC.xcframework'
    ]
    c.pod_target_xcconfig   = { 'OTHER_LDFLAGS' => '-lc++' }
    c.user_target_xcconfig  = { 'OTHER_LDFLAGS' => '-lc++' }
  end

  # --- Dimensioning subspec ---
  # Dimensioning ships as a prebuilt xcframework (its own `VisionSDKDimensioning`
  # Swift module). Consumers import it explicitly:
  #   import VisionSDKDimensioning
  #
  # CocoaPods does not allow `module_name` on subspecs, so we use the
  # xcframework's own module identity instead of compiling source files.
  # publish.yml unpacks the xcframeworks from VisionSDKDimensioning-bundle.zip.
  s.subspec 'Dimensioning' do |d|
    d.dependency 'VisionSDK/Core'
    # No PostHog dependency: MVDimensioningCore.xcframework statically embeds
    # its own copy, and VisionSDK never imports PostHog itself. Declaring it
    # here pulled in a second copy -- both a duplicate-class hazard and, via
    # `~> 3.0`, a path to PostHog >= 3.69, which breaks CocoaPods consumers.
    d.ios.deployment_target = '17.0'
    d.vendored_frameworks   = [
      'Sources/VisionSDKDimensioning.xcframework',
      'Sources/MVDimensioningCore.xcframework'
    ]
    # No d.resources: as of VisionSDK 2.7.0 the bundled YOLO + SAM2 models ship
    # unencrypted inside MVDimensioningCore.xcframework, so MVDimensioning.mlmodelkey
    # is gone along with the Apple ModelKeyServerService round-trip and its
    # team-ID gate. Third-party teams now work offline from first launch.
    d.frameworks            = ['ARKit', 'RealityKit', 'CoreML']
  end
end