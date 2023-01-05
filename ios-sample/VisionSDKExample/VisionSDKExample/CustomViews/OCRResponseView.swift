//
//  OCRResponseView.swift
//  VisionSDK
//
//  Created by Muzamil.Mughal on 12/06/2022.
//

import UIKit

class OCRResponseView: UIView {
    
    // MARK:- Outlets
    
    @IBOutlet weak var stackViewTrackingNumber: UIStackView!
    @IBOutlet weak var stackViewCourier: UIStackView!
    @IBOutlet weak var stackViewWeight: UIStackView!
    
    @IBOutlet weak var stackViewReceiverName: UIStackView!
    @IBOutlet weak var stackViewReceiverStreetAddress: UIStackView!
    @IBOutlet weak var stackViewReceiverCity: UIStackView!
    @IBOutlet weak var stackViewReceiverState: UIStackView!
    @IBOutlet weak var stackViewReceiverZipcode: UIStackView!
    @IBOutlet weak var stackViewReceiverAddress: UIStackView!
    
    @IBOutlet weak var stackViewSenderName: UIStackView!
    @IBOutlet weak var stackViewSenderStreetAddress: UIStackView!
    @IBOutlet weak var stackViewSenderCity: UIStackView!
    @IBOutlet weak var stackViewSenderState: UIStackView!
    @IBOutlet weak var stackViewSenderZipCode: UIStackView!
    @IBOutlet weak var stackViewSenderAddress: UIStackView!
    
    @IBOutlet weak var stackViewPONumber: UIStackView!
    @IBOutlet weak var stackViewReferenceNumber: UIStackView!
    @IBOutlet weak var stackViewTags: UIStackView!
    @IBOutlet weak var stackViewMetaData: UIStackView!
    
    
    @IBOutlet weak var labelTrackingNumber: UILabel!
    @IBOutlet weak var labelCourier: UILabel!
    @IBOutlet weak var labelWeight: UILabel!
    
    @IBOutlet weak var labelReceiverName: UILabel!
    @IBOutlet weak var labelReceiverStreetAddress: UILabel!
    @IBOutlet weak var labelReceiverCity: UILabel!
    @IBOutlet weak var labelReceiverState: UILabel!
    @IBOutlet weak var labelReceiverZipcode: UILabel!
    @IBOutlet weak var labelReceiverAddress: UILabel!
    
    @IBOutlet weak var labelSenderName: UILabel!
    @IBOutlet weak var labelSenderStreetAddress: UILabel!
    @IBOutlet weak var labelSenderCity: UILabel!
    @IBOutlet weak var labelSenderState: UILabel!
    @IBOutlet weak var labelSenderZipCode: UILabel!
    @IBOutlet weak var labelSenderAddress: UILabel!
    
    @IBOutlet weak var labelPONumber: UILabel!
    @IBOutlet weak var labelReferenceNumber: UILabel!
    @IBOutlet weak var labelTags: UILabel!
    @IBOutlet weak var labelMetaData: UILabel!
    
    
    // MARK:- External Properties
    
    var didDisappear: (()-> Void)?
    
    // MARK:- Internal Properties
    
    
    
    fileprivate var view: UIView!
    
    override func draw(_ rect: CGRect) {
        // Drawing code
    }
    
    func xibSetup() {
        view = loadViewFromNib()
        view.frame = bounds
        view.backgroundColor = UIColor.clear
        view.autoresizingMask = [UIView.AutoresizingMask.flexibleWidth, UIView.AutoresizingMask.flexibleHeight]
        addSubview(view)
        self.configureView()
    }
    
    func loadViewFromNib() -> UIView {
        let bundle = Bundle(for: OCRResponseView.self)
        let nib = UINib(nibName: "OCRResponseView", bundle: bundle)
        let view = nib.instantiate(withOwner: self, options: nil)[0] as! UIView
        return view
    }
    
    //MARK:- Initializers
    
    override init(frame: CGRect) {
        super.init(frame: frame)
        xibSetup()
    }
    
    required init?(coder aDecoder: NSCoder) {
        super.init(coder: aDecoder)
        xibSetup()
    }
    
    private func configureView() {
        
        labelTrackingNumber.text = ""
        labelCourier.text = ""
        labelWeight.text = ""
        
        labelReceiverName.text = ""
        labelReceiverStreetAddress.text = ""
        labelReceiverCity.text = ""
        labelReceiverState.text = ""
        labelReceiverZipcode.text = ""
        labelReceiverAddress.text = ""
        
        labelSenderName.text = ""
        labelSenderStreetAddress.text = ""
        labelSenderCity.text = ""
        labelSenderState.text = ""
        labelSenderZipCode.text = ""
        labelSenderAddress.text = ""
        
        labelPONumber.text = ""
        labelReferenceNumber.text = ""
        labelTags.text = ""
        labelMetaData.text = ""
        labelMetaData.attributedText = NSMutableAttributedString(string: "")
    }
    
    func setDataWith(_ values: Dictionary<String, String>, metaDataString: NSMutableAttributedString) {
        
        labelTrackingNumber.text = values["Tracking No."] ?? ""
        labelCourier.text = values["Courier"] ?? ""
        labelWeight.text = values["Weight"] ?? ""
        
        labelReceiverName.text = values["Receiver's name"] ?? ""
        labelReceiverStreetAddress.text = values["Receiver's street address"] ?? ""
        labelReceiverCity.text = values["Receiver's city"] ?? ""
        labelReceiverState.text = values["Receiver's state"] ?? ""
        labelReceiverZipcode.text = values["Receiver's zipcode"] ?? ""
        labelReceiverAddress.text = values["Receiver's address"] ?? ""
        
        labelSenderName.text = values["Sender's name"] ?? ""
        labelSenderStreetAddress.text = values["Sender's street address"] ?? ""
        labelSenderCity.text = values["Sender's city"] ?? ""
        labelSenderState.text = values["Sender's state"] ?? ""
        labelSenderZipCode.text = values["Sender's zipcode"] ?? ""
        labelSenderAddress.text = values["Sender's address"] ?? ""
        
        labelPONumber.text = values["PO #"] ?? ""
        labelReferenceNumber.text = values["REF #"] ?? ""
        labelTags.text = values["Tags"] ?? ""
        labelMetaData.attributedText = metaDataString
        
        
//        valuesDictionary.updateValue("N/A", forKey: "Tags")
//
//        valuesDictionary.updateValue("", forKey: "PO #")
//        valuesDictionary.updateValue("", forKey: "REF #")
        
        stackViewPONumber.isHidden  = ((values["PO #"]) ?? "").isEmpty
        stackViewReferenceNumber.isHidden  = ((values["REF #"]) ?? "").isEmpty
        stackViewTags.isHidden  = ((values["Tags"]) ?? "").isEmpty
        stackViewMetaData.isHidden  = metaDataString.string.isEmpty
    }
    
    @IBAction func didTapBackButton(_ sender: Any) {
        self.didDisappear?()
        self.removeFromSuperview()
    }
}
