package com.chymaster.octopusagiledashboard.ui.tariffcomparison

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.domain.model.DateRangeSelection
import com.chymaster.octopusagiledashboard.domain.model.TariffComparison
import com.chymaster.octopusagiledashboard.domain.model.TariffOption
import com.chymaster.octopusagiledashboard.domain.model.TimeRangePreset
import com.chymaster.octopusagiledashboard.domain.model.toUserMessage
import com.chymaster.octopusagiledashboard.domain.usecase.GetTariffComparisonUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TariffComparisonUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val selectedRange: DateRangeSelection = DateRangeSelection.Preset(TimeRangePreset.ONE_MONTH),
    val selectedTariff: TariffOption = TariffOption(Constants.FLEXIBLE_PRODUCT_CODE, "Flexible Octopus"),
    val curatedTariffs: List<TariffOption> = Constants.COMMON_TARIFFS,
    val comparison: TariffComparison? = null,
    // "More…" product picker
    val showTariffPicker: Boolean = false,
    val availableTariffs: List<TariffOption> = emptyList(),
    val isProductsLoading: Boolean = false,
    val productsError: String? = null,
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TariffComparisonViewModel @Inject constructor(
    private val getTariffComparisonUseCase: GetTariffComparisonUseCase,
    private val repository: OctopusRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TariffComparisonUiState())
    val uiState: StateFlow<TariffComparisonUiState> = _uiState.asStateFlow()

    private val _selectedRange = MutableStateFlow<DateRangeSelection>(
        DateRangeSelection.Preset(TimeRangePreset.ONE_MONTH)
    )
    private val _selectedTariff = MutableStateFlow(
        TariffOption(Constants.FLEXIBLE_PRODUCT_CODE, "Flexible Octopus")
    )
    private val _refresh = MutableStateFlow(0L)

    init {
        // Single data-loading pipeline. flatMap-style: any change to the range,
        // selected tariff, or refresh trigger cancels the in-flight comparison.
        viewModelScope.launch {
            combine(_selectedRange, _selectedTariff, _refresh) { range, tariff, refresh ->
                Triple(range, tariff, refresh)
            }
                .distinctUntilChanged()
                .collectLatest { (range, tariff, _) ->
                    _uiState.update {
                        it.copy(
                            isLoading = true,
                            isRefreshing = false,
                            selectedRange = range,
                            selectedTariff = tariff,
                            error = null
                        )
                    }
                    getTariffComparisonUseCase(range, tariff.id, tariff.displayName)
                        .onSuccess { comparison ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isRefreshing = false,
                                    comparison = comparison,
                                    error = null
                                )
                            }
                        }
                        .onFailure { e ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isRefreshing = false,
                                    comparison = null,
                                    error = e.toUserMessage()
                                )
                            }
                        }
                }
        }
    }

    fun onRangeSelected(range: DateRangeSelection) {
        _selectedRange.value = range
        _uiState.update { it.copy(selectedRange = range, error = null) }
    }

    fun onTariffSelected(option: TariffOption) {
        _selectedTariff.value = option
        _uiState.update { it.copy(selectedTariff = option, showTariffPicker = false) }
    }

    fun onMoreTariffsClick() {
        _uiState.update { it.copy(showTariffPicker = true) }
        if (_uiState.value.availableTariffs.isEmpty() && !_uiState.value.isProductsLoading) {
            loadAvailableTariffs()
        }
    }

    fun onDismissTariffPicker() {
        _uiState.update { it.copy(showTariffPicker = false) }
    }

    fun loadAvailableTariffs() {
        if (_uiState.value.isProductsLoading) return
        _uiState.update { it.copy(isProductsLoading = true, productsError = null) }
        viewModelScope.launch {
            repository.fetchAvailableTariffs()
                .onSuccess { tariffs ->
                    _uiState.update { it.copy(isProductsLoading = false, availableTariffs = tariffs) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isProductsLoading = false, productsError = e.toUserMessage())
                    }
                }
        }
    }

    fun onRefresh() {
        _refresh.value = System.currentTimeMillis()
        _uiState.update { it.copy(isRefreshing = true, error = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
