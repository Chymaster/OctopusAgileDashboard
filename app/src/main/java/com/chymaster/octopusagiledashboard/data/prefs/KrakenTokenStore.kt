package com.chymaster.octopusagiledashboard.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.chymaster.octopusagiledashboard.core.network.KrakenToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the Kraken GraphQL token in EncryptedSharedPreferences,
 * using the same Android Keystore-backed encryption as [SecureApiKeyStore].
 *
 * The token is stored across sessions so subsequent app launches can
 * reuse an unexpired token without re-exchanging the API key.
 */
@Singleton
class KrakenTokenStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "octopus_kraken_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getToken(): KrakenToken? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val refreshExpiresIn = prefs.getLong(KEY_REFRESH_EXPIRES_IN, 0L)
        val obtainedAt = prefs.getLong(KEY_OBTAINED_AT, 0L)
        if (refreshExpiresIn == 0L || obtainedAt == 0L) return null
        return KrakenToken(
            token = token,
            refreshToken = refreshToken,
            refreshExpiresIn = refreshExpiresIn,
            obtainedAt = obtainedAt
        )
    }

    fun saveToken(token: KrakenToken) {
        prefs.edit()
            .putString(KEY_TOKEN, token.token)
            .putString(KEY_REFRESH_TOKEN, token.refreshToken)
            .putLong(KEY_REFRESH_EXPIRES_IN, token.refreshExpiresIn)
            .putLong(KEY_OBTAINED_AT, token.obtainedAt)
            .apply()
    }

    fun clearToken() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_REFRESH_EXPIRES_IN)
            .remove(KEY_OBTAINED_AT)
            .apply()
    }

    companion object {
        private const val KEY_TOKEN = "kraken_token"
        private const val KEY_REFRESH_TOKEN = "kraken_refresh_token"
        private const val KEY_REFRESH_EXPIRES_IN = "kraken_refresh_expires_in"
        private const val KEY_OBTAINED_AT = "kraken_obtained_at"
    }
}
