package com.chymaster.octopusagiledashboard.ui.chart

import androidx.compose.ui.graphics.Color
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import java.time.Duration
import java.time.Instant
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
    val pointCount: Int = 1
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
