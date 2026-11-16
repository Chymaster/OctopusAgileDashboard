package com.chymaster.octopusagiledashboard.domain.tariff

import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import java.time.Instant
import java.time.ZoneId

/**
 * Standing-charge maths shared by the Dashboard and the Tariff Comparison.
 *
 * Standing charges are pence per day, billed once per calendar day in the
 * London time zone. When the tariff changed mid-range, each day is priced with
 * the tariff that was in force at that day's 00:00 — the same approximation
 * Octopus applies on a switch day.
 */
object TariffCostCalculator {

    private val LONDON: ZoneId = ZoneId.of("Europe/London")

    /**
     * The 00:00 London instants of every calendar day touched by
     * `[start, end)`. A 7-day preset (start 7 days ago, end tomorrow 00:00)
     * yields 8 days, matching the historic "days between + 1" count.
     */
    fun dayStarts(start: Instant, end: Instant, zone: ZoneId = LONDON): List<Instant> {
        val firstDay = start.atZone(zone).toLocalDate()
        val lastExclusiveDay = end.atZone(zone).toLocalDate()
        val days = ArrayList<Instant>()
        var day = firstDay
        while (day.isBefore(lastExclusiveDay)) {
            days.add(day.atStartOfDay(zone).toInstant())
            day = day.plusDays(1)
        }
        // A range shorter than a day still bills one day.
        if (days.isEmpty()) days.add(firstDay.atStartOfDay(zone).toInstant())
        return days
    }

    /**
     * Total standing charge (pence) over [dayStarts], each day priced with the
     * charge published by [tariffAt]'s tariff. Returns null when no day has a
     * matching charge at all.
     */
    fun standingChargeCost(
        charges: List<StandingCharge>,
        dayStarts: List<Instant>,
        tariffAt: (Instant) -> String?
    ): Double? {
        if (charges.isEmpty() || dayStarts.isEmpty()) return null

        var total = 0.0
        var matched = false
        for (day in dayStarts) {
            val tariffCode = tariffAt(day)
            val charge = selectCharge(charges, day, tariffCode) ?: continue
            total += charge.valueIncVat
            matched = true
        }
        return if (matched) total else null
    }

    /** Single-tariff variant: the latest charge on or before each day. */
    fun standingChargeCostForSingleTariff(
        charges: List<StandingCharge>,
        dayStarts: List<Instant>
    ): Double? = standingChargeCost(charges, dayStarts) { null }

    /** Standing-charge total per tariff code over [dayStarts], for breakdowns. */
    fun standingChargeByTariff(
        charges: List<StandingCharge>,
        dayStarts: List<Instant>,
        tariffAt: (Instant) -> String?
    ): Map<String, Double> {
        if (charges.isEmpty() || dayStarts.isEmpty()) return emptyMap()
        val totals = LinkedHashMap<String, Double>()
        for (day in dayStarts) {
            val tariffCode = tariffAt(day)
            val charge = selectCharge(charges, day, tariffCode) ?: continue
            val key = tariffCode ?: charge.tariffCode ?: "unknown"
            totals[key] = (totals[key] ?: 0.0) + charge.valueIncVat
        }
        return totals
    }

    /**
     * Latest charge published on or before [day] from [tariffCode]'s tariff.
     * When no charge is published yet for that day, falls back to the earliest
     * one for the tariff (matching the historic "latest charge × days"
     * behaviour for ranges that start before the first published charge).
     */
    private fun selectCharge(
        charges: List<StandingCharge>,
        day: Instant,
        tariffCode: String?
    ): StandingCharge? {
        val forTariff = charges.filter { charge ->
            tariffCode == null || charge.tariffCode == null || charge.tariffCode == tariffCode
        }
        val applicable = forTariff
            .filter { !it.validFrom.isAfter(day) }
            .maxByOrNull { it.validFrom }
        if (applicable != null) return applicable
        return forTariff.minByOrNull { it.validFrom }
    }
}
