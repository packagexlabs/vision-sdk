package io.packagex.visiondemo.data

import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.OcrField
import io.packagex.visiondemo.model.OcrResult
import io.packagex.visiondemo.model.OcrTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns raw extraction JSON (cloud or on-device) into a field list. Shipping labels, item labels and bills of
 * lading list only the original demos' fields ([DocumentFields]); everything else (the default-prompt VLM
 * answer) is flattened generically.
 * Ported from iOS `Model/OCRParser.swift`.
 */
object OcrParser {
    /** Keys that carry geometry, provenance or bulk text rather than extracted values. */
    private val noise: Set<String> = setOf(
        "extended_response", "raw_response", "raw_text", "ocr_text", "vertices", "validated_by",
        "bounding_box", "bbox", "confidence", "image_url", "metadata", "hash", "created_at",
        "updated_at", "object", "id", "file", "images", "usage", "model_version", "inference_time"
    )

    /**
     * Generic-path field order and labels (a default-prompt VLM answer read as a shipping label), following the original demo's response views
     * (e.g. SLResponseView: account -> tracking -> courier -> weight -> RMA -> dimensions -> tags ->
     * receiver -> middle mile -> sender -> PO -> reference -> shipment type). Keys not listed follow
     * alphabetically.
     */
    private val order: Map<DocType, List<String>> = mapOf(
        DocType.SL to listOf(
            "account_id", "tracking_number", "provider_name", "weight", "rma_number", "dimensions",
            "special_handling_labels", "tags", "recipient", "middle_mile", "sender", "purchase_order",
            "reference_number", "service_level_name"
        )
    )

    private val labels: Map<String, String> = mapOf(
        "tracking_number" to "Tracking No.", "provider_name" to "Courier", "service_level_name" to "Shipment Type",
        "purchase_order" to "PO #", "reference_number" to "REF #", "rma_number" to "RMA #", "account_id" to "Account Id",
        "recipient" to "Receiver", "sender" to "Sender", "middle_mile" to "Middle Mile", "special_handling_labels" to "Tags",
        "formatted_address" to "Address", "postal_code" to "Zip", "line1" to "Street", "line2" to "Street 2"
    )

    /** The field shown large at the top of the result. */
    private val primaryKey: Map<DocType, String> = mapOf(DocType.SL to "tracking_number")

    private fun label(key: String): String = labels[key] ?: JsonFields.humanize(key)

    private fun parseJsonObject(json: String): JsonObject? =
        (runCatching { Json.parseToJsonElement(json) }.getOrNull()) as? JsonObject

