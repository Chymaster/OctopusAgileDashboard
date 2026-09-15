package com.chymaster.octopusagiledashboard.ui.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ChartMode {
    PRICE, CONSUMPTION, COST
}

@Composable
fun PriceLineChart(
    points: List<HalfHourPoint>,
    chartMode: ChartMode,
    onPointTapped: (BinnedPoint?) -> Unit,
    modifier: Modifier = Modifier,
    useCalendarMonthBinning: Boolean = false,
    useCalendarDayBinning: Boolean = false,
    referencePrice: Double? = null,
    cheapThresholdPercent: Int = PriceColors.DEFAULT_CHEAP_PERCENT,
    moderateThresholdPercent: Int = PriceColors.DEFAULT_MODERATE_PERCENT,
) {
    // Reset zoom whenever the data changes (range switch / refresh) so the
    // chart returns to the binned overview instead of showing every half-hour
    // bar for a multi-month range.
    var isZoomed by remember(points) { mutableStateOf(false) }

    // Enable zone breakdown only for COST mode when a reference price is available.
    val enableZoneBreakdown = chartMode == ChartMode.COST && referencePrice != null

    // Auto-bin to keep bar count ≤ 20, or use calendar-month binning for 6M/1Y ranges.
    // Use zone-aware variants when zone breakdown is enabled.
    val binnedPoints = remember(
        points, useCalendarMonthBinning, useCalendarDayBinning,
        referencePrice, cheapThresholdPercent, moderateThresholdPercent
    ) {
        when {
            enableZoneBreakdown && useCalendarDayBinning ->
                binPointsByCalendarDay(points, referencePrice!!, cheapThresholdPercent, moderateThresholdPercent)
            enableZoneBreakdown && useCalendarMonthBinning ->
                binPointsByCalendarMonth(points, referencePrice!!, cheapThresholdPercent, moderateThresholdPercent)
            enableZoneBreakdown ->
                binPoints(points, referencePrice!!, cheapThresholdPercent, moderateThresholdPercent)
            useCalendarDayBinning -> binPointsByCalendarDay(points)
            useCalendarMonthBinning -> binPointsByCalendarMonth(points)
            else -> binPoints(points)
        }
    }
    val useBinned = !isZoomed && binnedPoints.size < points.size
    val displayData = if (useBinned) binnedPoints else null

    // Map to ChartBar list
    val londonZone = ZoneId.of("Europe/London")
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    val dayFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.UK)
    val monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.UK)

    val bars = remember(displayData, points, chartMode, useBinned, useCalendarMonthBinning, useCalendarDayBinning) {
        if (useBinned && displayData != null) {
            displayData.map { bin ->
                val value = when (chartMode) {
                    ChartMode.PRICE -> bin.avgPrice ?: 0.0
                    ChartMode.CONSUMPTION -> bin.totalConsumption ?: 0.0
                    ChartMode.COST -> (bin.totalCost ?: 0.0) / 100.0
                }
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
                    value = value,
                    intervalStart = bin.intervalStart,
                    intervalEnd = bin.intervalEnd,
                    greenSegment = if (enableZoneBreakdown) bin.greenCost / 100.0 else 0.0,
                    amberSegment = if (enableZoneBreakdown) bin.amberCost / 100.0 else 0.0,
                    redSegment = if (enableZoneBreakdown) bin.redCost / 100.0 else 0.0,
                    unitLabel = when (chartMode) {
                        ChartMode.COST -> "£"
                        ChartMode.PRICE -> "p"
                        ChartMode.CONSUMPTION -> "kWh"
                    },
                )
            }
        } else {
            points.map { p ->
                val value = when (chartMode) {
                    ChartMode.PRICE -> p.priceIncVat ?: 0.0
                    ChartMode.CONSUMPTION -> p.consumptionKwh ?: 0.0
                    ChartMode.COST -> (p.costIncVat ?: 0.0) / 100.0
                }
                // Compute per-zone cost segments for COST mode (pence → £)
                val gCost: Double
                val aCost: Double
                val rCost: Double
                if (enableZoneBreakdown) {
                    val costPounds = (p.costIncVat ?: 0.0) / 100.0
                    val price = p.priceIncVat
                    if (price != null) {
                        when (PriceColors.priceColor(price, referencePrice!!, cheapThresholdPercent, moderateThresholdPercent)) {
                            PriceColors.Cheap -> { gCost = costPounds; aCost = 0.0; rCost = 0.0 }
                            PriceColors.Moderate -> { gCost = 0.0; aCost = costPounds; rCost = 0.0 }
                            PriceColors.Expensive -> { gCost = 0.0; aCost = 0.0; rCost = costPounds }
                            else -> { gCost = 0.0; aCost = costPounds; rCost = 0.0 }
                        }
                    } else {
                        // No price data — assign cost to amber (the fallback zone)
                        gCost = 0.0; aCost = costPounds; rCost = 0.0
                    }
                } else {
                    gCost = 0.0; aCost = 0.0; rCost = 0.0
                }
                ChartBar(
                    label = p.intervalStart.atZone(londonZone).format(timeFormatter),
                    value = value,
                    intervalStart = p.intervalStart,
                    intervalEnd = p.intervalEnd,
                    greenSegment = gCost,
                    amberSegment = aCost,
                    redSegment = rCost,
                    unitLabel = when (chartMode) {
                        ChartMode.COST -> "£"
                        ChartMode.PRICE -> "p"
                        ChartMode.CONSUMPTION -> "kWh"
                    },
                )
            }
        }
    }

    // Per-bar colors
    val barColor = remember(chartMode) {
        when (chartMode) {
            ChartMode.PRICE -> ChartColors.PriceLine
            ChartMode.CONSUMPTION -> ChartColors.ConsumptionLine
            ChartMode.COST -> Color(0xFFE65100)
        }
    }
    val barColors = remember(bars, barColor) {
        bars.map { barColor }
    }

    // Y range — cover the full drawn extent of every bar (both the upward
    // positive stack and the downward negative stack), keeping zero inside so
    // the baseline is always visible. Zone bars' drawn span is
    // [sum of negative segments … sum of positive segments], not the net value.
    val (yMin, yMax) = remember(bars) { computeBarYRange(bars) }

    val isScrollable = isZoomed

    Column(modifier = modifier) {
        // Zoom toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = { isZoomed = !isZoomed },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (isZoomed)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Icon(
                    if (isZoomed) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
                    contentDescription = if (isZoomed) "Fit to screen" else "Zoom in"
                )
            }
        }

        CanvasBarChart(
            bars = bars,
            barColors = barColors,
            yMin = yMin,
            yMax = yMax,
            isScrollable = isScrollable,
            onBarTapped = { idx ->
                if (useBinned && displayData != null) {
                    onPointTapped(displayData.getOrNull(idx))
                } else {
                    val p = points.getOrNull(idx)
                    onPointTapped(p?.let {
                        BinnedPoint(it.intervalStart, it.intervalEnd, it.priceIncVat, it.consumptionKwh, it.costIncVat, 1)
                    })
                }
            }
        )
    }
}

