package io.packagex.texttemplates.data.repository

import android.util.Log
import io.packagex.texttemplates.data.local.TemplateDao
import io.packagex.texttemplates.data.local.TemplateEntity
import io.packagex.texttemplates.data.remote.ApiResult
import io.packagex.texttemplates.data.remote.PredictionApi
import io.packagex.texttemplates.data.remote.dto.DebugProcessRequest
import io.packagex.texttemplates.data.remote.dto.PredictionRequest
import io.packagex.texttemplates.data.remote.dto.PredictionResponse
import io.packagex.texttemplates.data.remote.dto.TemplateRawResponse
import io.packagex.texttemplates.prediction.ProcessedTemplate
import io.packagex.texttemplates.prediction.parseProcessedTemplate
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal data class TemplateReloadDiff(
    val total: Int,
    val added: Int,
    val refreshed: Int,
    val removed: Int,
    // Templates whose raw/processed payload couldn't be eagerly downloaded
    // during the reload — selecting one of these falls back to the lazy
    // on-demand fetch (i.e. needs the server reachable at selection time).
    val prefetchFailed: Int = 0,
)

internal class TemplateRepository constructor(
    private val api: PredictionApi,
    private val templateDao: TemplateDao,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "[TemplateRepository]"
    }

    // In-memory cache to avoid re-parsing JSON on every access. Concurrent —
    // the reload prefetch loads templates in parallel, so plain HashMaps
    // would race on resize.
    private val processedTemplateCache = ConcurrentHashMap<String, ProcessedTemplate>()
    private val rawTemplateCache = ConcurrentHashMap<String, TemplateRawResponse>()

    fun getCachedTemplates(): Flow<List<TemplateEntity>> = templateDao.getAllTemplates()

    suspend fun getCachedTemplatesSync(): List<TemplateEntity> = templateDao.getAllTemplatesSync()

    /**
     * Fetch the template list from the server and upsert into Room. Lighter than
     * [reloadAndCacheTemplates]: doesn't fetch each template's raw/processed
     * detail eagerly — those are loaded lazily by [getTemplateDetail] and
     * [getProcessedTemplate] on first access.
     */
    suspend fun fetchAndCacheTemplates(): ApiResult<List<TemplateEntity>> {
        return try {
            val response = api.getTemplates()
            if (!response.isSuccessful) {
                return ApiResult.Error("Failed to fetch templates: ${response.message()}", response.code())
            }
            val summaries = response.body() ?: emptyList()
            val serverIds = summaries.map { it.templateId }.toSet()
            for (s in summaries) {
                upsertSummary(s.templateId, s.templateName, s.updatedAt)
            }
            for (entity in templateDao.getAllTemplatesSync()) {
                if (entity.id !in serverIds) templateDao.deleteById(entity.id)
            }
            ApiResult.Success(templateDao.getAllTemplatesSync())
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Server-vs-cache diff for the current cache state. Mirrors iOS
     * `reloadAndCacheTemplates`. When the server's `updated_at` differs from
     * the cached `serverUpdatedAt`, the cached raw/processed blobs are cleared
     * so the next access re-fetches the latest content.
     */
    /** @param onProgress optional prefetch progress `(completed, total)`, invoked
     *  as each template's blobs finish. Fires on a background (IO) thread — marshal
     *  to your UI thread. Cancellation: cooperative — cancelling the caller's scope
     *  aborts the prefetch and this rethrows `CancellationException`. */
    suspend fun reloadAndCacheTemplates(
        onProgress: ((Int, Int) -> Unit)? = null,
    ): ApiResult<TemplateReloadDiff> {
        return try {
            val beforeIds = templateDao.getAllTemplatesSync().map { it.id }.toSet()
            val response = api.getTemplates()
            if (!response.isSuccessful) {
                return ApiResult.Error("Failed to fetch templates: ${response.message()}", response.code())
            }
            val summaries = response.body() ?: emptyList()
            val serverIds = summaries.map { it.templateId }.toSet()
            var refreshed = 0
            for (s in summaries) {
                val serverUpdatedAt = s.updatedAt.orEmpty()
                val existing = templateDao.getTemplateById(s.templateId)
                if (serverUpdatedAt.isNotEmpty()
                    && existing != null
                    && !timestampsSameInstant(existing.serverUpdatedAt, serverUpdatedAt)) {
                    templateDao.update(existing.copy(
                        rawTemplateJson = null,
                        processedTemplateJson = null,
                    ))
                    rawTemplateCache.remove(s.templateId)
                    processedTemplateCache.remove(s.templateId)
                    refreshed += 1
                }
                upsertSummary(s.templateId, s.templateName, s.updatedAt)
            }
            for (id in beforeIds) {
                if (id !in serverIds) {
                    templateDao.deleteById(id)
                    rawTemplateCache.remove(id)
                    processedTemplateCache.remove(id)
                }
            }

            // Eagerly download every template's raw + processed payloads so
            // selecting a template later works entirely from the local cache
            // — no server round-trip at selection time. Only cache misses are
            // fetched: blobs invalidated above (stale) or never loaded.
            // Best-effort: a failed prefetch is reported in the diff and the
            // lazy on-demand path remains as fallback. Concurrent — Room and
            // the in-memory caches are safe here, and OkHttp's dispatcher
            // caps per-host parallelism.
            val idsToPrefetch = templateDao.getAllTemplatesSync()
                .filter { it.rawTemplateJson == null || it.processedTemplateJson == null }
                .map { it.id }
            // Dispatchers.IO: the reload is typically launched from a
            // viewModelScope (Main) — keep the per-template JSON parsing off
            // the main thread.
            val prefetchTotal = idsToPrefetch.size
            if (prefetchTotal > 0) onProgress?.invoke(0, prefetchTotal)
            val completed = java.util.concurrent.atomic.AtomicInteger(0)
            val prefetchFailed = coroutineScope {
                idsToPrefetch.map { id ->
                    async(Dispatchers.IO) {
                        val rawOk = getTemplateDetail(id) is ApiResult.Success
                        val processedOk = getProcessedTemplate(id) is ApiResult.Success
                        onProgress?.invoke(completed.incrementAndGet(), prefetchTotal)
                        if (rawOk && processedOk) 0 else 1
                    }
                }.awaitAll().sum()
            }
            if (prefetchFailed > 0) {
                Log.w(TAG, "Template prefetch: $prefetchFailed of ${idsToPrefetch.size} failed")
            }

            val total = templateDao.getAllTemplatesSync().size
            val added = serverIds.minus(beforeIds).size
            val removed = beforeIds.minus(serverIds).size
            ApiResult.Success(TemplateReloadDiff(total, added, refreshed, removed, prefetchFailed))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // propagate cancellation — never swallow it as an ApiResult.Error
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Upsert helper that preserves cached raw/processed JSON when the caller
     * only knows the summary (id/name/updatedAt). Mirrors iOS TemplateCache.upsert.
     */
    private suspend fun upsertSummary(id: String, name: String, serverUpdatedAt: String?) {
        val existing = templateDao.getTemplateById(id)
        if (existing != null) {
            templateDao.update(existing.copy(
                name = name,
                lastUpdated = System.currentTimeMillis(),
                serverUpdatedAt = serverUpdatedAt ?: existing.serverUpdatedAt,
            ))
        } else {
            templateDao.insert(TemplateEntity(
                id = id,
                name = name,
                fieldCount = 0,
                serverUpdatedAt = serverUpdatedAt,
            ))
        }
    }

    /**
     * Load template detail. Hits the server lazily on cache miss, mirroring iOS.
     */
    suspend fun getTemplateDetail(id: String): ApiResult<TemplateRawResponse> {
        rawTemplateCache[id]?.let { return ApiResult.Success(it) }

        val entity = templateDao.getTemplateById(id)
        val cachedJson = entity?.rawTemplateJson
        if (cachedJson != null) {
            try {
                val raw = gson.fromJson(cachedJson, TemplateRawResponse::class.java)
                rawTemplateCache[id] = raw
                return ApiResult.Success(raw)
            } catch (e: Exception) {
                Log.w(TAG, "Cached raw template $id is unparseable, refetching: ${e.message}")
            }
        }

        return try {
            val resp = api.getTemplateRaw(id)
            if (!resp.isSuccessful) {
                ApiResult.Error("Failed to load template: ${resp.message()}", resp.code())
            } else {
                val raw = resp.body()!!
                val rawJson = gson.toJson(raw)
                val newEntity = (entity ?: TemplateEntity(id = id, name = raw.name, fieldCount = 0)).copy(
                    name = raw.name,
                    fieldCount = raw.annotations.size,
                    rawTemplateJson = rawJson,
                    lastUpdated = System.currentTimeMillis(),
                )
                templateDao.insert(newEntity)
                rawTemplateCache[id] = raw
                ApiResult.Success(raw)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // propagate cancellation, don't mask it as an ApiResult.Error
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Load processed template. Hits the server lazily on cache miss.
     */
    suspend fun getProcessedTemplate(id: String): ApiResult<ProcessedTemplate> {
        processedTemplateCache[id]?.let { return ApiResult.Success(it) }

        val entity = templateDao.getTemplateById(id)
        val cachedJson = entity?.processedTemplateJson
        if (cachedJson != null) {
            try {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val raw: Map<String, Any> = gson.fromJson(cachedJson, type)
                val processed = parseProcessedTemplate(raw, gson)
                processedTemplateCache[id] = processed
                return ApiResult.Success(processed)
            } catch (e: Exception) {
                Log.w(TAG, "Cached processed template $id is unparseable, refetching: ${e.message}")
            }
        }

        return try {
            val resp = api.getTemplateProcessed(id)
            if (!resp.isSuccessful) {
                ApiResult.Error("Failed to load processed template: ${resp.message()}", resp.code())
            } else {
                val raw = resp.body() ?: emptyMap()
                val rawJson = gson.toJson(raw)
                val processed = parseProcessedTemplate(raw, gson)
                val newEntity = (entity ?: TemplateEntity(id = id, name = "", fieldCount = 0)).copy(
                    processedTemplateJson = rawJson,
                    lastUpdated = System.currentTimeMillis(),
                )
                templateDao.insert(newEntity)
                processedTemplateCache[id] = processed
                ApiResult.Success(processed)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // propagate cancellation, don't mask it as an ApiResult.Error
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun process(templateId: String, imageB64: String): ApiResult<PredictionResponse> {
        return try {
            val response = api.process(templateId, PredictionRequest(imageB64))
            if (response.isSuccessful) {
                ApiResult.Success(response.body()!!)
            } else {
                ApiResult.Error("Prediction failed: ${response.message()}", response.code())
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun debugProcess(
        templateId: String,
        request: DebugProcessRequest,
    ): ApiResult<Map<String, Any>> {
        return try {
            val response = api.debugProcess(templateId, request)
            if (response.isSuccessful) {
                ApiResult.Success(response.body()!!)
            } else {
                ApiResult.Error("Debug process failed: ${response.message()}", response.code())
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun exportDebugData(data: Map<String, Any?>): ApiResult<Map<String, Any>> {
        return try {
            val response = api.exportDebug(data)
            if (response.isSuccessful) {
                ApiResult.Success(response.body()!!)
            } else {
                ApiResult.Error("Export failed: ${response.message()}", response.code())
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun clearCache() {
        templateDao.deleteAll()
        processedTemplateCache.clear()
        rawTemplateCache.clear()
    }
}

/**
 * True iff both ISO-8601 timestamp strings represent the same point in
 * time. Compares as parsed UTC instants (not raw strings), so a cache
 * entry written as `"…+05:00"` correctly matches a server response of
 * `"…Z"` (same moment, different zone formatting) instead of looking
 * "stale" and triggering an unnecessary cache invalidation. Falls back
 * to literal string equality when either side is unparseable — keeps
 * the legacy behaviour for any malformed timestamps already on disk.
 */
private fun timestampsSameInstant(a: String?, b: String): Boolean {
    if (a.isNullOrEmpty()) return false
    if (a == b) return true
    return try {
        Instant.parse(a) == Instant.parse(b)
    } catch (e: Exception) {
        false
    }
}
