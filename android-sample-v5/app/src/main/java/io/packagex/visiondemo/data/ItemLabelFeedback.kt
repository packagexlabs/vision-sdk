package io.packagex.visiondemo.data

import android.graphics.Bitmap
import io.packagex.visiondemo.BuildConfig
import io.packagex.visiondemo.model.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Per-field feedback on item-label results, sent the way the original demo's ILExtendedResponseView
 * does: a multipart POST to `<server>/submit-feedback` with the captured image, a `feedback_data`
 * JSON (per entity: original/corrected value, thumbs, barcode validation, vertices) and an optional
 * overall comment. Ported from iOS `Model/ItemLabelFeedback.swift`.
 */
object ItemLabelFeedback {
    data class Entry(val edited: String, val thumbs: Boolean?) // thumbs: null = none, true = up, false = down

    /** `BuildConfig.IL_FEEDBACK_URL` (defaults to the original demo's server; see app/build.gradle.kts). */
    val server: String get() = BuildConfig.IL_FEEDBACK_URL

    fun payload(r: OcrResult, entries: Map<String, Entry>, nowSeconds: Double): JsonObject {
        val fields = r.fields.mapIndexed { i, f ->
            val e = entries[f.id]
            val corrected = e?.edited ?: f.value
            val hasCorrection = corrected != f.value
            val fieldType = when {
                f.vertices == null -> "nonSpatial"
                f.value.toDoubleOrNull() != null -> "number"
                else -> "text"
            }
            val thumbs = when (e?.thumbs) {
                true -> "up"
                false -> "down"
                null -> "none"
            }
            buildJsonObject {
                put("entity_id", "${f.section ?: ""}|${f.key}|$i")
                put("entity_name", f.section?.let { "$it · ${f.label}" } ?: f.label)
                put("section_index", 0)
                put("field_type", fieldType)
                put("original_value", f.value)
                put("corrected_value", corrected)
                put("has_correction", hasCorrection)
                put("feedback_thumbs", thumbs)
                put("is_validated_by_barcode", f.validatedBy.contains("BARCODE"))
                put("last_updated", nowSeconds)
                val v = f.vertices
                if (v != null && v.size >= 4) {
                    fun pt(p: List<Double>) = buildJsonObject { put("x", p[0]); put("y", if (p.size > 1) p[1] else 0.0) }
                    putJsonObject("spatial_info") {
                        putJsonObject("vertices") {
                            put("top_left", pt(v[0]))
                            put("top_right", pt(v[1]))
                            put("bottom_left", pt(v[2]))
                            put("bottom_right", pt(v[3]))
                        }
                        putJsonArray("validated_by") { f.validatedBy.forEach { add(JsonPrimitive(it)) } }
                    }
                }
            }
        }
        fun count(test: (JsonObject) -> Boolean) = fields.count(test)
        return buildJsonObject {
            put("timestamp", nowSeconds)
            put("total_entities", fields.size)
            put("entities_with_corrections", count { it["has_correction"]!!.jsonPrimitive.boolean })
            put("entities_with_thumbs_up", count { it["feedback_thumbs"]!!.jsonPrimitive.content == "up" })
            put("entities_with_thumbs_down", count { it["feedback_thumbs"]!!.jsonPrimitive.content == "down" })
            put("entities_with_barcode_validation", count { it["is_validated_by_barcode"]!!.jsonPrimitive.boolean })
            put("raw_ocr_text", rawText(r.rawJson) ?: "")
            putJsonArray("feedback_data") { fields.forEach { add(it) } }
        }
    }

    /** Multipart POST; not unit-tested (network I/O). Returns a message for the user (success or failure reason). */
    suspend fun submit(image: Bitmap, r: OcrResult, entries: Map<String, Entry>, comment: String): String =
        withContext(Dispatchers.IO) {
            val boundary = "Boundary-${UUID.randomUUID()}"
            val body = ByteArrayOutputStream()
            fun part(name: String, data: ByteArray, filename: String? = null, type: String? = null) {
                body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"".toByteArray())
                if (filename != null) body.write("; filename=\"$filename\"".toByteArray())
                body.write("\r\n".toByteArray())
                if (type != null) body.write("Content-Type: $type\r\n".toByteArray())
                body.write("\r\n".toByteArray()); body.write(data); body.write("\r\n".toByteArray())
            }
            val jpeg = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            part("image", jpeg, filename = "feedback_image.jpg", type = "image/jpeg")
            val now = System.currentTimeMillis() / 1000.0
            part("feedback_data", payload(r, entries, now).toString().toByteArray())
            val text = comment.trim()
            if (text.isNotEmpty()) part("user_feedback_text", text.toByteArray())
            body.write("--$boundary--\r\n".toByteArray())

            val conn = URL("$server/submit-feedback").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val responseText = (if (code in 200..201) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
                val json = runCatching { Json.parseToJsonElement(responseText) as? JsonObject }.getOrNull()
                when {
                    code !in 200..201 -> "Server error: ${(json?.get("message") as? JsonPrimitive)?.content ?: "code $code"}"
                    (json?.get("status") as? JsonPrimitive)?.content != "success" -> "Server responded with unexpected format"
                    else -> "Feedback sent · ${(json["total_entities"] as? JsonPrimitive)?.content ?: r.fields.size} entities"
                }
            } catch (e: Exception) {
                "Network error: ${e.message}"
            } finally {
                conn.disconnect()
            }
        }

    /** The raw OCR text the original sends alongside (`raw_text` / `raw_response`), if present. */
    private fun rawText(json: String): String? {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
        var found: String? = null
        fun walk(n: JsonElement) {
            if (found != null) return
            when (n) {
                is JsonObject -> {
                    for (k in listOf("raw_text", "raw_response")) {
                        val v = n[k] as? JsonPrimitive
                        if (v != null && v.isString && v.content.isNotEmpty()) { found = v.content; return }
                    }
                    n.values.forEach(::walk)
                }
                is JsonArray -> n.forEach(::walk)
                else -> {}
            }
        }
        walk(root)
        return found
    }
}
