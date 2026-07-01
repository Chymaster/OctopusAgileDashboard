package com.example.octopusdashboard.ui.future

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.octopusdashboard.data.repository.OctopusRepository
import com.example.octopusdashboard.domain.model.AgilePrice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class FuturePricesUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val prices: List<AgilePrice> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class FuturePricesViewModel @Inject constructor(
    private val repository: OctopusRepository
) : ViewModel() {

    private val londonZone = ZoneId.of("Europe/London")

    private val _uiState = MutableStateFlow(FuturePricesUiState())
    val uiState: StateFlow<FuturePricesUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun onRefresh() {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)

            val now = LocalDate.now(londonZone)
            // Range: 2 days ago → tomorrow (covers past 2 days + future prices from API)
            val start = now.minusDays(2).atStartOfDay(londonZone).toInstant()
            val end = now.plusDays(2).atStartOfDay(londonZone).toInstant()

            // Start observing cached data immediately
            launch {
                repository.observeAgilePrices(start, end).collectLatest { prices ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        prices = prices.sortedBy { it.validFrom },
                        error = null
                    )
                }
            }

            // Refresh from API in background
            val result = repository.refreshAgilePrices(start, end)
            if (result.isFailure && _uiState.value.prices.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    error = result.exceptionOrNull()?.message ?: "Failed to load prices"
                )
            }
        }
    }
}
