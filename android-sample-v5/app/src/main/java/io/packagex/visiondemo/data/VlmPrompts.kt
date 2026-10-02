package io.packagex.visiondemo.data

import io.packagex.visiondemo.model.DocType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Custom VLM prompts for the cloud-only document types (Vehicle/Tire ID, ID card/passport, license
 * plate, meter reading) and how their answer becomes fields. Ported verbatim from iOS
 * `Model/VLMPrompts.swift`'s `VLMDocumentPrompt`.
 */
object VlmPrompts {
    /** A custom-prompt VLM type: what it asks, the result title, and its field order. */
    data class Spec(val prompt: String, val title: String, val order: List<String>, val primaryKey: String? = null)

    /** iOS `DocType.vlmPrompt`; null for the default-prompt VLM and the non-VLM types. */
    fun spec(type: DocType): Spec? = when (type) {
        DocType.Tire -> Spec(vehicleTire, "Vehicle / Tire ID", vehicleTireOrder)
        DocType.IdCard -> Spec(identityDocument, "Identity Document", identityDocumentOrder)
        DocType.Plate -> Spec(licensePlate, "License Plate", licensePlateOrder)
        DocType.Meter -> Spec(meterReading, "Meter Reading", meterReadingOrder, primaryKey = "reading")
        else -> null
    }

    val vehicleTire = """
        You are reading a photo taken by a vehicle inspector. Find every Vehicle Identification Number (VIN, 17 characters, no I/O/Q) and every Tire Identification Number (TIN: the DOT code on a tire sidewall, starting with "DOT", up to 13 characters after it) visible in the image. Respond ONLY with minified JSON, no prose: {"document_type":"VEHICLE_TIRE_ID","vin":string|null,"tin":string|null,"tin_plant_code":string|null,"tin_week_year":string|null,"tire_size":string|null,"tire_brand":string|null,"confidence":number}
    """.trimIndent()

    val identityDocument = """
        You are reading a photo of a government identity document (national ID card, driver's license, passport, or residence permit). Extract the printed data exactly as written. Dates as YYYY-MM-DD. Respond ONLY with minified JSON, no prose: {"document_type":"ID_CARD"|"PASSPORT"|"DRIVERS_LICENSE"|"RESIDENCE_PERMIT"|"OTHER","issuing_country":string|null,"document_number":string|null,"surname":string|null,"given_names":string|null,"full_name":string|null,"date_of_birth":string|null,"sex":string|null,"nationality":string|null,"place_of_birth":string|null,"issue_date":string|null,"expiry_date":string|null,"address":string|null,"mrz":string|null,"confidence":number}
    """.trimIndent()

    val licensePlate = """
        You are reading a photo of a vehicle taken by an operator. Find the vehicle registration (license) plate. Read the plate number exactly as printed, character by character; do not guess hidden characters. Respond ONLY with minified JSON, no prose: {"document_type":"LICENSE_PLATE","plate_number":string|null,"country":string|null,"region":string|null,"plate_type":string|null,"vehicle_color":string|null,"vehicle_make":string|null,"readable":boolean,"confidence":number}
    """.trimIndent()

    val vehicleTireOrder = listOf("vin", "tin", "tin_plant_code", "tin_week_year", "tire_size", "tire_brand", "confidence")
    val identityDocumentOrder = listOf(
        "document_type", "issuing_country", "document_number", "surname", "given_names", "full_name",
        "date_of_birth", "sex", "nationality", "place_of_birth", "issue_date", "expiry_date", "address", "mrz", "confidence",
    )
    val licensePlateOrder = listOf("plate_number", "country", "region", "plate_type", "vehicle_make", "vehicle_color", "readable", "confidence")

    val meterReading = "You are reading a photo of a utility meter (electricity, gas, water or heat) taken by a meter reader. " +
        "Read the main register (the consumption counter) exactly as displayed, digit by digit: keep leading zeros and the decimal point " +
        "or decimal digits (often red or separated); do not round or guess hidden digits. Respond ONLY with minified JSON, no prose: " +
        "{\"document_type\":\"METER_READING\",\"meter_type\":\"ELECTRIC\"|\"GAS\"|\"WATER\"|\"HEAT\"|\"OTHER\",\"reading\":string|null,\"unit\":string|null," +
        "\"meter_serial_number\":string|null,\"register_count\":number|null,\"registers\":[{\"label\":string,\"reading\":string,\"unit\":string|null}]|null," +
        "\"date_time\":string|null,\"confidence_notes\":string|null} where unit is as printed (kWh, m³, ft³, gal, MWh, GJ, …), " +
        "registers lists every register only when the meter shows more than one (e.g. multi-tariff T1/T2, day/night, import/export), " +
        "date_time is the date/time shown on the display if any, and confidence_notes is null unless some digits are illegible (then say which)."
    val meterReadingOrder = listOf("meter_type", "reading", "unit", "meter_serial_number", "register_count", "registers", "date_time", "confidence_notes")

    /** Canonical plate for lookups: upper-case ASCII letters/digits, 2–10 of them; null otherwise. */
    fun normalizedPlate(raw: String): String? {
        val plate = raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        return if (plate.length in 2..10) plate else null
    }

