package com.example.octopusdashboard.core.network

import android.util.Base64
import com.example.octopusdashboard.data.prefs.UserPreferencesRepository
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

        // Add Basic Auth for all requests (harmless for public endpoints)
        val apiKey = runBlocking { preferencesRepository.apiKeyFlow.first() }
        if (!apiKey.isNullOrBlank()) {
            val credentials = Base64.encodeToString(
                "$apiKey:".toByteArray(),
                Base64.NO_WRAP
            )
            requestBuilder.header("Authorization", "Basic $credentials")
        }

        return chain.proceed(requestBuilder.build())
    }
}
