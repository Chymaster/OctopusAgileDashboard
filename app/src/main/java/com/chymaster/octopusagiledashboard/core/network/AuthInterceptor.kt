package com.chymaster.octopusagiledashboard.core.network

import android.util.Base64
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val requestBuilder = original.newBuilder()

        // Only add Basic Auth to endpoints that require it.
        // Public endpoints (products/tariffs) don't need auth and may
        // reject requests with invalid credentials, causing 401 errors.
        val path = original.url.encodedPath
        val needsAuth = path.contains("/electricity-meter-points/") ||
                path.contains("/consumption/")

        if (needsAuth) {
            val apiKey = runBlocking { preferencesRepository.apiKeyFlow.first() }
            if (!apiKey.isNullOrBlank()) {
                val credentials = Base64.encodeToString(
                    "$apiKey:".toByteArray(),
                    Base64.NO_WRAP
                )
                requestBuilder.header("Authorization", "Basic $credentials")
            }
        }

        return chain.proceed(requestBuilder.build())
    }
}
