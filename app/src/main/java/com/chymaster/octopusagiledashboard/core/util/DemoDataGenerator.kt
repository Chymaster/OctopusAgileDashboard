package com.chymaster.octopusagiledashboard.core.util

import com.chymaster.octopusagiledashboard.data.local.entity.ConsumptionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Random
import kotlin.math.roundToInt

/**
 * Generates realistic demo [ConsumptionEntity] data for a typical UK household.
 *
 * Consumption follows a time-of-day profile (overnight baseline, morning/evening peaks).
 * Data is deterministic per half-hour slot (seeded by epoch second) so the same date
 * always produces the same "demo" data.
 *
 * Agile prices are NOT generated here — they are always fetched from the real
 * Octopus public API, even in demo mode.
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
 * Sentinel identifiers for demo consumption data.
 * Prices always come from the real Octopus API — only consumption is synthetic in demo mode.
 */
object DemoIdentifiers {
    const val MPAN = "DEMO_MPAN"
    const val SERIAL = "DEMO_SERIAL"
}
