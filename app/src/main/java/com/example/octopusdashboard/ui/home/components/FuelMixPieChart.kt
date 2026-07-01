package com.example.octopusdashboard.ui.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.example.octopusdashboard.domain.model.FuelMix
import com.example.octopusdashboard.ui.theme.BrandColors

/** Colors for each fuel type, matching common UK grid representations. */
private val FUEL_COLORS = mapOf(
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FuelMixPieChart(
    fuelMix: List<FuelMix>,
    lowCarbonPercentage: Double,
    modifier: Modifier = Modifier
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val colors = remember(fuelMix) {
        fuelMix.map { entry ->
            FUEL_COLORS[entry.fuel] ?: Color(0xFFBDBDBD)
        }
    }

    Column(
        modifier = modifier,
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
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 28.dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f
                )
                val arcSize = Size(diameter, diameter)

                var startAngle = -90f // Start from top
                for (i in fuelMix.indices) {
                    val sweepAngle = (fuelMix[i].percentage / 100f * 360f).toFloat()
                    if (sweepAngle > 0f) {
                        drawArc(
                            color = colors[i],
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

        Spacer(modifier = Modifier.height(6.dp))

        // Legend - flow layout for compact display
        FlowRow(
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            for (i in fuelMix.indices) {
                if (fuelMix[i].percentage > 0.5) { // Only show fuels with >0.5%
                    LegendItem(
                        color = colors[i],
                        label = fuelMix[i].fuel.replaceFirstChar { it.uppercase() },
                        percentage = fuelMix[i].percentage
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendItem(
    color: Color,
    label: String,
    percentage: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(6.dp),
            shape = CircleShape,
            color = color
        ) {}
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = String.format(java.util.Locale.UK, "%s %.0f%%", label, percentage),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
