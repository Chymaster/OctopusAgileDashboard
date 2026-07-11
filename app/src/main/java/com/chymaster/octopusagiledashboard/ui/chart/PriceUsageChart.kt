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
 * Dual-axis chart that overlays price (bars, LEFT Y axis in pence/kWh)
 * and usage (line, RIGHT Y axis in kWh) on a shared time axis.
 *
 * Price bars are ratio-coloured against [referencePrice] (the Flexible Octopus
 * tariff): green if < 70 %, amber if 70–130 %, red if > 130 %. When
 * [referencePrice] is null the bars fall back to amber.
 */
@Composable
fun PriceUsageChart(
    points: List<HalfHourPoint>,
    referencePrice: Double?,
    onPointTapped: (BinnedPoint?) -> Unit,
    modifier: Modifier = Modifier,
    useCalendarMonthBinning: Boolean = false,
) {
    if (points.isEmpty()) return

    // Trim edge intervals that have no consumption data.
    val trimmedPoints = remember(points) { points.trimMissingConsumption() }
    if (trimmedPoints.isEmpty()) return

    // Auto-bin to keep bar count ≤ 20, or use calendar-month binning for 6M/1Y ranges
    val binnedPoints = remember(trimmedPoints, useCalendarMonthBinning) {
        if (useCalendarMonthBinning) binPointsByCalendarMonth(trimmedPoints)
        else binPoints(trimmedPoints)
    }
    val useBinned = binnedPoints.size < trimmedPoints.size
    val displayData = if (useBinned) binnedPoints else null

    val londonZone = ZoneId.of("Europe/London")
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    val monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.UK)

    // Build ChartBar list (price values for bars)
    val bars = remember(displayData, trimmedPoints, useBinned, useCalendarMonthBinning) {
        if (useBinned && displayData != null) {
            displayData.map { bin ->
                val label = if (useCalendarMonthBinning) {
                    bin.intervalStart.atZone(londonZone).format(monthFormatter)
                } else {
                    bin.intervalStart.atZone(londonZone).format(timeFormatter)
                }
                ChartBar(
                    label = label,
                    value = bin.avgPrice ?: 0.0,
                    intervalStart = bin.intervalStart,
                    intervalEnd = bin.intervalEnd
                )
            }
        } else {
            trimmedPoints.map { p ->
                ChartBar(
                    label = p.intervalStart.atZone(londonZone).format(timeFormatter),
                    value = p.priceIncVat ?: 0.0,
                    intervalStart = p.intervalStart,
                    intervalEnd = p.intervalEnd
                )
            }
        }
    }

    // Per-bar colors based on price ratio
    val barColors = remember(bars, referencePrice) {
        bars.map { bar ->
            PriceColors.priceColor(bar.value, referencePrice)
        }
    }

    // Usage line values
    val usageValues = remember(displayData, trimmedPoints, useBinned) {
        if (useBinned && displayData != null) {
            displayData.map { it.totalConsumption ?: 0.0 }
        } else {
            trimmedPoints.map { it.consumptionKwh ?: 0.0 }
        }
    }

    // Compute aligned Y ranges so zero sits at the same position on both axes
    val priceYs = bars.map { it.value }
    val (priceMin, priceMax, usageMin, usageMax) = remember(priceYs, usageValues) {
        computeAlignedRanges(priceYs, usageValues)
    }

    val isScrollable = false
    val usageLineColor = ChartColors.ConsumptionLine

    Column(modifier = modifier) {
        // Legend row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendSwatch(
                color = PriceColors.Moderate,
                label = "Price (p/kWh)",
                shape = LegendShape.Square,
            )
            Spacer(Modifier.width(16.dp))
            LegendSwatch(
                color = usageLineColor,
                label = "Usage (kWh)",
                shape = LegendShape.Circle,
            )
        }

        CanvasBarChart(
            bars = bars,
            barColors = barColors,
            yMin = priceMin,
            yMax = priceMax,
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
            usageValues = usageValues,
            usageYMin = usageMin,
            usageYMax = usageMax,
            usageLineColor = usageLineColor,
        )
    }
}

/**
 * Computes four aligned Y-axis values: (priceMin, priceMax, usageMin, usageMax)
 * such that zero sits at the same vertical fraction on both the price and usage
 * axes. This prevents non-negative usage from *looking* negative when prices
 * dip below zero.
 */
private fun computeAlignedRanges(
    priceYs: List<Double>,
    usageYs: List<Double>,
): AlignedRanges {
    if (priceYs.isEmpty() || usageYs.isEmpty()) {
        return AlignedRanges(0.0, 1.0, 0.0, 1.0)
    }

    val rawPriceMin = priceYs.min()
    val rawPriceMax = priceYs.max()
    val rawUsageMax = usageYs.max()

    // Price axis: ensure zero is always included with a small buffer.
    val priceRange = (rawPriceMax - rawPriceMin).coerceAtLeast(0.1)
    val pricePad = priceRange * 0.1
    val pMin = (rawPriceMin - pricePad).coerceAtMost(0.0)
    val pMax = (rawPriceMax + pricePad).coerceAtLeast(0.0)

    // Where does zero sit on the price axis? 0 = bottom, 1 = top.
    val priceZeroFraction = if (pMin < 0 && pMax > 0) {
        -pMin / (pMax - pMin)
    } else {
        // Price is entirely >= 0 — axes already align at the bottom edge.
        return AlignedRanges(pMin, pMax, 0.0, rawUsageMax * 1.1)
    }

    // Usage axis: extend below zero so its zero fraction matches the price axis.
    val usagePad = (rawUsageMax * 0.1).coerceAtLeast(0.05)
    val uMax = rawUsageMax + usagePad
    val uMin = if (uMax > 0) {
        -uMax * priceZeroFraction / (1.0 - priceZeroFraction)
    } else {
        0.0
    }

    return AlignedRanges(pMin, pMax, uMin, uMax)
}

private data class AlignedRanges(
    val priceMin: Double,
    val priceMax: Double,
    val usageMin: Double,
    val usageMax: Double,
)

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
