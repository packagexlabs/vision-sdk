# VisionSDK
 Barcode and QR Code scanner framework for iOS. VisionSDK provides a way to detect barcodes and qr codes with both manual and auto capturing modes. It also provides OCR for text detection in offline(without internet) and online(label scanning with Restful API) modes. Written in Swift.

## Development Requirements
- iOS 13.0+
- Swift: 5.4.2
- Xcode Version: 13.0

## Installation
- In Xcode, move to "General > Build Phase > Linked Frameworks and Libraries"
- Add the framework to your project
- Make sure to mark it "Embed and Sign"
- Write Import statement on your source file
```swift
import VisionSDK
```

## Usage

### Add `Privacy - Camera Usage Description` to Info.plist file

<img src="https://github.com/packagexlabs/vision-sdk-sample-code/blob/main/Images/PermissionSettings.png" width="500">

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

## License

Copyright 2022 Muzamil Mughal.

Licensed under the MIT License.
