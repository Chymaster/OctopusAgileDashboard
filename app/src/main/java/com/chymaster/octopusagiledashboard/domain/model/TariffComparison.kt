package com.chymaster.octopusagiledashboard.domain.model

import java.time.Instant

/**
 * Result of comparing the user's current tariff against a selected alternative
 * over a date range. All costs are in pence.
 *
 * [usageCostCurrent] / [usageCostSelected] are summed over the [comparedSlotCount]
 * half-hour slots where BOTH tariffs had a published rate. Standing charges are
 * computed over the full [rangeStart]..[rangeEnd] (Dashboard-style).
 */
data class TariffComparison(
    val currentTariffName: String,
    val selectedTariffName: String,
    val rangeStart: Instant,
    val rangeEnd: Instant,
    val usageCostCurrent: Double?,
    val usageCostSelected: Double?,
    val standingChargeCurrent: Double?,
    val standingChargeSelected: Double?,
    val totalCostCurrent: Double?,
    val totalCostSelected: Double?,
    /** totalCostCurrent - totalCostSelected; positive = the selected plan is cheaper. */
    val totalSaving: Double?,
    val totalKwh: Double,
    val comparedSlotCount: Int,
    val totalSlotCount: Int,
    /**
     * One entry per compared half-hour slot, sorted by time. `costIncVat` holds
     * the per-slot saving in pence (current − selected, can be negative);
     * `priceIncVat`/`consumptionKwh` are carried through for chart binning.
     */
    val savingsPoints: List<HalfHourPoint>
)
