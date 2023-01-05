//
//  Models.swift
//  VisionSDK
//
//  Created by Muzamil.Mughal on 08/06/2022.
//

import Foundation
import Combine
import UIKit

struct ExtraImage : Codable, Equatable, Hashable {
    var imageID : String?

    enum CodingKeys: String, CodingKey {
        case imageID
    }
}

class OcrResponse: Codable, Equatable, Hashable {

    static func == (lhs: OcrResponse, rhs: OcrResponse) -> Bool {
       return lhs.uuid == rhs.uuid
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(uuid)
    }

    var callType : String?
    var output: Output?
    var uuid: String?
    var multiHops: Bool?
//    var notificationsEnabled: Bool = false
    var workflowId: Int?
//    var customLocationId: Int = -1

    init() {
        output = Output(duplicatePackages: nil, scanOutput: ScanOutput(packageId: "-1", success: ""))
        uuid = ""
    }


    required init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        callType = try values.decodeIfPresent(String.self, forKey: .callType) ?? String()
        output = try values.decodeIfPresent(Output.self, forKey: .output) ?? Output()
        uuid = try values.decodeIfPresent(String.self, forKey: .uuid) ?? String()
        multiHops = try values.decodeIfPresent(Bool.self, forKey: .multiHops) ?? false
        workflowId = try values.decodeIfPresent(Int.self, forKey: .workflowId) ?? Int()
//        customLocationId = try values.decodeIfPresent(Int.self, forKey: .customLocationId) ?? Int() // this key isn't coming from OCR
    }


    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(callType, forKey: .callType)
        try container.encode(output, forKey: .output)
        try container.encode(uuid, forKey: .uuid)
        try container.encode(multiHops, forKey: .multiHops)
        try container.encode(workflowId, forKey: .workflowId)
