package com.chymaster.octopusagiledashboard.domain.usecase

import com.chymaster.octopusagiledashboard.core.util.TariffCodes
import com.chymaster.octopusagiledashboard.core.util.TariffNames
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.data.repository.OctopusRepository
import com.chymaster.octopusagiledashboard.data.repository.TariffScheduleRepository
import com.chymaster.octopusagiledashboard.domain.model.ApiError
import com.chymaster.octopusagiledashboard.domain.model.DateRangeSelection
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.domain.model.TariffComparison
import com.chymaster.octopusagiledashboard.domain.model.TimeRangePreset
import com.chymaster.octopusagiledashboard.domain.tariff.SlotRateSelector
import com.chymaster.octopusagiledashboard.domain.tariff.TariffCostCalculator
import com.chymaster.octopusagiledashboard.domain.tariff.TariffScheduleResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Compares the user's **actual** tariff history against a selected alternative
 * over a date range, computing per-slot usage costs (consumption × the unit rate
 * of whichever tariff was in force in that slot), the Dashboard-style standing
 * charge, and totals — plus the per-slot savings that drive the comparison
 * charts.
 *
 * Costs are in pence. A positive [TariffComparison.totalSaving] means the
 * selected plan is cheaper.
 */
class GetTariffComparisonUseCase @Inject constructor(
    private val repository: OctopusRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val tariffScheduleRepository: TariffScheduleRepository
) {

    private val londonZone = ZoneId.of("Europe/London")

    suspend operator fun invoke(
        selection: DateRangeSelection,
        selectedProductCode: String,
        selectedTariffName: String
    ): Result<TariffComparison> {
        return try {
            val (start, end) = getDateRange(selection)
            val schedule = tariffScheduleRepository.get()
            val segments = TariffScheduleResolver.clipToRange(schedule, start, end)

            val gsp = preferencesRepository.gspFlow.first()
                ?: com.chymaster.octopusagiledashboard.core.util.Constants.DEFAULT_GSP
            val selectedTariffCode = TariffCodes.build(selectedProductCode, gsp)

            val currentProductCodes = TariffScheduleResolver.distinctProductCodes(segments)
            val currentNames = currentProductCodes.map { TariffNames.displayName(it) }
            val currentName = TariffNames.joinedDisplayName(currentProductCodes)

            coroutineScope {
                // Already normalised to one rate per slot by the repository.
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
                        ApiError.NoDataError(
                            "No rate data available for your tariff history in the selected range."
                        )
                    )
                }
                if (selectedRates.isEmpty()) {
                    return@coroutineScope Result.failure(
                        ApiError.NoDataError(
                            "No rate data available for $selectedTariffName in your region " +
                                "for the selected range."
                        )
                    )
                }
                if (consumption.isEmpty()) {
                    return@coroutineScope Result.failure(
                        ApiError.NoDataError("No usage data available for the selected range.")
                    )
                }

                // Current side is slot-normalised across the tariff history.
                val currentBySlot = currentPrices.associate { it.validFrom.toEpochMilli() to it.priceIncVat }
                val selectedBySlot = SlotRateSelector.buildSlotPriceMap(selectedRates, start, end)

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

                val days = TariffCostCalculator.dayStarts(start, end)
                val standingCurrent = TariffCostCalculator.standingChargeCost(
                    charges = currentCharges,
                    dayStarts = days,
                    tariffAt = { day ->
                        segments.lastOrNull { !day.isBefore(it.start) && day.isBefore(it.end) }
                            ?.tariffCode
                    }
                )
                val standingSelected =
                    TariffCostCalculator.standingChargeCostForSingleTariff(selectedCharges, days)

                val hasComparison = compared > 0
                val totalCurrent = if (hasComparison) usageCurrent + (standingCurrent ?: 0.0) else null
                val totalSelected = if (hasComparison) usageSelected + (standingSelected ?: 0.0) else null

                Result.success(
                    TariffComparison(
                        currentTariffName = currentName,
                        currentTariffNames = currentNames,
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
