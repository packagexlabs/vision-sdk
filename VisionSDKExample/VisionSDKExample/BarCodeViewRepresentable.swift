//
//  BarCodeViewRepresentable.swift
//  VisionSDK
//
//  Created by Muzamil.Mughal on 19/05/2022.
//

import Foundation
import SwiftUI
import AVFoundation

struct BarcodeViewRepresentable: UIViewControllerRepresentable {

    
}


// MARK: - Make functions
// MARK: -
extension BarcodeViewRepresentable {
    
    func makeUIViewController(context: Context) -> BarcodeViewController {
        
        let controller = UIStoryboard(name: "Scanner", bundle: nil)
             .instantiateViewController(withIdentifier: "BarcodeViewController") as! BarcodeViewController
        return controller
        
    }
    
    func updateUIViewController(_ barcodeViewController: BarcodeViewController, context: Context) {
        
    }
}


// MARK: - Context functions
// MARK: -
extension BarcodeViewRepresentable {
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject{
        
        let parent: BarcodeViewRepresentable
        
        init(_ parent: BarcodeViewRepresentable) {
            self.parent = parent
        }
    }
}