    private fun jsonString(el: JsonElement?): String? {
        val p = el as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    fun message(json: String): String? = jsonString(parseJsonObject(json)?.get("message"))

    fun documentClass(json: String): String? {
        val inference = (parseJsonObject(json)?.get("data") as? JsonObject)?.get("inference") as? JsonObject
        return jsonString(inference?.get("document_class"))
    }

    /**
     * Fields (with section, raw key and on-device geometry) plus any tables (arrays of flat rows,
     * e.g. BOL `inference.tables`, VLM invoice/receipt line items), rebuilt as header + rows.
     */
    fun parse(json: String, type: DocType): OcrResult {
        val top = parseJsonObject(json) ?: return OcrResult(type, emptyList(), emptyList(), null, json)
        val body = top["data"] as? JsonObject ?: top
        val (rows, tables) = when (type) {
            DocType.SL -> DocumentFields.shippingLabel(body) to emptyList()
            DocType.IL -> DocumentFields.itemLabel(body) to emptyList()
            DocType.BOL -> DocumentFields.billOfLading(body)
            else -> return parse(body, top, type, json)
        }
        return documentResult(rows, tables, top, type, json)
    }

    /**
     * Shipping label, item label and bill of lading (cloud or on-device): only the fields the original demos showed,
     * in their sections and order ([DocumentFields]), each linked to its on-device box when there is one.
     */
    private fun documentResult(
        rows: List<DocumentFields.Row>,
        tables: List<DocumentFields.Table>,
        top: JsonObject,
        type: DocType,
        rawJson: String,
    ): OcrResult {
        val entities = boxedEntities(top)
        val fields = rows.mapIndexed { index, row ->
            val entity = entities[row.value]
            OcrField(
                id = "${row.section ?: ""}|${row.key}|$index",
                key = row.key,
                label = row.label,
                value = row.value,
                section = row.section,
                vertices = entity?.second,
                validatedBy = entity?.first ?: emptyList()
            )
        }
        val primary = fields.firstOrNull { it.key == primaryKey[type] } ?: fields.firstOrNull()
        return OcrResult(type, fields, tables.map { OcrTable(it.title, it.headers, it.rows) }, primary, rawJson)
    }

    /** [root] is the object to list; [top] the whole response, searched for on-device boxes; [rawJson] is kept on the result. */
    fun parse(root: JsonObject, top: JsonObject, type: DocType, rawJson: String): OcrResult {
        val leaves = mutableListOf<Pair<List<String>, String>>()
        val tables = mutableListOf<Triple<List<String>, List<String>, List<List<String>>>>()
        walk(root, emptyList(), leaves, tables)

        // Drop wrapper levels every value shares ("inference", "response_json", ...).
        var strip = 0
        while (leaves.size > 1) {
            val first = leaves.first().first
            if (first.size <= strip + 1) break
            if (!leaves.all { it.first.size > strip + 1 && it.first[strip] == first[strip] }) break
            strip++
        }

        // Item labels (cloud) put vendor attributes under data.metadata.additional_attributes as
        // [{name: value}]; "metadata" is otherwise request bookkeeping, so lift just this list out.
        val attrs = (root["metadata"] as? JsonObject)?.get("additional_attributes") as? JsonArray
        attrs?.mapNotNull { it as? JsonObject }?.forEach { pair ->
            pair.entries.sortedBy { it.key }.forEach { (k, v) ->
                val s = JsonFields.string(v)
                if (s.isNotEmpty()) leaves.add((List(strip) { "" } + listOf("additional_attributes", k)) to s)
            }
        }

        val rank = order[type] ?: emptyList()
        fun rankIndex(head: String) = rank.indexOf(head).let { if (it < 0) rank.size else it }

        // Ranked heads first; then plain values before sections; within an object, its own values before nested ones.
        val sorted = leaves.map { (path, value) -> path.drop(strip) to value }
            .sortedWith(
                compareBy(
                    { rankIndex(it.first.firstOrNull() ?: "") },
                    { if (it.first.size > 1) 1 else 0 },
                    { it.first.firstOrNull() ?: "" },
                    { it.first.size },
                    { it.first.joinToString(".") }
                )
            )

        val entities = boxedEntities(top)
        val fields = sorted.mapIndexed { index, (path, value) ->
            val key = path.lastOrNull() ?: ""
            var section = if (path.size > 1) path.dropLast(1).filter { it.toIntOrNull() == null }.joinToString(" · ") { label(it) } else null
            if (section?.isEmpty() == true) section = null
            val entity = entities[value]
            OcrField(
                id = "${section ?: ""}|$key|$index",
                key = key,
                label = label(key),
                value = value,
                section = section,
                vertices = entity?.second,
                validatedBy = entity?.first ?: emptyList()
            )
        }

        val tbl = tables.map { (path, headers, rows) ->
            val strippedPath = path.drop(minOf(strip, path.size))
            OcrTable(
                title = strippedPath.filter { it.toIntOrNull() == null }.joinToString(" · ") { label(it) },
                headers = headers.map { label(it) },
                rows = rows
            )
        }

        val primaryKeyForType = primaryKey[type]
        val primary = fields.firstOrNull { it.key == primaryKeyForType }
            ?: fields.firstOrNull { it.label == "Full Name" }
            ?: fields.firstOrNull()

        return OcrResult(type, fields, tbl, primary, rawJson)
    }

    private fun isScalar(v: JsonElement) = v !is JsonObject && v !is JsonArray

    private fun walk(
        node: JsonElement,
        path: List<String>,
        leaves: MutableList<Pair<List<String>, String>>,
        tables: MutableList<Triple<List<String>, List<String>, List<List<String>>>>
    ) {
        when (node) {
            is JsonObject -> for (k in node.keys.sorted()) if (k !in noise) walk(node.getValue(k), path + k, leaves, tables)
            is JsonArray -> {
                val rows = node.mapNotNull { it as? JsonObject }
                if (rows.isNotEmpty() && rows.size == node.size &&
                    rows.all { obj -> obj.values.all(::isScalar) } && path.lastOrNull() != "additional_attributes"
                ) {
                    val headers = mutableListOf<String>()
                    for (r in rows) for (k in r.keys.sorted()) if (k !in noise && k !in headers) headers.add(k)
                    if (rows.size == 1 && headers.size <= 2) {
                        for (h in headers) walk(rows[0].getValue(h), path + h, leaves, tables)
                    } else if (headers.isNotEmpty()) {
                        tables.add(Triple(path, headers, rows.map { r -> headers.map { h -> JsonFields.string(r[h] ?: JsonNull) } }))
                    }
                } else if (node.all(::isScalar)) {
                    val v = node.map { JsonFields.string(it) }.filter { it.isNotEmpty() }.joinToString(", ")
                    if (v.isNotEmpty()) leaves.add(path to v)
                } else {
                    node.forEachIndexed { i, v -> walk(v, if (node.size > 1) path + "${i + 1}" else path, leaves, tables) }
                }
            }
            else -> {
                val v = JsonFields.string(node)
                if (v.isNotEmpty() && v != "null") leaves.add(path to v)
            }
        }
    }

    /** On-device responses carry `{ value, vertices: [TL, TR, BL, BR] }` entities; index their geometry by value. */
    private fun boxedEntities(json: JsonElement): Map<String, Pair<List<String>, List<List<Double>>>> {
        val out = mutableMapOf<String, Pair<List<String>, List<List<Double>>>>()
        fun walkEntities(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val value = node["value"]
                    val verts = node["vertices"] as? JsonArray
                    if (value != null && verts != null && verts.size >= 4) {
                        val vertList = verts.mapNotNull { vertEl -> (vertEl as? JsonArray)?.map { it.jsonPrimitive.double } }
                        val s = JsonFields.string(value)
                        val validatedBy = (node["validated_by"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.uppercase() }
                            ?: emptyList()
                        if (s.isNotEmpty() && vertList.size >= 4 && out[s] == null) out[s] = validatedBy to vertList
                    }
                    node.values.forEach(::walkEntities)
                }
                is JsonArray -> node.forEach(::walkEntities)
                else -> {}
            }
        }
        walkEntities(json)
        return out
    }
}
