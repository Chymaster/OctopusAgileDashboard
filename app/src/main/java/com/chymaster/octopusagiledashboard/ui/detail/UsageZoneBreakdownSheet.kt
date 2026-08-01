package com.chymaster.octopusagiledashboard.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors

@Composable
fun UsageZoneBreakdownSheet(
    visible: Boolean,
    greenKwh: Double,
    amberKwh: Double,
    redKwh: Double,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val totalKwh = greenKwh + amberKwh + redKwh

    val zones = remember(greenKwh, amberKwh, redKwh) {
        val raw = listOf(
            Triple("Cheap", PriceColors.Cheap, greenKwh),
            Triple("Moderate", PriceColors.Moderate, amberKwh),
            Triple("Expensive", PriceColors.Expensive, redKwh)
        ).filter { it.third > 0.0 }

        if (raw.isEmpty() || totalKwh <= 0.0) return@remember emptyList()

        // Displayed percentages that sum to exactly 100 (largest-remainder
        // method), so the legend never shows e.g. 60% + 36% + 3% = 99% because
        // each share was rounded independently. The donut still uses the raw
        // double percentage so its arcs match the true proportions.
        val rawPct = raw.map { it.third / totalKwh * 100.0 }
        val floored = rawPct.map { kotlin.math.floor(it).toInt() }
        var remainder = 100 - floored.sum()
        val order = raw.indices.sortedByDescending { rawPct[it] - floored[it] }
        val displayPct = floored.toMutableList()
        var idx = 0
        while (remainder > 0) {
            displayPct[order[idx % order.size]] += 1
            remainder--
            idx++
        }

        raw.mapIndexed { i, (label, color, kwh) ->
            ZoneEntry(label, color, rawPct[i], displayPct[i], kwh)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header row with title and close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Usage Zone Breakdown",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Donut pie chart
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 28.dp.toPx()
                    val diameter = size.minDimension - strokeWidth
                    val topLeft = Offset(
                        (size.width - diameter) / 2f,
                        (size.height - diameter) / 2f
                    )
                    val arcSize = Size(diameter, diameter)

                    var startAngle = -90f
                    for (zone in zones) {
                        val sweepAngle = (zone.percentage / 100f * 360f).toFloat()
                        if (sweepAngle > 0f) {
                            drawArc(
                                color = zone.color,
                                startAngle = startAngle,
                                sweepAngle = sweepAngle,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth)
                            )
                        }
                        startAngle += sweepAngle
                    }
                }

                // Center label
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = String.format(java.util.Locale.UK, "%.1f", totalKwh),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "kWh",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Legend
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (zone in zones) {
                    ZoneLegendRow(
                        color = zone.color,
                        label = zone.label,
                        displayPct = zone.displayPct,
                        kwh = zone.kwh
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoneLegendRow(
    color: Color,
    label: String,
    displayPct: Int,
    kwh: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(10.dp),
            shape = CircleShape,
            color = color
        ) {}
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format(java.util.Locale.UK, "%d%%", displayPct),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = String.format(java.util.Locale.UK, "%.1f kWh", kwh),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class ZoneEntry(
    val label: String,
    val color: Color,
    val percentage: Double,
    val displayPct: Int,
    val kwh: Double
)
