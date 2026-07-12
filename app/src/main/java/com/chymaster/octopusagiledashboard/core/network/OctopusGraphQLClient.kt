package com.chymaster.octopusagiledashboard.core.network

import android.util.Log
import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.data.prefs.KrakenTokenStore
import com.chymaster.octopusagiledashboard.data.prefs.SecureApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * GraphQL client for the Octopus Energy Kraken API (`POST /v1/graphql/`).
 *
 * **Auth flow**
 * 1. On first call it exchanges the REST API key (from [SecureApiKeyStore])
 *    for a short-lived Kraken Bearer token via the `obtainKrakenToken`
 *    mutation.
 * 2. The token is cached in-memory and persisted in
 *    [EncryptedSharedPreferences][KrakenTokenStore].
 * 3. Subsequent calls reuse the cached token until it expires (55 min).
 * 4. On expiry it refreshes via the refresh token; if that also expired,
 *    it exchanges the API key again.
 * 5. On a 401 response it clears the cached token and retries once.
 */
@Singleton
class OctopusGraphQLClient @Inject constructor(
    @Named("graphql") private val httpClient: OkHttpClient,
    private val secureApiKeyStore: SecureApiKeyStore,
    private val krakenTokenStore: KrakenTokenStore
) {
    private var cachedToken: KrakenToken? = null

    // --- public API --------------------------------------------------------

    /**
     * Execute a GraphQL [query] against the Octopus Kraken endpoint.
     *
     * @param query The GraphQL query or mutation string.
     * @return The `data` object from the response, or a failure.
     */
    suspend fun execute(query: String): Result<JsonObject> {
        return withContext(Dispatchers.IO) {
            try {
                executeInternal(query, isRetry = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Clear all cached token state (for testing / credential changes). */
    fun clearCache() {
        cachedToken = null
        krakenTokenStore.clearToken()
    }

    // --- token lifecycle ---------------------------------------------------

    /**
     * Ensure a valid Bearer token is available, refreshing/obtaining as
     * needed. Returns the token string on success.
     */
    private suspend fun ensureValidToken(): Result<String> {
        // Populate in-memory from persistent store if empty
        if (cachedToken == null) {
            cachedToken = krakenTokenStore.getToken()
        }

        val current = cachedToken
        if (current != null && !current.isAccessTokenExpired()) {
            return Result.success(current.token)
        }

        // Access token expired — try refresh, then fall back to API key
        return if (current != null && !current.isRefreshTokenExpired()) {
            obtainTokenViaRefresh(current.refreshToken!!)
        } else {
            obtainTokenViaApiKey()
        }
    }

    /** Exchange the REST API key for a new Kraken token. */
    private suspend fun obtainTokenViaApiKey(): Result<String> {
        val apiKey = secureApiKeyStore.getApiKey()
            ?: return Result.failure(IllegalStateException("No API key configured"))

        val mutation = """
            mutation {
                obtainKrakenToken(input: { APIKey: "$apiKey" }) {
                    token
                    refreshToken
                    refreshExpiresIn
                }
            }
        """.trimIndent()

        return requestToken(mutation)
    }

    /** Exchange a refresh token for a new Kraken token. */
    private suspend fun obtainTokenViaRefresh(refreshToken: String): Result<String> {
        val mutation = """
            mutation {
                obtainKrakenToken(input: { refreshToken: "$refreshToken" }) {
                    token
                    refreshToken
                    refreshExpiresIn
                }
            }
        """.trimIndent()

        return requestToken(mutation)
    }

    /**
     * Send an [obtainKrakenToken] mutation, parse the response, and
     * persist the resulting token.
     */
    private suspend fun requestToken(mutation: String): Result<String> {
        // Token exchange needs NO Authorization header — the API key
        // is passed inside the mutation body itself.
        val bodyObj = buildJsonObject { put("query", mutation) }
        val request = Request.Builder()
            .url(graphQlUrl)
            .post(bodyObj.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            return Result.failure(e)
        }

        val body = response.body?.string()
            ?: return Result.failure(IOException("Empty token response"))

        return try {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }
            val root = json.parseToJsonElement(body).jsonObject
            val errors = root["errors"]
            if (errors != null && errors !is JsonPrimitive) {
                return Result.failure(IOException("Token error: $errors"))
            }

            val data = root["data"]?.jsonObject
                ?: return Result.failure(IOException("No data in token response"))

            val tokenData = data["obtainKrakenToken"]?.jsonObject
                ?: return Result.failure(IOException("No obtainKrakenToken in response"))

            val accessToken = tokenData["token"]?.jsonPrimitive?.content
                ?: return Result.failure(IOException("No token field in response"))
            val refreshToken = tokenData["refreshToken"]?.jsonPrimitive?.content
            val refreshExpiresIn = tokenData["refreshExpiresIn"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: return Result.failure(IOException("No refreshExpiresIn field"))

            val newToken = KrakenToken(
                token = accessToken,
                refreshToken = refreshToken,
                refreshExpiresIn = refreshExpiresIn,
                obtainedAt = System.currentTimeMillis()
            )

            // Persist
            cachedToken = newToken
            krakenTokenStore.saveToken(newToken)

            Result.success(accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- GraphQL execution --------------------------------------------------

    private val graphQlUrl = "${Constants.BASE_URL}graphql/"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun executeInternal(
        query: String,
        isRetry: Boolean
    ): Result<JsonObject> {
        // 1. Obtain valid token
        val tokenResult = ensureValidToken()
        if (tokenResult.isFailure) {
            return Result.failure(tokenResult.exceptionOrNull()!!)
        }
        val token = tokenResult.getOrThrow()

        // 2. Build request with Bearer auth
        val bodyObj = buildJsonObject {
            put("query", query)
        }
        val request = Request.Builder()
            .url(graphQlUrl)
            .post(bodyObj.toString().toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .build()

        // 3. Execute
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Result.failure(e)
        }

        // 4. Handle 401 — clear token and retry once
        if (response.code == 401 && !isRetry) {
            Log.w(TAG, "401 from GraphQL — clearing token and retrying")
            cachedToken = null
            krakenTokenStore.clearToken()
            response.close()
            return executeInternal(query, isRetry = true)
        }

        // 5. Parse response
        val body = response.body?.string()
            ?: return Result.failure(IOException("Empty response body (HTTP ${response.code})"))

        return parseGraphQLResponse(body)
    }

    private fun parseGraphQLResponse(body: String): Result<JsonObject> {
        return try {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            }
            val root = json.parseToJsonElement(body).jsonObject

            // Check for errors
            val errors = root["errors"]
            if (errors != null && errors !is JsonPrimitive) {
                val message = when (errors) {
                    is JsonArray -> errors.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.content
                        ?: "GraphQL error"
                    is JsonObject -> errors["message"]?.jsonPrimitive?.content ?: "GraphQL error"
                    else -> "GraphQL error"
                }
                return Result.failure(IOException(message))
            }

            val data = root["data"]?.jsonObject
                ?: return Result.failure(IOException("No data in GraphQL response"))

            Result.success(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private class CancellationException : Exception()

    companion object {
        private const val TAG = "OctopusGQL"
    }
}
