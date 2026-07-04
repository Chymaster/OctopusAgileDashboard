package com.chymaster.octopusagiledashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.domain.usecase.TestConnectionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val apiKey: String = "",
    val mpan: String = "",
    val serialNumber: String = "",
    val gsp: String = Constants.DEFAULT_GSP,
    val productCode: String = Constants.DEFAULT_PRODUCT_CODE,
    val flexibleProductCode: String = Constants.FLEXIBLE_PRODUCT_CODE,
    val tariffCode: String = "",
    val isSaving: Boolean = false,
    val connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
    val saveSuccess: Boolean = false,
    val error: String? = null
)

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data object Success : ConnectionTestState
    data class Error(val message: String) : ConnectionTestState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    private val testConnectionUseCase: TestConnectionUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Load saved preferences
        viewModelScope.launch {
            preferencesRepository.apiKeyFlow.collect { key ->
                _uiState.value = _uiState.value.copy(apiKey = key ?: "")
            }
        }
        viewModelScope.launch {
            preferencesRepository.mpanFlow.collect { mpan ->
                _uiState.value = _uiState.value.copy(mpan = mpan ?: "")
            }
        }
        viewModelScope.launch {
            preferencesRepository.serialNumberFlow.collect { serial ->
                _uiState.value = _uiState.value.copy(serialNumber = serial ?: "")
            }
        }
        viewModelScope.launch {
            preferencesRepository.gspFlow.collect { gsp ->
                _uiState.value = _uiState.value.copy(gsp = gsp ?: Constants.DEFAULT_GSP)
            }
        }
        viewModelScope.launch {
            preferencesRepository.productCodeFlow.collect { code ->
                _uiState.value = _uiState.value.copy(productCode = code ?: Constants.DEFAULT_PRODUCT_CODE)
            }
        }
        viewModelScope.launch {
            preferencesRepository.flexibleProductCodeFlow.collect { code ->
                _uiState.value = _uiState.value.copy(flexibleProductCode = code ?: Constants.FLEXIBLE_PRODUCT_CODE)
            }
        }
    }

    fun onApiKeyChange(value: String) {
        _uiState.value = _uiState.value.copy(apiKey = value, error = null)
        updateTariffCode()
    }

    fun onMpanChange(value: String) {
        _uiState.value = _uiState.value.copy(mpan = value, error = null)
    }

    fun onSerialNumberChange(value: String) {
        _uiState.value = _uiState.value.copy(serialNumber = value, error = null)
    }

    fun onGspChange(value: String) {
        _uiState.value = _uiState.value.copy(gsp = value, error = null)
        updateTariffCode()
    }

    fun onProductCodeChange(value: String) {
        _uiState.value = _uiState.value.copy(productCode = value, error = null)
        updateTariffCode()
    }

    fun onFlexibleProductCodeChange(value: String) {
        _uiState.value = _uiState.value.copy(flexibleProductCode = value, error = null)
    }

    private fun updateTariffCode() {
        val state = _uiState.value
        val tariff = if (state.productCode.isNotBlank() && state.gsp.isNotBlank()) {
            "E-1R-${state.productCode}-${state.gsp}"
        } else ""
        _uiState.value = _uiState.value.copy(tariffCode = tariff)
    }

    fun save() {
        val state = _uiState.value
        if (state.gsp.isBlank()) {
            _uiState.value = state.copy(error = "Please select a region")
            return
        }

        _uiState.value = _uiState.value.copy(isSaving = true, error = null)

        viewModelScope.launch {
            preferencesRepository.saveCredentials(
                apiKey = state.apiKey,
                mpan = state.mpan,
                serialNumber = state.serialNumber,
                gsp = state.gsp,
                productCode = state.productCode.ifBlank { Constants.DEFAULT_PRODUCT_CODE }
            )
            preferencesRepository.saveFlexibleProductCode(
                state.flexibleProductCode.ifBlank { Constants.FLEXIBLE_PRODUCT_CODE }
            )
            _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)
        }
    }

    fun testConnection() {
        val state = _uiState.value
        if (state.apiKey.isBlank() || state.mpan.isBlank()) {
            _uiState.value = state.copy(
                connectionTestState = ConnectionTestState.Error("API key and MPAN are required to test connection")
            )
            return
        }

        _uiState.value = _uiState.value.copy(connectionTestState = ConnectionTestState.Testing)

        viewModelScope.launch {
            // Save first so the interceptor has the API key
            preferencesRepository.saveCredentials(
                apiKey = state.apiKey,
                mpan = state.mpan,
                serialNumber = state.serialNumber,
                gsp = state.gsp,
                productCode = state.productCode.ifBlank { Constants.DEFAULT_PRODUCT_CODE }
            )
            preferencesRepository.saveFlexibleProductCode(
                state.flexibleProductCode.ifBlank { Constants.FLEXIBLE_PRODUCT_CODE }
            )

            val result = testConnectionUseCase()
            _uiState.value = _uiState.value.copy(
                connectionTestState = if (result.isSuccess) {
                    ConnectionTestState.Success
                } else {
                    ConnectionTestState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
                }
            )
        }
    }

    fun resetConnectionTest() {
        _uiState.value = _uiState.value.copy(connectionTestState = ConnectionTestState.Idle)
    }

    fun clearSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }
}
