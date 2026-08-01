package com.chymaster.octopusagiledashboard.domain.usecase

import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.domain.model.ApiError
import com.chymaster.octopusagiledashboard.domain.model.DateRangeSelection
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import com.chymaster.octopusagiledashboard.domain.model.TariffComparison
import com.chymaster.octopusagiledashboard.domain.model.TimeRangePreset
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Compares the user's current tariff against a selected alternative over a
 * date range, computing per-slot usage costs (consumption × unit rate), the
 * Dashboard-style standing charge, and totals — plus the per-slot savings that
 * drive the comparison charts.
 *
 * Costs are in pence. A positive [TariffComparison.totalSaving] means the
 * selected plan is cheaper.
 */
class GetTariffComparisonUseCase @Inject constructor(
    private val repository: OctopusRepository,
    private val preferencesRepository: UserPreferencesRepository
) {

    private val londonZone = ZoneId.of("Europe/London")

    suspend operator fun invoke(
        selection: DateRangeSelection,
        selectedProductCode: String,
        selectedTariffName: String
    ): Result<TariffComparison> {
        return try {
            val (start, end) = getDateRange(selection)
            val cfg = preferencesRepository.tariffConfig.first()
            val currentProductCode = cfg.productCode
            // Same tariff-code pattern as fetchFlexiblePrice: E-1R-<product>-<gsp>.
            val selectedTariffCode = "E-1R-$selectedProductCode-${cfg.gsp.removePrefix("_")}"
            val currentName = Constants.COMMON_TARIFFS
                .firstOrNull { it.id == currentProductCode }?.displayName
                ?: currentProductCode

            coroutineScope {
                val currentPricesDeferred = async { repository.getAgilePrices(start, end) }
                val consumptionDeferred = async { repository.getConsumption(start, end) }
                val currentChargesDeferred = async {
                    val refresh = repository.refreshStandingCharges(start, end)
                    if (refresh.isFailure) emptyList()
                    else repository.observeStandingCharges(start, end).first()
                }
                val selectedRatesDeferred = async {
                    repository.fetchTariffRates(selectedProductCode, selectedTariffCode, start, end)
                }
                val selectedChargesDeferred = async {
                    repository.fetchTariffStandingCharges(
                        selectedProductCode, selectedTariffCode, start, end
                    )
                }

                val currentPricesResult = currentPricesDeferred.await()
                val consumptionResult = consumptionDeferred.await()
                val selectedRatesResult = selectedRatesDeferred.await()
                val currentCharges = currentChargesDeferred.await()
                val selectedCharges = selectedChargesDeferred.await().getOrDefault(emptyList())

                if (currentPricesResult.isFailure) {
                    return@coroutineScope Result.failure(
                        currentPricesResult.exceptionOrNull() ?: ApiError.NoDataError()
                    )
                }
                if (consumptionResult.isFailure) {
                    return@coroutineScope Result.failure(
                        consumptionResult.exceptionOrNull() ?: ApiError.NoDataError()
                    )
                }
                if (selectedRatesResult.isFailure) {
                    return@coroutineScope Result.failure(
                        selectedRatesResult.exceptionOrNull() ?: ApiError.NoDataError()
                    )
                }

                val currentPrices = currentPricesResult.getOrThrow()
                val consumption = consumptionResult.getOrThrow()
                val selectedRates = selectedRatesResult.getOrThrow()

                if (currentPrices.isEmpty()) {
                    return@coroutineScope Result.failure(
                        ApiError.NoDataError("No rate data available for your current tariff in the selected range.")
                    )
                }
                if (selectedRates.isEmpty()) {
                    return@coroutineScope Result.failure(
                        ApiError.NoDataError("No rate data available for $selectedTariffName in your region for the selected range.")
                    )
                }
                if (consumption.isEmpty()) {
                    return@coroutineScope Result.failure(
                        ApiError.NoDataError("No usage data available for the selected range.")
                    )
                }

                val currentBySlot = buildSlotPriceMap(currentPrices, start, end)
                val selectedBySlot = buildSlotPriceMap(selectedRates, start, end)

                var usageCurrent = 0.0
                var usageSelected = 0.0
                var totalKwh = 0.0
                var compared = 0
                val savings = mutableListOf<HalfHourPoint>()
                for (record in consumption) {
                    val key = record.intervalStart.toEpochMilli()
                    val currentPrice = currentBySlot[key] ?: continue
                    val selectedPrice = selectedBySlot[key] ?: continue
                    val currentCost = record.consumption * currentPrice
                    val selectedCost = record.consumption * selectedPrice
                    usageCurrent += currentCost
                    usageSelected += selectedCost
                    totalKwh += record.consumption
                    compared++
                    savings.add(
                        HalfHourPoint(
                            intervalStart = record.intervalStart,
                            intervalEnd = record.intervalEnd,
                            priceIncVat = null,   // null so binning aggregates costIncVat as savings
                            consumptionKwh = record.consumption,
                            costIncVat = currentCost - selectedCost
                        )
                    )
                }
                savings.sortBy { it.intervalStart }

                val standingCurrent = standingChargeCost(currentCharges, start, end)
                val standingSelected = standingChargeCost(selectedCharges, start, end)
                val hasComparison = compared > 0
                val totalCurrent = if (hasComparison) usageCurrent + (standingCurrent ?: 0.0) else null
                val totalSelected = if (hasComparison) usageSelected + (standingSelected ?: 0.0) else null

                Result.success(
                    TariffComparison(
                        currentTariffName = currentName,
                        selectedTariffName = selectedTariffName,
                        rangeStart = start,
                        rangeEnd = end,
                        usageCostCurrent = usageCurrent.takeIf { hasComparison },
                        usageCostSelected = usageSelected.takeIf { hasComparison },
                        standingChargeCurrent = standingCurrent,
                        standingChargeSelected = standingSelected,
                        totalCostCurrent = totalCurrent,
                        totalCostSelected = totalSelected,
                        totalSaving = if (totalCurrent != null && totalSelected != null) {
                            totalCurrent - totalSelected
                        } else null,
                        totalKwh = totalKwh,
                        comparedSlotCount = compared,
                        totalSlotCount = consumption.size,
                        savingsPoints = savings
                    )
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Maps every half-hour slot in [start]..[end) to the unit rate in effect at
     * that time. A rate applies from its [AgilePrice.validFrom] until the next
     * rate's validFrom (open-ended for Flexible-like tariffs), so we use the
     * "last rate with validFrom ≤ slot" rule rather than keying on validFrom.
     * This handles per-slot Agile rates, multi-rate Cosy/Go and long-lived
     * Flexible rates alike.
     */
    private fun buildSlotPriceMap(
        rates: List<AgilePrice>,
        start: Instant,
        end: Instant
    ): Map<Long, Double> {
        val sorted = rates.sortedBy { it.validFrom }
        val map = HashMap<Long, Double>()
        var idx = -1
        var t = start
        while (t.isBefore(end)) {
            while (idx + 1 < sorted.size && !sorted[idx + 1].validFrom.isAfter(t)) idx++
            if (idx >= 0) map[t.toEpochMilli()] = sorted[idx].priceIncVat
            t = t.plus(30, ChronoUnit.MINUTES)
        }
        return map
    }

    /** Dashboard-style standing charge: most-recent applicable value × calendar days. */
    private fun standingChargeCost(
        charges: List<StandingCharge>,
        start: Instant,
        end: Instant
    ): Double? {
        if (charges.isEmpty()) return null
        val charge = charges.maxByOrNull { it.validFrom } ?: return null
        val days = ChronoUnit.DAYS.between(
            start.atZone(londonZone).toLocalDate(),
            end.atZone(londonZone).toLocalDate()
        ).coerceAtLeast(1)
        return charge.valueIncVat * days
    }

    /** Maps a date-range selection to [start, end) Instants (mirrors DashboardViewModel). */
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
