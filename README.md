# VisionSDK iOS Integration

Barcode and QR Code scanner framework for iOS. VisionSDK provides a way to detect barcodes and qr codes with both manual
and auto capturing modes. It also provides OCR for text detection in offline(without internet) and online(label scanning
with Restful API) modes. Written in Swift.

## Development Requirements

- iOS 15.0+
- Swift: 5.7
- Xcode Version: 13.0

## Installation

### CocoaPods

[CocoaPods](https://cocoapods.org) is a dependency manager for Cocoa projects. For usage and installation instructions,
visit their website. To integrate VisionSDK into your Xcode project using CocoaPods, specify it in your `Podfile`:

```ruby
pod 'VisionSDK'
```

### Swift Package Manager

The [Swift Package Manager](https://swift.org/package-manager/) is a tool for automating the distribution of Swift code
and is integrated into the `swift` compiler.

Once you have your Swift package set up, adding VisionSDK as a dependency is as easy as adding it to the `dependencies`
value of your `Package.swift`.

```swift
dependencies: [
    .package(url: "https://github.com/packagexlabs/vision-sdk-sample-code.git", .upToNextMajor(from: "1.0.0"))
]
```

### Manual Framework Integration

- In Xcode, move to "General > Build Phase > Linked Frameworks and Libraries"
- Add
  the [VisionSDK.xcframework](https://github.com/packagexlabs/vision-sdk-sample-code/tree/main/Sources/VisionSDK.xcframework)
  from to your project
- Make sure to mark it "Embed and Sign"
- Write Import statement on your source file

```swift
import VisionSDK
```

## Usage

### Add `Privacy - Camera Usage Description` to Info.plist file
<img width="500" alt="PermissionSettings" src="https://user-images.githubusercontent.com/33172684/210703473-6c2b3cc8-2b8f-41d5-9f66-22f7244db759.png">

### Initialization

In order to use the OCR API, you have to set Constants.apiKey to your API key. Also, you also need to specify the API
environment that you have the API key for. Please note that these have to be set before using the API call. You can
generate your own API key at [cloud.packagex.io](https://cloud.packagex.io/auth/login). You can find the instruction
guide [here](https://docs.packagex.io/docs/getting-started/welcome).

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
        
        scannerView.configure(delegate: self, input: .init(focusImage: nil, focusImageRect: .zero, shouldDisplayFocusImage: true, shouldScanInFocusImageRect: true, isTextIndicationOn: true, isBarCodeOrQRCodeIndicationOn: true, sessionPreset: .high, nthFrameToProcess: 10, captureMode: .auto, captureType: .single, codeDetectionConfidence: 0.8), scanMode: .autoBarCodeOrQRCode)
        
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
    func codeScannerView(_ scannerView: CodeScannerView, didCaptureOCRImage image: UIImage, withCroppedImge croppedImage: UIImage?, withbarCodes barcodes: [String]) {
    
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
<img width="350" alt="ViewClass" src="https://user-images.githubusercontent.com/33172684/210703616-ecbb951b-3e99-4b5c-8953-bb8c77ff0886.png">

## Functional Description

### Configuration Methods

```swift

scannerView.configure(delegate: VisionSDK.CodeScannerViewDelegate, input: VisionSDK.CodeScannerView.Input = .default, scanMode: VisionSDK.CodeScannerMode = .qrCode)

```

#### Parameters

- `delegate` - Should be the class that confirms to the `CodeScannerViewDelegate` protocol
- `Input` - Input struct defines the properties of scanner view. These properties are:

    - `focusImage: UIImage` - Image to be displayed in the centre if the view. If not provided, VisionSDK will use the
      default image. Note that focus rectangle frame is subject to change with respect to different scan modes.
      
    - `focusImageRect: CGRECT` - Custom rect for the focus image. You can provide your preferred rect or use .zero for default. Note that default focus rectangle frame is subject to change with respect to different scan modes.

    - `shouldDisplayFocusImage: Bool` - set true if you need focused region to be drawn.

    - `shouldScanInFocusImageRect: Bool` - set true if you want to detect codes visible in focused region only. This
      will discard the codes detected to be outside of the focus image.

    - `isTextIndicationOn: Bool` - Set false if you do not want to detect text in live camera feed. If set
      false `codeScannerViewDidDetect(_ text: Bool, barCode: Bool, qrCode: Bool)` method will send `text` parameter as
      false.

    - `isBarCodeOrQRCodeIndicationOn: Bool` - Set false if you do not want to detect bar codes or qrcodes in live camera
      feed. Using this proerty my be helpful in cases if you want to perform manual capture based on code detection.

    - `sessionPreset: Session.Preset` - You can set session preset as per your requirement. Default is `.high`.

    - `nthFrameToProcess: Int` - This is the nth number of the frame that is processed for detection of text, barcodes,
      and qrcodes in live camera feed if enabled by `isTextIndicationOn` or `isBarCodeOrQRCodeIndicationOn`. Processing
      every single frame may be costly in terms of CPU usage and battery consumption. Default value is `10` which means
      that from camera stream of usual 30 fps, every 10 frame is processed. Its value should be set between 1 - 30.

    - `captureMode` - Defines whether the VisionSDK should capture codes automatically or not. If you want to capture
      code on user action, then set it to `.manual`. Default value is `.auto`. If otherwise, you will have to manually
      trigger scanning using `capturePhoto()` method.

    - `captureType` - Set it to `.multiple` if you want to allow multiple results from scan. In `.manual` case, you will
      have to manually trigger scanning using `capturePhoto()` method.
      
    - `codeDetectionConfidence: Float` - You can set the minimum confidence level for codes detected. Those below the given value will be dicarded. Value must be set on the scale of 0 - 1. Default is `0.8`.


- `scanMode` - Defines the scan mode. It has following options
    - `.barCode` - Detects barcodes only in this mode
    - `.qrCode` - Detects qr codes only in this mode
    - `.ocr` - Use this mode to capture photos for later user in OCR API call.
    - `.autoBarCodeOrQRCode` - Detects both bar codes and qr codes

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

Use this method to trigger code scan or photo capture when you are scanning for multiple codes, in manual capture or OCR
mode.

### Delegate Methods

```swift

func codeScannerView(_ scannerView: VisionSDK.CodeScannerView, didSuccess codes: [String])

```

This method returns with the codes scanned after successful scan

```swift

func codeScannerViewDidDetect(_ text: Bool, barCode: Bool, qrCode: Bool)

```

This method is called when text, barcode or qr code is detected in the camera stream. Values depend on whether text or
code indication is enabled while configuring the scanner view.

```swift

func codeScannerView(_ scannerView: CodeScannerView, didCaptureOCRImage image: UIImage, withCroppedImge croppedImage: UIImage?, withbarCodes barcodes: [String])

```

This method is called when `capturePhoto()` method is called in OCR Mode. It return with the captured image from camera stream, all the
detected codes in it, and an optional cropped document image if a document is detected with in the captured image.

```swift

func codeScannerView(_ scannerView: VisionSDK.CodeScannerView, didFailure error: VisionSDK.CodeScannerError)

```

This method is called when an error occurs in any stage of initializing or capturing the codes when there is none
detected.

### OCR Methods

```swift

func callScanAPIWith(_ image: UIImage, andBarcodes barcodes: [String], andApiKey apiKey: String? = nil, andToken token: String? = nil, andLocationId locationId: String? = nil, andOptions options: [String: String], _ completion: @escaping ((_ data: Data?, _ response: URLResponse?, _ error: NSError?)-> Void))

```

This method is called on the shared instance of `VisionAPIManager`. It can be accessed using `VisionAPIManager.shared`
syntax. This method recieves the captured image and the API Key or AuthToken as parameters. It returns with the OCR Response from
PackageX Platform API [Response](https://docs.packagex.io/docs/scans/models).

# VisionSDK Android Integration

The VisionSDK Android Integration is a barcode and QR code scanner framework for Android that
provides a simple and efficient way to detect barcodes and QR codes in both manual and
automatic capturing modes. It also includes OCR (Optical Character Recognition) capabilities
for text detection (label scanning with a Restful API) modes.

Some key features of the VisionSDK Android Integration include:

- Support for multiple view types (rectangular, square, fullscreen) for the scanning window
- Customization options for the scanning window size, shape, and border style
- Capture image and OCR API capabilities

## Installation
Vision SDK is hosted on JitPack.io

First add JitPack to your root project

```kotlin
maven { url "https://jitpack.io" }
```

Then add the following dependency to
your project's build.gradle file:

```
implementation 'com.github.packagexlabs:vision-sdk-android:v1.0'
```

## Usage

### Initialization

In order to use the OCR API, you have to set Constants.apiKey to your API key. Also, you also need to specify the API
environment that you have the API key for. Please note that these have to be set before using the API call. You can
generate your own API key at [cloud.packagex.io](https://cloud.packagex.io/auth/login). You can find the instruction
guide [here](https://docs.packagex.io/docs/getting-started/welcome).

Initialise the SDK first:

There are 2 ways for authentication
1. Via API key
2. Via Token

```kotlin
VisionSDK.getInstance().initialise(
    authentication = //TODO authentication,
    environment = //TODO environment
)
```

```kotlin
VisionSDK.getInstance().initialise(
    apiKey = Authentication.BearerToken(/*Yoru token here*/),
    environment = Environment.STAGING
)
```



### Basic Usage

To start scanning for barcodes and QR codes, use the startScanning method and specify the view type:

```kotlin
private fun startScanning() {

    //setting the scanning window configuration
    binding.customScannerView.startScanning(
        viewType = screenState.scanningWindow,
        scanningMode = screenState.scanningMode,
        detectionMode = screenState.detectionMode,
        scannerCallbacks = this
    )
}
  ```

#### View Types

There are 2 types of scanning windows:

1. `ViewType.WINDOW` by default show a square window for QR detection mode and rectangle for Barcode
2. `ViewType.FULLSCREEN` whole screen

#### Scanning Modes

There are 2 types of scanning mode

1. `Auto` mode will auto-detect any Barcode or QR code based on the detection mode
2. `Manual` mode will detect Barcode or QR code upon calling `Capture`

#### Detection Modes

Detection mode will tell which codes to detect

1. `QrAndBarcode` detects Barcode and QR Codes collectively
2. `Barcode` detects only barcode
3. `QR` detects only QR codes
4. `OCR` for OCR detection. This mode will call OCR api

#### Callback

There is also Scanner Callback that we need to provide while starting scanning. This is an interface,
and it will be giving different callbacks based on the detection mode.

1. `onBarcodeDetected` whenever a Barcode or QR code is detected in Single model
2. `onImageCaptured` whenever image is capture in OCR mode
3. `onMultipleBarcodesDetected` when multiple barcodes are detected in multiple mode
4. `onFailure` when some exception is thrown or unable to detect any Barcode/QRCode in manual mode

#### Common Exception Classes

When there is no Barcode or QRCode detected with in a specific time window then SDK
will throw an exception. There are custom exceptions

1. `BarCodeNotDetected` when no barcode detected in manual mode
2. `QRCodeNotDetected` when QR code not detected in manual mode

### Customizing the Scanning Window

To customize the appearance and behavior of the scanning window, you can use the setScanningWindowConfiguration method
and provide a configuration object with your desired settings.

For example:

As `ViewType.RECTANGLE` and `ViewType.SQUARE` have a window, you can configure the scanning window according to yours
requirements. There is also option for setting the scanning window radius along with vertical starting point.

  ```kotlin
//Setting the Barcode and QR code scanning window sizes
binding.customScannerView.setScanningWindowConfiguration(
    Configuration(
        barcodeWindow = ScanWindow(
            width = ((binding.root.width * 0.9).toFloat()),
            height = ((binding.root.width * 0.4).toFloat()),
            radius = 10f,
            verticalStartingPosition = (binding.root.height / 2) - ((binding.root.width * 0.4).toFloat())
        ), qrCodeWindow = ScanWindow(
            width = ((binding.root.width * 0.7).toFloat()),
            height = ((binding.root.width * 0.7).toFloat()),
            radius = 10f,

            //you can set the vertical position of the scanning window
            verticalStartingPosition = (binding.root.height / 2) - ((binding.root.width * 0.5).toFloat())
        )
    )
)
```

### Example

Following are some example of initialise the Scanner with the above-mentioned configuration

It will be detecting the Barcode manually with in a rectangular area

```kotlin
binding.customScannerView.startScanning(
    viewType = ViewType.WINDOW,
    scanningMode = ScanningMode.Manual,
    detectionMode = DetectionMode.Barcode,
    this
)

```

It will be using full screen for QR code detection and will auto capture barcodes

```kotlin
binding.customScannerView.startScanning(
    viewType = ViewType.FULLSCRREN,
    scanningMode = ScanningMode.Auto,
    detectionMode = DetectionMode.QR,
    this
)
```

### Listening to the Barcode and Text Detection

For the barcode and indicator there are different live data that you can observe

1. `customScannerView.barcodeIndicators` will post a new value whenever a new barcode or QR code is detected. To
   distinguish between Barcode and QR Code you can use the format field
   e.g `TWO_DIMENSIONAL_FORMATS.contains(it.format)`
2. `customScannerView.textIndicator` will post a new value whenever a text is detected on the screen

### Trigger Manual Capture

As mentioned above that we have a manual mode. For manual mode trigger, you can call `customScannerView.capture()`,
based on the mode
it will be giving different callbacks

if detection mode is

1. `DetectionMode.Barcode` then it will trigger the `onBarcodeDetected` callback in case of barcode
   detection or throw an exception `QRCodeNotDetected` if barcode not detected with in specific time frame.
2. `DetectionMode.QR` will return QR code or throw Exception of `BarCodeNotDetected`
3. `DetectionMode.OCR` will capture an image along with the current barcode and return in `onImageCaptured`

> __Make sure that when calling capture, scan mode should be manual__

### Capturing Image and OCR API

With the above live data options, the SDK also provide option to Capture Image and call OCR Api.

#### Capturing image:

You can capture an image when mode is OCR. In OCR mode when `capture` is called, then in the callback,
it will return an image.

```kotlin
customScannerView.captureImage()
```

#### Callback

```kotlin
fun onImageCaptured(bitmap: Bitmap, value: MutableList<Barcode>?) {
    //Image along with the barcodes
}
```

In the callback, it will return the image bitmap along with the barcodes list in the current frame.

### Making OCR Call:

For the OCR Api call, you need to set the Environment, there are multiple environment,
plus the Api key. You can call `makeOCRApiCall` for the ocr analysis on a bitmap. Bitmap and barcodes needs to be
provided. If there are no barcodes,
then provide an empty list.

Below is an example:

```kotlin
customScannerView.makeOCRApiCall(
    bitmap = bitmap,
    barcodeList = list,
    onScanResult = object : OCRResult {
        override fun onOCRResponse(ocrResponse: OCRResponse?) {
            //Successful result
        }

        override fun onOCRResponseFailed(throwable: Throwable?) {
            //Some issue occurred
        }
    })
```

In the callbacks, Success or error will be returned.
It returns with the OCR Response from PackageX Platform API [Response](https://docs.packagex.io/docs/scans/models).

## License

Copyright 2022 PackageX Labs.

Licensed under the MIT License.