//        try container.encode(customLocationId, forKey: .customLocationId)
    }

    private enum CodingKeys: String, CodingKey {
        case callType = "call_type"
        case output
        case uuid
        case additionalInfo
        case multiHops = "multiHops"
        case workflowId
//        case customLocationId
    }
    
    func getValuesToPrint() -> (NSMutableAttributedString, Dictionary<String, String>) {
        
        var valuesDictionary: Dictionary<String, String> = [:]
        
        valuesDictionary.updateValue("N/A", forKey: "Courier")
        valuesDictionary.updateValue("N/A", forKey: "Tracking No.")
        valuesDictionary.updateValue("N/A", forKey: "Weight")
        
        valuesDictionary.updateValue("", forKey: "Tags")
        
        valuesDictionary.updateValue("", forKey: "PO #")
        valuesDictionary.updateValue("", forKey: "REF #")
        
        valuesDictionary.updateValue("N/A", forKey: "Receiver's name")
        valuesDictionary.updateValue("N/A", forKey: "Receiver's street address")
        valuesDictionary.updateValue("N/A", forKey: "Receiver's city")
        valuesDictionary.updateValue("N/A", forKey: "Receiver's state")
        valuesDictionary.updateValue("N/A", forKey: "Receiver's zipcode")
        valuesDictionary.updateValue("N/A", forKey: "Receiver's address")
        
        
        valuesDictionary.updateValue("N/A", forKey: "Sender's name")
        valuesDictionary.updateValue("N/A", forKey: "Sender's street address")
        valuesDictionary.updateValue("N/A", forKey: "Sender's city")
        valuesDictionary.updateValue("N/A", forKey: "Sender's state")
        valuesDictionary.updateValue("N/A", forKey: "Sender's zipcode")
        valuesDictionary.updateValue("N/A", forKey: "Sender's address")
        
        
        if let courierInfo = self.output?.scanOutput?.courierInfo {
            if let courierName = courierInfo.courierName, !courierName.isEmpty {
                if let formattedName = couriersList[courierName] {
                    valuesDictionary.updateValue(formattedName, forKey: "Courier")
                }
                else {
                    valuesDictionary.updateValue(courierName.contains("amazon") ? "Amazon" : courierName, forKey: "Courier")
                }
            }
            
            if let trackingNo = courierInfo.trackingNo, !trackingNo.isEmpty {
                valuesDictionary.updateValue(trackingNo, forKey: "Tracking No.")
            }
            
            if let weight = courierInfo.weightInfo, !weight.isEmpty {
                valuesDictionary.updateValue(weight, forKey: "Weight")
            }
        }
        
        if let itemInfo = self.output?.scanOutput?.itemInfo {
            
            if let poNumber = itemInfo.poNumber, !poNumber.isEmpty {
                valuesDictionary.updateValue(poNumber, forKey: "PO #")
            }
            
            if let refNumber = itemInfo.refNumber, !refNumber.isEmpty {
                valuesDictionary.updateValue(refNumber, forKey: "REF #")
            }
        }
        
        if let address = self.output?.scanOutput?.address {
            
            if let senderAddressInfo = address.senderAddress {
                
                if let name = senderAddressInfo.name, !name.isEmpty {
                    valuesDictionary.updateValue(name, forKey: "Sender's name")
                }
                
                var addressString = ""
                
                if let addressLine = senderAddressInfo.addressLine, !addressLine.isEmpty {
                    valuesDictionary.updateValue(addressLine, forKey: "Sender's street address")
                    addressString += "\(addressLine) "
                }
                
                if let city = senderAddressInfo.city, !city.isEmpty {
                    valuesDictionary.updateValue(city.capitalized, forKey: "Sender's city")
                    addressString += "\(city.capitalized) "
                }
                
                if let state = senderAddressInfo.state, !state.isEmpty {
                    valuesDictionary.updateValue(state, forKey: "Sender's state")
                    addressString += "\(state) "
                }
                
                if let zipcode = senderAddressInfo.zipcode, !zipcode.isEmpty {
                    valuesDictionary.updateValue(zipcode.capitalized, forKey: "Sender's zipcode")
                    addressString += "\(zipcode.capitalized) "
                }
                
                if !addressString.isEmpty {
                    valuesDictionary.updateValue(addressString, forKey: "Sender's address")
                }
                
            }
            
            if let receiverAddressInfo = address.receiverAddress {
                
                if let name = receiverAddressInfo.name, !name.isEmpty {
                    valuesDictionary.updateValue(name, forKey: "Receiver's name")
                }
                
                if let name = self.output?.scanOutput?.data?.nonMembersFound?.first?.name, !name.isEmpty {
                    valuesDictionary.updateValue(name, forKey: "Receiver's name")
                }
                
                var addressString = ""
                
                if let addressLine = receiverAddressInfo.addressLine, !addressLine.isEmpty {
                    valuesDictionary.updateValue(addressLine, forKey: "Receiver's street address")
                    addressString += "\(addressLine) "
                }
                
                if let city = receiverAddressInfo.city, !city.isEmpty {
                    valuesDictionary.updateValue(city.capitalized, forKey: "Receiver's city")
                    addressString += "\(city.capitalized) "
                }
                
                if let state = receiverAddressInfo.state, !state.isEmpty {
                    valuesDictionary.updateValue(state, forKey: "Receiver's state")
                    addressString += "\(state) "
                }
                
                if let zipcode = receiverAddressInfo.zipcode, !zipcode.isEmpty {
                    valuesDictionary.updateValue(zipcode.capitalized, forKey: "Receiver's zipcode")
                    addressString += "\(zipcode.capitalized) "
                }
                
                if !addressString.isEmpty {
                    valuesDictionary.updateValue(addressString, forKey: "Receiver's address")
                }
            }
        }
        
        if let senderInfo = self.output?.scanOutput?.data?.senderFound?.first {
            
            var senderName = ""
            if let name = senderInfo.name, !name.isEmpty {
                senderName = name
            }
            else if let bussinessName = senderInfo.business_name, !bussinessName.isEmpty {
                senderName = bussinessName
            }
            
            if !senderName.isEmpty {
                senderName = senderName.replacingOccurrences(of: ".com", with: "")
                senderName = senderName.capitalized
                valuesDictionary.updateValue(senderName, forKey: "Sender's name")
            }
        }
        
        if let tagsInfo = self.output?.scanOutput?.courierInfo?.misc {
            
            var tags: [String] = []
            
            if let foodDeliveyFlag = tagsInfo.foodDeliveyFlag, foodDeliveyFlag {
                tags.append("Food Delivery")
            }
            
            if let timeSensitiveFlag = tagsInfo.timeSensitiveFlag, timeSensitiveFlag {
                tags.append("Time Sensitive")
            }
            
            if let fragileFlag = tagsInfo.fragileFlag, fragileFlag {
                tags.append("Fragile")
            }
            
            if let confidentialFlag = tagsInfo.confidentialFlag, confidentialFlag {
                tags.append("Confidential")
            }
            
            if let legalDocumentFlag = tagsInfo.legalDocumentFlag, legalDocumentFlag {
                tags.append("Legal Document")
            }
            
            if let oversizeFlag = tagsInfo.oversizeFlag, oversizeFlag {
                tags.append("Oversize")
            }
            
            if let returnToSenderFlag = tagsInfo.returnToSenderFlag, returnToSenderFlag {
                tags.append("Return to sender")
            }
            
            if let payStubsFlag = tagsInfo.payStubsFlag, payStubsFlag {
                tags.append("Pay Stubs")
            }
            
            let tagString = tags.joined(separator: ", ")
            
            valuesDictionary.updateValue(tagString, forKey: "Tags")
        }
        
        var metaDataAttributedString = NSMutableAttributedString(string: "")
        
        if let metaDataInfo = self.output?.scanOutput?.courierInfo {
            var metaDataArray: [String] = []
            
//            var dynamicLabels : [Int]?
//            var presetLabels : [String]?
//            var dynamicExtractedLabels : [String]?
//            var locationBasedLabels : [String]?
            
//            if let dynamicLabels = metaDataInfo.dynamicLabels {
//                metaDataArray += dynamicLabels.map( { "\($0)" } )
//            }
            
            if let presetLabels = metaDataInfo.presetLabels {
                metaDataArray += presetLabels.map( { $0 } )
            }
            
            if let dynamicExtractedLabels = metaDataInfo.dynamicExtractedLabels {
                metaDataArray += dynamicExtractedLabels.map( { $0 } )
            }
            
            if let locationBasedLabels = metaDataInfo.locationBasedLabels {
                metaDataArray += locationBasedLabels.map( { "\($0.key ?? ""): \($0.value ?? "")" } )
            }
            
            // For testing only
//            if metaDataArray.isEmpty {
//                metaDataArray = ["Muzamil", "Ali", "Yasin", "Mughal"]
//            }
            
            let combinedString = metaDataArray.joined(separator: ", ")
            let mutableAttributedString = NSMutableAttributedString(string: combinedString)
            
            for metaDataValue in metaDataArray {
                let range = (combinedString as NSString).range(of: metaDataValue)
                mutableAttributedString.addAttribute(NSAttributedString.Key.foregroundColor, value: UIColor(
                    red: .random(in: 0...1),
                    green: .random(in: 0...1),
                    blue: .random(in: 0...1),
                    alpha: 1.0
                ), range: range)
            }
            
            metaDataAttributedString = mutableAttributedString
            
        }
        
        
        return (metaDataAttributedString, valuesDictionary)
    }
}

