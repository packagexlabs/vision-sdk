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
    /** A custom-prompt VLM type: what it asks, the result title, its field order, and the keys tried in turn for the big card. */
    data class Spec(val prompt: String, val title: String, val order: List<String>, val primaryKeys: List<String> = emptyList())

    /** iOS `DocType.vlmPrompt`; null for the default-prompt VLM and the non-VLM types. */
    fun spec(type: DocType): Spec? = when (type) {
        DocType.Tire -> Spec(vehicleTire, "Vehicle / Tire ID", vehicleTireOrder)
        DocType.IdCard -> Spec(identityDocument, "Identity Document", identityDocumentOrder)
        DocType.Plate -> Spec(licensePlate, "License Plate", licensePlateOrder)
        // Prepaid meters showing only their remaining credit have no reading: the credit goes in the big card instead.
        DocType.Meter -> Spec(meterReading, "Meter Reading", meterReadingOrder, primaryKeys = listOf("reading", "credit"))
        else -> null
    }

    val vehicleTire = """
        You are reading a photo taken by a vehicle inspector. Find every Vehicle Identification Number (VIN, 17 characters, no I/O/Q) and every Tire Identification Number (TIN: the DOT code on a tire sidewall, starting with "DOT", up to 13 characters after it) visible in the image. Respond ONLY with minified JSON, no prose: {"document_type":"VEHICLE_TIRE_ID","vin":string|null,"tin":string|null,"tin_plant_code":string|null,"tin_week_year":string|null,"tire_size":string|null,"tire_brand":string|null,"confidence":number} Never make up a value: one that is not visible is null. If the photo shows no VIN and no tire code, respond only {"document_type":"NOT_FOUND"}.
    """.trimIndent()

    val identityDocument = """
        You are reading a photo of a government identity document (national ID card, driver's license, passport, or residence permit). Extract the printed data exactly as written. Dates as YYYY-MM-DD. Respond ONLY with minified JSON, no prose: {"document_type":"ID_CARD"|"PASSPORT"|"DRIVERS_LICENSE"|"RESIDENCE_PERMIT"|"OTHER","issuing_country":string|null,"document_number":string|null,"surname":string|null,"given_names":string|null,"full_name":string|null,"date_of_birth":string|null,"sex":string|null,"nationality":string|null,"place_of_birth":string|null,"issue_date":string|null,"expiry_date":string|null,"address":string|null,"mrz":string|null,"confidence":number} Never make up a value: one that is not printed is null. If the photo shows no identity document, respond only {"document_type":"NOT_FOUND"}.
    """.trimIndent()

    val licensePlate = """
        You are reading a photo of a vehicle taken by an operator. Find the vehicle registration (license) plate. Read the plate number exactly as printed, character by character; do not guess hidden characters. Respond ONLY with minified JSON, no prose: {"document_type":"LICENSE_PLATE","plate_number":string|null,"country":string|null,"region":string|null,"plate_type":string|null,"vehicle_color":string|null,"vehicle_make":string|null,"readable":boolean,"confidence":number} Never make up a value: one that is not visible is null. If the photo shows no license plate, respond only {"document_type":"NOT_FOUND"}.
    """.trimIndent()

    val vehicleTireOrder = listOf("vin", "tin", "tin_plant_code", "tin_week_year", "tire_size", "tire_brand", "confidence")
    val identityDocumentOrder = listOf(
        "document_type", "issuing_country", "document_number", "surname", "given_names", "full_name",
        "date_of_birth", "sex", "nationality", "place_of_birth", "issue_date", "expiry_date", "address", "mrz", "confidence",
    )
    val licensePlateOrder = listOf("plate_number", "country", "region", "plate_type", "vehicle_make", "vehicle_color", "readable", "confidence")

    val meterReading = "You are reading a photo of a meter taken by a meter reader: electricity, gas, water, heat (also cooling and district heating) or " +
        "another counter (hour counter, oil/fuel or flow meter). If several meters are visible, read the most central, fully visible one. " +
        "Read its main consumption register exactly as shown, digit by digit, only from the register itself (number drums, pointer dials or " +
        "the display), never from serial numbers, barcodes, nameplates, stickers or printed constants (Kh, Rr, rev/kWh, imp/kWh, V, A, Hz). " +
        "reading has one character per drum, dial or display digit, leading zeros included, and one '.' where the whole units end: first the " +
        "whole-unit digits, then '.', then the fraction digits (red, smaller or boxed digits, a red-framed last drum, digits after a decimal " +
        "point or comma, dials marked ×0.1, ×0.01, …), e.g. five black drums 00012 and three red drums 345 read 00012.345 (red drums are " +
        "part of the reading, never drop them). A comma between groups of three digits on a gallons or cubic-feet display separates " +
        "thousands: drop it. Write ? for a hidden or illegible digit, never guess. Number drums: a drum caught between two digits counts as " +
        "the lower one (between 9 and 0 is 9) unless the drum to its right already shows 0, then the higher one. Pointer dials: one digit " +
        "per dial, left to right; neighbouring dials turn in opposite directions, so follow each dial's own printed numbers; take the number " +
        "the pointer has last passed (between 9 and 0 is 9); if a pointer is exactly on a number, take it only if the dial to its right has " +
        "passed 0, else one less. Skip test dials, sweep hands, flow stars or wheels and any dial that is not part of the register, but keep " +
        "fraction dials after the drums (water meters: black drums are whole m³, then red drums, then the ×0.1, ×0.01, ×0.001, ×0.0001 " +
        "dials). Gas meters often have four register dials labelled ONE MILLION, ONE HUNDRED THOUSAND, TEN THOUSAND and ONE THOUSAND (or " +
        "1,000,000 … 1,000) CUBIC FEET PER REV: read those four left to right as four digits; the small ½ FOOT (HALF FOOT), 1 FOOT and 2 " +
        "FOOT dials are test dials, skip them. multiplier is null unless a multiplying factor for the register is printed on the meter face " +
        "(MULT, ×, Multiply by); never apply it to reading. Several registers (two-rate drums such as day/night or low/normal; displays with " +
        "OBIS 1.8.0 total import, 1.8.1/1.8.2 tariffs T1/T2, 2.8.0 export): reading is the total import register (1.8.0 or TOTAL) if shown, " +
        "else the first (top or left) one. reading_label is the register code shown beside the reading on an electronic display (an OBIS " +
        "code or tariff such as T1), null for drums, dials and unlabelled displays. reading is null when the display shows something other " +
        "than consumption (date, time, ID, voltage, power or demand kW, flow rate, temperature, error code, an all-8 segment test). Heat " +
        "meters: the energy register (kWh, MWh, GJ), not volume. Flow and fuel meters: the non-resettable total, not a resettable trip " +
        "counter. Prepaid meters: remaining credit (CREDIT, BAL, balance, money or kWh left) is not a reading; put it with its unit or " +
        "currency in credit, and give reading only if the cumulative consumption is shown. Respond ONLY with minified JSON, no prose: " +
        "{\"document_type\":\"METER_READING\",\"unit\":string|null,\"meter_type\":\"ELECTRIC\"|\"GAS\"|\"WATER\"|\"HEAT\"|\"OTHER\"," +
        "\"display_type\":\"DIGITAL\"|\"ANALOG_DRUM\"|\"ANALOG_DIALS\"|\"MIXED\",\"reading_label\":string|null,\"reading\":string|null," +
        "\"multiplier\":number|null,\"credit\":string|null,\"meter_serial_number\":string|null,\"register_count\":number|null," +
        "\"registers\":[{\"label\":string,\"reading\":string,\"unit\":string|null}]|null,\"date_time\":string|null,\"confidence_notes\":string|null} " +
        "where meter_type follows from the printed unit and the words on the face, never from dials or drums (ft³, CCF, therm or GAS → GAS; " +
        "gal, litres or WATER → WATER; kWh → ELECTRIC; HEAT for heat, cooling or district heating; OTHER for hour, oil/fuel or other " +
        "counters), display_type is DIGITAL for an electronic display, ANALOG_DRUM for number drums, ANALOG_DIALS for clock-style pointer " +
        "dials and MIXED for drums plus fraction dials, unit is as printed for the reading (kWh, MWh, kvarh, m³, ft³, CCF, gal, L, GJ, Gcal, " +
        "h, …), registers lists every visible register when two or more are visible, else null, date_time is the date/time shown on the " +
        "display if any, and confidence_notes is null unless a digit is illegible or a rule above was a close call (then say which digit and " +
        "why). If the photo shows no meter, or its register cannot be read at all, respond only {\"document_type\":\"NOT_FOUND\"}: " +
        "never make up a reading or reuse the example's digits."
    val meterReadingOrder = listOf(
        "meter_type", "display_type", "reading_label", "reading", "unit", "multiplier", "credit", "meter_serial_number",
        "register_count", "registers", "date_time", "confidence_notes",
    )

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

    /** The answer for a photo showing nothing of the type, as the prompts ask: {"document_type":"NOT_FOUND"} */
    fun notFound(answer: Answer): Boolean =
        answer is Answer.Obj && (answer.json["document_type"] as? JsonPrimitive)?.content == "NOT_FOUND"

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
