package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.WebSettings
import eu.kanade.tachiyomi.network.interceptor.AutoUserAgentInterceptor
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.HostCircuitBreakerInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.OkHttpClient

class NetworkHelper(
    val context: Context,
    private val preferences: NetworkPreferences,
    private val block: (OkHttpClient.Builder) -> Unit,
) {

    val cookieJar = AndroidCookieJar()
    val perSourceSessionManager = PerSourceSessionManager(cookieJar)

    val flareSolverr = FlareSolverr(
        cookieJar = cookieJar,
        endpointProvider = { preferences.flareSolverrUrl().get() },
        userAgents = HostUserAgentStore(preferences.flareSolverrUserAgents()),
    )

    val client: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .cache(
                Cache(
                    directory = File(context.cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                )
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            // Auto-detect the system WebView User-Agent and keep a source-specific override path for
            // Cloudflare/host workarounds without forcing every source through a custom UA.
            .addInterceptor(
                AutoUserAgentInterceptor(context, ::defaultUserAgent) { host ->
                    if (flareSolverr.isEnabled) flareSolverr.userAgents[host] else null
                },
            )
            // Some extension clients require this named interceptor on the shared default client.
            .addInterceptor(
                UserAgentInterceptor(::defaultUserAgent) { host ->
                    if (flareSolverr.isEnabled) flareSolverr.userAgents[host] else null
                },
            )
            // No IgnoreGzip/Brotli here: KeiSource (extension-lib 1.6) asserts they are absent on
            // the host client and refuses to build on top of it otherwise. Sources that want
            // brotli or zstd add OkHttp's own CompressionInterceptor to their own client.

        block(builder)

        builder.addInterceptor(
            CloudflareInterceptor(context, cookieJar, flareSolverr, ::defaultUserAgent),
        )
        builder.addInterceptor(HostCircuitBreakerInterceptor())

        when (preferences.dohProvider().get()) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            PREF_DOH_QUAD101 -> builder.dohQuad101()
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            PREF_DOH_SHECAN -> builder.dohShecan()
        }

        builder.build()
    }

    private data class CachedSourceClient(
        val route: SourceNetworkRoute,
        val client: OkHttpClient,
    )

    private val sourceClients = ConcurrentHashMap<Long, CachedSourceClient>()

    fun clientForSource(sourceId: Long): OkHttpClient {
        val routes = parseSourceNetworkRoutes(preferences.sourceNetworkRoutes().get())
            .getOrElse { throw IllegalStateException("Invalid per-source network routes", it) }
        val route = routes.firstOrNull { it.sourceId == sourceId }
        if (route == null) {
            sourceClients.remove(sourceId)
            return client
        }

        sourceClients[sourceId]?.takeIf { it.route == route }?.let { return it.client }

        val builder = client.newBuilder()
        route.proxyType?.let { proxyType ->
            val type = when (proxyType) {
                SourceNetworkRoute.ProxyType.HTTP -> Proxy.Type.HTTP
                SourceNetworkRoute.ProxyType.SOCKS5 -> Proxy.Type.SOCKS
            }
            builder.proxy(Proxy(type, InetSocketAddress.createUnresolved(route.proxyHost!!, route.proxyPort!!)))
        }
        if (route.useCloudflareDoh) {
            builder.dohCloudflare()
        }

        return builder.build().also { sourceClients[sourceId] = CachedSourceClient(route, it) }
    }

    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient = client

    val defaultUserAgent: String
        get() {
            val configured = preferences.defaultUserAgent().get().replace("\n", " ").trim()
            return configured
                .takeUnless { it.isBlank() || it == NetworkPreferences.DEFAULT_USER_AGENT }
                ?: systemUserAgent
        }

    private val systemUserAgent =
        WebSettings.getDefaultUserAgent(context.applicationContext).replace("\n", " ").trim()
}
