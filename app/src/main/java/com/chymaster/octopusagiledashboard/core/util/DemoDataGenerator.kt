package com.chymaster.octopusagiledashboard.core.util

import com.chymaster.octopusagiledashboard.data.local.entity.AgilePriceEntity
import com.chymaster.octopusagiledashboard.data.local.entity.ConsumptionEntity
import com.chymaster.octopusagiledashboard.data.local.entity.StandingChargeEntity
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
 *
 * The generator has two output shapes:
 *  - [generate] returns the unified [HalfHourPoint] model (used for in-memory rendering
 *    and tests).
 *  - [generateConsumptionEntities] / [generateAgilePriceEntities] /
 *    [generateStandingChargeEntity] produce Room-shaped entities so the demo data can
 *    be cached in the in-memory [com.chymaster.octopusagiledashboard.data.local.DemoCacheStore]
 *    and flow through the same observe→refresh pipeline as the real API.
 */
object DemoDataGenerator {

    private val londonZone = ZoneId.of("Europe/London")

    /** Synthetic standing charge in pence per day (typical UK value). */
    const val DEMO_STANDING_CHARGE_PENCE_PER_DAY = 50.0

    /**
     * Validity window for the synthetic standing charge entity. Standing charges
     * don't have 30-minute slots, so we use a wide window to cover any query range
     * without needing a per-range entity.
     */
    private const val STANDING_CHARGE_VALIDITY_SECONDS = 10L * 365 * 24 * 3600

    /** VAT factor used to back out the ex-VAT figure from the inc-VAT figure. */
    private const val VAT_DIVISOR = 1.05

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
            val isWeekend = zdt.dayOfWeek.value >= 6

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
     * Generate demo [ConsumptionEntity] rows for [start] to [end] using the same
     * time-of-day profile as [generate]. Rows are tagged with the demo MPAN/serial
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
     * Generate demo [AgilePriceEntity] rows for [start] to [end] using the same
     * sinusoidal price model as [generate]. Rows are tagged with the demo tariff
     * code so they can be filtered out of any future real-mode query.
     */
    fun generateAgilePriceEntities(start: Instant, end: Instant): List<AgilePriceEntity> {
        val rows = mutableListOf<AgilePriceEntity>()
        var t = start
        while (t.isBefore(end)) {
            val next = t.plus(30, ChronoUnit.MINUTES)
            val zdt = t.atZone(londonZone)
            val hour = zdt.hour
            val rng = Random(t.epochSecond)
            val priceIncVat = generatePrice(hour, rng)
            val priceExcVat = roundTo(priceIncVat / VAT_DIVISOR, 4)
            rows.add(
                AgilePriceEntity(
                    validFrom = t.toEpochMilli(),
                    validTo = next.toEpochMilli(),
                    priceExcVat = priceExcVat,
                    priceIncVat = priceIncVat,
                    tariffCode = DemoIdentifiers.TARIFF
                )
            )
            t = next
        }
        return rows
    }

    /**
     * Generate a single [StandingChargeEntity] valid from epoch 0 to [now] plus
     * 10 years, so it covers any realistic query range without needing a per-range
     * entity. The single entity matches the real API's "open-ended" standing
     * charge pattern (see [com.chymaster.octopusagiledashboard.data.mapper.STANDING_CHARGE_FALLBACK_SECONDS]).
     */
    fun generateStandingChargeEntity(now: Instant): StandingChargeEntity {
        val valueIncVat = DEMO_STANDING_CHARGE_PENCE_PER_DAY
        return StandingChargeEntity(
            validFrom = 0L,
            validTo = now.plusSeconds(STANDING_CHARGE_VALIDITY_SECONDS).toEpochMilli(),
            valueExcVat = roundTo(valueIncVat / VAT_DIVISOR, 4),
            valueIncVat = valueIncVat,
            tariffCode = DemoIdentifiers.TARIFF
        )
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

/**
 * Sentinel identifiers for demo data held in the in-memory
 * [com.chymaster.octopusagiledashboard.data.local.DemoCacheStore]. They are not
 * used to filter data in the real data path (the demo store is bypassed when
 * credentials are present), but they are still set on every entity so that any
 * future mix-up is easy to spot in logs.
 */
object DemoIdentifiers {
    const val MPAN = "DEMO_MPAN"
    const val SERIAL = "DEMO_SERIAL"
    const val TARIFF = "DEMO_TARIFF"
}
