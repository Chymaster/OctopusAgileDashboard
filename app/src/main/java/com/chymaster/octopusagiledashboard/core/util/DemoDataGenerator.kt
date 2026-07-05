package com.chymaster.octopusagiledashboard.core.util

import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.roundToInt
import java.util.Random

/**
 * Generates realistic demo [HalfHourPoint] data for a typical UK household.
 *
 * Consumption follows a time-of-day profile (overnight baseline, morning/evening peaks)
 * and prices follow a sinusoidal pattern mimicking Agile tariffs (cheap overnight, expensive
 * in the late afternoon). Data is deterministic per half-hour slot (seeded by epoch second)
 * so the same date always produces the same "demo" data.
 */
object DemoDataGenerator {

    private val londonZone = ZoneId.of("Europe/London")

    /** Synthetic standing charge in pence per day (typical UK value). */
    const val DEMO_STANDING_CHARGE_PENCE_PER_DAY = 50.0

    /**
     * Generate demo [HalfHourPoint]s covering [start] to [end].
     * Real Agile prices from the API can be merged on top afterwards.
     */
    fun generate(start: Instant, end: Instant): List<HalfHourPoint> {
        val points = mutableListOf<HalfHourPoint>()
        var t = start
        while (t.isBefore(end)) {
            val next = t.plus(30, ChronoUnit.MINUTES)
            val zdt = t.atZone(londonZone)
            val hour = zdt.hour
            val dayOfWeek = zdt.dayOfWeek.value // 1=Mon, 7=Sun
            val isWeekend = dayOfWeek >= 6

            // Deterministic RNG per slot — same instant always yields same data
            val rng = Random(t.epochSecond)

            val consumption = generateConsumption(hour, isWeekend, rng)
            val price = generatePrice(hour, rng)
            val cost = roundTo(price * consumption, 2)

            points.add(
                HalfHourPoint(
                    intervalStart = t,
                    intervalEnd = next,
                    priceIncVat = price,
                    consumptionKwh = consumption,
                    costIncVat = cost
                )
            )
            t = next
        }
        return points
    }

    /**
     * Merge demo points with real Agile prices where available.
     * Real prices replace the demo-generated ones and cost is recomputed.
     */
    fun mergeWithRealPrices(
        demoPoints: List<HalfHourPoint>,
        realPrices: List<com.chymaster.octopusagiledashboard.domain.model.AgilePrice>
    ): List<HalfHourPoint> {
        val priceMap = realPrices.associateBy { it.validFrom }
        return demoPoints.map { demo ->
            val realPrice = priceMap[demo.intervalStart]
            if (realPrice != null) {
                demo.copy(
                    priceIncVat = realPrice.priceIncVat,
                    costIncVat = roundTo(realPrice.priceIncVat * (demo.consumptionKwh ?: 0.0), 2)
                )
            } else {
                demo
            }
        }
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

    private fun generatePrice(hour: Int, rng: Random): Double {
        val basePrice = 25.0
        // Sinusoidal: cheapest ~4am, most expensive ~4pm
        val timeWave = 10.0 * sin((hour - 4) * PI / 12)
        val noise = (rng.nextDouble() - 0.5) * 8.0
        return roundTo((basePrice + timeWave + noise).coerceIn(5.0, 45.0), 2)
    }

    private fun roundTo(value: Double, decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return (value * factor).roundToInt() / factor
    }
}
