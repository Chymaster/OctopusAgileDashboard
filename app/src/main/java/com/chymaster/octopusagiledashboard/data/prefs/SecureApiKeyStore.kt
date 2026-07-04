package com.chymaster.octopusagiledashboard.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the Octopus API key in EncryptedSharedPreferences backed by the
 * Android Keystore. This prevents the key from being readable via ADB,
 * rooted-device access, or unencrypted backups.
 */
@Singleton
class SecureApiKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "octopus_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun hasApiKey(): Boolean = prefs.contains(KEY_API_KEY)

    companion object {
        private const val KEY_API_KEY = "api_key"
    }
}
