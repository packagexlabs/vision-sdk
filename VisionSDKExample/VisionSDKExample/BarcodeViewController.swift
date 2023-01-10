//
//  BarcodeViewController.swift
//  VisionSDK
//
//  Created by Muzamil.Mughal on 19/05/2022.
//

import Foundation
import UIKit
import AVFoundation
import VisionSDK

public enum CameraZoomLevel: Double {
    case one = 1
    case two = 1.5
    case three = 2
}

class BarcodeViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    
    @IBOutlet weak var buttonSound: RoundButtonView!
    @IBOutlet weak var buttonTorch: RoundButtonView!
    @IBOutlet weak var buttonTextIndicator: RoundButtonView!
    @IBOutlet weak var buttonBarCodeIndicator: RoundButtonView!
    @IBOutlet weak var buttonQRCodeIndicator: RoundButtonView!
    @IBOutlet weak var buttonAuto: UIButton!
    @IBOutlet weak var buttonBarCode: UIButton!
    @IBOutlet weak var buttonQrCode: UIButton!
    @IBOutlet weak var buttonOCR: UIButton!
    @IBOutlet weak var buttonCamera: UIButton!
    @IBOutlet weak var buttonZoomLevelOne: UIButton!
    @IBOutlet weak var buttonZoomLevelTwo: UIButton!
    @IBOutlet weak var buttonZoomLevelThree: UIButton!
    @IBOutlet weak var viewZoomButtonsBackground: UIView!
    @IBOutlet weak var segmentedControlCaptureMode: UISegmentedControl!
    @IBOutlet weak var segmentedControlOCRMode: UISegmentedControl!
    @IBOutlet weak var autoScannerView: CodeScannerView!
    @IBOutlet weak var barCodeScannerView: CodeScannerView!
    @IBOutlet weak var qrCodeScannerView: CodeScannerView!
    @IBOutlet weak var oCRScannerView: CodeScannerView!

    @IBOutlet weak var viewOnlineOCR: UIView!
    
    @IBOutlet weak var viewProgressBarBackground: UIView!
    @IBOutlet weak var constraintProgressBarWidth: NSLayoutConstraint!
    
    @IBOutlet weak var imageViewCapturedImage: UIImageView!
    
    var didLayoutSubviews = false
    var soundEffect: AVAudioPlayer?
    
    var viewOCRResponse: OCRResponseView?
    
    var scanMode: CodeScannerMode = .autoBarCodeOrQRCode
    var currentZoomLevel: CameraZoomLevel = .two
    var captureMode: CaptureMode = .manual
    var ocrMode: OCRMode = .online

    override func viewDidLoad() {
        super.viewDidLoad()
        
        self.buttonSound.setImageWithSystemName(name: "speaker.wave.2")
        self.buttonTorch.setImageWithSystemName(name: "bolt.slash")
        self.buttonTextIndicator.setImageWithSystemName(name: "textformat.alt", withTintColor: .gray)
        self.buttonBarCodeIndicator.setImageWithSystemName(name: "barcode", withTintColor: .gray)
        self.buttonQRCodeIndicator.setImageWithSystemName(name: "qrcode", withTintColor: .gray)
        
    }
    
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        autoScannerView.stopRunning()
        barCodeScannerView.stopRunning()
        qrCodeScannerView.stopRunning()
        oCRScannerView.stopRunning()
        didLayoutSubviews = false
        viewOCRResponse?.removeFromSuperview()
    }
    
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if didLayoutSubviews == false {
            didLayoutSubviews = true
            setScanModeWith(scanMode)
        }
    }
    
    // MARK: Class Methods
    
    private func setScanModeWith(_ mode: CodeScannerMode) {
        scanMode = mode
        buttonAuto.isSelected = scanMode == .autoBarCodeOrQRCode
        buttonBarCode.isSelected = scanMode == .barCode
        buttonQrCode.isSelected = scanMode == .qrCode
        buttonOCR.isSelected = scanMode == .ocr
        
        autoScannerView.isHidden = scanMode != .autoBarCodeOrQRCode
        barCodeScannerView.isHidden = scanMode != .barCode
        qrCodeScannerView.isHidden = scanMode != .qrCode
        oCRScannerView.isHidden = scanMode != .ocr
        
        buttonCamera.isHidden = (scanMode != .ocr && captureMode == .auto)
        
        segmentedControlCaptureMode.isHidden = scanMode == .ocr
        segmentedControlOCRMode.isHidden = scanMode != .ocr
        
        buttonZoomLevelOne.isHidden = scanMode != .ocr
        buttonZoomLevelTwo.isHidden = scanMode != .ocr
        buttonZoomLevelThree.isHidden = scanMode != .ocr
        viewZoomButtonsBackground.isHidden = scanMode != .ocr
        
        segmentedControlCaptureMode.selectedSegmentIndex = (captureMode == .auto) ? 1 : 0
        segmentedControlOCRMode.selectedSegmentIndex = (ocrMode == .online) ? 0 : 1
//        self.barCodeScannerView.setScanModeTo(mode)
        
        setupScanner()
    }
    
    private func setupScanner() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            setupCodeScannerViews()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                if granted {
                    DispatchQueue.main.async { [weak self] in
                        self?.setupCodeScannerViews()
                    }
                }
                else {
                    DispatchQueue.main.async { [weak self] in
                        self?.showPermissionAlert()
                    }
                }
            }
        default:
            showPermissionAlert()
        }
    }

    private func setupCodeScannerViews() {
        autoScannerView.deConfigure()
        barCodeScannerView.deConfigure()
        qrCodeScannerView.deConfigure()
        oCRScannerView.deConfigure()
        
        autoScannerView.configure(delegate: self, input: .init(focusImage: nil, shouldDisplayFocusImage: true, shouldScanInFocusImageRect: true, isTextIndicationOn: true, isBarCodeOrQRCodeIndicationOn: true, sessionPreset: .high, nthFrameToProcess: 10, captureMode: captureMode, captureType: .single), scanMode: .autoBarCodeOrQRCode)
        barCodeScannerView.configure(delegate: self, input: .init(focusImage: nil, shouldDisplayFocusImage: true, shouldScanInFocusImageRect: true, isTextIndicationOn: true, isBarCodeOrQRCodeIndicationOn: true, sessionPreset: .high, nthFrameToProcess: 10, captureMode: captureMode, captureType: .single), scanMode: .barCode)
        qrCodeScannerView.configure(delegate: self, input: .init(focusImage: nil, shouldDisplayFocusImage: true, shouldScanInFocusImageRect: true, isTextIndicationOn: true, isBarCodeOrQRCodeIndicationOn: true, sessionPreset: .high, nthFrameToProcess: 10, captureMode: captureMode, captureType: .single), scanMode: .qrCode)
        oCRScannerView.configure(delegate: self, input: .init(focusImage: nil, shouldDisplayFocusImage: true, shouldScanInFocusImageRect: true, isTextIndicationOn: true, isBarCodeOrQRCodeIndicationOn: true, sessionPreset: .high, nthFrameToProcess: 10, captureMode: captureMode, captureType: .single), scanMode: .ocr)
        
        startDesirableScanner()
        setZoomLevelWith(.one)
        viewOnlineOCR.isHidden = true
        viewProgressBarBackground.isHidden = true
        imageViewCapturedImage.isHidden = true
    }
    
    private func startDesirableScanner() {
        if scanMode == .barCode {
            barCodeScannerView.setCaptureModeTo(self.captureMode)
            barCodeScannerView.startRunning()
        }
        else if scanMode == .qrCode {
            qrCodeScannerView.setCaptureModeTo(self.captureMode)
            qrCodeScannerView.startRunning()
        }
        else if scanMode == .ocr {
            oCRScannerView.setCaptureModeTo(self.captureMode)
            oCRScannerView.startRunning()
        }
        else if scanMode == .autoBarCodeOrQRCode {
            autoScannerView.setCaptureModeTo(self.captureMode)
            autoScannerView.startRunning()
        }
        
        self.animateOCRIndicator()
    }
    
    private func animateOCRIndicator() {
        guard scanMode == .ocr else { return }
        
        self.viewOnlineOCR.alpha = 1
        self.viewOnlineOCR.isHidden = true
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            
            self.viewOnlineOCR.alpha = 0
            self.viewOnlineOCR.isHidden = false
            
            UIView.animate(withDuration: 0.5) {
                self.viewOnlineOCR.alpha = 1
            } completion: { _ in
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                    UIView.animate(withDuration: 0.3) {
                        self.viewOnlineOCR.alpha = 0
                    } completion: { _ in
                        self.viewOnlineOCR.isHidden = true
                    }
                }
            }
        }
    }
    
    private func setZoomLevelWith(_ level: CameraZoomLevel) {
        assert(Thread.isMainThread)
        
        currentZoomLevel = level
        buttonZoomLevelOne.isSelected = level == .one
        buttonZoomLevelTwo.isSelected = level == .two
        buttonZoomLevelThree.isSelected = level == .three
        
        buttonZoomLevelOne.layer.cornerRadius = buttonZoomLevelOne.frame.size.height / 2
        buttonZoomLevelTwo.layer.cornerRadius = buttonZoomLevelTwo.frame.size.height / 2
        buttonZoomLevelThree.layer.cornerRadius = buttonZoomLevelThree.frame.size.height / 2
        
        viewZoomButtonsBackground.layer.cornerRadius = viewZoomButtonsBackground.frame.size.height / 2
        viewOnlineOCR.layer.cornerRadius = 3
        
        guard let videoDevice = AVCaptureDevice.DiscoverySession(deviceTypes: [.builtInWideAngleCamera], mediaType: .video, position: .back).devices.first
        else {
                return
        }
        try? videoDevice.lockForConfiguration()
        videoDevice.videoZoomFactor = CGFloat(currentZoomLevel.rawValue)
        videoDevice.unlockForConfiguration()
    }

    
    private func setTorchActive(isOn: Bool) {
        assert(Thread.isMainThread)
        
        guard let videoDevice = AVCaptureDevice.DiscoverySession(deviceTypes: [.builtInWideAngleCamera], mediaType: .video, position: .back).devices.first,
            videoDevice.hasTorch, videoDevice.isTorchAvailable
        else {
                return
        }
        try? videoDevice.lockForConfiguration()
        videoDevice.torchMode = isOn ? .on : .off
        videoDevice.unlockForConfiguration()
    }
    
    func showOCRResponseViewWith(responseDict: Dictionary<String, String>, image: UIImage, metaDataString: NSMutableAttributedString) {
        
        self.oCRScannerView.stopRunning()
        self.imageViewCapturedImage.isHidden = false
        self.imageViewCapturedImage.image = image
        
        let ocrResponseView = OCRResponseView(frame: CGRect(x: 0, y: 0, width: self.view.bounds.width, height: self.view.bounds.height))
        self.view.addSubview(ocrResponseView)
        ocrResponseView.setDataWith(responseDict, metaDataString: metaDataString)
        ocrResponseView.didDisappear = { [weak self] in
            self?.imageViewCapturedImage.isHidden = true
            self?.setTorchModeTo(0)
            self?.setScanModeWith(self?.scanMode ?? .ocr)
            
        }
        self.viewOCRResponse = ocrResponseView
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    
}


