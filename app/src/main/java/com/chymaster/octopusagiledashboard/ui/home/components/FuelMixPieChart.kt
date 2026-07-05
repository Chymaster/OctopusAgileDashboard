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
import com.chymaster.octopusagiledashboard.ui.theme.BrandColors

/**
 * Per-fuel colors used by the detail sheet. The simplified pie chart on the
 * Home screen only uses [BrandColors.LowCarbonGreen] / [BrandColors.HighCarbon]
 * because the goal there is a glanceable two-way split; the sheet shows every
 * fuel in its own color.
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
 * Simplified grid-mix pie: two segments (low carbon vs high carbon) with a
 * centre percentage and a tappable surface that opens the detailed breakdown
 * in a sheet. Designed for the small card on the Home screen where the
 * full nine-fuel legend was being clipped.
 */
@Composable
fun FuelMixPieChart(
    lowCarbonPercentage: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

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
            modifier = Modifier.size(120.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 22.dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f
                )
                val arcSize = Size(diameter, diameter)

                // Two-segment donut: start at the top (-90°) and sweep
                // low-carbon first, then high-carbon. We render the low-carbon
                // arc and the high-carbon arc as two separate drawArc calls so
                // the colours are explicit and the centre label is legible.
                val lowSweep = (lowCarbonPercentage.coerceIn(0.0, 100.0) / 100f * 360f).toFloat()
                val highSweep = ((100.0 - lowCarbonPercentage).coerceIn(0.0, 100.0) / 100f * 360f).toFloat()

                if (lowSweep > 0f) {
                    drawArc(
                        color = BrandColors.LowCarbonGreen,
                        startAngle = -90f,
                        sweepAngle = lowSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth)
                    )
                }
                if (highSweep > 0f) {
                    drawArc(
                        color = BrandColors.HighCarbon,
                        startAngle = -90f + lowSweep,
                        sweepAngle = highSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = String.format(java.util.Locale.UK, "%.0f%%", lowCarbonPercentage),
                    style = MaterialTheme.typography.displaySmall,
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
