package com.example.octopusdashboard.ui.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.nativeCanvas
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
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
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
                val arcRadiusY = (size.height - strokeWidth * 2) / 2f

                // Clock-like tick marks around the arc
                val majorTickInterval = 15
                val totalTicks = 36
                val outerTickR = arcRadius + strokeWidth / 2 + 2.dp.toPx()
                val majorTickLength = 8.dp.toPx()
                val minorTickLength = 4.dp.toPx()

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
                }

                // Helper to draw a pressure-gauge style tapered hand
                fun drawPressureHand(
                    angle: Double,
                    length: Float,
                    baseWidth: Float,
                    color: androidx.compose.ui.graphics.Color
                ) {
                    val rad = Math.toRadians(angle)
                    val tipX = cx + length * cos(rad).toFloat()
                    val tipY = cy + length * sin(rad).toFloat()
                    // Perpendicular direction for the base width
                    val perpX = -sin(rad).toFloat()
                    val perpY = cos(rad).toFloat()
                    // Tail extends slightly behind center
                    val tailLen = length * 0.15f
                    val tailX = cx - tailLen * cos(rad).toFloat()
                    val tailY = cy - tailLen * sin(rad).toFloat()

                    val path = Path().apply {
                        moveTo(tipX, tipY) // tip (pointed)
                        lineTo(cx + perpX * baseWidth / 2, cy + perpY * baseWidth / 2) // base right
                        lineTo(tailX + perpX * baseWidth / 3, tailY + perpY * baseWidth / 3) // tail right
                        lineTo(tailX - perpX * baseWidth / 3, tailY - perpY * baseWidth / 3) // tail left
                        lineTo(cx - perpX * baseWidth / 2, cy - perpY * baseWidth / 2) // base left
                        close()
                    }
                    drawPath(path, color, style = Fill)
                }

                // Draw reference hand (flexible/standard tariff — subordinate)
                if (referencePrice != null) {
                    val refAngle = priceToAngle(referencePrice)
                    val refRad = Math.toRadians(refAngle.toDouble())

                    // Reference tick on arc
                    val markerLength = 12.dp.toPx()
                    val innerR = arcRadius - strokeWidth / 2 - markerLength
                    val outerR = arcRadius - strokeWidth / 2 + 4.dp.toPx()
                    drawLine(
                        color = referenceColor,
                        start = Offset(cx + innerR * cos(refRad).toFloat(), cy + innerR * sin(refRad).toFloat()),
                        end = Offset(cx + outerR * cos(refRad).toFloat(), cy + outerR * sin(refRad).toFloat()),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // "Std Tariff" label on arc - use elliptical radii for correct positioning
                    val labelOffset = 6.dp.toPx()
                    val flexPaint = android.graphics.Paint().apply {
                        this.color = referenceColor.hashCode()
                        textSize = 8.5f.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        "Std Tariff",
                        cx + (arcRadius + labelOffset) * cos(refRad).toFloat(),
                        cy + (arcRadiusY + labelOffset) * sin(refRad).toFloat() + 4.dp.toPx(),
                        flexPaint
                    )

                    // Pressure-style hand (thinner, shorter)
                    drawPressureHand(
                        angle = refAngle.toDouble(),
                        length = arcRadius - strokeWidth / 2 - 16.dp.toPx(),
                        baseWidth = 4.dp.toPx(),
                        color = referenceColor
                    )
                }

                // Draw agile needle (prominent, on top)
                if (currentPrice != null) {
                    val needleAngle = priceToAngle(currentPrice)
                    drawPressureHand(
                        angle = needleAngle.toDouble(),
                        length = arcRadius - strokeWidth / 2 - 8.dp.toPx(),
                        baseWidth = 6.dp.toPx(),
                        color = needleColor
                    )

                    // Pivot circle
                    drawCircle(color = needleColor, radius = 6.dp.toPx(), center = Offset(cx, cy))
                    drawCircle(color = surfaceColor, radius = 3.dp.toPx(), center = Offset(cx, cy))
                }
            }
        }

        // Text below the gauge
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            if (currentPrice != null) {
                Text(
                    text = String.format(java.util.Locale.UK, "%.1f", currentPrice),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = PriceColors.priceColor(currentPrice),
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
                Text(
                    text = String.format(java.util.Locale.UK, "Std Tariff: %.1f p/kWh", referencePrice),
                    style = MaterialTheme.typography.labelSmall,
                    color = referenceColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
