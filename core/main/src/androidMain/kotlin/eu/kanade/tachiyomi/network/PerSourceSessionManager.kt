package eu.kanade.tachiyomi.network

import okhttp3.HttpUrl

class PerSourceSessionManager(
    private val cookieJar: AndroidCookieJar,
    private val sourceHostProvider: (String) -> String? = { null },
) {

    fun clearSessionForSource(sourceId: String, hostOverride: String? = null) {
        val host = hostOverride ?: sourceHostProvider(sourceId) ?: return
        clearSessionForHost(host)
    }

    fun clearSessionForHost(host: String) {
        val normalizedHost = normalizeHost(host) ?: return

        listOf(
            HttpUrl.Builder().scheme("https").host(normalizedHost).build(),
            HttpUrl.Builder().scheme("http").host(normalizedHost).build(),
        ).forEach { clearSessionForUrl(it) }
    }

    fun clearSessionForUrl(url: HttpUrl) {
        cookieJar.remove(url, maxAge = 0)
    }

    private fun normalizeHost(host: String): String? {
        val trimmed = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trim('/')
            .substringBefore('/')
            .substringBefore(':')
            .trim()

        return trimmed.takeIf { it.isNotEmpty() }
    }
}
