//
//  RoundButtonView.swift
//  VisionSDK
//
//  Created by Muzamil.Mughal on 22/05/2022.
//

import UIKit

class RoundButtonView: UIView {

    @IBOutlet weak var imageViewImage: UIImageView!
    
    var currentMode = 0
    
    @IBInspectable public var cornerRadius: Double  {
        get {
            return self.layer.cornerRadius
        }
        set {
            self.layer.cornerRadius = newValue
        }
    }
    
    
    func setImageWithSystemName(name: String, withTintColor tintColor: UIColor = .white) {
        self.imageViewImage.image = UIImage(systemName: name)?.withRenderingMode(.alwaysTemplate)
        self.imageViewImage.tintColor = tintColor
    }
}