    private val vinLetters = mapOf(
        'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6, 'G' to 7, 'H' to 8, 'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4,
        'N' to 5, 'P' to 7, 'R' to 9, 'S' to 2, 'T' to 3, 'U' to 4, 'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9,
    )
    private val vinWeights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    /** ISO 3779 check digit (position 9). Null when the string isn't a 17-char VIN. */
    fun vinChecksumIsValid(raw: String): Boolean? {
        val vin = raw.uppercase().filter { it.isLetterOrDigit() }
        if (vin.length != 17) return null
        var sum = 0
        for ((i, ch) in vin.withIndex()) sum += (if (ch in '0'..'9') ch - '0' else vinLetters[ch] ?: return false) * vinWeights[i]
        val check = sum % 11
        return vin[8] == (if (check == 10) 'X' else '0' + check)
    }

    /** The model's answer in a VLM response: the JSON object it returned, or its text when that isn't JSON. */
    sealed interface Answer {
        data class Obj(val json: JsonObject) : Answer
        data class Text(val text: String) : Answer
    }

    /**
     * `data.model_response[0]` (older backends: `data.inference.response_json`). The model's output may be an
     * object or a string, possibly JSON wrapped in a markdown code fence. Null when there is none.
     */
    fun answer(response: String): Answer? {
        val top = runCatching { Json.parseToJsonElement(response) }.getOrNull() as? JsonObject ?: return null
        val body = top["data"] as? JsonObject ?: return null
        val output = (body["model_response"] as? JsonArray)?.firstOrNull()
            ?: ((body["inference"] as? JsonObject)?.get("response_json"))
        return output?.let(::answerFromOutput)
    }

    private val fence = Regex("^```[A-Za-z]*\\s*|\\s*```$")

    fun answerFromOutput(output: JsonElement): Answer? {
        if (output is JsonObject) return Answer.Obj(output)
        val p = output as? JsonPrimitive ?: return null
        if (!p.isString) return null
        var text = p.content.trim()
        if (text.startsWith("```")) text = fence.replace(text, "")   // ```json\n{…}\n```
        // The object, even with prose around it.
        val open = text.indexOf('{')
        val close = text.lastIndexOf('}')
        if (open in 0 until close) {
            val obj = runCatching { Json.parseToJsonElement(text.substring(open, close + 1)) }.getOrNull() as? JsonObject
            if (obj != null) return Answer.Obj(obj)
        }
        return if (text.isEmpty()) null else Answer.Text(text)
    }

    /** One row of a VLM answer. [section] heads values inside a nested object or list ("Registers 2"); null at the top level. */
    data class Field(val label: String, val value: String, val section: String? = null, val key: String = "")

    private fun isNested(v: JsonElement) = v is JsonObject || (v is JsonArray && v.any { it is JsonObject || it is JsonArray })

    /**
     * The model's answer -> (label, value) rows: keys in [order] first, then the rest alphabetically; nulls and
     * empty values skipped; nested objects and lists flattened into sections after the top-level values.
     */
    fun fields(answer: Answer, order: List<String>, keepDocumentType: Boolean): List<Field> {
        val first = when (answer) {
            is Answer.Text -> return listOf(Field("Response", answer.text, key = "response"))
            is Answer.Obj -> answer.json
        }
        val out = mutableListOf<Field>()
        val nested = mutableListOf<Field>()
        val keys = order.filter { it in first } + first.keys.filter { it !in order }.sorted()
        for (key in keys) {
            if (key == "document_type" && !keepDocumentType) continue
            val raw = first.getValue(key)
            if (isNested(raw)) {
                flatten(raw, JsonFields.humanize(key), nested)
                continue
            }
            val v = value(raw)
            if (v.isEmpty()) continue
            out.add(Field(JsonFields.humanize(key), v, key = key))
            if (key == "vin") vinChecksumIsValid(v)?.let { ok -> out.add(Field("VIN Checksum", if (ok) "Valid" else "INVALID — re-scan")) }
            if (key == "plate_number") {
                val confident = ((first["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 1.0) >= 0.7 &&
                    ((first["readable"] as? JsonPrimitive)?.booleanOrNull ?: true)
                val plate = normalizedPlate(v)
                if (plate != null && confident) {
                    out.add(Field("Plate (Normalized)", plate))
                    out.add(Field("Validation", "Valid"))
                } else {
                    out.add(Field("Validation", "INVALID — re-scan"))
                }
            }
        }
        return out + nested
    }

    /** A scalar (or list of scalars) as shown: "" for null / "null" / blank, Yes/No for booleans. */
    fun value(v: JsonElement): String {
        if (v is JsonPrimitive && !v.isString) v.booleanOrNull?.let { return if (it) "Yes" else "No" }
        if (v is JsonArray) return v.map(::value).filter { it.isNotEmpty() }.joinToString(", ")
        val s = JsonFields.string(v).trim()
        return if (s.lowercase() == "null") "" else s
    }

    /** Objects -> one row per key under [section]; lists of objects -> one section per item ("Registers 1", …). */
    private fun flatten(node: JsonElement, section: String, out: MutableList<Field>) {
        when (node) {
            is JsonObject -> {
                val inner = mutableListOf<Field>()
                for (k in node.keys.sorted()) {
                    val v = node.getValue(k)
                    if (isNested(v)) {
                        flatten(v, section + " · " + JsonFields.humanize(k), inner)
                    } else {
                        val s = value(v)
                        if (s.isNotEmpty()) out.add(Field(JsonFields.humanize(k), s, section, k))
                    }
                }
                out += inner
            }
            is JsonArray -> node.forEachIndexed { i, v -> flatten(v, if (node.size > 1) "$section ${i + 1}" else section, out) }
            else -> value(node).takeIf { it.isNotEmpty() }?.let { out.add(Field(section, it)) }
        }
    }
}
