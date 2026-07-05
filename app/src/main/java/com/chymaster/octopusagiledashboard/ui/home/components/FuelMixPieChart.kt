package com.chymaster.octopusagiledashboard.ui.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chymaster.octopusagiledashboard.domain.model.FuelMix
import com.chymaster.octopusagiledashboard.domain.model.LOW_CARBON_FUELS
import com.chymaster.octopusagiledashboard.ui.theme.BrandColors

/**
 * Per-fuel colors used by the nested donut on the Home screen and the
 * detailed breakdown in the bottom sheet.
 */
internal val FUEL_COLORS: Map<String, Color> = mapOf(
    "gas" to Color(0xFF78909C),
    "coal" to Color(0xFF455A64),
    "nuclear" to Color(0xFF7E57C2),
    "wind" to Color(0xFF29B6F6),
    "solar" to Color(0xFFFFCA28),
    "hydro" to Color(0xFF26A69A),
    "biomass" to Color(0xFF8D6E63),
    "imports" to Color(0xFFAB47BC),
    "other" to Color(0xFFBDBDBD)
)

/**
 * Nested grid-mix donut: an **outer ring** shows the two-way low/high carbon
 * split and an **inner ring** breaks the mix down by individual fuel source.
 * Low-carbon fuels in the inner ring are always grouped under the green
 * section of the outer ring so the two layers read as one picture.
 *
 * Tapping anywhere opens the full detail sheet.
 */
@Composable
fun FuelMixPieChart(
    lowCarbonPercentage: Double,
    fuelMix: List<FuelMix>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Order fuels so low-carbon sources come first (aligned under the outer
    // ring's green segment) followed by high-carbon sources.
    val orderedFuelMix = remember(fuelMix) {
        val low = fuelMix.filter { it.fuel in LOW_CARBON_FUELS && it.percentage > 0.0 }
            .sortedByDescending { it.percentage }
        val high = fuelMix.filter { it.fuel !in LOW_CARBON_FUELS && it.percentage > 0.0 }
            .sortedByDescending { it.percentage }
        low + high
    }

    Column(
        modifier = modifier.clickable(
            role = Role.Button,
            onClickLabel = "Show grid mix details",
            onClick = onClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Grid Mix",
            style = MaterialTheme.typography.labelMedium,
            color = labelColor
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(150.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // ── Outer ring: low carbon vs high carbon ──
                val outerStroke = 3.dp.toPx()
                val outerDiameter = size.minDimension - outerStroke
                val outerTopLeft = Offset(
                    (size.width - outerDiameter) / 2f,
                    (size.height - outerDiameter) / 2f
                )
                val outerArcSize = Size(outerDiameter, outerDiameter)

                val lowSweep = (lowCarbonPercentage.coerceIn(0.0, 100.0) / 100f * 360f).toFloat()
                val highSweep = ((100.0 - lowCarbonPercentage).coerceIn(0.0, 100.0) / 100f * 360f).toFloat()

                if (lowSweep > 0f) {
                    drawArc(
                        color = BrandColors.LowCarbonGreen,
                        startAngle = -90f,
                        sweepAngle = lowSweep,
                        useCenter = false,
                        topLeft = outerTopLeft,
                        size = outerArcSize,
                        style = Stroke(width = outerStroke)
                    )
                }
                if (highSweep > 0f) {
                    drawArc(
                        color = BrandColors.HighCarbon,
                        startAngle = -90f + lowSweep,
                        sweepAngle = highSweep,
                        useCenter = false,
                        topLeft = outerTopLeft,
                        size = outerArcSize,
                        style = Stroke(width = outerStroke)
                    )
                }

                // ── Inner ring: per-fuel breakdown ──
                // Low-carbon fuels are listed first so their arcs sweep
                // under the green section of the outer ring.
                val innerStroke = 16.dp.toPx()
                val innerDiameter = outerDiameter - outerStroke - innerStroke - 6.dp.toPx()
                val innerTopLeft = Offset(
                    (size.width - innerDiameter) / 2f,
                    (size.height - innerDiameter) / 2f
                )
                val innerArcSize = Size(innerDiameter, innerDiameter)

                var innerStartAngle = -90f
                for (entry in orderedFuelMix) {
                    val sweepAngle = (entry.percentage / 100f * 360f).toFloat()
                    if (sweepAngle > 0f) {
                        drawArc(
                            color = FUEL_COLORS[entry.fuel] ?: Color(0xFFBDBDBD),
                            startAngle = innerStartAngle,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = innerTopLeft,
                            size = innerArcSize,
                            style = Stroke(width = innerStroke)
                        )
                    }
                    innerStartAngle += sweepAngle
                }
            }

            // Centre label: low-carbon percentage
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = String.format(java.util.Locale.UK, "%.0f%%", lowCarbonPercentage),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.LowCarbonGreen,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Low carbon",
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Tappable hint — explicit so users know the chart opens a detail view
        // rather than being purely decorative.
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.TouchApp,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "Tap for details",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
        }
    }
}
