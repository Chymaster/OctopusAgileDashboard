package com.chymaster.octopusagiledashboard.core.util

import com.chymaster.octopusagiledashboard.data.local.entity.AgilePriceEntity
import com.chymaster.octopusagiledashboard.data.local.entity.ConsumptionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Random
import kotlin.math.roundToInt

/**
 * Generates realistic demo data for a typical UK household.
 *
 * Consumption follows a time-of-day profile (overnight baseline, morning/evening peaks).
 * Agile prices follow a typical UK Agile tariff pattern (cheap overnight, peaks in
 * morning/evening). Data is deterministic per half-hour slot (seeded by epoch second)
 * so the same date always produces the same "demo" data.
 */
object DemoDataGenerator {

    private val londonZone = ZoneId.of("Europe/London")

    /**
     * Generate demo [ConsumptionEntity] rows for [start] to [end] using a
     * time-of-day household profile. Rows are tagged with the demo MPAN/serial
     * so they can be filtered out of any future real-mode query.
     */
    fun generateConsumptionEntities(start: Instant, end: Instant): List<ConsumptionEntity> {
        val rows = mutableListOf<ConsumptionEntity>()
        var t = start
        while (t.isBefore(end)) {
            val next = t.plus(30, ChronoUnit.MINUTES)
            val zdt = t.atZone(londonZone)
            val hour = zdt.hour
            val isWeekend = zdt.dayOfWeek.value >= 6
            val rng = Random(t.epochSecond)
            val consumption = generateConsumption(hour, isWeekend, rng)
            rows.add(
                ConsumptionEntity(
                    intervalStart = t.toEpochMilli(),
                    intervalEnd = next.toEpochMilli(),
                    consumption = consumption,
                    mpan = DemoIdentifiers.MPAN,
                    serialNumber = DemoIdentifiers.SERIAL
                )
            )
            t = next
        }
        return rows
    }

    /**
     * Generate demo [AgilePriceEntity] rows for [start] to [end] using a
     * realistic UK Agile tariff price profile. Prices are in pence per kWh
     * (inc VAT) and follow a typical time-of-day pattern: cheap overnight,
     * moderate daytime, peak in morning and evening.
     */
    fun generateAgilePriceEntities(
        start: Instant,
        end: Instant,
        tariffCode: String
    ): List<AgilePriceEntity> {
        val rows = mutableListOf<AgilePriceEntity>()
        var t = start
        while (t.isBefore(end)) {
            val next = t.plus(30, ChronoUnit.MINUTES)
            val zdt = t.atZone(londonZone)
            val hour = zdt.hour
            val rng = Random(t.epochSecond + 999L) // different seed from consumption
            val priceIncVat = generatePrice(hour, rng)
            val priceExcVat = roundTo(priceIncVat / 1.05, 4) // reverse 5% VAT
            rows.add(
                AgilePriceEntity(
                    validFrom = t.toEpochMilli(),
                    validTo = next.toEpochMilli(),
                    priceExcVat = priceExcVat,
                    priceIncVat = priceIncVat,
                    tariffCode = tariffCode
                )
            )
            t = next
        }
        return rows
    }

    private fun generatePrice(hour: Int, rng: Random): Double {
        val base = when (hour) {
            in 0..5 -> 8.0 + rng.nextDouble() * 7.0     // overnight cheap: 8-15p
            in 6..8 -> 18.0 + rng.nextDouble() * 12.0   // morning peak: 18-30p
            in 9..16 -> 12.0 + rng.nextDouble() * 10.0  // daytime: 12-22p
            in 17..20 -> 22.0 + rng.nextDouble() * 15.0 // evening peak: 22-37p
            else -> 10.0 + rng.nextDouble() * 10.0      // late evening: 10-20p
        }
        return roundTo(base, 4)
    }

    private fun generateConsumption(hour: Int, isWeekend: Boolean, rng: Random): Double {
        val base = when (hour) {
            in 0..5 -> 0.08 + rng.nextDouble() * 0.07   // overnight baseline
            in 6..8 -> 0.35 + rng.nextDouble() * 0.25   // morning peak
            in 9..16 -> 0.15 + rng.nextDouble() * 0.10  // daytime
            in 17..20 -> 0.50 + rng.nextDouble() * 0.30 // evening peak
            else -> 0.18 + rng.nextDouble() * 0.12      // late evening
        }
        val weekendFactor = if (isWeekend && hour in 9..16) 1.3 else 1.0
        return roundTo(base * weekendFactor, 3)
    }

    private fun roundTo(value: Double, decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return (value * factor).roundToInt() / factor
    }
}

/**
 * Sentinel identifiers for demo data.
 * In demo mode both prices and consumption are synthetic — no API calls needed.
 */
object DemoIdentifiers {
    const val MPAN = "DEMO_MPAN"
    const val SERIAL = "DEMO_SERIAL"
    const val TARIFF_CODE = "E-1R-AGILE-24-10-01-L"
}
