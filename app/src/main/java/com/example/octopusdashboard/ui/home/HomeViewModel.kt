package com.example.octopusdashboard.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.octopusdashboard.data.repository.GreenEnergyRepository
import com.example.octopusdashboard.data.repository.OctopusRepository
import com.example.octopusdashboard.domain.model.AgilePrice
import com.example.octopusdashboard.domain.model.GreenEnergyData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    val greenEnergyData: GreenEnergyData? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: OctopusRepository,
    private val greenEnergyRepository: GreenEnergyRepository
) : ViewModel() {

    private val londonZone = ZoneId.of("Europe/London")

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadAllData()
        startGreenEnergyRefreshLoop()
    }

    fun onRefresh() {
        loadAllData()
    }

    private fun loadAllData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)

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

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        priceTimeline = sorted,
                        currentAgilePrice = current?.priceIncVat,
                        currentPriceStartTime = current?.validFrom
                    )
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
                    _uiState.value = _uiState.value.copy(
                        error = pricesResult.exceptionOrNull()?.message ?: "Failed to load prices"
                    )
                }

                flexibleJob.await().onSuccess { price ->
                    _uiState.value = _uiState.value.copy(flexiblePrice = price)
                }.onFailure { e ->
                    android.util.Log.w("HomeViewModel", "Failed to fetch flexible price", e)
                }

                greenJob.await().onSuccess { data ->
                    _uiState.value = _uiState.value.copy(greenEnergyData = data)
                }.onFailure { e ->
                    // Green energy is non-critical; show silently if other data loaded
                    if (_uiState.value.priceTimeline.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            error = e.message ?: "Failed to load grid data"
                        )
                    }
                }
            }

            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    private fun startGreenEnergyRefreshLoop() {
        viewModelScope.launch {
            while (true) {
                delay(15 * 60 * 1000L) // 15 minutes
                greenEnergyRepository.fetchGenerationMix().onSuccess { data ->
                    _uiState.value = _uiState.value.copy(greenEnergyData = data)
                }
            }
        }
    }
}
