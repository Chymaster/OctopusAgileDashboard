package com.chymaster.octopusagiledashboard.ui.chart

import androidx.compose.ui.graphics.Color
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A single aggregated data point for chart display in compacted mode.
 */
data class BinnedPoint(
    val intervalStart: Instant,
    val intervalEnd: Instant,
    val avgPrice: Double?,
    val totalConsumption: Double?,
    val totalCost: Double?,
    val pointCount: Int = 1,
    /** Consumption (kWh) within this bin that fell in each price zone. */
    val greenConsumption: Double = 0.0,
    val amberConsumption: Double = 0.0,
    val redConsumption: Double = 0.0,
    /** Cost (pence) within this bin that fell in each price zone. */
    val greenCost: Double = 0.0,
    val amberCost: Double = 0.0,
    val redCost: Double = 0.0,
)

/**
 * Trims leading and trailing [HalfHourPoint] entries that have no consumption
 * data. The Octopus API frequently returns price data for intervals where
 * consumption has not yet been recorded (typically the first and last
 * half-hour slots), which causes the usage line to dip to zero at the edges
 * of the chart. This function removes those edge gaps while preserving any
 * interior nulls (which represent genuine missing data).
 */
fun List<HalfHourPoint>.trimMissingConsumption(): List<HalfHourPoint> {
    if (isEmpty()) return this
    // Only trim if there is at least one point with positive consumption —
    // otherwise the entire series has no usage data and trimming is pointless.
    if (none { (it.consumptionKwh ?: 0.0) > 0.0 }) return this
    val firstWithData = indexOfFirst { (it.consumptionKwh ?: 0.0) > 0.0 }
    val lastWithData = indexOfLast { (it.consumptionKwh ?: 0.0) > 0.0 }
    return subList(firstWithData, lastWithData + 1)
}

/**
 * Bins half-hour points into larger time intervals for compact chart display.
 * Automatically picks the bin size that keeps bar count closest to 20 without
 * exceeding it. If the raw count is already ≤ 20, no binning is performed.
 *
 * For each bin: price is averaged, consumption and cost are summed.
 */
fun binPoints(points: List<HalfHourPoint>): List<BinnedPoint> {
    if (points.isEmpty()) return emptyList()
    if (points.size <= 20) {
        return points.map { BinnedPoint(it.intervalStart, it.intervalEnd, it.priceIncVat, it.consumptionKwh, it.costIncVat, 1) }
    }
    val binSeconds = optimalBinSeconds(points.size)
    return binPoints(points, binSeconds)
}

/**
 * Available bin durations in ascending order (seconds).
 */
private val BIN_DURATIONS_SECONDS = longArrayOf(
    1800L,          // 30m
    3600L,          // 1h
    7200L,          // 2h
    14400L,         // 4h
    43200L,         // 12h
    86400L,         // 1d
    259200L,        // 3d
    604800L,        // 7d
    1209600L,       // 14d
    2592000L,       // 1M
    5184000L,       // 2M
    15724800L,      // 6M
    31536000L,      // 1Y
    63072000L,      // 2Y
    157680000L,     // 5Y
)

/**
 * Picks the smallest bin duration (in seconds) that brings the bar count
 * at or below [targetBars] (default 20). Returns the raw half-hour interval
 * (1800 s) if even that fits.
 */
fun optimalBinSeconds(pointCount: Int, targetBars: Int = 20): Long {
    if (pointCount <= targetBars) return 1800L
    val minBinHalfHours = (pointCount + targetBars - 1) / targetBars  // ceiling division
    val minBinSeconds = minBinHalfHours * 1800L
    return BIN_DURATIONS_SECONDS.firstOrNull { it >= minBinSeconds }
        ?: BIN_DURATIONS_SECONDS.last()
}

/**
 * Bins half-hour points using an explicit [binDurationSeconds] duration.
 * If [binDurationSeconds] is less than or equal to the raw interval (1800 s),
 * returns each point wrapped as a single-item [BinnedPoint].
 */