struct Output : Codable {
    var duplicatePackages : [String]?
    var scanOutput : ScanOutput?
    var duplicatePackagesNStatus: [[String]]?
    var timeLogs: Timelogs?

    enum CodingKeys: String, CodingKey {
        case duplicatePackages = "duplicate_packages"
        case scanOutput = "scan_output"
        case duplicatePackagesNStatus = "duplicate_packages_n_status"
        case timeLogs = "time_logs"
    }
}

class Timelogs: Codable {
    var imageUploadTime, qasidRunTime1, scanTime, qasidStartTime: String?
    var qasidRunTime2: String?

    enum CodingKeys: String, CodingKey {
        case imageUploadTime = "image_upload_time"
        case qasidRunTime1 = "qasid_run_time_1"
        case scanTime = "scan_time"
        case qasidStartTime = "qasid_start_time"
        case qasidRunTime2 = "qasid_run_time_2"
    }

    init(imageUploadTime: String?, qasidRunTime1: String?, scanTime: String?, qasidStartTime: String?, qasidRunTime2: String?) {
        self.imageUploadTime = imageUploadTime
        self.qasidRunTime1 = qasidRunTime1
        self.scanTime = scanTime
        self.qasidStartTime = qasidStartTime
        self.qasidRunTime2 = qasidRunTime2
    }
}


struct ScanOutput : Codable {
    var packageId: String?
    var success : String?
    var data : MembersData?
    var courierInfo : CourierInfo?
    var address : Address?
    var itemInfo: ItemInfo?

    enum CodingKeys: String, CodingKey {
        case packageId = "package_id"
        case courierInfo = "courier_info"
        case success = "success"
        case address = "address"
        case data = "data"
        case itemInfo = "item_info"
    }
    