// MARK: Helper functions
// MArk: -
extension BarcodeViewController {
    
    func showPermissionAlert() {
        let alertController = UIAlertController(title: "No Camera Access", message: "To give this app your camera access. Go to Settings -> VisionSDK -> Switch on camera", preferredStyle: UIAlertController.Style.alert)
        
        let yesAction = UIAlertAction(title: "Open Settings", style: UIAlertAction.Style.default, handler: { (alert) in
            guard let settingsUrl = URL(string: UIApplication.openSettingsURLString) else {
                return
            }

            if UIApplication.shared.canOpenURL(settingsUrl) {
                UIApplication.shared.open(settingsUrl, completionHandler: { (success) in
                    debugPrint("Settings opened: \(success)") // Prints true
                })
            }
        })

        let cancelOption = UIAlertAction(title: "Cancel", style: .destructive, handler: { (_) in
        })

        alertController.addAction(yesAction)
        alertController.addAction(cancelOption)
        alertController.modalPresentationStyle = .overFullScreen
        self.present(alertController, animated: true, completion: nil)
    }
    
    func playSoundEffect(isSuccess: Bool) {
        if self.buttonSound.currentMode == 0 {
            self.playSound(isSuccess: isSuccess)
        }
        else if self.buttonSound.currentMode == 2 {
            AudioServicesPlaySystemSound(SystemSoundID(kSystemSoundID_Vibrate))
        }
    }
    
