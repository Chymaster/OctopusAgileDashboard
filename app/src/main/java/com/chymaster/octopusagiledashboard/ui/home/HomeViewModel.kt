package com.chymaster.octopusagiledashboard.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.repository.GreenEnergyRepository
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.domain.model.GreenEnergyData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val currentAgilePrice: Double? = null,
    val currentPriceStartTime: Instant? = null,
    val priceTimeline: List<AgilePrice> = emptyList(),
    val flexiblePrice: Double? = null,
    val greenEnergyData: GreenEnergyData? = null,
    val cheapThresholdPercent: Int = 70,
    val moderateThresholdPercent: Int = 130,
    val hasCredentials: Boolean = false,
    val isDemoMode: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: OctopusRepository,
    private val greenEnergyRepository: GreenEnergyRepository,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val londonZone = ZoneId.of("Europe/London")

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            preferencesRepository.isDemoMode.collect { isDemo ->
                _uiState.update { it.copy(hasCredentials = !isDemo, isDemoMode = isDemo) }
            }
        }
        refreshJob = loadAllData()
        startGreenEnergyRefreshLoop()

        // App-open background refresh: fetch future prices so the Future Prices
        // screen has data ready when the user navigates to it.
        viewModelScope.launch {
            val now = Instant.now()
            val futureEnd = now.atZone(londonZone).toLocalDate()
                .plusDays(2).atStartOfDay(londonZone).toInstant()
            repository.refreshAgilePrices(now, futureEnd)
        }

        // Observe threshold preferences
        viewModelScope.launch {
            preferencesRepository.cheapThresholdPercentFlow.collect { percent ->
                _uiState.update { it.copy(cheapThresholdPercent = percent) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.moderateThresholdPercentFlow.collect { percent ->
                _uiState.update { it.copy(moderateThresholdPercent = percent) }
            }
        }
    }

    fun onRefresh() {
        refreshJob?.cancel()
        refreshJob = loadAllData()
    }

    companion object {
        /** 30 days in milliseconds — cache TTL for the flexible price. */
        private const val FLEXIBLE_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }

    private fun loadAllData(): Job {
        return viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            // Immediately show cached flexible price if available (and within TTL)
            val cachedPrice = preferencesRepository.cachedFlexiblePriceFlow.first()
            val cachedTimestamp = preferencesRepository.cachedFlexiblePriceTimestampFlow.first()
            if (cachedPrice != null && System.currentTimeMillis() - cachedTimestamp < FLEXIBLE_CACHE_TTL_MS) {
                _uiState.update { it.copy(flexiblePrice = cachedPrice) }
            }

            // Calculate time window: 2 hours ago to 4 hours ahead
            val now = Instant.now()
            val start = now.minusSeconds(2 * 3600)
            val end = now.plusSeconds(4 * 3600)

            // Start observing cached prices immediately
            launch {
                repository.observeAgilePrices(start, end).collectLatest { prices ->
                    val sorted = prices.sortedBy { it.validFrom }
                    val current = sorted.find { price ->
                        price.validFrom <= now && price.validTo > now
                    }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            priceTimeline = sorted,
                            currentAgilePrice = current?.priceIncVat,
                            currentPriceStartTime = current?.validFrom
                        )
                    }
                }
            }

            // Refresh from API in background + fetch other data in parallel
            coroutineScope {
                val pricesJob = async {
                    repository.refreshAgilePrices(start, end)
                }
                val flexibleJob = async {
                    repository.fetchFlexiblePrice()
                }
                val greenJob = async {
                    greenEnergyRepository.fetchGenerationMix()
                }

                // Collect results
                val pricesResult = pricesJob.await()
                if (pricesResult.isFailure && _uiState.value.priceTimeline.isEmpty()) {
                    _uiState.update {
                        it.copy(error = pricesResult.exceptionOrNull()?.message ?: "Failed to load prices")
                    }
                }

                flexibleJob.await().onSuccess { price ->
                    _uiState.update { it.copy(flexiblePrice = price) }
                    preferencesRepository.saveFlexiblePriceCache(price)
                }.onFailure { e ->
                    android.util.Log.w("HomeViewModel", "Failed to fetch flexible price", e)
                }

                greenJob.await().onSuccess { data ->
                    _uiState.update { it.copy(greenEnergyData = data) }
                }.onFailure { e ->
                    // Green energy is non-critical; show silently if other data loaded
                    if (_uiState.value.priceTimeline.isEmpty()) {
                        _uiState.update {
                            it.copy(error = e.message ?: "Failed to load grid data")
                        }
                    }
                }
            }

            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun startGreenEnergyRefreshLoop() {
        viewModelScope.launch {
            while (true) {
                delay(15 * 60 * 1000L) // 15 minutes
                greenEnergyRepository.fetchGenerationMix().onSuccess { data ->
                    _uiState.update { it.copy(greenEnergyData = data) }
                }
            }
        }
    }
}
