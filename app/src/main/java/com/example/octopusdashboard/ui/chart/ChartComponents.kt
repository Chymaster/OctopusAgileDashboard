package com.example.octopusdashboard.ui.chart

import androidx.compose.ui.graphics.Color
import com.example.octopusdashboard.domain.model.HalfHourPoint
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
 * Bins half-hour points into larger time intervals for compact chart display.
 * Chooses bin size based on the number of data points:
 *   ≤ 48  (1 day)    → no binning
 *   ≤ 168 (3.5 days) → 2-hour bins
 *   ≤ 720 (15 days)  → 6-hour bins
 *   ≤ 2000 (~6 weeks)→ 1-day bins
 *   > 2000            → 3-day bins
 *
 * For each bin: price is averaged, consumption and cost are summed.
 */
fun binPoints(points: List<HalfHourPoint>): List<BinnedPoint> {
    if (points.isEmpty()) return emptyList()

    val binSeconds = when {
        points.size <= 48 -> return points.map { BinnedPoint(it.intervalStart, it.intervalEnd, it.priceIncVat, it.consumptionKwh, it.costIncVat, 1) }
        points.size <= 168 -> 2 * 3600L    // 2 hours
        points.size <= 720 -> 6 * 3600L    // 6 hours
        points.size <= 2000 -> 24 * 3600L  // 1 day
        else -> 72 * 3600L                 // 3 days
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
        val binKey = (epochSecond / binSeconds) * binSeconds
        val bin = bins.getOrPut(binKey) { BinAccumulator(binKey) }

        point.priceIncVat?.let { bin.prices.add(it) }
        bin.consumption += point.consumptionKwh ?: 0.0
        bin.cost += point.costIncVat ?: 0.0
        bin.count++
    }

    return bins.values.map { bin ->
        val start = Instant.ofEpochSecond(bin.binStart)
        val end = Instant.ofEpochSecond(bin.binStart + binSeconds)
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

object ChartFormatters {

    private val londonZone = ZoneId.of("Europe/London")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.UK)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM\nHH:mm", Locale.UK)

    /**
     * Creates a time axis formatter that maps sequential indices to actual times.
     * Shows the date when the time crosses midnight (hour == 0).
     * @param points the list of HalfHourPoint, used to resolve index → time
     */
    fun timeAxisFormatter(points: List<HalfHourPoint>): CartesianValueFormatter {
        if (points.isEmpty()) {
            return CartesianValueFormatter { _, _, _ -> " " }
        }
        return CartesianValueFormatter { _, value, _ ->
            val index = value.toInt().coerceIn(0, points.size - 1)
            val zoned = points[index].intervalStart.atZone(londonZone)
            // Show date when crossing midnight (hour 0) or if previous point was on a different day
            val showDate = zoned.hour == 0 || (index > 0 && run {
                val prevZoned = points[index - 1].intervalStart.atZone(londonZone)
                prevZoned.toLocalDate() != zoned.toLocalDate()
            })
            if (showDate) zoned.format(dateTimeFormatter) else zoned.format(timeFormatter)
        }
    }

    val priceAxisFormatter = CartesianValueFormatter { _, value, _ ->
        String.format(Locale.UK, "%.1f p", value)
    }

    val consumptionAxisFormatter = CartesianValueFormatter { _, value, _ ->
        String.format(Locale.UK, "%.3f kWh", value)
    }

    val priceMarkerFormatter = DefaultCartesianMarker.ValueFormatter.default(suffix = " p/kWh")
    val consumptionMarkerFormatter = DefaultCartesianMarker.ValueFormatter.default(suffix = " kWh")

    /**
     * Creates a time axis formatter for binned data.
     * For sub-day bins, shows date at midnight crossings.
     * For day+ bins, always shows the date.
     */
    fun binnedTimeAxisFormatter(binnedPoints: List<BinnedPoint>): CartesianValueFormatter {
        if (binnedPoints.isEmpty()) {
            return CartesianValueFormatter { _, _, _ -> " " }
        }
        val binDuration = Duration.between(binnedPoints[0].intervalStart, binnedPoints[0].intervalEnd)
        val isDailyOrLarger = binDuration.toHours() >= 24
        return CartesianValueFormatter { _, value, _ ->
            val index = value.toInt().coerceIn(0, binnedPoints.size - 1)
            val zoned = binnedPoints[index].intervalStart.atZone(londonZone)
            if (isDailyOrLarger) {
                zoned.format(dateFormatter)
            } else {
                val showDate = zoned.hour == 0 || (index > 0 && run {
                    val prevZoned = binnedPoints[index - 1].intervalStart.atZone(londonZone)
                    prevZoned.toLocalDate() != zoned.toLocalDate()
                })
                if (showDate) zoned.format(dateTimeFormatter) else zoned.format(timeFormatter)
            }
        }
    }
}

object ChartColors {
    val PriceLine = Color(0xFF6750A4)
    val PriceLineFill = Color(0x406750A4)
    val ConsumptionLine = Color(0xFF386A20)
    val ConsumptionLineFill = Color(0x40386A20)
    val Marker = Color(0xFF6750A4)
}