    func playSound(isSuccess: Bool) {
        
        guard self.buttonSound.currentMode == 0 else { return }
        guard let url = Bundle.main.url(forResource: isSuccess ? "SuccessSound" : "ErrorSound", withExtension: ".mp3") else { return }
        
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback)
        } catch(let error) {
            print(error.localizedDescription)
        }
        
        do {
            soundEffect = try AVAudioPlayer(contentsOf: url)
            soundEffect?.play()
        } catch (let error){
            print(error.localizedDescription)
        }
    }
    
    func setTorchModeTo(_ mode: Int) {
        
        self.buttonTorch.currentMode = mode
        let isOn = (mode == 1)
        self.buttonTorch.setImageWithSystemName(name: isOn ? "bolt" : "bolt.slash")
    }

}

// MARK: Actions

extension BarcodeViewController {
    
    @IBAction func didTapSoundButton(_ sender: Any) {
        if self.buttonSound.currentMode == 0 {
            self.buttonSound.currentMode = 1
            self.buttonSound.setImageWithSystemName(name: "speaker.slash")
        }
        else if self.buttonSound.currentMode == 1 {
            self.buttonSound.currentMode = 2
            self.buttonSound.setImageWithSystemName(name: "speaker.zzz")
        }
        else {
            self.buttonSound.currentMode = 0
            self.buttonSound.setImageWithSystemName(name: "speaker.wave.2")
        }
    }
    
