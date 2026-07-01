package com.example.octopusdashboard.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.octopusdashboard.data.prefs.UserPreferencesRepository
import com.example.octopusdashboard.domain.model.DateRangeSelection
import com.example.octopusdashboard.domain.model.HalfHourPoint
import com.example.octopusdashboard.ui.chart.BinnedPoint
import com.example.octopusdashboard.domain.model.TimeRangePreset
import com.example.octopusdashboard.domain.usecase.GetDashboardDataUseCase
import com.example.octopusdashboard.domain.usecase.RefreshDashboardDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DashboardUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val points: List<HalfHourPoint> = emptyList(),
    val selectedRange: DateRangeSelection = DateRangeSelection.Preset(TimeRangePreset.SEVEN_DAYS),
    val error: String? = null,
    val selectedBinnedPoint: BinnedPoint? = null,
    val hasCredentials: Boolean = false,
    // Summary stats
    val totalCost: Double? = null,
    val totalKwh: Double? = null,
    val avgPrice: Double? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getDashboardDataUseCase: GetDashboardDataUseCase,
    private val refreshDashboardDataUseCase: RefreshDashboardDataUseCase,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val londonZone = ZoneId.of("Europe/London")

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _selectedRange = MutableStateFlow<DateRangeSelection>(
        DateRangeSelection.Preset(TimeRangePreset.SEVEN_DAYS)
    )

    private var dataJob: Job? = null

    init {
        viewModelScope.launch {
            preferencesRepository.hasCredentials.collect { hasCreds ->
                _uiState.update { it.copy(hasCredentials = hasCreds) }
            }
        }
        viewModelScope.launch {
            _selectedRange.collectLatest { range ->
                loadData(range)
            }
        }
    }

    fun onRangeSelected(range: DateRangeSelection) {
        _selectedRange.value = range
    }

    fun onRefresh() {
        dataJob?.cancel()
        val range = _selectedRange.value
        dataJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            val (start, end) = getDateRange(range)
            val result = refreshDashboardDataUseCase(start, end)
            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        error = result.exceptionOrNull()?.message ?: "Refresh failed"
                    )
                }
            } else {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun onPointTapped(point: BinnedPoint?) {
        _uiState.update { it.copy(selectedBinnedPoint = point) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun loadData(range: DateRangeSelection): Job {
        return viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = it.points.isEmpty(),
                    error = null,
                    selectedRange = range
                )
            }
            val (start, end) = getDateRange(range)

            // Start observing Room cache immediately — shows cached data right away
            launch {
                getDashboardDataUseCase(start, end).collectLatest { points ->
                    val prices = points.mapNotNull { it.priceIncVat }
                    val totalCost = points.sumOf { it.costIncVat ?: 0.0 }
                    val totalKwh = points.sumOf { it.consumptionKwh ?: 0.0 }
                    // Usage-weighted average: total cost / total usage
                    val avgPrice = if (totalKwh > 0) totalCost / totalKwh else null

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            points = points,
                            error = null,
                            totalCost = if (totalKwh > 0) totalCost else null,
                            totalKwh = if (totalKwh > 0) totalKwh else null,
                            avgPrice = avgPrice,
                            minPrice = prices.minOrNull(),
                            maxPrice = prices.maxOrNull()
                        )
                    }
                }
            }

            // Refresh from API in background — Room observation will auto-update when data arrives
            val refreshResult = refreshDashboardDataUseCase(start, end)
            if (refreshResult.isFailure && _uiState.value.points.isEmpty()) {
                _uiState.update {
                    it.copy(error = refreshResult.exceptionOrNull()?.message ?: "Failed to refresh")
                }
            }
        }
    }

    private fun getDateRange(selection: DateRangeSelection): Pair<Instant, Instant> {
        val now = LocalDate.now(londonZone)
        return when (selection) {
            is DateRangeSelection.Preset -> {
                val start = when (selection.preset) {
                    TimeRangePreset.SEVEN_DAYS -> now.minusDays(7)
                    TimeRangePreset.ONE_MONTH -> now.minusDays(30)
                    TimeRangePreset.SIX_MONTHS -> now.minusDays(182)
                    TimeRangePreset.ONE_YEAR -> now.minusDays(365)
                }
                Pair(
                    start.atStartOfDay(londonZone).toInstant(),
                    now.plusDays(1).atStartOfDay(londonZone).toInstant()
                )
            }
            is DateRangeSelection.Custom -> {
                Pair(
                    selection.range.startDate.atStartOfDay(londonZone).toInstant(),
                    selection.range.endDate.plusDays(1).atStartOfDay(londonZone).toInstant()
                )
            }
        }
    }
}
