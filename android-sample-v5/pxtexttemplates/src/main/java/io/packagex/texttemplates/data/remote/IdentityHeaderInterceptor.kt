package io.packagex.texttemplates.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `X-User-Email` and `X-API-Key` to every outbound request. Mirrors iOS
 * where the same two headers are attached in PredictionApi.
 *
 * Both values are supplied by the SDK caller (from `PXConfiguration`) rather
 * than read from a `BuildConfig` field or an app-owned identity store — the
 * SDK is self-contained and has no compile dependency on the host app.
 *
 * [emailProvider] is evaluated per request so a host that changes the active
 * user mid-session can return the current value; empty/null values are omitted
 * so a local dev backend running with `AUTH_DISABLED=1` keeps working without
 * configuration.
 */
internal class IdentityHeaderInterceptor(
    private val apiKey: String,
    private val emailProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
        val email = emailProvider().orEmpty()
        if (email.isNotEmpty()) builder.addHeader("X-User-Email", email)
        if (apiKey.isNotEmpty()) builder.addHeader("X-API-Key", apiKey)
        return chain.proceed(builder.build())
    }
}
