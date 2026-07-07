package com.chymaster.octopusagiledashboard.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that automatically retries requests on transient
 * HTTP errors (429 Too Many Requests, 503 Service Unavailable).
 *
 * Uses exponential backoff (1s, 2s, 4s) or the server's `Retry-After`
 * header value, whichever is provided. Up to [MAX_RETRIES] retry
 * attempts after the original request.
 *
 * Does NOT retry 401 (credentials problem) or network-level exceptions
 * (those are handled by the caller's try-catch).
 */
class RetryInterceptor : Interceptor {

    companion object {
        private const val MAX_RETRIES = 3
        private val RETRYABLE_CODES = setOf(429, 503)
        private val BACKOFF_BASE_MS = longArrayOf(1_000, 2_000, 4_000)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempt = 0

        while (response.code in RETRYABLE_CODES && attempt < MAX_RETRIES) {
            response.close()
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            val waitMs = retryAfter?.let { it * 1000 }
                ?: BACKOFF_BASE_MS[attempt.coerceAtMost(BACKOFF_BASE_MS.lastIndex)]
            Thread.sleep(waitMs)
            attempt++
            response = chain.proceed(request)
        }

        return response
    }
}
