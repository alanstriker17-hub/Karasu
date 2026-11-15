package eu.kanade.tachiyomi.network.interceptor

import android.os.SystemClock
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.Response

internal class HostCircuitBreakerInterceptor : Interceptor {

    private val hosts = ConcurrentHashMap<String, HostState>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        waitForHost(host, chain)

        val response = chain.proceed(request)
        if (response.code == HTTP_TOO_MANY_REQUESTS || response.code == HTTP_UNAVAILABLE) {
            recordFailure(host, response)
        } else {
            recordSuccess(host)
        }
        return response
    }

    private fun waitForHost(host: String, chain: Interceptor.Chain) {
        val state = hosts[host] ?: return
        var scheduledAt: Long? = null

        while (true) {
            if (chain.call().isCanceled()) throw IOException("Request canceled while waiting for host rate limit")

            val waitMillis = synchronized(state) {
                if (!state.throttled) return
                val now = SystemClock.elapsedRealtime()
                if (
                    state.failures == 0 &&
                    now - state.lastFailureAt >= RECOVERY_WINDOW_MS &&
                    state.nextRequestAt <= now
                ) {
                    state.throttled = false
                    return
                }

                if (scheduledAt == null || scheduledAt!! < state.backoffUntil) {
                    scheduledAt = maxOf(now, state.backoffUntil, state.nextRequestAt)
                    state.nextRequestAt = scheduledAt!! + REQUEST_INTERVAL_MS
                }
                maxOf(scheduledAt!! - now, state.backoffUntil - now, 0L)
            }
            if (waitMillis <= 0) return

            try {
                Thread.sleep(waitMillis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while waiting for host rate limit").apply {
                    initCause(e)
                }
            }
        }
    }

    private fun recordFailure(host: String, response: Response) {
        val state = hosts.getOrPut(host) { HostState() }
        synchronized(state) {
            val now = SystemClock.elapsedRealtime()
            state.throttled = true
            state.failures = (state.failures + 1).coerceAtMost(MAX_FAILURES)
            state.lastFailureAt = now
            val exponentialDelay = (BASE_BACKOFF_MS * (1L shl (state.failures - 1)))
                .coerceAtMost(MAX_BACKOFF_MS)
            val retryAfterDelay = response.header("Retry-After")
                ?.toLongOrNull()
                ?.coerceIn(0, MAX_BACKOFF_MS / 1_000)
                ?.times(1_000)
                ?: 0L
            val backoff = maxOf(exponentialDelay, retryAfterDelay)
            state.backoffUntil = maxOf(state.backoffUntil, now + backoff)
            state.nextRequestAt = maxOf(state.nextRequestAt, state.backoffUntil)
        }
    }

    private fun recordSuccess(host: String) {
        val state = hosts[host] ?: return
        synchronized(state) {
            state.failures = 0
            state.backoffUntil = 0L
        }
    }

    private class HostState {
        var throttled = false
        var failures = 0
        var lastFailureAt = 0L
        var backoffUntil = 0L
        var nextRequestAt = 0L
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAVAILABLE = 503
        const val REQUEST_INTERVAL_MS = 500L
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val RECOVERY_WINDOW_MS = 60_000L
        const val MAX_FAILURES = 7
    }
}
