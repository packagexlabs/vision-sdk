Pod::Spec.new do |spec|

spec.name			= "VisionSDK"
spec.version			= "1.5.7"
spec.summary			= "Barcode and QR Code scanner framework for iOS."
spec.description		=  <<-DESC
Barcode and QR Code scanner framework for iOS. VisionSDK provides a way to detect barcodes and qr codes with both manual and auto capturing modes. It also provides OCR for text detection in offline(without internet) and online(label scanning with Restful API) modes. Written in Swift.
		DESC
spec.license			= "MIT"
spec.homepage			= 'https://github.com/packagexlabs/vision-sdk'
spec.author			= { "Muzamil Mughal" => "muzamilmughal81@gmail.com" }
spec.swift_version		= "5.0"
spec.ios.deployment_target 	= "16.0"
spec.source			= { :git => "https://github.com/packagexlabs/vision-sdk.git", :tag => spec.version }
spec.vendored_frameworks	= ['Sources/VisionSDK.xcframework', 'Sources/TensorFlowLiteC.xcframework']
spec.static_framework = true
spec.pod_target_xcconfig = {
    'OTHER_LDFLAGS' => '-lc++'
}
spec.user_target_xcconfig = {
'OTHER_LDFLAGS' => '-lc++'
}
  
end
