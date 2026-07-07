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
    modifier: Modifier = Modifier
) {
    var isZoomed by remember { mutableStateOf(false) }

    // Auto-bin to keep bar count ≤ 20
    val binnedPoints = remember(points) { binPoints(points) }
    val useBinned = !isZoomed && binnedPoints.size < points.size
    val displayData = if (useBinned) binnedPoints else null

    // Map to ChartBar list
    val londonZone = ZoneId.of("Europe/London")
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    val bars = remember(displayData, points, chartMode, useBinned) {
        if (useBinned && displayData != null) {
            displayData.map { bin ->
                val value = when (chartMode) {
                    ChartMode.PRICE -> bin.avgPrice ?: 0.0
                    ChartMode.CONSUMPTION -> bin.totalConsumption ?: 0.0
                    ChartMode.COST -> (bin.totalCost ?: 0.0) / 100.0
                }
                ChartBar(
                    label = bin.intervalStart.atZone(londonZone).format(timeFormatter),
                    value = value,
                    intervalStart = bin.intervalStart,
                    intervalEnd = bin.intervalEnd
                )
            }
        } else {
            points.map { p ->
                val value = when (chartMode) {
                    ChartMode.PRICE -> p.priceIncVat ?: 0.0
                    ChartMode.CONSUMPTION -> p.consumptionKwh ?: 0.0
                    ChartMode.COST -> (p.costIncVat ?: 0.0) / 100.0
                }
                ChartBar(
                    label = p.intervalStart.atZone(londonZone).format(timeFormatter),
                    value = value,
                    intervalStart = p.intervalStart,
                    intervalEnd = p.intervalEnd
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

    // Y range
    val yMin = remember(bars) {
        val min = bars.minOfOrNull { it.value } ?: 0.0
        if (min < 0) min * 1.1 else 0.0
    }
    val yMax = remember(bars) {
        (bars.maxOfOrNull { it.value } ?: 1.0) * 1.1
    }

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
