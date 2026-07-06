package com.chymaster.octopusagiledashboard.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.domain.model.TariffConfig
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = Constants.PREFERENCES_NAME
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val secureApiKeyStore: SecureApiKeyStore,
    private val octopusRepository: Lazy<OctopusRepository>
) {

    private val dataStore = context.dataStore

    companion object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val MPAN = stringPreferencesKey("mpan")
        val SERIAL_NUMBER = stringPreferencesKey("serial_number")
        val GSP = stringPreferencesKey("gsp")
        val PRODUCT_CODE = stringPreferencesKey("product_code")
        val FLEXIBLE_PRODUCT_CODE = stringPreferencesKey("flexible_product_code")
        val LAST_PRICES_REFRESH = longPreferencesKey("last_prices_refresh")
        val LAST_CONSUMPTION_REFRESH = longPreferencesKey("last_consumption_refresh")
        val CACHED_FLEXIBLE_PRICE = doublePreferencesKey("cached_flexible_price")
        val CACHED_FLEXIBLE_PRICE_TIMESTAMP = longPreferencesKey("cached_flexible_price_timestamp")
        val CHEAP_THRESHOLD_PERCENT = intPreferencesKey("cheap_threshold_percent")
        val MODERATE_THRESHOLD_PERCENT = intPreferencesKey("moderate_threshold_percent")
    }

    /**
     * Reads the API key from encrypted storage. One-time migration: if the key
     * exists in plain DataStore but not yet in EncryptedSharedPreferences, it
     * is moved on first access and removed from DataStore.
     */
    val apiKeyFlow: Flow<String?> = dataStore.data.map { prefs ->
        val encrypted = secureApiKeyStore.getApiKey()
        if (encrypted != null) {
            encrypted
        } else {
            // Migrate from plain DataStore if present
            val legacy = prefs[API_KEY]
            if (!legacy.isNullOrBlank()) {
                secureApiKeyStore.saveApiKey(legacy)
                legacy
            } else {
                null
            }
        }
    }
    val mpanFlow: Flow<String?> = dataStore.data.map { it[MPAN] }
    val serialNumberFlow: Flow<String?> = dataStore.data.map { it[SERIAL_NUMBER] }
    val gspFlow: Flow<String?> = dataStore.data.map { it[GSP] }
    val productCodeFlow: Flow<String?> = dataStore.data.map { it[PRODUCT_CODE] }
    val flexibleProductCodeFlow: Flow<String?> = dataStore.data.map { it[FLEXIBLE_PRODUCT_CODE] }

    val hasCredentials: Flow<Boolean> = combine(apiKeyFlow, mpanFlow, serialNumberFlow) { key, mpan, serial ->
        !key.isNullOrBlank() && !mpan.isNullOrBlank() && !serial.isNullOrBlank()
    }

    val tariffConfig: Flow<TariffConfig> = combine(productCodeFlow, gspFlow) { product, gsp ->
        val effectiveProduct = product?.takeIf { it.isNotBlank() } ?: Constants.DEFAULT_PRODUCT_CODE
        val effectiveGsp = gsp?.takeIf { it.isNotBlank() } ?: Constants.DEFAULT_GSP
        TariffConfig(
            productCode = effectiveProduct,
            tariffCode = "E-1R-$effectiveProduct-${effectiveGsp.removePrefix("_")}",
            gsp = effectiveGsp
        )
    }

    suspend fun saveApiKey(apiKey: String) {
        secureApiKeyStore.saveApiKey(apiKey)
        // Remove from plain DataStore if it was there (migration cleanup)
        dataStore.edit { it.remove(API_KEY) }
    }

    suspend fun saveMpan(mpan: String) {
        dataStore.edit { it[MPAN] = mpan }
    }

    suspend fun saveSerialNumber(serialNumber: String) {
        dataStore.edit { it[SERIAL_NUMBER] = serialNumber }
    }

    suspend fun saveGsp(gsp: String) {
        dataStore.edit { it[GSP] = gsp }
    }

    suspend fun saveProductCode(productCode: String) {
        dataStore.edit { it[PRODUCT_CODE] = productCode }
    }

    suspend fun saveFlexibleProductCode(flexibleProductCode: String) {
        dataStore.edit { it[FLEXIBLE_PRODUCT_CODE] = flexibleProductCode }
    }

    suspend fun saveLastPricesRefresh(timestamp: Long) {
        dataStore.edit { it[LAST_PRICES_REFRESH] = timestamp }
    }

    suspend fun saveLastConsumptionRefresh(timestamp: Long) {
        dataStore.edit { it[LAST_CONSUMPTION_REFRESH] = timestamp }
    }

    val lastPricesRefreshFlow: Flow<Long> = dataStore.data.map { it[LAST_PRICES_REFRESH] ?: 0L }
    val lastConsumptionRefreshFlow: Flow<Long> = dataStore.data.map { it[LAST_CONSUMPTION_REFRESH] ?: 0L }

    /** Cached flexible price value, or null if never cached. */
    val cachedFlexiblePriceFlow: Flow<Double?> = dataStore.data.map { it[CACHED_FLEXIBLE_PRICE] }

    /** Epoch millis when the flexible price was last fetched from the API. */
    val cachedFlexiblePriceTimestampFlow: Flow<Long> = dataStore.data.map { it[CACHED_FLEXIBLE_PRICE_TIMESTAMP] ?: 0L }

    suspend fun saveFlexiblePriceCache(price: Double) {
        dataStore.edit { prefs ->
            prefs[CACHED_FLEXIBLE_PRICE] = price
            prefs[CACHED_FLEXIBLE_PRICE_TIMESTAMP] = System.currentTimeMillis()
        }
    }

    /** Cheap/green threshold as a percentage of the flexible price (default 70%). */
    val cheapThresholdPercentFlow: Flow<Int> = dataStore.data.map { it[CHEAP_THRESHOLD_PERCENT] ?: 70 }

    /** Moderate/amber threshold as a percentage of the flexible price (default 130%). */
    val moderateThresholdPercentFlow: Flow<Int> = dataStore.data.map { it[MODERATE_THRESHOLD_PERCENT] ?: 130 }

    suspend fun saveCheapThresholdPercent(percent: Int) {
        dataStore.edit { it[CHEAP_THRESHOLD_PERCENT] = percent }
    }

    suspend fun saveModerateThresholdPercent(percent: Int) {
        dataStore.edit { it[MODERATE_THRESHOLD_PERCENT] = percent }
    }

    suspend fun saveCredentials(apiKey: String, mpan: String, serialNumber: String, gsp: String, productCode: String) {
        secureApiKeyStore.saveApiKey(apiKey)
        dataStore.edit { prefs ->
            prefs.remove(API_KEY) // Keep API key only in encrypted store
            prefs[MPAN] = mpan
            prefs[SERIAL_NUMBER] = serialNumber
            prefs[GSP] = gsp
            prefs[PRODUCT_CODE] = productCode
        }
        // Wipe both caches after credential change so no stale data from
        // the previous mode (demo or real) flashes on the next observation.
        octopusRepository.get().wipeAllCaches()
    }
}
