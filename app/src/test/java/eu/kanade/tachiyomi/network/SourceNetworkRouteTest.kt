package eu.kanade.tachiyomi.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceNetworkRouteTest {

    @Test
    fun `parses source DoH and proxy overrides`() {
        val routes = parseSourceNetworkRoutes(
            "123,cloudflare,socks5,127.0.0.1,1080;456,inherit,http,proxy.example,8080",
        ).getOrThrow()

        assertEquals(2, routes.size)
        assertTrue(routes[0].useCloudflareDoh)
        assertEquals(SourceNetworkRoute.ProxyType.SOCKS5, routes[0].proxyType)
        assertEquals("127.0.0.1", routes[0].proxyHost)
        assertEquals(1080, routes[0].proxyPort)
        assertFalse(routes[1].useCloudflareDoh)
        assertEquals(SourceNetworkRoute.ProxyType.HTTP, routes[1].proxyType)
    }

    @Test
    fun `empty route list and explicit clear are accepted`() {
        assertTrue(parseSourceNetworkRoutes("").getOrThrow().isEmpty())
        assertTrue(parseSourceNetworkRoutes("none").getOrThrow().isEmpty())
        assertTrue(parseSourceNetworkRoutes("123,cloudflare,none,,").getOrThrow().single().useCloudflareDoh)
    }

    @Test
    fun `invalid and duplicate routes are rejected`() {
        assertTrue(parseSourceNetworkRoutes("123,cloudflare,socks5,127.0.0.1,0").isFailure)
        assertTrue(parseSourceNetworkRoutes("123,cloudflare,none,,;123,inherit,none,,").isFailure)
        assertTrue(parseSourceNetworkRoutes("123,unknown,none,,").isFailure)
    }
}