/**
 * Computes the Y range (with 10% headroom) covering the full drawn extent of
 * [bars], keeping zero inside the range so the zero baseline is always visible.
 *
 * For zone-breakdown bars the drawn span is from the sum of the negative
 * segments to the sum of the positive segments (a mixed-sign bar can extend
 * both sides of zero); the net [ChartBar.value] alone is not enough. For plain
 * bars the span is simply [ChartBar.value].
 *
 * Returns `yMin to yMax`. Both ends are guarded so `yMax > 0` even for an
 * all-negative (or all-zero) series — otherwise the zero baseline collapses to
 * the top edge of the chart.
 */
internal fun computeBarYRange(bars: List<ChartBar>): Pair<Double, Double> {
    if (bars.isEmpty()) return 0.0 to 0.01
    val isZone = bars.any { it.hasZoneBreakdown }
    val min = if (isZone) {
        bars.minOfOrNull { bar ->
            listOf(0.0, bar.greenSegment, bar.amberSegment, bar.redSegment)
                .filter { it < 0.0 }.sum()
        } ?: 0.0
    } else {
        bars.minOfOrNull { it.value } ?: 0.0
    }
    val max = if (isZone) {
        bars.maxOfOrNull { bar ->
            listOf(0.0, bar.greenSegment, bar.amberSegment, bar.redSegment)
                .filter { it > 0.0 }.sum()
        } ?: 0.0
    } else {
        bars.maxOfOrNull { it.value } ?: 0.0
    }
    val yMin = if (min < 0) min * 1.1 else 0.0
    val yMax = if (max > 0) max * 1.1 else 0.01
    return yMin to yMax
}
