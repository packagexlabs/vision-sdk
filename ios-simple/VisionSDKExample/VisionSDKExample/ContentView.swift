//
//  ContentView.swift
//  VisionSDKExample
//
//  Created by Muzamil.Mughal on 10/08/2022.
//

import SwiftUI
import VisionSDK

struct ContentView: View {
    var body: some View {
        BarcodeViewRepresentable()
            .onAppear {
//                Please store your API key here
                Constants.apiKey = ""
                
            }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
