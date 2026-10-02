package io.packagex.texttemplates.sdk

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import io.packagex.texttemplates.extraction.models.BarcodeFrameResult
import io.packagex.texttemplates.extraction.models.OcrFrameResult
import io.packagex.texttemplates.prediction.RectD
import java.io.File
import java.lang.reflect.Type

/**
 * The retained extraction for one scan — everything the engine needs to
 * `repredict` the scan against another template WITHOUT re-running OCR/barcode/
 * orientation. Deliberately image-free (the host supplies the image at
 * `report` time) so records stay small and many can be kept before eviction.
 */
internal data class ScanCapture(
    val ocr: OcrFrameResult,
    val barcodes: BarcodeFrameResult,
    val frameWidth: Int,
    val frameHeight: Int,
    val rotation: Int,
    val previewRect: RectD?,
    val innerBox: RectD?,
)

/**
 * One persisted scan: the [capture] (for `repredict`) plus the internal [debug]
 * logs (for `report`, image-stripped). Never exposes an image and never exposes
 * [debug] to customers — only [scanId] crosses the public boundary.
 */
internal data class ScanRecord(
    val scanId: String,
    val templateId: String?,
    val createdAt: Long,
    val capture: ScanCapture,
    /** Image-free `buildDebugExport()` payload, re-sent verbatim on `report`. */
    val debug: JsonObject?,
)

/**
 * On-disk scan cache keyed by scanId, under `filesDir/px_scans`. Bounded LRU
 * (keeps the newest [maxScans] by file mtime); `report`/`repredict` throw if a
 * scanId has been evicted. Cleared wholesale by [PXClient.clearScanCache].
 *
 * Records hold only debug info + the retained extraction (no image), so each is
 * a few KB — hundreds fit before the cap bites.
 */
internal class PXScanStore(
    filesDir: File,
    private val maxScans: Int = 200,
) {
    private val dir = File(filesDir, "px_scans").apply { mkdirs() }
    private val lock = Any()

    companion object {
        /** Gson with a `Pair<Int,Int>` adapter (Gson can't round-trip Kotlin
         *  `Pair` by default) for the OCR/barcode corner points, so the retained
         *  extraction serializes and deserializes faithfully for `repredict`. */
        val gson = GsonBuilder()
            .serializeNulls()
            .registerTypeHierarchyAdapter(
                Pair::class.java,
                object : JsonSerializer<Pair<*, *>>, JsonDeserializer<Pair<*, *>> {
                    override fun serialize(src: Pair<*, *>, type: Type, ctx: JsonSerializationContext): JsonElement =
                        JsonArray().apply {
                            add((src.first as Number).toInt())
                            add((src.second as Number).toInt())
                        }

                    override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): Pair<Int, Int> {
                        val a = json.asJsonArray
                        return a[0].asInt to a[1].asInt
                    }
                },
            )
            .create()
    }

    fun put(record: ScanRecord) = synchronized(lock) {
        File(dir, "${record.scanId}.json").writeText(gson.toJson(record))
        evict()
    }

    fun get(scanId: String): ScanRecord? = synchronized(lock) {
        val f = File(dir, "$scanId.json")
        if (!f.exists()) return null
        runCatching { gson.fromJson(f.readText(), ScanRecord::class.java) }.getOrNull()
    }

    fun clear() = synchronized(lock) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    /** Number of scans currently cached. */
    fun count(): Int = synchronized(lock) {
        dir.listFiles()?.count { it.isFile && it.name.endsWith(".json") } ?: 0
    }

    /** Keep the newest [maxScans] records by mtime; delete the rest. */
    private fun evict() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(maxScans).forEach { it.delete() }
    }
}
