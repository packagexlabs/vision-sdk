Pod::Spec.new do |s|
  s.name             = 'VisionSDK'
  s.version          = '2.2.6'
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
  # Dimensioning ships as a prebuilt xcframework (its own `VisionSDKDimensioning`
  # Swift module). Consumers import it explicitly:
  #   import VisionSDKDimensioning
  #
  # CocoaPods does not allow `module_name` on subspecs, so we use the
  # xcframework's own module identity instead of compiling source files.
  # publish.yml unpacks the xcframeworks from VisionSDKDimensioning-bundle.zip.
  #
  # PostHog is deliberately NOT a dependency. MVDimensioningCore.xcframework
  # statically links its own copy (nm: ~1090 defined, 0 undefined), so nothing
  # is needed at link time. The dependency existed only to satisfy a bare
  # `import PostHog` line in MVDimensioningCore's .swiftinterface — the
  # xcframework ships no binary .swiftmodule, so consumers compile from the
  # textual interface and every import in it must resolve.
  #
  # That one line cost us real breakage: `~> 3.0` resolves to PostHog 3.69.0,
  # which added BUILD_LIBRARY_FOR_DISTRIBUTION = YES to its pod_target_xcconfig
  # and fails every clean (uncached) CocoaPods install with
  #   PostHog.swiftinterface:11:19: underlying Objective-C module 'PostHog' not found
  # Pinning below 3.69 only defers it. The import is now stripped from all nine
  # .swiftinterface files (enforced by the "Strip PostHog import" step in
  # .github/workflows/publish.yml), so PostHog leaves the consumer dependency
  # graph entirely. Nothing in the public API references a PostHog type, so
  # stripping is signature-safe. Do not re-add this dependency.
  s.subspec 'Dimensioning' do |d|
    d.dependency 'VisionSDK/Core'
    d.ios.deployment_target = '17.0'
    d.vendored_frameworks   = [
      'Sources/VisionSDKDimensioning.xcframework',
      'Sources/MVDimensioningCore.xcframework'
    ]
    d.resources             = 'Sources/MVDimensioning.mlmodelkey'
    d.frameworks            = ['ARKit', 'RealityKit', 'CoreML']
  end
end