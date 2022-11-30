# VisionSDK
 Barcode and QR Code scanner framework for iOS. VisionSDK provides a way to detect barcodes and qr codes with both manual and auto capturing modes. It also provides OCR for text detection in offline(without internet) and online(label scanning with Restful API) modes. Written in Swift.

## Development Requirements
- iOS 13.0+
- Swift: 5.4.2
- Xcode Version: 13.0

## Installation

### CocoaPods

[CocoaPods](https://cocoapods.org) is a dependency manager for Cocoa projects. For usage and installation instructions, visit their website. To integrate VisionSDK into your Xcode project using CocoaPods, specify it in your `Podfile`:

```ruby
pod 'VisionSDK'
```

### Swift Package Manager

The [Swift Package Manager](https://swift.org/package-manager/) is a tool for automating the distribution of Swift code and is integrated into the `swift` compiler. 

Once you have your Swift package set up, adding VisionSDK as a dependency is as easy as adding it to the `dependencies` value of your `Package.swift`.

```swift
dependencies: [
    .package(url: "https://github.com/packagexlabs/vision-sdk-sample-code.git", .upToNextMajor(from: "1.0.0"))
]
```
### Manual Framework Integration

- In Xcode, move to "General > Build Phase > Linked Frameworks and Libraries"
- Add the [VisionSDK.xcframework](https://github.com/packagexlabs/vision-sdk-sample-code/tree/main/Sources/VisionSDK.xcframework) from  to your project
- Make sure to mark it "Embed and Sign"
- Write Import statement on your source file
```swift
import VisionSDK
```

## Usage

### Add `Privacy - Camera Usage Description` to Info.plist file

<img src="https://github.com/packagexlabs/vision-sdk-sample-code/blob/main/Images/PermissionSettings.png" width="500">

### Initialization

In order to use the OCR API, you have to set Constants.apiKey to your API key. Also, you also need to specify the API environment that you have the API key for. Please note that these have to be set before using the API call.

```swift
Constants.apiKey = "your_api_key"
Constants.apiEnvironment = .staging
```

### The Basis Of Usage

```swift
import VisionSDK
import AVFoundation

final class ViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        setupScanner()
    }

    private func setupScanner() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            setupScannerView()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                if granted {
                    DispatchQueue.main.async { [weak self] in
                        self?.setupScannerView()
                    }
                }
            }
        default:
            showAlert()
        }
    }

    private func setupScannerView() {
        let scannerView = CodeScannerView(frame: view.bounds)
        view.addSubview(scannerView)
        
        scannerView.configure(delegate: self, input: .init(focusImage: nil, shouldDisplayFocusImage: true, shouldScanInFocusImageRect: true, isTextIndicationOn: true, isBarCodeOrQRCodeIndicationOn: true, sessionPreset: .high, nthFrameToProcess: 10, captureMode: .auto, captureType: .single), scanMode: .autoBarCodeOrQRCode)
        
        scannerView.startRunning()
    }

    private func showAlert() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            let alert = UIAlertController(title: "Error", message: "Camera is required to use in this application", preferredStyle: .alert)
            alert.addAction(.init(title: "OK", style: .default))
            self?.present(alert, animated: true)
        }
    }
}

extension ViewController: CodeScannerViewDelegate {
    func codeScannerView(_ scannerView: CodeScannerView, didFailure error: CodeScannerError) {
        print(error)
    }
    
    func codeScannerView(_ scannerView: CodeScannerView, didSuccess codes: [String]) {
        print(codes)
    }
    
    // called when text, barcode, or qr code is detected in camera stream, Depends on configuration of scanner view e.g. isTextIndicationOn, isBarCodeOrQRCodeIndicationOn
    func codeScannerViewDidDetect(_ text: Bool, barCode: Bool, qrCode: Bool) {
    
    }
    
    // returns captured image with all barcodes detected in it
    func codeScannerView(_ scannerView: CodeScannerView, didCaptureOCRImage image: UIImage, withbarCodes barcodes: [String]) {
    
    }
}
```

### Customization

#### Source Code Way

```swift
override func viewDidLoad() {
        super.viewDidLoad()

        let scannerView = CodeScannerView(frame: view.bounds)
        
        scannerView.setScanModeTo(.barcode)
        scannerView.setCaptureTypeTo(.multiple)
        scannerView.setCaptureModeTo(.manual)
        
}
```

#### Interface Builder Way

|Setup Custom Class|
|-|
|<img src="https://github.com/packagexlabs/vision-sdk-sample-code/blob/main/Images/ViewClass.png" width="350">

## Functional Description

### Configuration Methods

```swift

scannerView.configure(delegate: VisionSDK.CodeScannerViewDelegate, input: VisionSDK.CodeScannerView.Input = .default, scanMode: VisionSDK.CodeScannerMode = .qrCode)

```
#### Parameters

- delegate - Should be the class that confirms to the CodeScannerViewDelegate protocol
- Input - Input struct defines the properties of scanner view. These properties are:
-- sdbnsdl


## License

Copyright 2022 Muzamil Mughal.

Licensed under the MIT License.
