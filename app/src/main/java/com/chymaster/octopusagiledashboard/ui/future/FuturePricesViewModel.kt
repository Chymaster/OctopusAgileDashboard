package com.chymaster.octopusagiledashboard.ui.future

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chymaster.octopusagiledashboard.core.util.TariffNames
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.data.repository.TariffScheduleRepository
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.domain.model.toUserMessage
import com.chymaster.octopusagiledashboard.domain.tariff.TariffScheduleResolver
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
    /** Last date the observed window covers — advances at midnight. */
    val loadedEndDay: LocalDate? = null,
    /** When non-null, the UI should scroll to this date's section. */
    val scrollTargetDate: LocalDate? = null,
    /** Tariff in force right now (drives the screen title). */
    val currentTariffName: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FuturePricesViewModel @Inject constructor(
    private val repository: OctopusRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val tariffScheduleRepository: TariffScheduleRepository
) : ViewModel() {

    private val londonZone = ZoneId.of("Europe/London")

    private val _uiState = MutableStateFlow(FuturePricesUiState())
    val uiState: StateFlow<FuturePricesUiState> = _uiState.asStateFlow()

    private var dataJob: Job? = null

    init {
        dataJob = loadData()

        // Tariff name for the title.
        viewModelScope.launch {
            tariffScheduleRepository.state.collect { schedule ->
                val current = TariffScheduleResolver.agreementAt(schedule.agreements, Instant.now())
                _uiState.update {
                    it.copy(currentTariffName = current?.productCode?.let(TariffNames::displayName))
                }
            }
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
        dataJob?.cancel()
        dataJob = loadData()
    }

    /**
     * Re-checks the published tail on user action — returning to the screen or
     * the app — and rolls the observed window forward at midnight. Completeness
     * is derived from the cached rows, so this fetches only what Octopus has
     * published since the last load; a covered window costs no network call.
     */
    fun revalidate() {
        viewModelScope.launch {
            val today = LocalDate.now(londonZone)
            val endDay = today.plusDays(2)
            if (_uiState.value.loadedEndDay != endDay) {
                _uiState.update { it.copy(loadedEndDay = endDay) }
            }
            val startDay = _uiState.value.loadedStartDay ?: today.minusDays(2)
            repository.getAgilePrices(
                startDay.atStartOfDay(londonZone).toInstant(),
                endDay.atStartOfDay(londonZone).toInstant(),
                force = true
            )
        }
    }

    private fun loadData(): Job {
        return viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            val now = LocalDate.now(londonZone)
            // Default range: 2 days ago → 2 days ahead
            val initialStart = now.minusDays(2).atStartOfDay(londonZone).toInstant()
            val futureEnd = now.plusDays(2).atStartOfDay(londonZone).toInstant()

            // Load from the repository. User action: always re-check the tail,
            // but only the part Octopus has published since the last load.
            repository.getAgilePrices(initialStart, futureEnd, force = true).onFailure { e ->
                _uiState.update { it.copy(error = e.toUserMessage()) }
            }

            // Fetch flexible price for colour thresholds (cached 7 days, API only if expired)
            repository.fetchFlexiblePrice().onSuccess { price ->
                _uiState.update { it.copy(flexiblePrice = price) }
            }.onFailure { e ->
                android.util.Log.w("FuturePricesVM", "Failed to fetch flexible price", e)
            }

            _uiState.update {
                it.copy(
                    loadedStartDay = now.minusDays(2),
                    loadedEndDay = now.plusDays(2)
                )
            }

            // Observe prices from repository — the range dynamically expands
            // when loadedStartDay changes (via loadOlderPrices or jumpToDate)
            // and rolls forward when loadedEndDay advances at midnight.
            _uiState
                .flatMapLatest { state ->
                    val startDay = state.loadedStartDay ?: now.minusDays(2)
                    val endDay = state.loadedEndDay ?: now.plusDays(2)
                    repository.observeAgilePrices(
                        startDay.atStartOfDay(londonZone).toInstant(),
                        endDay.atStartOfDay(londonZone).toInstant()
                    )
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
            repository.getAgilePrices(startInstant, endInstant).onFailure { e ->
                _uiState.update { it.copy(isLoadingOlder = false) }
                return@launch
            }

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
            repository.getAgilePrices(startInstant, endInstant).onFailure { e ->
                _uiState.update { it.copy(isRefreshing = false, error = e.toUserMessage()) }
                return@launch
            }

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
