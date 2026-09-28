package io.packagex.visiondemo.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generic JSON value/label helpers shared by [OcrParser] and [ItemLabelFeedback].
 * Ported from iOS `Model/VLMPrompts.swift`'s `JSONFields`.
 */
object JsonFields {
    /** A JSON value as plain text: strings and primitives as-is, objects/arrays re-serialized, null as "". */
    fun string(v: JsonElement): String = when (v) {
        is JsonNull -> ""
        is JsonPrimitive -> v.content
        else -> v.toString()
    }

    /** `"postal_code"` -> `"Postal Code"`, `"qty"` -> `"Qty"`, `"someCamel"` -> `"Some Camel"`. */
    fun humanize(key: String): String {
        val spaced = key.replace("_", " ").replace(Regex("([a-z])([A-Z])"), "$1 $2")
        return spaced.split(" ").filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
    }
}
