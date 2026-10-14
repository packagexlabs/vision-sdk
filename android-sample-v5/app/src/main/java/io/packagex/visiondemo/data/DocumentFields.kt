package io.packagex.visiondemo.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * The fields a Vision Scanner result shows for shipping labels, item labels and bills of lading: the selection,
 * labels, sections and order of the original demos' response views (Android `ResponseExtractor`
 * extractSLResponse / extractILResponse / extractBOLResponse; iOS SLResponseView, ILResponseView, BOLResponseView).
 * Everything else in the response (ids, hashes, timings, options, matching, raw text, duplicate provider lists, …)
 * is left out, and so are empty values. Cloud and on-device responses share these shapes: `data` for shipping
 * labels, `data.inference` for item labels and bills of lading. Same lists as iOS `Model/DocumentFields.swift`.
 */
object DocumentFields {
    data class Row(val section: String?, val label: String, val value: String, val key: String = "")
    data class Table(val title: String, val headers: List<String>, val rows: List<List<String>>)

    // region Shipping label

    fun shippingLabel(d: JsonObject): List<Row> {
        val b = Rows()
        val pkg = "Package Info"
        b.add(pkg, "Account ID", text(d["account_id"]), "account_id")
        b.add(pkg, "Box ID", text(at(d, "additional_attributes", "box_id")), "box_id")
        b.add(pkg, "Tracking #", text(d["tracking_number"]), "tracking_number")
        b.add(pkg, "Courier", text(d["provider_name"]), "provider_name")
        b.add(pkg, "Weight", pounds(d["weight"]), "weight")
        b.add(pkg, "RMA", text(d["rma_number"]), "rma_number")
        b.add(pkg, "Dimensions", boxDimensions(d["dimensions"]), "dimensions")
        val tagKeys = tagSources(d)
        b.add(pkg, "Tags", tagKeys.mapNotNull(::tagName).distinct().joinToString(", "), "tags")
        val special = list(d["special_handling_labels"])
        // "signature_required" -> "Signature Required", as the old Android sample.
        b.add(pkg, "Special Handling Labels", special.joinToString(", ") { JsonFields.humanize(it) }, "special_handling_labels")

        party(d["recipient"], "Receiver Info", b)

        // Middle mile: its own contact, or the first of two providers (courier hand-off).
        val providers = (d["providers"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val middleMile = d["middle_mile"] as? JsonObject
        if (middleMile != null || providers.size == 2) {
            val first = if (providers.size == 2) providers[0] else JsonObject(emptyMap())
            b.add("Middle Mile Info", "Tracking #", text(first["tracking_number"]), "tracking_number")
            b.add("Middle Mile Info", "Courier", text(first["name"]), "name")
            b.add("Middle Mile Info", "Shipment Type", text(at(first, "service_level", "name")), "service_level")
            party(middleMile, "Middle Mile Info", b)
        }

        party(d["sender"], "Sender Info", b)

        val logistics = "Logistics Attributes"
        b.add(logistics, "PO #", text(d["purchase_order"]), "purchase_order")
        b.add(logistics, "Ref #", text(d["reference_number"]), "reference_number")
        b.add(logistics, "Invoice #", text(d["invoice_number"]), "invoice_number")
        b.add(logistics, "Shipment Type", text(d["service_level_name"]), "service_level_name")

        // The original's Metadata section listed every extracted label; only the ones not already shown as tags
        // or special handling labels are new.
        val extra = list(d["extracted_labels"]).filter { it !in special && it !in tagKeys }
        b.add("Metadata", "Labels", extra.joinToString(", "), "extracted_labels")
        return b.rows
    }

    private val tagNames = mapOf(
        "food" to "Food Delivery", "food_delivey" to "Food Delivery", "food_delivery" to "Food Delivery",
        "time_sensitive" to "Time Sensitive", "fragile" to "Fragile", "confidential" to "Confidential",
        "legal_document" to "Legal Document", "oversized" to "Oversize", "oversize" to "Oversize",
        "return_to_sender" to "Return to sender", "pay_stub" to "Pay Stubs", "pay_stubs" to "Pay Stubs",
    )

    /** Cloud lists tags in `extracted_labels`; on-device may set `tags: {"fragile_flag": true, …}`. */
    private fun tagSources(d: JsonObject): List<String> {
        val flags = (d["tags"] as? JsonObject)?.filter { (_, v) -> (v as? JsonPrimitive)?.content == "true" }?.keys?.sorted() ?: emptyList()
        return flags + list(d["extracted_labels"]).filter { tagName(it) != null }
    }

    private fun tagName(key: String): String? = tagNames[key.removeSuffix("_flag")]

    // endregion

    // region Item label

    /** `data` of the response; fields come from `data.inference`, vendor attributes also from `data.metadata`. */
    fun itemLabel(data: JsonObject): List<Row> {
        val inf = data["inference"] as? JsonObject ?: data
        val b = Rows()

        // Old Android sample's "Courier Info" (on-device item labels read the courier too).
        b.add("Courier Info", "Courier", text(inf["provider_name"]), "provider_name")
        b.add("Courier Info", "Tracking #", text(inf["tracking_number"]), "tracking_number")
        b.add("Courier Info", "Service Level", text(inf["service_level_name"]), "service_level_name")

        party(inf["recipient"], "Receiver Info", b, state = "state_code")
        party(inf["sender"], "Sender Info", b, state = "state_code")

        val supplier = inf["supplier"] as? JsonObject ?: JsonObject(emptyMap())
        b.add("Supplier Info", "ID", text(supplier["id"]), "id")
        b.add("Supplier Info", "Email", text(supplier["email"]), "email")
        b.add("Supplier Info", "Name", text(supplier["name"]), "name")
        b.add("Supplier Info", "Phone", text(supplier["phone"]), "phone")
        b.add("Supplier Info", "Business Name", text(supplier["business"]), "business")
        address(supplier["address"], "Supplier Info", "state_code", b)

        // The original showed these one per block, each under its own heading.
        val details = "Label Details"
        b.add(details, "Purchase Orders", text(inf["purchase_orders"]), "purchase_orders")
        b.add(details, "Sales Orders", text(inf["sales_orders"] ?: inf["sales_order"]), "sales_orders")
        b.add(details, "Model", text(inf["model"]), "model")
        b.add(details, "UPC", text(inf["upc"]), "upc")
        b.add(details, "GTIN", text(inf["gtin"]), "gtin")
        b.add(details, "Manufacturing Date", date(at(inf, "dates", "manufacturing")), "manufacturing")
        b.add(details, "Expiry Date", date(at(inf, "dates", "expiry")), "expiry")
        b.add(details, "Origin Country", text(at(inf, "origin", "country")), "country")

        val item = inf["item"] as? JsonObject ?: JsonObject(emptyMap())
        val it = "Item Details"
        b.add(it, "SKU", text(item["sku"]), "sku")
        b.add(it, "Name", text(item["name"]), "name")
        b.add(it, "Numbers", text(item["numbers"]), "numbers")
        b.add(it, "Quantity", quantity(item["quantity"]), "quantity")
        b.add(it, "Raw Dimensions", text(at(item, "dimensions", "raw")), "raw")
        b.add(it, "Dimensions", hwl(item["dimensions"]), "dimensions")
        b.add(it, "Volume", measure(item["volume"]), "volume")
        b.add(it, "Color", text(item["color"]), "color")
        b.add(it, "Size", text(item["size"]), "size")
        b.add(it, "Model", text(item["model"]), "model")
        b.add(it, "UPC", text(item["upc"]), "upc")
        b.add(it, "GTIN", text(item["gtin"]), "gtin")
        b.add(it, "SSCC", text(item["sscc"]), "sscc")
        b.add(it, "CLEI", text(item["clei"]), "clei")
        b.add(it, "Version", text(item["version"]), "version")
        b.add(it, "Season", text(item["season"]), "season")
        b.add(it, "Weight", measure(item["weight"]), "weight")
        b.add(it, "Mac Address(es)", text(item["mac_address"]), "mac_address")
        b.add(it, "Price", text(item["price"]), "price")
        b.add(it, "Chemical Formula", text(item["chemical_formula"]), "chemical_formula")

        val pkg = inf["package"] as? JsonObject ?: JsonObject(emptyMap())
        val pk = "Package Details"
        b.add(pk, "Raw Dimensions", text(at(pkg, "dimensions", "raw")), "raw")
        b.add(pk, "Dimensions", hwl(pkg["dimensions"]), "dimensions")
        b.add(pk, "Weight", measure(pkg["weight"]), "weight")
        b.add(pk, "Volume", measure(pkg["volume"]), "volume")

        val customer = inf["customer"] as? JsonObject ?: JsonObject(emptyMap())
        b.add("Customer Info", "ID", text(customer["id"]), "id")
        b.add("Customer Info", "Name", text(customer["name"]), "name")
        b.add("Customer Info", "SKU", text(customer["sku"]), "sku")
        b.add("Customer Info", "Item Number", text(customer["item_number"] ?: customer["itemNumber"]), "item_number")
        address(customer["address"], "Customer Info", "state_code", b)

        b.add("Pallet", "ID", text(at(inf, "pallet", "id")), "id")
        b.add("Pallet", "Number", text(at(inf, "pallet", "number")), "number")
        b.add("Carton", "ID", text(at(inf, "carton", "id")), "id")
        b.add("Carton", "Number", text(at(inf, "carton", "number")), "number")

        val ids = "Identifiers"
        b.add(ids, "LOT Number", text(at(inf, "lot", "number")), "lot")
        b.add(ids, "Batch Number", text(at(inf, "batch", "number")), "batch")
        b.add(ids, "Invoice Number", text(inf["invoice_number"]), "invoice_number")
        b.add(ids, "BOL Number", text(inf["bol_number"]), "bol_number")
        b.add(ids, "Serial Numbers", text(inf["serial_numbers"]), "serial_numbers")
        b.add(ids, "Barcode Values", text(inf["barcode_values"]), "barcode_values")
        b.add(ids, "Job ID", text(inf["job_id"]), "job_id")
        b.add(ids, "Reference", text(inf["reference"]), "reference")

        // Vendor attributes: on-device {name: value}; cloud [{name: value}, …] (in inference or metadata).
        val attrs = sortedMapOf<String, String>()
        for (source in listOf(inf["additional_attributes"], at(data, "metadata", "additional_attributes"))) {
            val dicts = when (source) {
                is JsonArray -> source.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(source)
                else -> emptyList()
            }
            for (o in dicts) for ((k, v) in o) attrs[k.replace(":", "").trim()] = text(v)
        }
        // Only attributes that add something: the cloud repeats most label fields here (cartonId, UPC, …).
        for ((k, v) in attrs) if (!b.shows(v)) b.add("Additional Attributes", k, v, k)
        return b.rows
    }

    // endregion

    // region Bill of lading

    private val bolLogistics = listOf(
        "Container #" to "container_number", "Customer PO #" to "customer_purchase_order_number",
        "Bill of Lading #" to "bill_of_lading", "House Bill of Lading #" to "house_bill_of_lading",
        "Master Bill of Lading #" to "master_bill_of_lading", "Line Bill of Lading #" to "line_bill_of_lading",
        "Load #" to "load_number", "Invoice #" to "invoice_number", "Order #" to "order_number",
        "PO #" to "purchase_order_number", "Reference #" to "reference_number", "Shipping ID" to "shipping_id",
    )

    /** `data` of the response; fields come from `data.inference`. */
    fun billOfLading(data: JsonObject): Pair<List<Row>, List<Table>> {
        val inf = data["inference"] as? JsonObject ?: data
        val la = inf["logistics_attributes"] as? JsonObject ?: JsonObject(emptyMap())
        val b = Rows()
        for ((label, key) in bolLogistics) {
            // On-device writes some of these camelCased (`billOfLading`), and its key-value pass at inference level.
            val camel = camel(key)
            val v = listOf(la[key], la[camel], inf[camel]).map(::text).firstOrNull { it.isNotEmpty() } ?: ""
            b.add("Logistics Attributes", label, v, key)
        }
        b.add("Logistics Attributes", "Shipping Date", isoDate(la["shipping_date"]).ifEmpty { text(inf["shippingDate"]) }, "shipping_date")

        val custom = inf["custom_attributes"] as? JsonObject ?: JsonObject(emptyMap())
        for (k in custom.keys.sorted()) b.add("Custom Attributes", k, text(custom[k]), k)

        party(inf["recipient"], "Receiver Info", b)
        party(inf["sender"], "Sender Info", b)

        // Old Android sample: on-device GS1 / key-value extras.
        val extra = inf["additional_attributes"] as? JsonObject ?: JsonObject(emptyMap())
        for (k in extra.keys.sorted()) b.add("Additional Attributes", k, text(extra[k]), k)

        val tables = ((inf["tables"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { t ->
            val rows = (t as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return@mapNotNull null
            table(rows)
        }
        return b.rows to tables
    }

    /**
     * Rows of one `inference.tables` entry. Columns come keyed "1", "2", … with the printed header as the first
     * row, so numeric keys are ordered numerically and that first row becomes the header; named keys are the header.
     */
    private fun table(rows: List<JsonObject>): Table? {
        val keys = rows.flatMap { it.keys }.distinct()
        val numeric = keys.isNotEmpty() && keys.all { k -> k.toIntOrNull() != null }
        val ordered = if (numeric) keys.sortedBy { it.toInt() } else keys.sorted()
        var cells = rows.map { r -> ordered.map { text(r[it]) } }.filter { row -> row.any { it.isNotEmpty() } }
        var headers = ordered
        if (numeric && cells.size > 1) {
            headers = cells.first()
            cells = cells.drop(1)
        }
        // Columns empty in every row add nothing.
        val keep = headers.indices.filter { i -> cells.any { it[i].isNotEmpty() } }
        if (keep.isEmpty() || cells.isEmpty()) return null
        return Table("Inventory Items", keep.map { headers[it] }, cells.map { row -> keep.map { row[it] } })
    }

    private fun camel(snake: String): String =
        snake.split("_").mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar { it.uppercase() } }.joinToString("")

    // endregion

    // region Document classification

    /** The original's display names for `inference.document_class`. */
    fun documentClass(raw: String): String = mapOf(
        "shipping_label" to "Shipping Label", "item_label" to "Item Label", "bill_of_lading" to "Bill of Lading",
        "others" to "Others", "receipt" to "Receipt", "invoice" to "Invoice",
    )[raw] ?: JsonFields.humanize(raw)

    // endregion

    // region Helpers

    private class Rows {
        val rows = mutableListOf<Row>()

        fun add(section: String?, label: String, value: String, key: String) {
            val v = value.trim()
            if (v.isEmpty() || v.equals("N/A", ignoreCase = true)) return
            rows.add(Row(section, label, v, key))
        }

        /** Whether [value] is already shown: equal to a row's value (ignoring case) or, when 4+ characters, part of one. */
        fun shows(value: String): Boolean {
            val v = value.trim().lowercase()
            return rows.any { val r = it.value.lowercase(); r == v || (v.length >= 4 && r.contains(v)) }
        }
    }

    /** Name, business, address and phone of a recipient / sender / middle-mile contact. */
    private fun party(p: JsonElement?, section: String, b: Rows, state: String = "state") {
        val o = p as? JsonObject ?: JsonObject(emptyMap())
        val name = text(o["name"])
        val business = text(o["business"])
        b.add(section, "Name", name, "name")
        if (!business.equals(name, ignoreCase = true)) b.add(section, "Business Name", business, "business")
        address(o["address"], section, state, b)
        b.add(section, "Phone", text(o["phone"]), "phone")
    }

    private fun address(a: JsonElement?, section: String, state: String, b: Rows) {
        val o = a as? JsonObject ?: JsonObject(emptyMap())
        b.add(section, "Street Address", listOf(text(o["line1"]), text(o["line2"])).filter { it.isNotEmpty() }.joinToString(" "), "line1")
        b.add(section, "City", capitalized(text(o["city"])), "city")
        b.add(section, "State", text(o[state]), state)
        b.add(section, "Zip Code", text(o["postal_code"]), "postal_code")
        b.add(section, "Address", text(o["formatted_address"]), "formatted_address")
    }

    private fun capitalized(s: String) = s.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

    fun at(d: JsonElement?, vararg keys: String): JsonElement? = keys.fold(d) { acc, k -> (acc as? JsonObject)?.get(k) }

    /** A scalar or list of scalars as shown; "" for null, "null", blanks and objects. Whole numbers without ".0". */
    fun text(v: JsonElement?): String {
        if (v == null || v is JsonObject) return ""
        if (v is JsonArray) return v.map(::text).filter { it.isNotEmpty() }.joinToString(", ")
        val p = v as? JsonPrimitive
        if (p != null && !p.isString) {
            p.doubleOrNull?.let { d -> if (d == Math.floor(d) && !d.isInfinite() && !p.content.contains('e', true)) return d.toLong().toString() }
        }
        return VlmPrompts.value(v)
    }

    private fun list(v: JsonElement?): List<String> = (v as? JsonArray)?.map(::text)?.filter { it.isNotEmpty() } ?: emptyList()

    /** Shipping-label weight is in pounds (cloud and on-device: a number); 2 decimals at most, as the old Android sample. */
    private fun pounds(v: JsonElement?): String {
        val p = v as? JsonPrimitive
        val d = p?.takeIf { !it.isString }?.doubleOrNull ?: p?.content?.toDoubleOrNull()
        if (d != null) {
            if (d <= 0.0) return ""
            return BigDecimal(d).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " lbs"
        }
        val s = text(v)
        return if (s.isEmpty() || s.any { it.isLetter() }) s else "$s lbs"
    }

    /** Shipping-label dimensions "L × W × H [unit]", only when all three are known. */
    private fun boxDimensions(v: JsonElement?): String {
        val d = v as? JsonObject ?: return ""
        val lwh = listOf("length", "width", "height").map { text(d[it]) }
        if (lwh.any { it.isEmpty() }) return ""
        val unit = text(d["unit"])
        return lwh.joinToString(" × ") + if (unit.isEmpty()) "" else " $unit"
    }

    /** `{value, unit}` → "3.2 kg"; else its `raw` text. */
    private fun measure(v: JsonElement?): String {
        val value = text(at(v, "value"))
        return if (value.isEmpty()) text(at(v, "raw")) else listOf(value, text(at(v, "unit"))).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Item / package dimensions as the original listed them: height, width, length. */
    private fun hwl(v: JsonElement?): String =
        listOf("Height" to "height", "Width" to "width", "Length" to "length").mapNotNull { (label, key) ->
            measure(at(v, key)).takeIf { it.isNotEmpty() }?.let { "$label: $it" }
        }.joinToString(", ")

    /** Item-label quantity: a number (cloud) or a list of numbers (on-device); whole units. */
    private fun quantity(v: JsonElement?): String {
        val first = (v as? JsonArray)?.firstOrNull() ?: v
        val n = (first as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return text(v)
        return n.toLong().toString()
    }

    /** `{day, month, year, raw}` → "28-1-2022" (the originals' day-month-year), else `raw`. */
    private fun date(v: JsonElement?): String {
        val parts = listOf("day", "month", "year").map { text(at(v, it)) }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) text(at(v, "raw")) else parts.joinToString("-")
    }

    /** `{year, month, day}` → "2026-09-30". */
    private fun isoDate(v: JsonElement?): String {
        val y = (at(v, "year") as? JsonPrimitive)?.intOrNull ?: return ""
        val md = listOf("month", "day").mapNotNull { (at(v, it) as? JsonPrimitive)?.intOrNull }.map { String.format(Locale.US, "%02d", it) }
        return (listOf(y.toString()) + md).joinToString("-")
    }

    // endregion
}
