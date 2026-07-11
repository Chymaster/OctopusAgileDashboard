package com.chymaster.octopusagiledashboard.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dual-axis chart that shows usage bars (stacked green/amber/red by price zone,
 * LEFT Y axis in kWh) with an average-price line overlay (RIGHT Y axis in
 * pence/kWh) on a shared time axis.
 *
 * Zone classification uses [referencePrice] (the Flexible Octopus tariff) and
 * the user-configurable [cheapThresholdPercent]/[moderateThresholdPercent]
 * thresholds from settings.
 */
@Composable
fun PriceUsageChart(
    points: List<HalfHourPoint>,
    referencePrice: Double?,
    cheapThresholdPercent: Int = PriceColors.DEFAULT_CHEAP_PERCENT,
    moderateThresholdPercent: Int = PriceColors.DEFAULT_MODERATE_PERCENT,
    onPointTapped: (BinnedPoint?) -> Unit,
    modifier: Modifier = Modifier,
    useCalendarMonthBinning: Boolean = false,
    useCalendarDayBinning: Boolean = false,
) {
    if (points.isEmpty()) return

    // Trim edge intervals that have no consumption data.
    val trimmedPoints = remember(points) { points.trimMissingConsumption() }
    if (trimmedPoints.isEmpty()) return

    // Auto-bin to keep bar count ≤ 20, or use calendar-month binning for 6M/1Y ranges.
    // Zone-aware overloads classify each half-hour point and accumulate per-zone consumption.
    val binnedPoints = remember(
        trimmedPoints, useCalendarMonthBinning, useCalendarDayBinning, referencePrice,
        cheapThresholdPercent, moderateThresholdPercent
    ) {
        if (useCalendarDayBinning)
            binPointsByCalendarDay(trimmedPoints, referencePrice, cheapThresholdPercent, moderateThresholdPercent)
        else if (useCalendarMonthBinning)
            binPointsByCalendarMonth(trimmedPoints, referencePrice, cheapThresholdPercent, moderateThresholdPercent)
        else
            binPoints(trimmedPoints, referencePrice, cheapThresholdPercent, moderateThresholdPercent)
    }
    val useBinned = binnedPoints.size < trimmedPoints.size
    val displayData = if (useBinned) binnedPoints else null

    val londonZone = ZoneId.of("Europe/London")
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    val dayFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.UK)
    val monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.UK)

    // Build ChartBar list — bars now represent total consumption per interval
    // with stacked green/amber/red segments for the zone breakdown.
    val bars = remember(displayData, trimmedPoints, useBinned, useCalendarMonthBinning, useCalendarDayBinning) {
        if (useBinned && displayData != null) {
            displayData.map { bin ->
                val label = when {
                    useCalendarMonthBinning ->
                        bin.intervalStart.atZone(londonZone).format(monthFormatter)
                    useCalendarDayBinning ->
                        bin.intervalStart.atZone(londonZone).format(dayFormatter)
                    else ->
                        bin.intervalStart.atZone(londonZone).format(timeFormatter)
                }
                ChartBar(
                    label = label,
                    value = bin.totalConsumption ?: 0.0,
                    intervalStart = bin.intervalStart,
                    intervalEnd = bin.intervalEnd,
                    greenSegment = bin.greenConsumption,
                    amberSegment = bin.amberConsumption,
                    redSegment = bin.redConsumption,
                )
            }
        } else {
            trimmedPoints.map { p ->
                val kwh = p.consumptionKwh ?: 0.0
                val zone = PriceColors.priceColor(
                    p.priceIncVat ?: 0.0, referencePrice,
                    cheapThresholdPercent, moderateThresholdPercent
                )
                ChartBar(
                    label = p.intervalStart.atZone(londonZone).format(timeFormatter),
                    value = kwh,
                    intervalStart = p.intervalStart,
                    intervalEnd = p.intervalEnd,
                    greenSegment = if (zone == PriceColors.Cheap) kwh else 0.0,
                    amberSegment = if (zone == PriceColors.Moderate) kwh else 0.0,
                    redSegment = if (zone == PriceColors.Expensive) kwh else 0.0,
                )
            }
        }
    }

    // Price overlay line values (swapped: was usage line, now price line)
    val priceLineValues = remember(displayData, trimmedPoints, useBinned) {
        if (useBinned && displayData != null) {
            displayData.map { it.avgPrice ?: 0.0 }
        } else {
            trimmedPoints.map { it.priceIncVat ?: 0.0 }
        }
    }

    // Compute Y ranges for consumption bars (left axis, always from zero)
    val consumptionMax = bars.maxOfOrNull { it.value } ?: 1.0
    val consumptionYMin = 0.0
    val consumptionYMax = consumptionMax * 1.1

    // Compute Y ranges for price overlay line (right axis)
    val priceYMin = remember(priceLineValues) {
        val min = priceLineValues.minOrNull() ?: 0.0
        if (min < 0) min * 1.1 else 0.0
    }
    val priceYMax = remember(priceLineValues) {
        (priceLineValues.maxOrNull() ?: 1.0) * 1.1
    }

    val isScrollable = false
    val priceLineColor = ChartColors.PriceLine

    // Single-color fallback for PriceLineChart backward compatibility.
    // Not used when bars have zone breakdown, but must be non-empty.
    val barColors = remember(bars) {
        bars.map { PriceColors.Moderate }
    }

    Column(modifier = modifier) {
        // Legend row — three zone swatches + price line indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendSwatch(
                color = PriceColors.Cheap,
                label = "Cheap",
                shape = LegendShape.Square,
            )
            Spacer(Modifier.width(10.dp))
            LegendSwatch(
                color = PriceColors.Moderate,
                label = "Moderate",
                shape = LegendShape.Square,
            )
            Spacer(Modifier.width(10.dp))
            LegendSwatch(
                color = PriceColors.Expensive,
                label = "Expensive",
                shape = LegendShape.Square,
            )
            Spacer(Modifier.width(14.dp))
            LegendSwatch(
                color = priceLineColor,
                label = "Price",
                shape = LegendShape.Circle,
            )
        }

        CanvasBarChart(
            bars = bars,
            barColors = barColors,
            yMin = consumptionYMin,
            yMax = consumptionYMax,
            isScrollable = isScrollable,
            onBarTapped = { idx ->
                if (useBinned && displayData != null) {
                    onPointTapped(displayData.getOrNull(idx))
                } else {
                    val p = trimmedPoints.getOrNull(idx)
                    onPointTapped(p?.let {
                        BinnedPoint(it.intervalStart, it.intervalEnd, it.priceIncVat, it.consumptionKwh, it.costIncVat, 1)
                    })
                }
            },
            usageValues = priceLineValues,
            usageYMin = priceYMin,
            usageYMax = priceYMax,
            usageLineColor = priceLineColor,
        )
    }
}

private enum class LegendShape { Square, Circle }

@Composable
private fun LegendSwatch(
    color: Color,
    label: String,
    shape: LegendShape,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (shape) {
            LegendShape.Square -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
            LegendShape.Circle -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
