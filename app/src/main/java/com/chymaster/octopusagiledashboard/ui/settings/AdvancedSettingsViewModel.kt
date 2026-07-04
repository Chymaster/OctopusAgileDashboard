package com.chymaster.octopusagiledashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AdvancedSettingsUiState(
    val cheapPercent: Int = PriceColors.DEFAULT_CHEAP_PERCENT,
    val moderatePercent: Int = PriceColors.DEFAULT_MODERATE_PERCENT,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false
)

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdvancedSettingsUiState())
    val uiState: StateFlow<AdvancedSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.cheapThresholdPercentFlow.collect { percent ->
                _uiState.value = _uiState.value.copy(cheapPercent = percent)
            }
        }
        viewModelScope.launch {
            preferencesRepository.moderateThresholdPercentFlow.collect { percent ->
                _uiState.value = _uiState.value.copy(moderatePercent = percent)
            }
        }
    }

    fun onCheapPercentChange(percent: Int) {
        _uiState.value = _uiState.value.copy(cheapPercent = percent, saveSuccess = false)
    }

    fun onModeratePercentChange(percent: Int) {
        _uiState.value = _uiState.value.copy(moderatePercent = percent, saveSuccess = false)
    }

    fun save() {
        val state = _uiState.value
        _uiState.value = state.copy(isSaving = true)
        viewModelScope.launch {
            preferencesRepository.saveCheapThresholdPercent(state.cheapPercent)
            preferencesRepository.saveModerateThresholdPercent(state.moderatePercent)
            _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)
        }
    }

    fun resetToDefaults() {
        _uiState.value = _uiState.value.copy(
            cheapPercent = PriceColors.DEFAULT_CHEAP_PERCENT,
            moderatePercent = PriceColors.DEFAULT_MODERATE_PERCENT,
            saveSuccess = false
        )
    }

    fun clearSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }
}