fun binPoints(points: List<HalfHourPoint>, binDurationSeconds: Long): List<BinnedPoint> {
    if (points.isEmpty()) return emptyList()
    if (binDurationSeconds <= 1800L) {
        return points.map { BinnedPoint(it.intervalStart, it.intervalEnd, it.priceIncVat, it.consumptionKwh, it.costIncVat, 1) }
    }

    data class BinAccumulator(
        val binStart: Long,
        val prices: MutableList<Double> = mutableListOf(),
        var consumption: Double = 0.0,
        var cost: Double = 0.0,
        var count: Int = 0
    )

    val bins = linkedMapOf<Long, BinAccumulator>()

    for (point in points) {
        val epochSecond = point.intervalStart.epochSecond
        val binKey = (epochSecond / binDurationSeconds) * binDurationSeconds
        val bin = bins.getOrPut(binKey) { BinAccumulator(binKey) }

        point.priceIncVat?.let { bin.prices.add(it) }
        bin.consumption += point.consumptionKwh ?: 0.0
        bin.cost += point.costIncVat ?: 0.0
        bin.count++
    }

    return bins.values.map { bin ->
        val start = Instant.ofEpochSecond(bin.binStart)
        val end = Instant.ofEpochSecond(bin.binStart + binDurationSeconds)
        BinnedPoint(
            intervalStart = start,
            intervalEnd = end,
            avgPrice = bin.prices.takeIf { it.isNotEmpty() }?.average(),
            totalConsumption = bin.consumption.takeIf { it != 0.0 },
            totalCost = bin.cost.takeIf { it != 0.0 },
            pointCount = bin.count
        )
    }
}

/**
 * Zone-aware version of [binPoints] that also classifies each half-hour point
 * into a price zone (green/amber/red) and accumulates per-zone consumption.
 * The [referencePrice] and [cheapPercent]/[moderatePercent] thresholds define
 * the zone boundaries using [PriceColors.priceColor].
 */
fun binPoints(
    points: List<HalfHourPoint>,
    binDurationSeconds: Long,
    referencePrice: Double?,
    cheapPercent: Int = PriceColors.DEFAULT_CHEAP_PERCENT,
    moderatePercent: Int = PriceColors.DEFAULT_MODERATE_PERCENT
): List<BinnedPoint> {
    if (points.isEmpty()) return emptyList()
    if (binDurationSeconds <= 1800L) {
        return points.map { p ->
            val kwh = p.consumptionKwh ?: 0.0
            val zone = PriceColors.priceColor(
                p.priceIncVat ?: 0.0, referencePrice, cheapPercent, moderatePercent
            )
            BinnedPoint(
                intervalStart = p.intervalStart,
                intervalEnd = p.intervalEnd,
                avgPrice = p.priceIncVat,
                totalConsumption = p.consumptionKwh,
                totalCost = p.costIncVat,
                pointCount = 1,
                greenConsumption = if (zone == PriceColors.Cheap) kwh else 0.0,
                amberConsumption = if (zone == PriceColors.Moderate) kwh else 0.0,
                redConsumption = if (zone == PriceColors.Expensive) kwh else 0.0,
                greenCost = if (zone == PriceColors.Cheap) (p.costIncVat ?: 0.0) else 0.0,
                amberCost = if (zone == PriceColors.Moderate) (p.costIncVat ?: 0.0) else 0.0,
                redCost = if (zone == PriceColors.Expensive) (p.costIncVat ?: 0.0) else 0.0,
            )
        }
    }

    data class ZoneAccumulator(
        val binStart: Long,
        val prices: MutableList<Double> = mutableListOf(),
        var consumption: Double = 0.0,
        var cost: Double = 0.0,
        var count: Int = 0,
        var greenKwh: Double = 0.0,
        var amberKwh: Double = 0.0,
        var redKwh: Double = 0.0,
        var greenCost: Double = 0.0,
        var amberCost: Double = 0.0,
        var redCost: Double = 0.0,
    )

    val bins = linkedMapOf<Long, ZoneAccumulator>()

    for (point in points) {
        val epochSecond = point.intervalStart.epochSecond
        val binKey = (epochSecond / binDurationSeconds) * binDurationSeconds
        val bin = bins.getOrPut(binKey) { ZoneAccumulator(binKey) }

        point.priceIncVat?.let { bin.prices.add(it) }
        val kwh = point.consumptionKwh ?: 0.0
        bin.consumption += kwh
        bin.cost += point.costIncVat ?: 0.0
        bin.count++

        val price = point.priceIncVat
        val pointCost = point.costIncVat ?: 0.0
        if (price != null) {
            when (PriceColors.priceColor(price, referencePrice, cheapPercent, moderatePercent)) {
                PriceColors.Cheap -> {
                    bin.greenKwh += kwh
                    bin.greenCost += pointCost
                }
                PriceColors.Moderate -> {
                    bin.amberKwh += kwh
                    bin.amberCost += pointCost
                }
                PriceColors.Expensive -> {
                    bin.redKwh += kwh
                    bin.redCost += pointCost
                }
            }
        } else {
            // No price data — assign to amber (the fallback zone)
            bin.amberKwh += kwh
            bin.amberCost += pointCost
        }
    }

    return bins.values.map { bin ->
        val start = Instant.ofEpochSecond(bin.binStart)
        val end = Instant.ofEpochSecond(bin.binStart + binDurationSeconds)
        BinnedPoint(
            intervalStart = start,
            intervalEnd = end,
            avgPrice = bin.prices.takeIf { it.isNotEmpty() }?.average(),
            totalConsumption = bin.consumption.takeIf { it != 0.0 },
            totalCost = bin.cost.takeIf { it != 0.0 },
            pointCount = bin.count,
            greenConsumption = bin.greenKwh,
            amberConsumption = bin.amberKwh,
            redConsumption = bin.redKwh,
            greenCost = bin.greenCost,
            amberCost = bin.amberCost,
            redCost = bin.redCost,
        )
    }
}

