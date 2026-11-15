package eu.kanade.tachiyomi.network

data class SourceNetworkRoute(
    val sourceId: Long,
    val useCloudflareDoh: Boolean,
    val proxyType: ProxyType?,
    val proxyHost: String?,
    val proxyPort: Int?,
) {
    enum class ProxyType {
        HTTP,
        SOCKS5,
    }
}

/**
 * Parses semicolon-separated records in the form:
 * `sourceId,dns,proxy,host,port`, where dns is `inherit` or `cloudflare`,
 * and proxy is `none`, `http`, or `socks5`.
 */
fun parseSourceNetworkRoutes(value: String): Result<List<SourceNetworkRoute>> {
    if (value.isBlank() || value.trim() == "none") return Result.success(emptyList())

    val routes = mutableListOf<SourceNetworkRoute>()
    val sourceIds = mutableSetOf<Long>()
    for (record in value.split(';')) {
        val fields = record.split(',', limit = 5).map { it.trim() }
        if (fields.size != 5) return Result.failure(IllegalArgumentException("Expected five fields per route"))

        val sourceId = fields[0].toLongOrNull()
            ?: return Result.failure(IllegalArgumentException("Source ID must be an integer"))
        if (!sourceIds.add(sourceId)) {
            return Result.failure(IllegalArgumentException("Duplicate source ID"))
        }

        val useCloudflareDoh = when (fields[1].lowercase()) {
            "inherit" -> false
            "cloudflare" -> true
            else -> return Result.failure(IllegalArgumentException("DNS must be inherit or cloudflare"))
        }

        val proxyType = when (fields[2].lowercase()) {
            "none" -> null
            "http" -> SourceNetworkRoute.ProxyType.HTTP
            "socks5" -> SourceNetworkRoute.ProxyType.SOCKS5
            else -> return Result.failure(IllegalArgumentException("Proxy must be none, http, or socks5"))
        }

        val proxyHost: String?
        val proxyPort: Int?
        if (proxyType == null) {
            if (fields[3].isNotEmpty() || fields[4].isNotEmpty()) {
                return Result.failure(IllegalArgumentException("A route without a proxy must have empty host and port"))
            }
            proxyHost = null
            proxyPort = null
        } else {
            proxyHost = fields[3].takeIf(String::isNotBlank)
                ?: return Result.failure(IllegalArgumentException("Proxy host is required"))
            if (proxyHost.any { it.isWhitespace() }) {
                return Result.failure(IllegalArgumentException("Proxy host cannot contain whitespace"))
            }
            proxyPort = fields[4].toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return Result.failure(IllegalArgumentException("Proxy port must be between 1 and 65535"))
        }

        routes += SourceNetworkRoute(sourceId, useCloudflareDoh, proxyType, proxyHost, proxyPort)
    }
    return Result.success(routes)
}
