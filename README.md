# VisionSDK iOS Integration

Barcode and QR Code scanner framework for iOS. VisionSDK provides a way to detect barcodes and qr codes with both manual
and auto capturing modes. It also provides OCR for text detection in offline(without internet) and online(label scanning
with Restful API) modes. Written in Swift.

![Example1](ReadMeContent/Videos/Sample/VisionSDKSample.gif)

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
    .package(url: "https://github.com/packagexlabs/vision-sdk.git", .upToNextMajor(from: "1.0.0"))
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

        // You can modify FocusSettings properties
        let focusSettings = CodeScannerView.FocusSettings()

        // You can modify focus ObjectDetectionConfiguration properties
        let objectDetectionConfiguration = CodeScannerView.ObjectDetectionConfiguration()

        // You can modify focus CameraSettings properties
        let cameraSettings = CodeScannerView.CameraSettings()
        
        // For price Tag mode you can use this
        let priceTagDetectionSettings = CodeScannerView.PriceTagDetectionSettings()
        scannerView.priceTagDetectionSettings = priceTagDetectionSettings
        
        
        scannerView.configure(delegate: self, focusSettings: focusSettings, objectDetectionConfiguration: objectDetectionConfiguration, cameraSettings: cameraSettings, captureMode: .auto, captureType: .single, scanMode: .autoBarCodeOrQRCode)
        
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

    // called when text, barcode, or qr code is detected in camera stream, Depends on configuration of scanner view in ObjectDetectionConfiguration e.g. isTextIndicationOn, isBarCodeOrQRCodeIndicationOn
    func codeScannerViewDidDetect(_ text: Bool, barCode: Bool, qrCode: Bool, document: Bool) {
        
    }

    // returns captured image with all barcodes detected in it, cropped image only document is detected in the image, and savedImageURL only if CameraSettings.shouldAutoSaveCapturedImage is set to true in scanner view configuration
    func codeScannerView(_ scannerView: VisionSDK.CodeScannerView, didCaptureOCRImage image: UIImage, withCroppedImge croppedImage: UIImage?, withbarCodes barcodes: [String], savedImageURL: URL?) {
    
    }

    // This function is called only in Price Tag mode and requires boolean return value to display verification success on camera view. Please note that .priceTag mode works only if you are authenticated user. For authentication, use VisionAPIManager.checkPriceTagAuthenticationWithKey method
    func codeScannerViewDidCapturePrice(_ price: String, withSKU sKU: String) -> Bool {
        
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

### Static Properties

- `videoDevice: AVCaptureDevice` - It is the video device that is being used by the CodeScannerView. Camera zoom, torch or other device based operations must be carried out on this property. It can be accessed using `CodeScannerView.videoDevice` syntax


### Configuration Methods

```swift

scannerView.configure(delegate: VisionSDK.CodeScannerViewDelegate, focusSettings: VisionSDK.CodeScannerView.FocusSettings = .default, objectDetectionConfiguration: VisionSDK.CodeScannerView.ObjectDetectionConfiguration = .default, cameraSettings: VisionSDK.CodeScannerView.CameraSettings = .default, captureMode: VisionSDK.CaptureMode = .auto, captureType: VisionSDK.CaptureType = .single, scanMode: VisionSDK.CodeScannerMode = .qrCode)

```

#### Parameters

- `delegate` - Should be the class that confirms to the `CodeScannerViewDelegate` protocol
- `FocusSettings` - FocusSettings struct defines the focus related properties of scanner view. These properties are:

    - `focusImage: UIImage?` - Image to be displayed in the centre if the view. If not provided, VisionSDK will use the
      default image. Note that focus rectangle frame is subject to change with respect to different scan modes.
      
    - `focusImageRect: CGRect` - Custom rect for the focus image. You can provide your preferred rect or use .zero for default. Note that default focus rectangle frame is subject to change with respect to different scan modes.

    - `shouldDisplayFocusImage: Bool` - set true if you need focused region to be drawn.

    - `shouldScanInFocusImageRect: Bool` - set true if you want to detect codes visible in focused region only. This will discard the codes detected to be outside of the focus image. This applies only in barcode, qr and autoBarCodeOrQRCode modes.
    
    - `showCodeBoundariesInMultipleScan: Bool` - set true if you want to display boundaries around detetcted codes in multiple code scan. Default value is 'true'
    
    - `validCodeBoundryBorderColor: UIColor` - Color of border drawn around detected valid code. Default value is '.green'
        
    - `validCodeBoundryBorderWidth: CGFloat` - Width of border drawn around detected valid code. Default value is '1.0'
            
    - `validCodeBoundryFillColor: UIColor` - Fill color of border drawn around detected valid code. Default value is '.green.withAlphaComponent(0.3)'
    
    - `inValidCodeBoundryBorderColor: UIColor` - Color of border drawn around detected invalid code. Default value is '.red'
        
    - `inValidCodeBoundryBorderWidth: CGFloat` - Width of border drawn around detected invalid code. Default value is '1.0'
            
    - `inValidCodeBoundryFillColor: UIColor` - Fill color of border drawn around detected invalid code. Default value is '.red.withAlphaComponent(0.3)'
      
    - `showDocumentBoundries: Bool` - Set true if you want to display boundaries around document detected in camera view.

    - `documentBoundryBorderColor: UIColor` - Set the border color of boundaries drawn around detected document in camera stream.
      
    - `documentBoundryFillColor: UIColor` - Set the fill color of boundaries drawn around detected document in camera stream.
      
    - `focusImageTintColor: UIColor` - Set the color you want the focus image to be displayed in when no code is detected.
      
    - `focusImageHighlightedColor: UIColor` - Set the color you want the focus image to be displayed in when code is detected.
      
- `ObjectDetectionConfiguration` - ObjectDetectionConfiguration struct defines the object detection properties of scanner view. These properties are:

    - `isTextIndicationOn: Bool` - Set false if you do not want to detect text in live camera feed. If set
      false `codeScannerViewDidDetect(_ text: Bool, barCode: Bool, qrCode: Bool, document: Bool)` method will send `text` parameter as
      false.

    - `isBarCodeOrQRCodeIndicationOn: Bool` - Set false if you do not want to detect bar codes or qrcodes in live camera
      feed. Using this proerty my be helpful in cases if you want to perform manual capture based on code detection.
      
    - `isDocumentIndicationOn: Bool` - Set false if you do not want to detect document in live camera feed.

    - `codeDetectionConfidence: Float` - You can set the minimum confidence level for codes detected. Those below the given value will be dicarded. Value must be set on the scale of 0 - 1. Default is `0.5`.
      
    - `documentDetectionConfidence: Float` - You can set the minimum confidence level for document detection. Those below the given value will be dicarded. Value must be set on the scale of 0 - 1. Default is `0.9`.
      
    - `secondsToWaitBeforeDocumentCapture: Double` - Time threshold to wait before capturing a document automatically in OCR mode. VisionSDK only captures the document if it is continuously detected for the n(value of this property) seconds. Default is `3.0`.
    
    - `selectedTemplateId: String?` - Set this value using the template id if you want to detect multiple codes using a specific template. To use a specific template, set the captureType to .multiple as well.

- `CameraSettings` - CameraSettings struct defines the camera related properties of scanner view. These properties are:

    - `sessionPreset: Session.Preset` - You can set session preset as per your requirement. Default is `.high`.

    - `nthFrameToProcess: Int` - This is the nth number of the frame that is processed for detection of text, barcodes,
      and qrcodes in live camera feed if enabled by `isTextIndicationOn` or `isBarCodeOrQRCodeIndicationOn`. Processing
      every single frame may be costly in terms of CPU usage and battery consumption. Default value is `10` which means
      that from camera stream of usual 30 fps, every 10 frame is processed. Its value should be set between 1 - 30.

    - `shouldAutoSaveCapturedImage: Bool` - Set true if you want captured image to be saved and get its URL. Upon true ` func codeScannerView(_ scannerView: VisionSDK.CodeScannerView, didCaptureOCRImage image: UIImage, withCroppedImge croppedImage: UIImage?, withbarCodes barcodes: [String], savedImageURL: URL?)` will respond with URL of the saved image as well. To clear stored images, you can use `CodeScannerView.removeAllSavedImages()` instance method.
      
- `PriceTagDetectionSettings` - PriceTagDetectionSettings struct defines the price tag related properties of scanner view. These properties are:

    - `shouldDisplayOnScreenIndicators: Bool` - Set true if you want to display price tag verification indicators on screen after detection.

    - `validTagImage: UIImage` - Set the image you want to be dispayed on camera stream when a price tag has been detected and verified as valid.

    - `invalidTagImage: UIImage` - Set the image you want to be dispayed on camera stream when a price tag has been detected and is invalid.
      
- `captureMode` - Defines whether the VisionSDK should capture codes automatically or not. If you want to capture
      code on user action, then set it to `.manual`. Default value is `.auto`. If otherwise, you will have to manually
      trigger scanning using `capturePhoto()` method.

- `captureType` - Set it to `.multiple` if you want to allow multiple results from scan. In `.manual` case, you will
      have to manually trigger scanning using `capturePhoto()` method.
 
- `scanMode` - Defines the scan mode. It has following options
    - `.barCode` - Detects barcodes only in this mode
    - `.qrCode` - Detects qr codes only in this mode
    - `.autoBarCodeOrQRCode` - Detects both bar codes and qr codes
    - `.ocr` - Use this mode to capture photos for later user in OCR API call.
    - `.photo` - Use this mode to capture regular photos without the need for OCR
    - `.priceTag` - Use this mode for scanning price tags. This mode is only available for authenticated users.

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

Use this method to trigger code scan or photo capture when you are scanning for multiple codes, in manual capture, OCR, or Photo
mode.

```swift

scannerView.saveImageToVisionSDK(image: UIImage, withName imageName: String) -> URL?

```

Use this method to save an image to VisionSDK internal storage and obtain its URL. Note that VisionSDK saves those images on device storage.

```swift

scannerView.removeAllSavedImages()

```

Use this method to delete all images saved internally by VisionSDK. Saving too many images might consume significant storage space, so you can use this method to free up space.

### Delegate Methods

```swift

func codeScannerView(_ scannerView: VisionSDK.CodeScannerView, didSuccess codes: [String])

```

This method returns with the codes scanned after successful scan

```swift

func codeScannerViewDidDetect(_ text: Bool, barCode: Bool, qrCode: Bool, document: Bool)

```

This method is called when text, barcode or qr code is detected in the camera stream. Values depend on whether text or
code indication is enabled while configuring the scanner view.

```swift

func codeScannerView(_ scannerView: VisionSDK.CodeScannerView, didCaptureOCRImage image: UIImage, withCroppedImge croppedImage: UIImage?, withbarCodes barcodes: [String], savedImageURL: URL?)

```

This method is called when `capturePhoto()` method is called in OCR or Photo Mode. It return with the captured image from camera stream, all the
detected codes in it, and an optional cropped document image if a document is detected with in the captured image. `savedImageURL` parameter provides with the on device image url if `CameraSettings.shouldAutoSaveCapturedImage` is set to `true` while configuring the scanner view.

```swift

func codeScannerView(_ scannerView: VisionSDK.CodeScannerView, didFailure error: VisionSDK.CodeScannerError)

```

This method is called when an error occurs in any stage of initializing or capturing the codes when there is none
detected.

### Custom Template Scanning Methods

In order to use custom templates for code scanning. It is necessary to create a template first. You can create templates using the following code

```swift

    func openTemplateController() {
    
        let scanController = GenerateTemplateController.instantiate()
        scanController.delegate = self
        
        if let sheet = scanController.sheetPresentationController {
            sheet.prefersGrabberVisible = true
        }
        
        self.present(scanController, animated: true)
    }
    
```

Listen to the `GenerateTemplateControllerDelegate` methods to get response.

```swift

    extension BarcodeViewController: GenerateTemplateControllerDelegate {
    
        // This function provides you with the ID of the template that has been created
        func templateScanController(_ controller: GenerateTemplateController, didFinishWith result: String) {
            print(result)
        }
    
        func templateScanController(_ controller: GenerateTemplateController, didFailWithError error: any Error) {
            controller.dismiss(animated: true)
        }
    
        func templateScanControllerDidCancel(_ controller: GenerateTemplateController) {
            print("Template creation cancelled")
        }
    }
    
```


NOTE: VisionSDK automatically saves created templates into its secure storage. You can access those saved template using methods below

```swift
    
    // This method returns all the ids of the saved templates
    CodeScannerView.getAllTemplates()

    // This method deletes the template with the specified ID
    CodeScannerView.deleteTemplateWithId(_ id: String)
    
    // This method deletes all saved templates
    CodeScannerView.deleteAllTemplates()
    
```

### On-Device OCR Methods

This method must be called first before using offline device OCR. Preparation should complete first without any errors. For that, listen to completion block in the params.

```swift

    // This method must be provided with `apiKey` or `token`.
    // modelClass: VSDKModelClass - Select required Model Class. Currently supported is .shippingLabel only
    // modelSize: VSDKModelSize - Select the model size for the above selected Model Class. Currently supported sizes are .micro and .large only.
    
    func prepareOfflineOCR(withApiKey apiKey: String? = nil, andToken token: String? = nil, forModelClass modelClass: VSDKModelClass, withModelSize modelSize: VSDKModelSize = .micro, withProgressTracking progress: ((_ currentProgress: Float, _ totalSize: Float)->())?, withCompletion completion:((_ error: NSError?)->())?)

```    

For extraction of data using Offline OCR use the following method.

```swift

    func extractDataFromImage(_ image: CIImage, withBarcodes barcodes: [String], _ completion: @escaping ((Data?, NSError?) -> Void))

```   

It returns with the OCR Response based on PackageX Platform API [Response](https://docs.packagex.io/docs/scans/models).
 
These methods are called on the shared instance of `OnDeviceOCRManager`. It can be accessed using `OnDeviceOCRManager.shared`
syntax.
    
### OCR Methods

```swift

func checkPriceTagAuthenticationWithKey(_ apiKey: String?, _ completion: @escaping ((_ error: NSError?) -> Void))

func callScanAPIWith(_ image: UIImage, andBarcodes barcodes: [String], andApiKey apiKey: String? = nil, andToken token: String? = nil, andLocationId locationId: String? = nil, andOptions options: [String: String], withImageResizing shouldResizeImage: Bool = true, _ completion: @escaping ((_ data: Data?, _ response: URLResponse?, _ error: NSError?)-> Void))

func callManifestAPIWith(_ image: UIImage, andBarcodes barcodes: [String], andApiKey apiKey: String? = nil, withImageResizing shouldResizeImage: Bool = true, _ completion: @escaping ((_ data: Data?, _ response: URLResponse?, _ error: NSError?) -> Void))

```

These methods are called on the shared instance of `VisionAPIManager`. It can be accessed using `VisionAPIManager.shared`
syntax. 

To use price tag scanning, you need to authenticate first using `checkPriceTagAuthenticationWithKey` method. Upon successful completion block calling without error, you can use price tag mode of scanner view.

`callScanAPIWith` method recieves the captured image and the API Key or AuthToken as parameters. It returns with the OCR Response from
PackageX Platform API [Response](https://docs.packagex.io/docs/scans/models).

`callManifestAPIWith` method recieves the captured image and the API Key as parameters.

# VisionSDK Android Integration

The VisionSDK Android Integration is a barcode and QR code scanner framework for Android that
provides a simple and efficient way to detect barcodes and QR codes in both manual and
automatic capturing modes. It also includes AI capabilites to extract information from logistic
documents.

Some key features of the VisionSDK Android Integration include:

- Barcode and QR code scanning
- Focus on a specific area of camera preview
- Document detection
- Capturing of image
- Information extraction from logistic documents (via both local ML models (offline) and REST API)
    - Shipping Label
    - Bill of Lading
    - Price Tag (under progress)

## Installation
Vision SDK is hosted on JitPack.io

First add JitPack to your root project

```kotlin
maven { url = uri("https://jitpack.io") }
```

Then add the following dependency to
your project's build.gradle file:

```kotlin
implementation ("com.github.packagexlabs:vision-sdk-android:v2.0.2")
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
VisionSDK.getInstance().initialize(
    environment = //TODO environment,
    authentication = //TODO authentication,
    manifest = //TODO manifest
)
```



### Basic Usage

Set initial setting/configurations of the camera:

```kotlin
    visionCameraView.setStateAndFocusSettings(ScreenState(), FocusSettings())

    val nthFrameToProcess = 15
    visionCameraView.shouldOnlyProcessNthFrame(nthFrameToProcess)
```

To start scanning for barcodes, QR codes, text or documents, use the startCamera method after it is initialized. See the code below for example:

```kotlin
private fun startScanning() {

    visionCameraView.setObjectDetectionConfiguration(
        ObjectDetectionConfiguration(
           isTextIndicationOn = true,
           isBarcodeOrQRCodeIndicationOn = true,
           isDocumentIndicationOn = true,
           secondsToWaitBeforeDocumentCapture = 3
        )
    )
    visionCameraView.setScannerCallback(object : ScannerCallback {
      fun detectionCallbacks(barcodeDetected: Boolean, qrCodeDetected: Boolean, textDetected: Boolean, documentDetected: Boolean) {

      }

      fun onBarcodesDetected(barcodeList: List<Barcode>) {

      }

      fun onFailure(exception: ScannerException) {

      }

      fun onImageCaptured(bitmap: Bitmap, imageFile: File?, value: List<Barcode>) {
         // bitmap: The bitmap of captured image
         // imageFile: Optional image file if user requested the image to be saved as file also.
         // value: List of barcodes that were detected in the given image.
      }
    })

    visionCameraView.setCameraLifecycleCallback(object : CameraLifecycleCallback {
      fun onCameraStarted()
      fun onCameraStopped()
    })

    visionCameraView.initialize {
        visionCameraView.startCamera()
    }
}
  ```

Note that, once `onBarcodesDetected` or `onImageCaptured` callbacks are called, VisionSDK will stop analysing camera feed for text or barcodes/QR codes.
This is to prevent extra processing and battery consumption. When client wants to start analysing camera feed again, after consuming the results of previous scan, client needs to call the following function:

```kotlin
  visionCameraView.rescan()
```

#### Scanning Modes

There are 2 types of scanning mode

1. `Auto` mode will auto-detect any Barcode or QR code based on the detection mode
2. `Manual` mode will detect Barcode or QR code upon calling `Capture`

#### Detection Modes

Detection mode is used to specify what the user is trying to detect

1. `Barcode` detects only barcode
2. `QRCode` detects only QR codes
3. `OCR` for OCR detection. This mode will capture an image for the user. User can then decide to extract logistic data from it using either API call or on-device OCR capabilities.
4. `PriceTag` is underprogress
5. `Photo` is used to take images without any processing

### Trigger Manual Capture

As mentioned above that we have a manual mode. For manual mode trigger, you can call `visionCameraView.capture()`,
based on the mode
it will be giving different callbacks

If detection mode is

1. `DetectionMode.Barcode` then it will trigger the `onBarcodeDetected` callback in case of barcode detection or throw an exception.
2. `DetectionMode.QRCode` will return QR code or throw exception.
3. `DetectionMode.OCR` will capture an image along with the current barcode and return in `onImageCaptured`

> __Make sure that when calling capture, scan mode should be manual__

#### Capturing image:

You can capture an image when mode is OCR. In OCR mode when `capture` is called, then in the callback,
it will return an image.

```kotlin
visionCameraView.captureImage()
```

```kotlin
fun onImageCaptured(bitmap: Bitmap, imageFile: File?, value: List<Barcode>) {
   //Image along with the barcodes
}
```

In the callback, it will return the image bitmap along with the barcodes list in the current frame.

### Making OCR Call:

To make an API call, first thing that the user needs to make sure is that `VisionSDK` is initialized. See [Initialization](#initialization) for details.

Once the user has successfully initialized `VisionSDK`, the they can use class `ApiManager` for making API calls.

Following are the APIs that are available for our users through VisionSDK:
1. Shipping Label (both online and on-device)
2. Bill of Lading (only online)

Logistic information can be extracted from image in these two contexts. Users can post these images using the following suspended functions from `ApiManager` class.

#### Shipping Label
```kotlin
val jsonResponse = ApiManager().shippingLabelApiCallSync(
   bitmap = bitmap,
   barcodeList = list,
   locationId = "OPTIONAL_LOCATION_ID"
   recipient = mapOf(
      "contact_id" to "CONTACT_ID_HERE"
   ),
   sender = mapOf(
      "contact_id" to "CONTACT_ID_HERE"
   ),
   options = mapOf(
      "match" to mapOf(
         "search" to listOf("recipients"),
         "location" to true
      ),
      "postprocess" to mapOf(
         "require_unique_hash" to false
      ),
      "transform" to mapOf(
         "tracker" to "outbound",
         "use_existing_tracking_number" to false
      )
   ),
   metadata = mapOf(
      "Test" to "Pass"
   )
)
```

#### Bill of Lading
```Kotlin
val jsonResponse = ApiManager().manifestApiCallSync(
   bitmap = bitmap,
   barcodeList = list
)
```

### Making On-Device Shipping Label Call (usage without internet):

You need internet to download the important files for the first time. These files are used for processing and extracting data from the images.
In order to do that, you need to use `configure()` function of the class `OnDeviceOCRManager`.

```kotlin
val onDeviceOCRManager = OnDeviceOCRManager(
   context: Context,
   platformType: PlatformType,
   modelClass: ModelClass,
   modelSize: ModelSize
)
```

`PlatformType` is an enum with following values:
```kotlin
Native // For Android native apps
Flutter // For Flutter apps
ReactNative // For React Native apps
```

`ModelClass` is an enum that is used to inform the VisionSDK of the kind of OCR capabilities you require in your app.
Following are its option:
```kotlin
ShippingLabel
BillOfLading
PriceTag
```
`PriceTag` is not currently supported.

`ModelSize` is another enum (an optional parameter here) that is used to inform the VisionSDK about the type of files that it should download. Following are the types of model:
```kotlin
Nano
Micro
Small
Medium
Large
XLarge
```
Currently, only `Micro` and `Large` are supported.

After creating the instance of `OnDeviceOCRManager`, you need to call its `configure()` function. This suspend function will download the important files, if needed, and then load them in memory.

```kotlin
onDeviceOCRManager.configure(
   executionProvider: ExecutionProvider = ExecutionProvider.NNAPI,
   progressListener: (suspend (Float) -> Unit)? = null
)
```

`ExecutionProvider` parameter is optional and usually the default value works fine.

The `progressListener` (also optional) parameter can be used to track the progress of downloading and loading of files. Its value is from 0.0 to 1.0.

After configuring it successfully, you need to call the function `getPredictions()` and pass it the bitmap of the image you want to perform OCR operations on and list of barcodes to try to extract information using regex.

```kotlin
onDeviceOCRManager.getPredictions(bitmap: Bitmap, barcodes: List<Barcode>): String
```

The result of `getPredictions()` function is a JSON response in `String` form.

Once you are done with the `OnDeviceOCRManager`, make sure that you destroy its instance by calling the following method:

```kotlin
onDeviceOCRManager.destroy()
```

## Attribution
https://www.flaticon.com/free-icon/browser_9892390?term=template&page=1&position=10&origin=search&related_id=9892390

## License

Copyright © 2024 PackageX Labs.

Licensed under the MIT License.
