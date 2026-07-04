package com.chymaster.octopusagiledashboard.ui.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val londonZone = ZoneId.of("Europe/London")
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

@Composable
fun PriceTimelineChart(
    prices: List<AgilePrice>,
    currentPriceStartTime: Instant?,
    referencePrice: Double? = null,
    modifier: Modifier = Modifier
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    val sortedPrices = remember(prices) { prices.sortedBy { it.validFrom } }
    val currentIndex = remember(sortedPrices, currentPriceStartTime) {
        sortedPrices.indexOfFirst { it.validFrom == currentPriceStartTime }
    }

    val minPrice = remember(sortedPrices) {
        val lowest = sortedPrices.minOfOrNull { it.priceIncVat } ?: 0.0
        if (lowest < 0) lowest - 2 else 0.0
    }
    val maxPrice = remember(sortedPrices) { sortedPrices.maxOfOrNull { it.priceIncVat }?.let { it + 2 } ?: 40.0 }

    // Tapped bar index for tooltip
    var tappedIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = modifier) {
        Text(
            text = "Price Timeline (2h ago → 4h ahead)",
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )

        if (sortedPrices.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                        .pointerInput(sortedPrices) {
                            detectTapGestures { offset ->
                                val chartLeft = 36.dp.toPx()
                                val chartRight = size.width.toFloat() - 4.dp.toPx()
                                val chartWidth = chartRight - chartLeft
                                val barCount = sortedPrices.size
                                val gap = 1.5.dp.toPx()
                                val barWidth = (chartWidth - gap * (barCount - 1)) / barCount

                                val relX = offset.x - chartLeft
                                val idx = if (barWidth + gap > 0) (relX / (barWidth + gap)).toInt() else -1
                                tappedIndex = if (idx in sortedPrices.indices) {
                                    if (tappedIndex == idx) null else idx // toggle
                                } else null
                            }
                        }
                ) {
                    val chartLeft = 36.dp.toPx()
                    val chartRight = size.width - 4.dp.toPx()
                    val chartTop = 20.dp.toPx()
                    val chartBottom = size.height - 24.dp.toPx()
                    val chartWidth = chartRight - chartLeft
                    val chartHeight = chartBottom - chartTop
                    val barCount = sortedPrices.size
                    val gap = 1.5.dp.toPx()
                    val barWidth = (chartWidth - gap * (barCount - 1)) / barCount

                    // Y-axis price labels
                    val yLabelPaint = android.graphics.Paint().apply {
                        color = labelColor.hashCode()
                        textSize = 9.sp.toPx()
                        textAlign = android.graphics.Paint.Align.RIGHT
                        isAntiAlias = true
                    }
                    val ySteps = 4
                    for (i in 0..ySteps) {
                        val priceVal = minPrice + (maxPrice - minPrice) * i / ySteps
                        val y = chartBottom - (chartHeight * i / ySteps)
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format(Locale.UK, "%.0f", priceVal),
                            chartLeft - 4.dp.toPx(),
                            y + 3.dp.toPx(),
                            yLabelPaint
                        )
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.15f),
                            start = Offset(chartLeft, y),
                            end = Offset(chartRight, y),
                            strokeWidth = 0.5.dp.toPx()
                        )
                    }

                    // Zero baseline
                    val zeroFraction = ((0.0 - minPrice) / (maxPrice - minPrice)).coerceIn(0.0, 1.0)
                    val zeroY = chartBottom - (chartHeight * zeroFraction).toFloat()
                    if (minPrice < 0) {
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.4f),
                            start = Offset(chartLeft, zeroY),
                            end = Offset(chartRight, zeroY),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    // Draw bars
                    for (i in sortedPrices.indices) {
                        val price = sortedPrices[i].priceIncVat
                        val isCurrent = i == currentIndex
                        val isTapped = i == tappedIndex
                        val fraction = ((price - minPrice) / (maxPrice - minPrice)).coerceIn(0.0, 1.0)
                        val x = chartLeft + i * (barWidth + gap)

                        val barTop: Float
                        val barDrawHeight: Float
                        if (price >= 0 || minPrice >= 0) {
                            // Positive bar: grows upward from zero baseline
                            barTop = zeroY - (chartHeight * (fraction - zeroFraction).coerceAtLeast(0.0)).toFloat()
                            barDrawHeight = zeroY - barTop
                        } else {
                            // Negative bar: grows downward from zero baseline
                            barTop = zeroY
                            barDrawHeight = (chartHeight * (zeroFraction - fraction).coerceAtLeast(0.0)).toFloat()
                        }

                        if (barDrawHeight > 0f) {
                            drawRoundRect(
                                color = PriceColors.priceColor(price, referencePrice),
                                topLeft = Offset(x, barTop),
                                size = Size(barWidth, barDrawHeight),
                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                            )
                        }

                        if (isCurrent) {
                            // "now" indicator: text + downward arrow above the bar
                            val arrowCenterX = x + barWidth / 2
                            val minArrowTipY = 18.dp.toPx()
                            val arrowRefY = if (price >= 0) barTop else barTop + barDrawHeight
                            val arrowTipY = maxOf(arrowRefY - 4.dp.toPx(), minArrowTipY)
                            val arrowBaseY = arrowTipY - 8.dp.toPx()
                            val arrowHalfWidth = 5.dp.toPx()

                            // "now" text above the arrow
                            val nowPaint = android.graphics.Paint().apply {
                                color = primaryColor.hashCode()
                                textSize = 9.sp.toPx()
                                textAlign = android.graphics.Paint.Align.CENTER
                                isAntiAlias = true
                                isFakeBoldText = true
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                "now",
                                arrowCenterX,
                                arrowBaseY - 2.dp.toPx(),
                                nowPaint
                            )

                            // Downward-pointing triangle
                            val path = Path().apply {
                                moveTo(arrowCenterX, arrowTipY)
                                lineTo(arrowCenterX - arrowHalfWidth, arrowBaseY)
                                lineTo(arrowCenterX + arrowHalfWidth, arrowBaseY)
                                close()
                            }
                            drawPath(path, primaryColor)
                        }

                        // Tapped bar tooltip
                        if (isTapped) {
                            // Highlight bar
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.3f),
                                topLeft = Offset(x, barTop),
                                size = Size(barWidth, barDrawHeight),
                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                            )

                            // Tooltip background
                            val tooltipText = String.format(Locale.UK, "%.1f p", price)
                            val timeText = sortedPrices[i].validFrom.atZone(londonZone).format(timeFormatter)
                            val tooltipPaint = android.graphics.Paint().apply {
                                this.color = android.graphics.Color.WHITE
                                textSize = 10.sp.toPx()
                                textAlign = android.graphics.Paint.Align.CENTER
                                isAntiAlias = true
                                isFakeBoldText = true
                            }
                            val bgPaint = android.graphics.Paint().apply {
                                this.color = android.graphics.Color.argb(200, 40, 40, 40)
                                isAntiAlias = true
                            }

                            val tooltipX = x + barWidth / 2
                            val tooltipY = if (price >= 0) barTop - 8.dp.toPx() else barTop + barDrawHeight + 18.dp.toPx()
                            val textWidth = tooltipPaint.measureText("$tooltipText $timeText")
                            val bgRect = android.graphics.RectF(
                                tooltipX - textWidth / 2 - 6.dp.toPx(),
                                tooltipY - 14.dp.toPx(),
                                tooltipX + textWidth / 2 + 6.dp.toPx(),
                                tooltipY + 4.dp.toPx()
                            )
                            drawContext.canvas.nativeCanvas.drawRoundRect(bgRect, 4.dp.toPx(), 4.dp.toPx(), bgPaint)
                            drawContext.canvas.nativeCanvas.drawText(
                                tooltipText,
                                tooltipX,
                                tooltipY - 2.dp.toPx(),
                                tooltipPaint
                            )
                            val timePaint = android.graphics.Paint(tooltipPaint).apply {
                                this.color = android.graphics.Color.LTGRAY
                                textSize = 8.sp.toPx()
                                isFakeBoldText = false
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                timeText,
                                tooltipX,
                                tooltipY + 8.dp.toPx(),
                                timePaint
                            )
                        }
                    }

                    // X-axis time labels
                    val labelInterval = when {
                        barCount <= 8 -> 1
                        barCount <= 16 -> 2
                        else -> 3
                    }
                    val xLabelPaint = android.graphics.Paint().apply {
                        color = labelColor.hashCode()
                        textSize = 8.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    for (i in sortedPrices.indices step labelInterval) {
                        val x = chartLeft + i * (barWidth + gap) + barWidth / 2
                        val timeLabel = sortedPrices[i].validFrom.atZone(londonZone).format(timeFormatter)
                        drawContext.canvas.nativeCanvas.drawText(
                            timeLabel,
                            x,
                            chartBottom + 14.dp.toPx(),
                            xLabelPaint
                        )
                    }
                }
            }
        }
    }
}
