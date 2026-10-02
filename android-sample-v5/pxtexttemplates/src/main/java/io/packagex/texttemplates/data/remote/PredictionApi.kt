package io.packagex.texttemplates.data.remote

import io.packagex.texttemplates.data.remote.dto.DebugProcessRequest
import io.packagex.texttemplates.data.remote.dto.PredictionRequest
import io.packagex.texttemplates.data.remote.dto.PredictionResponse
import io.packagex.texttemplates.data.remote.dto.TemplateSummary
import io.packagex.texttemplates.data.remote.dto.TemplateRawResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

internal interface PredictionApi {
    // Paths are prefixed with `api/` to match the FastAPI router
    // (prefix="/api") and the iOS client. The configured base URL is bare
    // (e.g. https://text-templates.web.app), so the `/api` segment must live
    // in the path — otherwise every call 404s (the "Update templates" bug).
    @GET("api/templates")
    suspend fun getTemplates(): Response<List<TemplateSummary>>

    @GET("api/templates/{id}/raw")
    suspend fun getTemplateRaw(@Path("id") id: String): Response<TemplateRawResponse>

    @GET("api/templates/{id}/processed")
    suspend fun getTemplateProcessed(@Path("id") id: String): Response<Map<String, Any>>

    @POST("api/templates/{id}/process")
    suspend fun process(
        @Path("id") id: String,
        @Body request: PredictionRequest
    ): Response<PredictionResponse>

    @POST("api/templates/{id}/debug-process")
    suspend fun debugProcess(
        @Path("id") id: String,
        @Body request: DebugProcessRequest
    ): Response<Map<String, Any>>

    @POST("api/debug-export")
    suspend fun exportDebug(@Body data: Map<String, @JvmSuppressWildcards Any?>): Response<Map<String, Any>>
}