/**
 * Zone-aware auto-binning overload. Picks the optimal bin duration via
 * [optimalBinSeconds] so bar count stays ≤ 20, then delegates to the
 * zone-aware explicit-duration overload.
 */
fun binPoints(
    points: List<HalfHourPoint>,
    referencePrice: Double?,
    cheapPercent: Int = PriceColors.DEFAULT_CHEAP_PERCENT,
    moderatePercent: Int = PriceColors.DEFAULT_MODERATE_PERCENT
): List<BinnedPoint> {
    if (points.isEmpty()) return emptyList()
    if (points.size <= 20) {
        return binPoints(points, 1800L, referencePrice, cheapPercent, moderatePercent)
    }
    val binSeconds = optimalBinSeconds(points.size)
    return binPoints(points, binSeconds, referencePrice, cheapPercent, moderatePercent)
}

/**
 * Bins half-hour points by calendar month. Each bin spans from the 1st of the
 * month at 00:00 to the last day at 23:30 (London time). Price is averaged,
 * consumption and cost are summed.
 *
 * Used for 6M and 1Y date ranges so each bar represents a natural calendar month.
 */
fun binPointsByCalendarMonth(points: List<HalfHourPoint>): List<BinnedPoint> {
    if (points.isEmpty()) return emptyList()
    val londonZone = ZoneId.of("Europe/London")

    data class MonthAccumulator(
        val yearMonth: YearMonth,
        val prices: MutableList<Double> = mutableListOf(),
        var consumption: Double = 0.0,
        var cost: Double = 0.0,
        var count: Int = 0
    )

    val months = linkedMapOf<YearMonth, MonthAccumulator>()

    for (point in points) {
        val zoned = point.intervalStart.atZone(londonZone)
        val yearMonth = YearMonth.from(zoned)
        val acc = months.getOrPut(yearMonth) { MonthAccumulator(yearMonth) }
        point.priceIncVat?.let { acc.prices.add(it) }
        acc.consumption += point.consumptionKwh ?: 0.0
        acc.cost += point.costIncVat ?: 0.0
        acc.count++
    }

    return months.values.map { acc ->
        val firstDay = acc.yearMonth.atDay(1)
        val lastDay = acc.yearMonth.atEndOfMonth()
        val intervalStart = firstDay.atStartOfDay(londonZone).toInstant()
        val intervalEnd = lastDay.atTime(23, 30).atZone(londonZone).toInstant()
        BinnedPoint(
            intervalStart = intervalStart,
            intervalEnd = intervalEnd,
            avgPrice = acc.prices.takeIf { it.isNotEmpty() }?.average(),
            totalConsumption = acc.consumption.takeIf { it != 0.0 },
            totalCost = acc.cost.takeIf { it != 0.0 },
            pointCount = acc.count
        )
    }
}

/**
 * Zone-aware version of [binPointsByCalendarMonth] that classifies each
 * half-hour point into a price zone and accumulates per-zone consumption.
 */