    struct ItemInfo : Codable {
        var poNumber : String?
        var refNumber : String?
        
        enum CodingKeys: String, CodingKey {
            case poNumber = "po_number"
            case refNumber = "ref_number"
        }
    }
}

struct MembersData : Codable {
    var businessesFound : [Member]?
    var membersFound : [Member]?
    var nonMembersFound : [Member]?
    var suggestions : [Member]?
    var senderFound : [Member]?
    
    enum CodingKeys: String, CodingKey {
        case businessesFound = "businesses_found"
        case membersFound = "members_found"
        case nonMembersFound = "non_members_found"
        case suggestions = "suggestions"
        case senderFound = "sender_found"
    }
}


struct Member : Codable {
    var business_name : String?
    var combined_info : String?
    var name : String?

    enum CodingKeys: String, CodingKey {
        case business_name = "business_name"
        case combined_info = "combined_info"
        case name = "name"
    }
}

struct CourierInfo : Codable {
    var courierName : String?
    var trackingNo : String?
    var misc : Miscellaneous?
    var weightInfo : String?
    var dynamicLabels : [Int]?
    var presetLabels : [String]?
    var dynamicExtractedLabels : [String]?
    var locationBasedLabels : [LocationLabel]?

    enum CodingKeys: String, CodingKey {
        case courierName = "courier_name"
        case trackingNo = "tracking_no"
        case misc = "miscellaneous"
        case weightInfo = "weight_info"
        case dynamicLabels = "dynamic_labels"
        case presetLabels = "preset_labels"
        case dynamicExtractedLabels = "dynamic_extracted_labels"
        case locationBasedLabels = "location_based_labels"
    }
    
    struct LocationLabel: Codable {
//        {
//               "id" : 5461,
//               "key" : "visionx",
//               "value" : "VisionX"
//              }
        
        var id: Int?
        var key, value: String?

        enum CodingKeys: String, CodingKey {
            case id = "id"
            case key = "key"
            case value = "value"
        }
    }
}

class Miscellaneous: Codable {
    var foodDeliveyFlag, legalDocumentFlag, payStubsFlag, confidentialFlag: Bool?
    var fragileFlag, oversizeFlag, timeSensitiveFlag, returnToSenderFlag: Bool?

    enum CodingKeys: String, CodingKey {
        case foodDeliveyFlag = "food_delivey_flag"
        case legalDocumentFlag = "legal_document_flag"
        case payStubsFlag = "pay_stubs_flag"
        case confidentialFlag = "confidential_flag"
        case fragileFlag = "fragile_flag"
        case oversizeFlag = "oversize_flag"
        case timeSensitiveFlag = "time_sensitive_flag"
        case returnToSenderFlag = "return_to_sender_flag"
    }
}

struct Address : Codable {
    var receiverAddress : ReceiverAddress?
    var senderAddress : SenderAddress?

    enum CodingKeys: String, CodingKey {
        case receiverAddress = "receiver_address"
        case senderAddress = "sender_address"
    }

    struct ReceiverAddress : Codable {
        
        var city : String?
        var name : String?
        var state : String?
        var zipcode : String?
        var zipcodeLine : String?
        var addressLine : String?

        enum CodingKeys: String, CodingKey {
            
            case city = "city"
            case name = "name"
            case state = "state"
            case zipcode = "zipcode"
            case zipcodeLine = "zip_code_line"
            case addressLine = "address_line_1"
        }
    }

    struct SenderAddress : Codable {
        var city : String?
        var name : String?
        var state : String?
        var zipcode : String?
        var zipcodeLine : String?
        var addressLine : String?

        enum CodingKeys: String, CodingKey {
            case city = "city"
            case name = "name"
            case state = "state"
            case zipcode = "zipcode"
            case zipcodeLine = "zip_code_line"
            case addressLine = "address_line_1"
        }
    }
}