    @IBAction func didTapTorchButton(_ sender: Any) {
        if self.buttonTorch.currentMode == 0 {
            self.setTorchModeTo(1)
            self.setTorchActive(isOn: true)
        }
        else {
            self.setTorchModeTo(0)
            self.setTorchActive(isOn: false)
        }
    }
    
    @IBAction func didTapAutoButton(_ sender: UIButton) {
        self.setTorchModeTo(0)
        setScanModeWith(.autoBarCodeOrQRCode)
    }
    
    @IBAction func didTapBarCodeButton(_ sender: UIButton) {
        self.setTorchModeTo(0)
        setScanModeWith(.barCode)
    }
    
    @IBAction func didTapQrCodeButton(_ sender: UIButton) {
        self.setTorchModeTo(0)
        setScanModeWith(.qrCode)
    }
    
    @IBAction func didTapOCRButton(_ sender: UIButton) {
        self.setTorchModeTo(0)
        setScanModeWith(.ocr)
    }
    
    @IBAction func didTapCameraButton(_ sender: Any) {
        if scanMode == .ocr, viewOnlineOCR.isHidden {
            oCRScannerView.capturePhoto()
        }
        else if scanMode == .barCode, captureMode == .manual {
            barCodeScannerView.capturePhoto()
        }
        else if scanMode == .qrCode, captureMode == .manual {
            qrCodeScannerView.capturePhoto()
        }
        else if scanMode == .autoBarCodeOrQRCode, captureMode == .manual {
            autoScannerView.capturePhoto()
        }
    }
    
    @IBAction func didTapZoomOneButton(_ sender: UIButton) {
        guard scanMode == .ocr else { return }
        setZoomLevelWith(.one)
    }
    
    @IBAction func didTapZoomTwoButton(_ sender: UIButton) {
        guard scanMode == .ocr else { return }
        setZoomLevelWith(.two)
    }
    
    @IBAction func didTapZoomThreeButton(_ sender: UIButton) {
        guard scanMode == .ocr else { return }
        setZoomLevelWith(.three)
    }
    
    @IBAction func didTapSegmentedControl(_ sender: UISegmentedControl) {
        guard scanMode != .ocr else { return }
        captureMode = (sender.selectedSegmentIndex == 1) ? .auto : .manual
        
        barCodeScannerView.setCaptureModeTo(self.captureMode)
        qrCodeScannerView.setCaptureModeTo(self.captureMode)
        autoScannerView.setCaptureModeTo(self.captureMode)
        
        buttonCamera.isHidden = (scanMode != .ocr && captureMode == .auto)
        
    }
    