fun binPointsByCalendarMonth(
    points: List<HalfHourPoint>,
    referencePrice: Double?,
    cheapPercent: Int = PriceColors.DEFAULT_CHEAP_PERCENT,
    moderatePercent: Int = PriceColors.DEFAULT_MODERATE_PERCENT
): List<BinnedPoint> {
    if (points.isEmpty()) return emptyList()
    val londonZone = ZoneId.of("Europe/London")

    data class ZoneMonthAccumulator(
        val yearMonth: YearMonth,
        val prices: MutableList<Double> = mutableListOf(),
        var consumption: Double = 0.0,
        var cost: Double = 0.0,
        var count: Int = 0,
        var greenKwh: Double = 0.0,
        var amberKwh: Double = 0.0,
        var redKwh: Double = 0.0,
        var greenCost: Double = 0.0,
        var amberCost: Double = 0.0,
        var redCost: Double = 0.0,
    )

    val months = linkedMapOf<YearMonth, ZoneMonthAccumulator>()

    for (point in points) {
        val zoned = point.intervalStart.atZone(londonZone)
        val yearMonth = YearMonth.from(zoned)
        val acc = months.getOrPut(yearMonth) { ZoneMonthAccumulator(yearMonth) }
        point.priceIncVat?.let { acc.prices.add(it) }
        val kwh = point.consumptionKwh ?: 0.0
        acc.consumption += kwh
        acc.cost += point.costIncVat ?: 0.0
        acc.count++

        val price = point.priceIncVat
        val pointCost = point.costIncVat ?: 0.0
        if (price != null) {
            when (PriceColors.priceColor(price, referencePrice, cheapPercent, moderatePercent)) {
                PriceColors.Cheap -> {
                    acc.greenKwh += kwh
                    acc.greenCost += pointCost
                }
                PriceColors.Moderate -> {
                    acc.amberKwh += kwh
                    acc.amberCost += pointCost
                }
                PriceColors.Expensive -> {
                    acc.redKwh += kwh
                    acc.redCost += pointCost
                }
            }
        } else {
            acc.amberKwh += kwh
            acc.amberCost += pointCost
        }
    }

    return months.values.map { acc ->
        val firstDay = acc.yearMonth.atDay(1)
        val lastDay = acc.yearMonth.atEndOfMonth()
        val intervalStart = firstDay.atStartOfDay(londonZone).toInstant()
        val intervalEnd = lastDay.atTime(23, 30).atZone(londonZone).toInstant()
        BinnedPoint(
            intervalStart = intervalStart,
            intervalEnd = intervalEnd,
            avgPrice = acc.prices.takeIf { it.isNotEmpty() }?.average(),
            totalConsumption = acc.consumption.takeIf { it != 0.0 },
            totalCost = acc.cost.takeIf { it != 0.0 },
            pointCount = acc.count,
            greenConsumption = acc.greenKwh,
            amberConsumption = acc.amberKwh,
            redConsumption = acc.redKwh,
            greenCost = acc.greenCost,
            amberCost = acc.amberCost,
            redCost = acc.redCost,
        )
    }
}

/**
 * Bins half-hour points by calendar day. Each bin spans from 00:00 to 23:30
 * (London time). Price is averaged, consumption and cost are summed.
 *
 * Used for the 7D date range so each bar represents a natural calendar day.
 */
fun binPointsByCalendarDay(points: List<HalfHourPoint>): List<BinnedPoint> {
    if (points.isEmpty()) return emptyList()
    val londonZone = ZoneId.of("Europe/London")

    data class DayAccumulator(
        val date: LocalDate,
        val prices: MutableList<Double> = mutableListOf(),
        var consumption: Double = 0.0,
        var cost: Double = 0.0,
        var count: Int = 0
    )

    val days = linkedMapOf<LocalDate, DayAccumulator>()

    for (point in points) {
        val zoned = point.intervalStart.atZone(londonZone)
        val date = zoned.toLocalDate()
        val acc = days.getOrPut(date) { DayAccumulator(date) }
        point.priceIncVat?.let { acc.prices.add(it) }
        acc.consumption += point.consumptionKwh ?: 0.0
        acc.cost += point.costIncVat ?: 0.0
        acc.count++
    }

    return days.values.map { acc ->
        val intervalStart = acc.date.atStartOfDay(londonZone).toInstant()
        val intervalEnd = acc.date.atTime(23, 30).atZone(londonZone).toInstant()
        BinnedPoint(
            intervalStart = intervalStart,
            intervalEnd = intervalEnd,
            avgPrice = acc.prices.takeIf { it.isNotEmpty() }?.average(),
            totalConsumption = acc.consumption.takeIf { it != 0.0 },
            totalCost = acc.cost.takeIf { it != 0.0 },
            pointCount = acc.count
        )
    }
}

/**
 * Zone-aware version of [binPointsByCalendarDay] that classifies each
 * half-hour point into a price zone and accumulates per-zone consumption.
 */
