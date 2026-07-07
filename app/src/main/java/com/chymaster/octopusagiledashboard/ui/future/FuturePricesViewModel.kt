package com.chymaster.octopusagiledashboard.ui.future

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class FuturePricesUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val prices: List<AgilePrice> = emptyList(),
    val flexiblePrice: Double? = null,
    val error: String? = null,
    val cheapThresholdPercent: Int = 70,
    val moderateThresholdPercent: Int = 130,
    /** True while older prices are being loaded (infinite scroll-up). */
    val isLoadingOlder: Boolean = false,
    /** Earliest date currently loaded — used to know where to extend from. */
    val loadedStartDay: LocalDate? = null,
    /** When non-null, the UI should scroll to this date's section. */
    val scrollTargetDate: LocalDate? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FuturePricesViewModel @Inject constructor(
    private val repository: OctopusRepository,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val londonZone = ZoneId.of("Europe/London")

    private val _uiState = MutableStateFlow(FuturePricesUiState())
    val uiState: StateFlow<FuturePricesUiState> = _uiState.asStateFlow()

    private var dataJob: Job? = null

    init {
        dataJob = loadData()

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
        dataJob?.cancel()
        dataJob = loadData()
    }

    private fun loadData(): Job {
        return viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            val now = LocalDate.now(londonZone)
            // Default range: 2 days ago → 2 days ahead
            val initialStart = now.minusDays(2).atStartOfDay(londonZone).toInstant()
            val futureEnd = now.plusDays(2).atStartOfDay(londonZone).toInstant()

            // Load from repository (Room-first, API only if empty)
            repository.getAgilePrices(initialStart, futureEnd)

            _uiState.update { it.copy(loadedStartDay = now.minusDays(2)) }

            // Observe prices from repository — the range dynamically expands
            // when loadedStartDay changes (via loadOlderPrices or jumpToDate).
            _uiState
                .flatMapLatest { state ->
                    val startDay = state.loadedStartDay ?: now.minusDays(2)
                    val startInstant = startDay.atStartOfDay(londonZone).toInstant()
                    repository.observeAgilePrices(startInstant, futureEnd)
                }
                .distinctUntilChanged()
                .collectLatest { prices ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            prices = prices.sortedBy { p -> p.validFrom },
                            error = null
                        )
                    }
                }
        }
    }

    /**
     * Load one more day of historical prices. Called when the user scrolls
     * to the top of the list (infinite scroll-up).
     */
    fun loadOlderPrices() {
        val currentStart = _uiState.value.loadedStartDay ?: return
        if (_uiState.value.isLoadingOlder) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingOlder = true) }

            val newStart = currentStart.minusDays(1)
            val startInstant = newStart.atStartOfDay(londonZone).toInstant()
            val endInstant = currentStart.atStartOfDay(londonZone).toInstant()

            // Single call: Room-first, API only if empty
            repository.getAgilePrices(startInstant, endInstant)

            _uiState.update {
                it.copy(
                    loadedStartDay = newStart,
                    isLoadingOlder = false
                )
            }
        }
    }

    /**
     * Jump to a specific date. Loads data for that date if not cached,
     * then signals the UI to scroll there.
     */
    fun jumpToDate(date: LocalDate) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }

            val startInstant = date.atStartOfDay(londonZone).toInstant()
            val endInstant = date.plusDays(1).atStartOfDay(londonZone).toInstant()

            // Single call: Room-first, API only if empty
            repository.getAgilePrices(startInstant, endInstant)

            // Expand loaded range if the date is before our current start
            val currentStart = _uiState.value.loadedStartDay
            if (currentStart == null || date < currentStart) {
                _uiState.update { it.copy(loadedStartDay = date) }
            }

            _uiState.update { it.copy(isRefreshing = false, scrollTargetDate = date) }
        }
    }

    /** Called by the UI after it has scrolled to the target date. */
    fun onScrollToTargetConsumed() {
        _uiState.update { it.copy(scrollTargetDate = null) }
    }
}