    @IBAction func didTapSegmentedControlOCRMode(_ sender: UISegmentedControl) {
        guard scanMode == .ocr else { return }
        
        ocrMode = (sender.selectedSegmentIndex == 0) ? .online : .offline
    }
    
    @IBAction func didTapBackButton(_ sender: Any) {
        
    }
    
    @IBAction func dismissVC(_ sender: Any) {
        self.dismiss(animated: true, completion: nil)
    }
}

// MARK: - QRScannerViewDelegate
extension BarcodeViewController: CodeScannerViewDelegate {
    
    func codeScannerView(_ scannerView: CodeScannerView, didFailure error: CodeScannerError) {
        
        switch error {
        case .unauthorized:
                print("unauthorized")
        case .noTextDetected:
            let alertController = UIAlertController(title: "No Text Found", message: "Please capture photo when text indicator is active", preferredStyle: .alert)
            
            let cancelAction = UIAlertAction(title: "Ok", style: .cancel, handler: { _ in //[weak self] _ in
                
            })
            alertController.addAction(cancelAction)
            present(alertController, animated: true, completion: nil)
        case .noBarCodeDetected:
            let alertController = UIAlertController(title: "No Barcode Found", message: "Please capture photo when Barcode indicator is active or manually enter the code", preferredStyle: .alert)
            
            alertController.addTextField {
                $0.placeholder = "Enter Code"
            }
            
            let cancelAction = UIAlertAction(title: "Ok", style: .cancel, handler: { _ in //[weak self] _ in
                
            })
            alertController.addAction(cancelAction)
            present(alertController, animated: true, completion: nil)
            
        case .noQRCodeDetected:
            let alertController = UIAlertController(title: "No QR Code Found", message: "Please capture photo when QR Code indicator is active or manually enter the code", preferredStyle: .alert)
            
            alertController.addTextField {
                $0.placeholder = "Enter Code"
            }
            
            let cancelAction = UIAlertAction(title: "Ok", style: .cancel, handler: { _ in //[weak self] _ in
                
            })
            alertController.addAction(cancelAction)
            present(alertController, animated: true, completion: nil)
        case .noBarCodeORQRCodeDetected:
            let alertController = UIAlertController(title: "No Bar Code Or QR Code Found", message: "Please enter code manually or scan text", preferredStyle: .alert)
            
            alertController.addTextField {
                $0.placeholder = "Enter Code"
            }
            
            let cancelAction = UIAlertAction(title: "Ok", style: .cancel, handler: { _ in //[weak self] _ in
                
            })
            
            alertController.addAction(cancelAction)
            present(alertController, animated: true, completion: nil)
            
        default:
            print("error")
        }
    }

    func codeScannerView(_ scannerView: CodeScannerView, didSuccess codes: [String]) {
        
        self.playSoundEffect(isSuccess: true)
        
        showAlert(code: codes.joined(separator: ", ")) {
            scannerView.rescan()
        }
    }
    
    func codeScannerViewDidDetect(_ text: Bool, barCode: Bool, qrCode: Bool) {
        
        self.buttonTextIndicator.setImageWithSystemName(name: "textformat.alt", withTintColor: text ? .green : .gray)
        self.buttonBarCodeIndicator.setImageWithSystemName(name: "barcode", withTintColor: barCode ? .green : .gray)
        self.buttonQRCodeIndicator.setImageWithSystemName(name: "qrcode", withTintColor: qrCode ? .green : .gray)
    }
    
    func codeScannerView(_ scannerView: CodeScannerView, didCaptureOCRImage image: UIImage, withbarCodes barcodes: [String]) {
        if self.ocrMode == .online {
            self.callOCRAPIWithImage(image, andBarcodes: barcodes)
        }
        else {
            guard let cgImage = image.cgImage else { return }
            VisionAPIManager.shared.recongizeTextFromImage(cgImage) { textStrings in
                var transcript = ""
                
                for textString in textStrings {
                    transcript += textString
                    transcript += "\n"
                }
                
                if barcodes.count > 0 {
                    transcript += "\n Barcodes \n \n"
                    
                    for barcode in barcodes {
                        transcript += barcode
                        transcript += "\n"
                    }
                }
                
                self.showAlert(code: transcript) {
                    
                }
            }
        }
    }
}