fun binPointsByCalendarDay(
    points: List<HalfHourPoint>,
    referencePrice: Double?,
    cheapPercent: Int = PriceColors.DEFAULT_CHEAP_PERCENT,
    moderatePercent: Int = PriceColors.DEFAULT_MODERATE_PERCENT
): List<BinnedPoint> {
    if (points.isEmpty()) return emptyList()
    val londonZone = ZoneId.of("Europe/London")

    data class ZoneDayAccumulator(
        val date: LocalDate,
        val prices: MutableList<Double> = mutableListOf(),
        var consumption: Double = 0.0,
        var cost: Double = 0.0,
        var count: Int = 0,
        var greenKwh: Double = 0.0,
        var amberKwh: Double = 0.0,
        var redKwh: Double = 0.0,
        var greenCost: Double = 0.0,
        var amberCost: Double = 0.0,
        var redCost: Double = 0.0,
    )

    val days = linkedMapOf<LocalDate, ZoneDayAccumulator>()

    for (point in points) {
        val zoned = point.intervalStart.atZone(londonZone)
        val date = zoned.toLocalDate()
        val acc = days.getOrPut(date) { ZoneDayAccumulator(date) }
        point.priceIncVat?.let { acc.prices.add(it) }
        val kwh = point.consumptionKwh ?: 0.0
        acc.consumption += kwh
        acc.cost += point.costIncVat ?: 0.0
        acc.count++

        val price = point.priceIncVat
        val pointCost = point.costIncVat ?: 0.0
        if (price != null) {
            when (PriceColors.priceColor(price, referencePrice, cheapPercent, moderatePercent)) {
                PriceColors.Cheap -> {
                    acc.greenKwh += kwh
                    acc.greenCost += pointCost
                }
                PriceColors.Moderate -> {
                    acc.amberKwh += kwh
                    acc.amberCost += pointCost
                }
                PriceColors.Expensive -> {
                    acc.redKwh += kwh
                    acc.redCost += pointCost
                }
            }
        } else {
            acc.amberKwh += kwh
            acc.amberCost += pointCost
        }
    }

    return days.values.map { acc ->
        val intervalStart = acc.date.atStartOfDay(londonZone).toInstant()
        val intervalEnd = acc.date.atTime(23, 30).atZone(londonZone).toInstant()
        BinnedPoint(
            intervalStart = intervalStart,
            intervalEnd = intervalEnd,
            avgPrice = acc.prices.takeIf { it.isNotEmpty() }?.average(),
            totalConsumption = acc.consumption.takeIf { it != 0.0 },
            totalCost = acc.cost.takeIf { it != 0.0 },
            pointCount = acc.count,
            greenConsumption = acc.greenKwh,
            amberConsumption = acc.amberKwh,
            redConsumption = acc.redKwh,
            greenCost = acc.greenCost,
            amberCost = acc.amberCost,
            redCost = acc.redCost,
        )
    }
}

/**
 * Formats a time label for a bin starting at [instant] with the given [binDurationSeconds].
 * For sub-day bins, shows HH:mm (with dd/MM at midnight crossings).
 * For day+ bins, always shows dd/MM.
 */
fun formatBinLabel(instant: Instant, binDurationSeconds: Long): String {
    val londonZone = ZoneId.of("Europe/London")
    val zoned = instant.atZone(londonZone)
    return if (binDurationSeconds >= 24 * 3600L) {
        zoned.format(DateTimeFormatter.ofPattern("dd/MM", Locale.UK))
    } else {
        zoned.format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))
    }
}

/**
 * Formats a time label for a raw half-hour point, showing the date at midnight crossings.
 */
fun formatRawLabel(instant: Instant, prevInstant: Instant? = null): String {
    val londonZone = ZoneId.of("Europe/London")
    val zoned = instant.atZone(londonZone)
    val showDate = zoned.hour == 0 || (prevInstant != null && run {
        val prevZoned = prevInstant.atZone(londonZone)
        prevZoned.toLocalDate() != zoned.toLocalDate()
    })
    return if (showDate) {
        zoned.format(DateTimeFormatter.ofPattern("dd/MM\nHH:mm", Locale.UK))
    } else {
        zoned.format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))
    }
}

object ChartColors {
    val PriceLine = Color(0xFF6750A4)
    val PriceLineFill = Color(0x406750A4)
    val ConsumptionLine = Color(0xFF1976D2)
    val ConsumptionLineFill = Color(0x401976D2)
    val Marker = Color(0xFF6750A4)
}
