package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.webkit.WebSettings
import okhttp3.Interceptor
import okhttp3.Response

class AutoUserAgentInterceptor(
    private val context: Context,
    private val defaultUserAgentProvider: () -> String,
    private val sourceUserAgentProvider: (String) -> String? = { null },
) : Interceptor {

    private val startupUserAgent =
        WebSettings.getDefaultUserAgent(context.applicationContext).replace("\n", " ").trim()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val host = originalRequest.url.host
        val sourceUserAgent = sourceUserAgentProvider(host)

        val request = when {
            !originalRequest.header("User-Agent").isNullOrEmpty() -> originalRequest

            sourceUserAgent != null -> originalRequest.newBuilder()
                .removeHeader("User-Agent")
                .addHeader("User-Agent", sourceUserAgent)
                .build()

            else -> originalRequest.newBuilder()
                .removeHeader("User-Agent")
                .addHeader("User-Agent", defaultUserAgentProvider().ifBlank { startupUserAgent })
                .build()
        }

        return chain.proceed(request)
    }
}