// MARK: - Private
private extension BarcodeViewController {

    func showAlert(code: String, withCompletion completion: @escaping (()->Void)) {
        let alertController = UIAlertController(title: code, message: nil, preferredStyle: .actionSheet)
        
        let copyAction = UIAlertAction(title: "Copy", style: .default) { _ in //[weak self] _ in
            UIPasteboard.general.string = code
            completion()
        }
        alertController.addAction(copyAction)
        
        let cancelAction = UIAlertAction(title: "Cancel", style: .cancel, handler: { _ in //[weak self] _ in
            completion()
        })
        alertController.addAction(cancelAction)
        
        present(alertController, animated: true, completion: nil)
    }
}


extension BarcodeViewController {
    
    private func callOCRAPIWithImage(_ image: UIImage, andBarcodes barcodes: [String]) {
        
        self.viewProgressBarBackground.isHidden = false
        self.constraintProgressBarWidth.constant = 0
        self.viewProgressBarBackground.layoutIfNeeded()
        self.constraintProgressBarWidth.constant = self.viewProgressBarBackground.frame.width
        
        UIView.animate(withDuration: 10.0) {
            self.viewProgressBarBackground.layoutIfNeeded()
        }
        
        VisionAPIManager.shared.callScanAPIWith(image, andBarcodes: barcodes, andApiKey: Constants.apiKey) {
            
            [weak self] data, response, error in
            
            guard let self = self else { return }
            
            DispatchQueue.main.async {
                self.viewProgressBarBackground.isHidden = true
            }
            
            // Check if there's an error or response data is nil
            guard error == nil else {
                DispatchQueue.main.async {
                    self.playSoundEffect(isSuccess: false)
                    self.showAlert(code: error?.localizedDescription ?? "", withCompletion: {} )
                }
                return
            }
            
            guard let response = response else {
                DispatchQueue.main.async {
                    self.playSoundEffect(isSuccess: false)
                    self.showAlert(code: "Response of request was found nil", withCompletion: {} )
                }
                return
            }
            
            guard let data = data else {
                DispatchQueue.main.async {
                    self.playSoundEffect(isSuccess: false)
                    self.showAlert(code: "Data of request was found nil", withCompletion: {} )
                }
                return
            }
             
            let _ = (response as! HTTPURLResponse).statusCode
            
            // Check the request status code, It must be 200
            
//            guard [200,201,202,204].contains(statusCode) else {
//                DispatchQueue.main.async {
//                    self.showAlert(code: "Data of request was found nil", withCompletion: {} )
////                    completion(nil, statusCode,  NSError(domain: "HTTP", code: 0, userInfo: ["Status Code Error": "Status code is not valid"]))
//                }
//                return
//            }
            
            DispatchQueue.main.async {
                
                do {
                    
                    if let jsonResponse = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                        
                        print(jsonResponse)
                        
                        if let responseJson = jsonResponse["data"], let dataDict = try? JSONSerialization.data(withJSONObject: responseJson) {
                            let decoder = JSONDecoder()
                            
                            let ocrResponse = try decoder.decode(OcrResponse.self, from: dataDict)
                            self.playSoundEffect(isSuccess: true)
                            let values = ocrResponse.getValuesToPrint()
                            self.showOCRResponseViewWith(responseDict: values.1, image: image, metaDataString: values.0)
                            //                        self.showAlert(code: values, withCompletion: {} )
                        }
                    }
                }
                catch let error {
                    
                    self.playSoundEffect(isSuccess: false)
                    
                    do {
                        // create json object from data or use JSONDecoder to convert to Model stuct
                        if let jsonResponse = try JSONSerialization.jsonObject(with: data, options: .mutableContainers) as? [String: Any] {
                            if let message = jsonResponse["message"] as? String {
                                self.showAlert(code: message, withCompletion: {} )
                            }
                            else {
                                self.showAlert(code: error.localizedDescription, withCompletion: {} )
                            }

                        } else {
                            throw URLError(.badServerResponse)
                        }
                    } catch let error {
                        self.showAlert(code: error.localizedDescription, withCompletion: {} )
                    }
                }
                
            }
        }
    }
}
