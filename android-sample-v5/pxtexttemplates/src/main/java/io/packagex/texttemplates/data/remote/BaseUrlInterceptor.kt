package io.packagex.texttemplates.data.remote

import okhttp3.Interceptor
import okhttp3.Response

internal class BaseUrlInterceptor constructor() : Interceptor {
    @Volatile
    var baseUrl: String = ""

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (baseUrl.isNotEmpty()) {
            val newUrl = request.url.toString()
                .replace("http://localhost/", baseUrl.trimEnd('/') + "/")
            request = request.newBuilder().url(newUrl).build()
        }
        return chain.proceed(request)
    }
}
