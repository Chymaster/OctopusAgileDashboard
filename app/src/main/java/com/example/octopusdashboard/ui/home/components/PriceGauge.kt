package com.example.octopusdashboard.ui.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.octopusdashboard.ui.theme.PriceColors
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun PriceGauge(
    currentPrice: Double?,
    referencePrice: Double?,
    modifier: Modifier = Modifier
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val needleColor = MaterialTheme.colorScheme.primary
    val referenceColor = MaterialTheme.colorScheme.tertiary
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface

    // Dynamically compute range so flexible price is always at the center
    val minPrice: Double
    val maxPrice: Double
    if (referencePrice != null) {
        val spread = listOfNotNull(currentPrice, referencePrice).let { prices ->
            val maxDist = prices.maxOf { kotlin.math.abs(it - referencePrice) }
            maxDist.coerceAtLeast(15.0) * 1.2
        }
        minPrice = (referencePrice - spread).coerceAtLeast(0.0)
        maxPrice = referencePrice + spread
    } else {
        minPrice = 0.0
        maxPrice = 40.0
    }

    // Threshold stops for colored arc segments
    val thresholds = listOf(
        PriceColors.CHEAP_THRESHOLD to PriceColors.Cheap,
        PriceColors.MODERATE_THRESHOLD to PriceColors.Moderate,
        PriceColors.EXPENSIVE_THRESHOLD to PriceColors.Expensive,
        maxPrice to PriceColors.VeryExpensive
    ).filter { it.first > minPrice }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Agile Price",
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .aspectRatio(1.2f),
            contentAlignment = Alignment.BottomCenter
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 20.dp.toPx()
                val arcSize = Size(
                    size.width - strokeWidth * 2,
                    size.height - strokeWidth * 2
                )
                val topLeft = Offset(strokeWidth, strokeWidth)

                val startAngle = 135f
                val totalSweep = 270f
                val range = maxPrice - minPrice

                // Draw colored arc segments
                var segStart = minPrice
                for ((threshold, color) in thresholds) {
                    val segFraction = ((threshold - segStart) / range).coerceIn(0.0, 1.0)
                    val segSweep = (totalSweep * segFraction).toFloat()
                    if (segSweep > 0f) {
                        drawArc(
                            color = color,
                            startAngle = startAngle + (totalSweep * ((segStart - minPrice) / range)).toFloat(),
                            sweepAngle = segSweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                        )
                    }
                    segStart = threshold
                }

                fun priceToAngle(price: Double): Float {
                    val fraction = ((price - minPrice) / range).coerceIn(0.0, 1.0)
                    return startAngle + (totalSweep * fraction).toFloat()
                }

                val cx = size.width / 2f
                val cy = size.height / 2f
                val arcRadius = (size.width - strokeWidth * 2) / 2f

                // Tick marks: ~16 total, major every 4th
                val totalTicks = 16
                val majorTickInterval = 4
                val outerTickR = arcRadius + strokeWidth / 2 + 2.dp.toPx()
                val majorTickLength = 8.dp.toPx()
                val minorTickLength = 4.dp.toPx()

                val labelPaint = android.graphics.Paint().apply {
                    color = labelColor.toArgb()
                    textSize = 9.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }

                for (i in 0..totalTicks) {
                    val angle = startAngle + (totalSweep * i / totalTicks)
                    val rad = Math.toRadians(angle.toDouble())
                    val isMajor = i % majorTickInterval == 0
                    val tickLen = if (isMajor) majorTickLength else minorTickLength
                    val tickWidth = if (isMajor) 1.5.dp.toPx() else 0.8.dp.toPx()

                    drawLine(
                        color = tickColor,
                        start = Offset(
                            cx + (outerTickR - tickLen) * cos(rad).toFloat(),
                            cy + (outerTickR - tickLen) * sin(rad).toFloat()
                        ),
                        end = Offset(
                            cx + outerTickR * cos(rad).toFloat(),
                            cy + outerTickR * sin(rad).toFloat()
                        ),
                        strokeWidth = tickWidth,
                        cap = StrokeCap.Round
                    )

                    // Price labels at major tick positions
                    if (isMajor) {
                        val priceVal = minPrice + range * i / totalTicks
                        val labelR = outerTickR + 12.dp.toPx()
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format(java.util.Locale.UK, "%.0f", priceVal),
                            cx + labelR * cos(rad).toFloat(),
                            cy + labelR * sin(rad).toFloat() + 3.dp.toPx(),
                            labelPaint
                        )
                    }
                }

                // Reference price: filled circle marker on the arc
                if (referencePrice != null) {
                    val refAngle = priceToAngle(referencePrice)
                    val refRad = Math.toRadians(refAngle.toDouble())
                    drawCircle(
                        color = referenceColor,
                        radius = 5.dp.toPx(),
                        center = Offset(
                            cx + arcRadius * cos(refRad).toFloat(),
                            cy + arcRadius * sin(refRad).toFloat()
                        )
                    )
                }

                // Current price: tapered triangle needle
                if (currentPrice != null) {
                    val needleAngle = priceToAngle(currentPrice)
                    val rad = Math.toRadians(needleAngle.toDouble())
                    val needleLength = arcRadius - strokeWidth / 2 - 8.dp.toPx()
                    val tipX = cx + needleLength * cos(rad).toFloat()
                    val tipY = cy + needleLength * sin(rad).toFloat()
                    val perpX = -sin(rad).toFloat()
                    val perpY = cos(rad).toFloat()
                    val baseHalfWidth = 5.dp.toPx()

                    val path = Path().apply {
                        moveTo(tipX, tipY)
                        lineTo(cx + perpX * baseHalfWidth, cy + perpY * baseHalfWidth)
                        lineTo(cx - perpX * baseHalfWidth, cy - perpY * baseHalfWidth)
                        close()
                    }
                    drawPath(path, needleColor, style = Fill)

                    // Pivot circle
                    drawCircle(color = needleColor, radius = 6.dp.toPx(), center = Offset(cx, cy))
                    drawCircle(color = surfaceColor, radius = 3.dp.toPx(), center = Offset(cx, cy))
                }
            }
        }

        // Text below the gauge — price is the focal point
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            if (currentPrice != null) {
                Text(
                    text = String.format(java.util.Locale.UK, "%.1f", currentPrice),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "p/kWh",
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.headlineMedium,
                    color = labelColor,
                    textAlign = TextAlign.Center
                )
            }
            if (referencePrice != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(referenceColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = String.format(java.util.Locale.UK, "%.1f p/kWh", referencePrice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}