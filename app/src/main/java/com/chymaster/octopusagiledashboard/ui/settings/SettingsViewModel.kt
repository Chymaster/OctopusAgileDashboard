package com.chymaster.octopusagiledashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.core.util.TariffCodes
import com.chymaster.octopusagiledashboard.core.util.TariffNames
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.data.repository.TariffScheduleRepository
import com.chymaster.octopusagiledashboard.domain.model.TariffAgreement
import com.chymaster.octopusagiledashboard.domain.usecase.TestConnectionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class DetectedTariffUi(
    val tariffCode: String,
    val displayName: String,
    val validFrom: Instant,
    val validTo: Instant?,
    val isCurrent: Boolean
)

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
    val error: String? = null,
    // Auto-fetch serial number fields
    val serialNumbers: List<String> = emptyList(),
    val isFetchingSerials: Boolean = false,
    val serialFetchError: String? = null,
    // Auto-fetch account number fields
    val accountNumber: String = "",
    val accountNumbers: List<String> = emptyList(),
    val isFetchingAccount: Boolean = false,
    val accountFetchError: String? = null,
    // Tariff detection
    val autoDetect: Boolean = true,
    val detectedTariffs: List<DetectedTariffUi> = emptyList(),
    val isDetecting: Boolean = false,
    val detectError: String? = null
) {
    override fun toString(): String =
        "SettingsUiState(apiKey=***, mpan=$mpan, serialNumber=$serialNumber, gsp=$gsp, " +
            "productCode=$productCode, flexibleProductCode=$flexibleProductCode, " +
            "tariffCode=$tariffCode, isSaving=$isSaving, connectionTestState=$connectionTestState, " +
            "saveSuccess=$saveSuccess, error=$error, " +
            "serialNumbers=$serialNumbers, isFetchingSerials=$isFetchingSerials, serialFetchError=$serialFetchError, " +
            "accountNumber=$accountNumber, accountNumbers=$accountNumbers, isFetchingAccount=$isFetchingAccount, accountFetchError=$accountFetchError, " +
            "autoDetect=$autoDetect, detectedTariffs=$detectedTariffs, isDetecting=$isDetecting, detectError=$detectError)"
}

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data object Success : ConnectionTestState
    data class Error(val message: String) : ConnectionTestState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    private val testConnectionUseCase: TestConnectionUseCase,
    private val octopusRepository: OctopusRepository,
    private val tariffScheduleRepository: TariffScheduleRepository
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
            preferencesRepository.accountNumberFlow.collect { account ->
                _uiState.value = _uiState.value.copy(accountNumber = account ?: "")
            }
        }
        viewModelScope.launch {
            preferencesRepository.gspFlow.collect { gsp ->
                _uiState.value = _uiState.value.copy(gsp = gsp ?: Constants.DEFAULT_GSP)
                updateTariffCode()
            }
        }
        viewModelScope.launch {
            preferencesRepository.productCodeFlow.collect { code ->
                _uiState.value = _uiState.value.copy(productCode = code ?: Constants.DEFAULT_PRODUCT_CODE)
                updateTariffCode()
            }
        }
        viewModelScope.launch {
            preferencesRepository.flexibleProductCodeFlow.collect { code ->
                _uiState.value = _uiState.value.copy(flexibleProductCode = code ?: Constants.FLEXIBLE_PRODUCT_CODE)
            }
        }
        viewModelScope.launch {
            preferencesRepository.autoDetectTariffFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(autoDetect = enabled)
            }
        }
        viewModelScope.launch {
            tariffScheduleRepository.detected.collect { agreements ->
                _uiState.value = _uiState.value.copy(
                    detectedTariffs = agreements.map { it.toDetectedUi() }
                )
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

    /**
     * Called when the user selects a serial number from the dropdown
     * (or when a single serial is auto-selected).
     */
    fun onSerialNumberSelected(serial: String) {
        _uiState.value = _uiState.value.copy(
            serialNumber = serial,
            serialFetchError = null
        )
        // Persist immediately so demo mode turns off.
        viewModelScope.launch {
            preferencesRepository.saveSerialNumber(serial)
        }
    }

    fun onAccountNumberChange(value: String) {
        _uiState.value = _uiState.value.copy(accountNumber = value, error = null)
    }

    /**
     * Called when the user selects an account number from the dropdown
     * (or when a single account is auto-selected).
     */
    fun onAccountNumberSelected(number: String) {
        _uiState.value = _uiState.value.copy(
            accountNumber = number,
            accountFetchError = null
        )
        viewModelScope.launch {
            preferencesRepository.saveAccountNumber(number)
        }
    }

    /**
     * Fetches account numbers from the Octopus API via the viewer query.
     * If a single account is returned, auto-selects it. If multiple, populates
     * the dropdown list.
     */
    private fun fetchAccountNumbers() {
        _uiState.value = _uiState.value.copy(
            isFetchingAccount = true,
            accountFetchError = null
        )

        viewModelScope.launch {
            val result = octopusRepository.fetchAccountNumbers()
            result.onSuccess { numbers ->
                _uiState.value = _uiState.value.copy(
                    isFetchingAccount = false,
                    accountNumbers = numbers
                )
                if (numbers.size == 1) {
                    onAccountNumberSelected(numbers.first())
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isFetchingAccount = false,
                    accountFetchError = e.message ?: "Failed to fetch account number"
                )
            }
        }
    }

    /**
     * Fetches meter serial numbers from the Octopus API for the given MPAN.
     * If a single serial is returned, auto-selects it. If multiple, populates
     * the dropdown list. Updates [SettingsUiState.isFetchingSerials],
     * [SettingsUiState.serialNumbers], and [SettingsUiState.serialFetchError].
     */
    private fun fetchMeterSerials(mpan: String) {
        _uiState.value = _uiState.value.copy(
            isFetchingSerials = true,
            serialFetchError = null
        )

        viewModelScope.launch {
            // Fetch serial numbers from the meter-point endpoint.
            val result = octopusRepository.fetchMeterSerials(mpan)
            result.onSuccess { serials ->
                _uiState.value = _uiState.value.copy(
                    isFetchingSerials = false,
                    serialNumbers = serials
                )
                if (serials.size == 1) {
                    onSerialNumberSelected(serials.first())
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isFetchingSerials = false,
                    serialFetchError = e.message ?: "Failed to fetch meter serial number"
                )
            }

            // Also auto-populate the GSP from the API (reliably returned).
            val gspResult = octopusRepository.fetchMeterGsp(mpan)
            gspResult.onSuccess { apiGsp ->
                _uiState.value = _uiState.value.copy(gsp = apiGsp)
                updateTariffCode()
                preferencesRepository.saveGsp(apiGsp)
            }
        }
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
            TariffCodes.build(state.productCode, state.gsp)
        } else ""
        _uiState.value = _uiState.value.copy(tariffCode = tariff)
    }

    /** Turn account-based tariff detection on/off (off = manual override). */
    fun onAutoDetectChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoDetect = enabled, detectError = null)
        viewModelScope.launch {
            preferencesRepository.saveAutoDetectTariff(enabled)
            if (enabled) fetchDetectedTariffs()
        }
    }

    /**
     * Re-detect the tariff history from the Octopus account. Works even while
     * the manual override is on, so the user can see what was detected.
     */
    fun fetchDetectedTariffs() {
        if (_uiState.value.isDetecting) return
        _uiState.value = _uiState.value.copy(isDetecting = true, detectError = null)
        viewModelScope.launch {
            val result = tariffScheduleRepository.detectNow()
            val detected = tariffScheduleRepository.detected.value.map { it.toDetectedUi() }
            _uiState.value = _uiState.value.copy(
                isDetecting = false,
                detectedTariffs = detected,
                detectError = if (detected.isEmpty()) {
                    result.exceptionOrNull()?.message
                        ?: result.getOrNull()?.error
                        ?: "No tariff history found — check your API key and MPAN."
                } else {
                    null
                }
            )
        }
    }

    private fun TariffAgreement.toDetectedUi(): DetectedTariffUi {
        val now = Instant.now()
        return DetectedTariffUi(
            tariffCode = tariffCode,
            displayName = TariffNames.displayName(productCode),
            validFrom = validFrom,
            validTo = validTo,
            isCurrent = !validFrom.isAfter(now) && (validTo == null || validTo.isAfter(now))
        )
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

            // Auto-fetch serial number if API key + MPAN are set but serial is blank.
            val updatedState = _uiState.value
            if (updatedState.apiKey.isNotBlank() && updatedState.mpan.isNotBlank()
                && updatedState.serialNumber.isBlank()
            ) {
                fetchMeterSerials(updatedState.mpan)
            }

            // Auto-fetch account number if API key is set but account is blank.
            if (updatedState.apiKey.isNotBlank()
                && updatedState.accountNumber.isBlank()
            ) {
                fetchAccountNumbers()
            }

            // Detect the tariff history whenever detection is enabled.
            if (updatedState.apiKey.isNotBlank() && updatedState.autoDetect) {
                fetchDetectedTariffs()
            }
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

            // Auto-fetch serial number if connection was successful and serial is blank.
            if (result.isSuccess) {
                val updatedState = _uiState.value
                if (updatedState.serialNumber.isBlank()) {
                    fetchMeterSerials(updatedState.mpan)
                }
                if (updatedState.accountNumber.isBlank()) {
                    fetchAccountNumbers()
                }
                if (updatedState.autoDetect) {
                    fetchDetectedTariffs()
                }
            }
        }
    }

    fun resetConnectionTest() {
        _uiState.value = _uiState.value.copy(connectionTestState = ConnectionTestState.Idle)
    }

    fun clearSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }
}
