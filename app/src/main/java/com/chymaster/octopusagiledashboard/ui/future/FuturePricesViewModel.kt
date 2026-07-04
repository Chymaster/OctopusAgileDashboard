package com.chymaster.octopusagiledashboard.ui.future

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class FuturePricesUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val prices: List<AgilePrice> = emptyList(),
    val flexiblePrice: Double? = null,
    val error: String? = null
)

@HiltViewModel
class FuturePricesViewModel @Inject constructor(
    private val repository: OctopusRepository,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val londonZone = ZoneId.of("Europe/London")

    private val _uiState = MutableStateFlow(FuturePricesUiState())
    val uiState: StateFlow<FuturePricesUiState> = _uiState.asStateFlow()

    private var dataJob: Job? = null

    companion object {
        private const val FLEXIBLE_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }

    init {
        dataJob = loadData()
    }

    fun onRefresh() {
        dataJob?.cancel()
        dataJob = loadData()
    }

    private fun loadData(): Job {
        return viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            // Immediately show cached flexible price if available (and within TTL)
            val cachedPrice = preferencesRepository.cachedFlexiblePriceFlow.first()
            val cachedTimestamp = preferencesRepository.cachedFlexiblePriceTimestampFlow.first()
            if (cachedPrice != null && System.currentTimeMillis() - cachedTimestamp < FLEXIBLE_CACHE_TTL_MS) {
                _uiState.update { it.copy(flexiblePrice = cachedPrice) }
            }

            val now = LocalDate.now(londonZone)
            // Range: 2 days ago → tomorrow (covers past 2 days + future prices from API)
            val start = now.minusDays(2).atStartOfDay(londonZone).toInstant()
            val end = now.plusDays(2).atStartOfDay(londonZone).toInstant()

            // Start observing cached data immediately
            launch {
                repository.observeAgilePrices(start, end).collectLatest { prices ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            prices = prices.sortedBy { it.validFrom },
                            error = null
                        )
                    }
                }
            }

            // Refresh from API in background + fetch flexible price
            launch {
                val result = repository.refreshAgilePrices(start, end)
                if (result.isFailure && _uiState.value.prices.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            error = result.exceptionOrNull()?.message ?: "Failed to load prices"
                        )
                    }
                }
            }

            repository.fetchFlexiblePrice().onSuccess { price ->
                _uiState.update { it.copy(flexiblePrice = price) }
                preferencesRepository.saveFlexiblePriceCache(price)
            }.onFailure { e ->
                android.util.Log.w("FuturePricesViewModel", "Failed to fetch flexible price", e)
            }
        }
    }
}