let couriersList = [
    "usps": "USPS",
    "ups": "UPS",
    "amazon": "Amazon",
    "purolator": "Purolator",
    "fedex": "FedEx",
    "canada-post": "CANADA POST",
    "canpar": "Canpar",
    "cdl": "CDL",
    "china-ems": "CHINA-EMS",
    "china-post": "CHINA-POST",
    "dhl": "DHL",
    "dynamex": "DYNAMEX",
    "equick-cn": "Equick CN",
    "fastway": "fastway",
    "australia-post": "AUSTRALIA POST",
    "hongkong-post": "Hongkong Post",
    "india-post": "India Post",
    "japan-post": "JP Post",
    "jd": "JD",
    "lasership": "LaserShip",
    "mark3": "MARK 3",
    "maxx": "Max",
    "ontrac": "OnTrac",
    "pakistan-post": "PAKISTAN POST",
    "royal-mail": "Royal Mail",
    "sf-express": "SF EXPRESS",
    "singapore-post": "Singapore POST",
    "sto-express": "sto express",
    "tnt": "TNT",
    "yto-express": "YTO EXPRESS",
    "yun-express": "YunExpress",
    "zto-express": "ZTO EXPRESS",
    "dpd": "dpd",
    "star-track": "STARTRACK",
    "hermes": "Hermes",
    "parcel-force": "PARCEL-FORCE",
    "postnl": "postnl",
    "yodel": "YODEL",
    "gls": "GLS",
    "colissimo": "colissimo",
    "collect-plus": "collect +",
    "transworld": "transworld",
    "crawfords-delivery-services" : "Crawfords",
    "whistl": "whistl",
    "an-post": "an post",
    "wish-post": "wish POST",
    
    //Those below are not with confirmed formatting
    
    "city-air-express": "City Air Express",
    "sagawa-express": "Sagawa Express",
    "fukuyama-transportation": "Fukuyama Transportation",
    "skyworldwide": "Sky Worldwide",
    "toll-group": "Toll Group",
    "ide-express": "IDE Express",
    "road-express": "Road Express",
    "endeavour": "endeavour",
    "overnight-express": "Overnight Express",
    "rhyme-express": "Rhyme Express",
    "yuantong-express": "Yuantong Express",
    "best-express": "Best Express",
    "deppon": "Deppon",
    "tmall": "tmall",
    "netease": "Netease",
    "unitop": "Unitop",
    "chronopost": "Chrono Post",
    "newzealand-post": "New Zealand Post",
    "ninja-van": "ninja van",
    "pos-laju": "POS LAJU",
    "city-link": "City Link",
    "lel-express": "LEL Express",
    "gdex": "GDEX",
    "blibli": "Blibli",
    "tokopedia": "Tokopedia",
    "tiki": "tiki",
    "j&t-express": "J&T Express",
    "jne": "JNE",
    "surpostal": "Surpostal",
    "espana": "Espana",
    "mercado": "Mercado",
    "webpack": "Webpack",
    "andreani": "Andreani",
    "correo-argentino": "Correo Argentino",
    "urbano": "Urbano",
    "correos": "Correos",
    "yamato-transport": "Yamato Transport",
    "247-express": "247 Express",
    "chilexpress": "Chilexpress",
    "flash-express": "Flash Express",
    "kerry-express": "Kerry Express",
    "shopee-xpress": "Shopee Express",
    "thailand-post": "Thailand Post",
    "viettel-post": "Viettel Post",
    "wsp-express": "WSP Express",
    "zoom-express": "Zoom Express",
    "inpost": "inpost",
    "poczta_polska": "Poczta Polska",
    "blue-express": "Blue Express",
    "olva": "Olva",
    "aramex": "Aramex",
    "nhat-tin-logistics": "Nhat Tin Logistics",
    "bpost": "BPost",
    "vietnam-post": "Vietnam Post",
    "ocs": "OCS",
    "dawn-wing": "Dawn Wing",
    "the-courier-guy": "The Courier Guy",
    "ccd-courier": "CCD Courier",
    "bex-express": "BEX Express",
    "postnard": "Postnard",
    "2go-express": "2GO Express",
    "jrs-express": "JRS Express",
    "geodis": "Geodis",
    "lso": "LSO",
    "statovernight": "statovernight",
    "bring": "Bring",
    "sr-group": "SR Group",
    "pentagon-freight": "Pentagon Freight",
    "loomis-express": "Loomis Express",
    "transferd": "Transferd",
    "logi-trans": "Logi Trans",
    "jetpak": "jetpak",
    "db-schenker": "DB-SCHENKER",
    "merkesdal": "merkesdal",
    "kuehne-nagel": "KUEHNE+NAGEL",
    "postnord": "postnord",
    "nor-lines": "Nor Lines",
    "envialia": "envialia",
    "nacex" : "NACEX",
    "mrw": "MRW",
    "correos-express": "Correos Express",
    "redur": "REDUR",
    "ctt": "ctt Express",
    "sending": "sending",
]
