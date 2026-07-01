package com.example.octopusdashboard.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.octopusdashboard.core.util.Constants
import com.example.octopusdashboard.domain.model.TariffConfig
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
    @param:ApplicationContext private val context: Context
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
    }

    val apiKeyFlow: Flow<String?> = dataStore.data.map { it[API_KEY] }
    val mpanFlow: Flow<String?> = dataStore.data.map { it[MPAN] }
    val serialNumberFlow: Flow<String?> = dataStore.data.map { it[SERIAL_NUMBER] }
    val gspFlow: Flow<String?> = dataStore.data.map { it[GSP] }
    val productCodeFlow: Flow<String?> = dataStore.data.map { it[PRODUCT_CODE] }
    val flexibleProductCodeFlow: Flow<String?> = dataStore.data.map { it[FLEXIBLE_PRODUCT_CODE] }

    val hasCredentials: Flow<Boolean> = combine(apiKeyFlow, mpanFlow, serialNumberFlow) { key, mpan, serial ->
        !key.isNullOrBlank() && !mpan.isNullOrBlank() && !serial.isNullOrBlank()
    }

    val tariffConfig: Flow<TariffConfig?> = combine(productCodeFlow, gspFlow) { product, gsp ->
        if (!product.isNullOrBlank() && !gsp.isNullOrBlank()) {
            TariffConfig(
                productCode = product,
                tariffCode = "E-1R-$product-${gsp.removePrefix("_")}",
                gsp = gsp
            )
        } else null
    }

    suspend fun saveApiKey(apiKey: String) {
        dataStore.edit { it[API_KEY] = apiKey }
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

    suspend fun saveCredentials(apiKey: String, mpan: String, serialNumber: String, gsp: String, productCode: String) {
        dataStore.edit { prefs ->
            prefs[API_KEY] = apiKey
            prefs[MPAN] = mpan
            prefs[SERIAL_NUMBER] = serialNumber
            prefs[GSP] = gsp
            prefs[PRODUCT_CODE] = productCode
        }
    }
}
