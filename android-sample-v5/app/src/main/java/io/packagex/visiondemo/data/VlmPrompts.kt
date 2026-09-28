package io.packagex.visiondemo.data

/**
 * Custom VLM prompts for the cloud-only document types (Vehicle/Tire ID, ID card/passport, license
 * plate). Ported verbatim from iOS `Model/VLMPrompts.swift`'s `VLMDocumentPrompt`.
 */
object VlmPrompts {
    val vehicleTire = """
        You are reading a photo taken by a vehicle inspector. Find every Vehicle Identification Number (VIN, 17 characters, no I/O/Q) and every Tire Identification Number (TIN: the DOT code on a tire sidewall, starting with "DOT", up to 13 characters after it) visible in the image. Respond ONLY with minified JSON, no prose: {"document_type":"VEHICLE_TIRE_ID","vin":string|null,"tin":string|null,"tin_plant_code":string|null,"tin_week_year":string|null,"tire_size":string|null,"tire_brand":string|null,"confidence":number}
    """.trimIndent()

    val identityDocument = """
        You are reading a photo of a government identity document (national ID card, driver's license, passport, or residence permit). Extract the printed data exactly as written. Dates as YYYY-MM-DD. Respond ONLY with minified JSON, no prose: {"document_type":"ID_CARD"|"PASSPORT"|"DRIVERS_LICENSE"|"RESIDENCE_PERMIT"|"OTHER","issuing_country":string|null,"document_number":string|null,"surname":string|null,"given_names":string|null,"full_name":string|null,"date_of_birth":string|null,"sex":string|null,"nationality":string|null,"place_of_birth":string|null,"issue_date":string|null,"expiry_date":string|null,"address":string|null,"mrz":string|null,"confidence":number}
    """.trimIndent()

    val licensePlate = """
        You are reading a photo of a vehicle taken by an operator. Find the vehicle registration (license) plate. Read the plate number exactly as printed, character by character; do not guess hidden characters. Respond ONLY with minified JSON, no prose: {"document_type":"LICENSE_PLATE","plate_number":string|null,"country":string|null,"region":string|null,"plate_type":string|null,"vehicle_color":string|null,"vehicle_make":string|null,"readable":boolean,"confidence":number}
    """.trimIndent()
}
