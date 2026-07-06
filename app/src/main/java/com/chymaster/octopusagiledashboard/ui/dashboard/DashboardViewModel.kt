package com.chymaster.octopusagiledashboard.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.domain.model.DateRangeSelection
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import com.chymaster.octopusagiledashboard.ui.chart.BinnedPoint
import com.chymaster.octopusagiledashboard.ui.chart.trimMissingConsumption
import com.chymaster.octopusagiledashboard.domain.model.TimeRangePreset
import com.chymaster.octopusagiledashboard.domain.usecase.GetDashboardDataUseCase
import com.chymaster.octopusagiledashboard.domain.usecase.RefreshDashboardDataUseCase
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors
import java.time.temporal.ChronoUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
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
    /** Points trimmed for chart display — edge intervals with no consumption removed. */
    val chartPoints: List<HalfHourPoint> = emptyList(),
    /**
     * Chart points to always display — falls back to zero-filled points across
     * the selected time range when no real data is available yet. This ensures
     * the chart always shows the selected date range even before the Octopus
     * usage API returns data.
     */
    val displayChartPoints: List<HalfHourPoint> = emptyList(),
    val selectedRange: DateRangeSelection = DateRangeSelection.Preset(TimeRangePreset.SEVEN_DAYS),
    val error: String? = null,
    val selectedBinnedPoint: BinnedPoint? = null,
    val hasCredentials: Boolean = false,
    val isDemoMode: Boolean = false,
    // Summary stats
    val totalCost: Double? = null,
    val usageCost: Double? = null,
    val standingChargeCost: Double? = null,
    val totalKwh: Double? = null,
    val avgPrice: Double? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val flexiblePrice: Double? = null,
    val showCostBreakdown: Boolean = false,
    // Usage zone breakdown
    val cheapThresholdPercent: Int = PriceColors.DEFAULT_CHEAP_PERCENT,
    val moderateThresholdPercent: Int = PriceColors.DEFAULT_MODERATE_PERCENT,
    val greenUsageKwh: Double = 0.0,
    val amberUsageKwh: Double = 0.0,
    val redUsageKwh: Double = 0.0,
    val showUsageBreakdown: Boolean = false
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getDashboardDataUseCase: GetDashboardDataUseCase,
    private val refreshDashboardDataUseCase: RefreshDashboardDataUseCase,
    private val repository: OctopusRepository,
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
                // Cache wipe is handled by the repository (via
                // UserPreferencesRepository.saveCredentials) and the
                // observe* methods use transformLatest to react to
                // credential changes automatically.
                dataJob?.cancel()
                dataJob = loadData(_selectedRange.value)
            }
        }
        viewModelScope.launch {
            preferencesRepository.cheapThresholdPercentFlow.collect { pct ->
                _uiState.update { it.copy(cheapThresholdPercent = pct) }
                recomputeZoneBreakdown()
            }
        }
        viewModelScope.launch {
            preferencesRepository.moderateThresholdPercentFlow.collect { pct ->
                _uiState.update { it.copy(moderateThresholdPercent = pct) }
                recomputeZoneBreakdown()
            }
        }
        // Immediately show cached flexible price if available (and within TTL)
        viewModelScope.launch {
            val cachedPrice = preferencesRepository.cachedFlexiblePriceFlow.first()
            val cachedTimestamp = preferencesRepository.cachedFlexiblePriceTimestampFlow.first()
            if (cachedPrice != null && System.currentTimeMillis() - cachedTimestamp < FLEXIBLE_CACHE_TTL_MS) {
                _uiState.update { it.copy(flexiblePrice = cachedPrice) }
                recomputeZoneBreakdown()
            }
        }
        // Skip the initial emission — the hasCredentials collector above
        // handles the first loadData. Only react to actual range changes.
        viewModelScope.launch {
            _selectedRange.drop(1).collectLatest { range ->
                dataJob?.cancel()
                dataJob = loadData(range)
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
            coroutineScope {
                val dataJobAsync = async {
                    refreshDashboardDataUseCase(start, end)
                }
                val flexibleJob = async {
                    repository.fetchFlexiblePrice()
                }
                val result = dataJobAsync.await()
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
                flexibleJob.await().onSuccess { price ->
                    _uiState.update { it.copy(flexiblePrice = price) }
                    preferencesRepository.saveFlexiblePriceCache(price)
                    recomputeZoneBreakdown()
                }.onFailure { e ->
                    android.util.Log.w("DashboardViewModel", "Failed to fetch flexible price", e)
                }
            }
        }
    }

    fun onPointTapped(point: BinnedPoint?) {
        _uiState.update { it.copy(selectedBinnedPoint = point) }
    }

    fun onToggleCostBreakdown() {
        _uiState.update { it.copy(showCostBreakdown = !it.showCostBreakdown) }
    }

    fun onToggleUsageBreakdown() {
        _uiState.update { it.copy(showUsageBreakdown = !it.showUsageBreakdown) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        /** 30 days in milliseconds — cache TTL for the flexible price. */
        private const val FLEXIBLE_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }

    /**
     * Single, source-agnostic load path. The repository branches on
     * `hasCredentials` internally, so this function:
     *
     *  1. Seeds the in-memory [DemoCacheStore] when no credentials are
     *     configured, so the first observation emission is a fully populated
     *     list (this is what eliminates the flicker).
     *  2. Starts the dashboard flow (which reads from the demo store or Room
     *     depending on credential state) and the standing-charge flow.
     *  3. Triggers a refresh in the background — the public Agile API in
     *     demo mode, the authenticated API in real mode.
     */
    private fun loadData(range: DateRangeSelection): Job {
        return viewModelScope.launch {
            val (start, end) = getDateRange(range)
            val hasCreds = _uiState.value.hasCredentials

            // Demo seeding is handled by the repository's observe* methods
            // (auto-seed when DemoCacheStore is empty).

            _uiState.update {
                it.copy(
                    isLoading = it.points.isEmpty(),
                    error = null,
                    selectedRange = range,
                    isDemoMode = !hasCreds,
                    // Show placeholder points immediately so the chart renders
                    // the time range even before data arrives.
                    displayChartPoints = it.chartPoints.ifEmpty {
                        generatePlaceholderPoints(start, end)
                    }
                )
            }

            coroutineScope {
                // Observe the dashboard flow. The repository branches
                // internally on hasCredentials: demo → DemoCacheStore, real → Room.
                launch {
                    getDashboardDataUseCase(start, end).collectLatest { points ->
                        updateDashboardWithPoints(points, start, end)
                    }
                }

                // Observe standing charges and compute the standing charge cost.
                // The repository returns the synthetic demo entity when in
                // demo mode, so this code is identical for both paths.
                launch {
                    repository.observeStandingCharges(start, end).collectLatest { charges ->
                        _uiState.update { it.copy(standingChargeCost = computeStandingChargeCost(charges, start, end)) }
                        recalculateTotalCost()
                    }
                }

                // Refresh in the background. The observation flows will
                // auto-update when the refresh writes to the demo store or
                // Room. In demo mode the seeded data stays visible even if
                // the public API refresh fails.
                val refreshResult = refreshDashboardDataUseCase(start, end)
                if (refreshResult.isFailure && _uiState.value.points.isEmpty()) {
                    _uiState.update {
                        it.copy(error = refreshResult.exceptionOrNull()?.message ?: "Failed to refresh")
                    }
                }
            }
        }
    }

    /** Update dashboard UI state with the given [HalfHourPoint]s. */
    private fun updateDashboardWithPoints(
        points: List<HalfHourPoint>,
        start: Instant,
        end: Instant
    ) {
        val prices = points.mapNotNull { it.priceIncVat }
        val usageCost = points.sumOf { it.costIncVat ?: 0.0 }
        val totalKwh = points.sumOf { it.consumptionKwh ?: 0.0 }
        // Usage-weighted average: usage cost / total usage
        val avgPrice = if (totalKwh > 0) usageCost / totalKwh else null

        val trimmed = points.trimMissingConsumption()
        // Always have chart points to show — use real data, or
        // generate placeholder points spanning the selected range
        // so the chart axis shows the full date range.
        val displayPoints = trimmed.ifEmpty {
            generatePlaceholderPoints(start, end)
        }

        _uiState.update {
            it.copy(
                isLoading = false,
                points = points,
                chartPoints = trimmed,
                displayChartPoints = displayPoints,
                error = null,
                usageCost = if (totalKwh > 0) usageCost else null,
                totalKwh = if (totalKwh > 0) totalKwh else null,
                avgPrice = avgPrice,
                minPrice = prices.minOrNull(),
                maxPrice = prices.maxOrNull()
            )
        }
        recomputeZoneBreakdown()
        recalculateTotalCost()
    }

    private fun recalculateTotalCost() {
        val state = _uiState.value
        val usage = state.usageCost
        val standing = state.standingChargeCost
        val total = if (usage != null || standing != null) {
            (usage ?: 0.0) + (standing ?: 0.0)
        } else null
        _uiState.update { it.copy(totalCost = total) }
    }

    /** Recompute green/amber/red usage breakdown from current points and thresholds. */
    private fun recomputeZoneBreakdown() {
        val state = _uiState.value
        val refPrice = state.flexiblePrice
        val cheapPct = state.cheapThresholdPercent
        val moderatePct = state.moderateThresholdPercent
        var greenKwh = 0.0
        var amberKwh = 0.0
        var redKwh = 0.0
        for (point in state.points) {
            val price = point.priceIncVat ?: continue
            val kwh = point.consumptionKwh ?: continue
            if (kwh <= 0) continue
            when (PriceColors.priceColor(price, refPrice, cheapPct, moderatePct)) {
                PriceColors.Cheap -> greenKwh += kwh
                PriceColors.Expensive -> redKwh += kwh
                else -> amberKwh += kwh
            }
        }
        _uiState.update {
            it.copy(
                greenUsageKwh = greenKwh,
                amberUsageKwh = amberKwh,
                redUsageKwh = redKwh
            )
        }
    }

    /**
     * Compute the total standing charge cost (in pence) for the given date range.
     * Standing charges are in pence per day; we find the applicable charge and
     * multiply by the number of calendar days covered. Works for both real
     * (50p/day from the API) and demo (50p/day from the synthetic entity) data.
     */
    private fun computeStandingChargeCost(
        charges: List<StandingCharge>,
        start: Instant,
        end: Instant
    ): Double? {
        if (charges.isEmpty()) return null
        // Use the most recent applicable standing charge
        val charge = charges.maxByOrNull { it.validFrom } ?: return null
        val days = ChronoUnit.DAYS.between(
            start.atZone(londonZone).toLocalDate(),
            end.atZone(londonZone).toLocalDate()
        ).coerceAtLeast(1)
        return charge.valueIncVat * days
    }

    /**
     * Generate placeholder [HalfHourPoint]s covering [start] to [end] at
     * half-hour intervals so the chart always renders the selected time range
     * even when no real data has arrived yet.
     */
    private fun generatePlaceholderPoints(start: Instant, end: Instant): List<HalfHourPoint> {
        val points = mutableListOf<HalfHourPoint>()
        var t = start
        while (t.isBefore(end)) {
            val next = t.plus(30, ChronoUnit.MINUTES)
            points.add(
                HalfHourPoint(
                    intervalStart = t,
                    intervalEnd = next,
                    priceIncVat = null,
                    consumptionKwh = null,
                    costIncVat = null,
                )
            )
            t = next
        }
        return points
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
