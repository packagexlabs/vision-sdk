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


In order to use the OCR API, you have to set Constants.apiKey to your API key. Also, you also need to specify the API environment that you have the API key for. Please note that these have to be set before using the API call. You can generate your own API key at [cloud.packagex.io](https://cloud.packagex.io/auth/login). You can find the instruction guide [here](https://docs.packagex.io/docs/getting-started/welcome).

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

- `delegate` - Should be the class that confirms to the `CodeScannerViewDelegate` protocol
- `Input` - Input struct defines the properties of scanner view. These properties are:

  -   `focusImage: UIImage` - Image to be displayed in the centre if the view. If not provided, VisionSDK will use the default image. Note that focus rectangle frame is subject to change with respect to different scan modes.

  -   `shouldDisplayFocusImage: Bool` - set true if you need focused region to be drawn.

  -   `shouldScanInFocusImageRect: Bool` - set true if you want to detect codes visible in focused region only. This will discard the codes detected to be outside of the focus image.

  -   `isTextIndicationOn: Bool` - Set false if you do not want to detect text in live camera feed. If set false `codeScannerViewDidDetect(_ text: Bool, barCode: Bool, qrCode: Bool)` method will send `text` parameter as false.

  -   `isBarCodeOrQRCodeIndicationOn: Bool` - Set false if you do not want to detect bar codes or qrcodes in live camera feed. Using this proerty my be helpful in cases if you want to perform manual capture based on code detection.

  -   `sessionPreset: Session.Preset` - You can set session preset as per your requirement. Default is `.high`.

  -   `nthFrameToProcess: Int` - This is the nth number of the frame that is processed for detection of text, barcodes, and qrcodes in live camera feed if enabled by `isTextIndicationOn` or `isBarCodeOrQRCodeIndicationOn`. Processing every single frame may be costly in terms of CPU usage and battery consumption. Default value is `10` which means that from camera stream of usual 30 fps, every 10 frame is processed. Its value should be set between 1 - 30.

  -   `captureMode` - Defines whether the VisionSDK should capture codes automatically or not. If you want to capture code on user action, then set it to `.manual`. Default value is `.auto`. If otherwise, you will have to manually trigger scanning using `capturePhoto()` method.

  -   `captureType` - Set it to `.multiple` if you want to allow multiple results from scan. In `.manual` case, you will have to manually trigger scanning using `capturePhoto()` method.

- `scanMode` - Defines the scan mode. It has following options
  -   `.barCode` - Detects barcodes only in this mode
  -   `.qrCode` - Detects qr codes only in this mode
  -   `.ocr` - Use this mode to capture photos for later user in OCR API call.
  -   `.autoBarCodeOrQRCode` - Detects both bar codes and qr codes

```swift

scannerView.setScanModeTo(_ mode: VisionSDK.CodeScannerMode)

```
Sets the scan mode to desired mode.


```swift

scannerView.setCaptureModeTo(_ mode: VisionSDK.CaptureMode)

```
Sets the capture mode to desired mode.


```swift

scannerView.setCaptureTypeTo(_ type: VisionSDK.CaptureType)

```
Sets the capture type to desired type.


```swift

scannerView.startRunning()

```
Needs `configure()` method to be called before it. It starts the camera session and scanning.


```swift

scannerView.stopRunning()

```
Stops camera session and scanning.


```swift

scannerView.rescan()

```
Use this function to resume scanning


```swift

scannerView.deConfigure()

```
Removes all the configurations of scannerView and stops scanning.


```swift

scannerView.capturePhoto()

```
Use this method to trigger code scan or photo capture when you are scanning for multiple codes, in manual capture or OCR mode.


### Delegate Methods

```swift

func codeScannerView(_ scannerView: VisionSDK.CodeScannerView, didSuccess codes: [String])

```
This method returns with the codes scanned after successful scan


```swift

func codeScannerViewDidDetect(_ text: Bool, barCode: Bool, qrCode: Bool)

```
This method is called when text, barcode or qr code is detected in the camera stream. Values depend on whether text or code indication is enabled while configuring the scanner view.


```swift

func codeScannerView(_ scannerView: VisionSDK.CodeScannerView, didCaptureOCRImage image: UIImage, withbarCodes barcodes: [String])

```
This method is called when `capturePhoto()` method is called in OCR Mode. It return with the captured image and all the detected codes in it.


```swift

func codeScannerView(_ scannerView: VisionSDK.CodeScannerView, didFailure error: VisionSDK.CodeScannerError)

```
This method is called when an error occurs in any stage of initializing or capturing the codes when there is none detected.


### OCR Methods

```swift

func callScanAPIWith(_ image: UIImage, andApiKey apiKey: String, _ completion: @escaping ((_ data: Data?, _ response: URLResponse?, _ error: NSError?) -> Void))

```
This method is called on the shared instance of `VisionAPIManager`. It can be accessed using `VisionAPIManager.shared` syntax. This method recieves the captured image and the API Key as parameters. It returns with the OCR Response from PackageX Platform API [Response](https://docs.packagex.io/docs/scans/models).


## License

Copyright 2022 Muzamil Mughal.

Licensed under the MIT License.
