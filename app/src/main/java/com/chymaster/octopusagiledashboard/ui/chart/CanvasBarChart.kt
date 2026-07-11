package com.chymaster.octopusagiledashboard.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val londonZone = ZoneId.of("Europe/London")
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.UK)
private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM\nHH:mm", Locale.UK)
private val monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.UK)

/**
 * A single bar for the canvas chart.
 */
data class ChartBar(
    val label: String,
    val value: Double,
    val intervalStart: Instant,
    val intervalEnd: Instant,
)

/**
 * Reusable Canvas-based bar chart with thick bars that fill available width.
 *
 * When [barCount] <= 20 the chart renders in "fit mode" — bars fill the
 * available width with small gaps. When [barCount] > 20 the chart switches
 * to "scroll mode" — bars are drawn at a comfortable fixed width and the
 * chart is horizontally scrollable.
 *
 * Optionally overlays a usage line (for dual-axis display) with a separate
 * Y scale on the right axis.
 */
@Composable
fun CanvasBarChart(
    bars: List<ChartBar>,
    barColors: List<Color>,
    yMin: Double,
    yMax: Double,
    isScrollable: Boolean,
    fixedBarWidthDp: Dp = 18.dp,
    gapDp: Dp = 1.5.dp,
    modifier: Modifier = Modifier,
    onBarTapped: (Int) -> Unit = {},
    selectedIndex: Int? = null,
    // Optional usage line overlay
    usageValues: List<Double>? = null,
    usageYMin: Double = 0.0,
    usageYMax: Double = 0.0,
    usageLineColor: Color = ChartColors.ConsumptionLine,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Tapped bar index for tooltip
    var tappedIndex by remember { mutableStateOf<Int?>(null) }

    val chartBarCount = bars.size
    if (chartBarCount == 0) return

    val usageData = usageValues?.takeIf { it.size == bars.size }
    val hasUsageLine = usageData != null

    // Canvas height is fixed; width depends on mode
    val canvasHeight = 260.dp
    val density = LocalDensity.current

    val scrollModifier = if (isScrollable) {
        val totalBarWidthPx = with(density) {
            val barW = fixedBarWidthDp.toPx()
            val g = gapDp.toPx()
            (chartBarCount * barW + (chartBarCount - 1) * g).toDp()
        }
        // Add padding for Y-axis labels
        val canvasWidth = totalBarWidthPx + 44.dp
        Modifier
            .horizontalScroll(rememberScrollState())
            .width(canvasWidth)
    } else {
        Modifier.fillMaxWidth()
    }

    Box(modifier = modifier.then(scrollModifier).height(canvasHeight)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .pointerInput(bars) {
                    detectTapGestures { offset ->
                        val chartLeft = 36.dp.toPx()
                        val rightPad = if (hasUsageLine) 36.dp.toPx() else 4.dp.toPx()
                        val chartRight = if (isScrollable) {
                            val barW = fixedBarWidthDp.toPx()
                            val g = gapDp.toPx()
                            chartLeft + chartBarCount * (barW + g)
                        } else {
                            size.width.toFloat() - rightPad
                        }
                        val chartWidth = chartRight - chartLeft
                        val gap = gapDp.toPx()
                        val barWidth = if (isScrollable) {
                            fixedBarWidthDp.toPx()
                        } else {
                            (chartWidth - gap * (chartBarCount - 1)) / chartBarCount
                        }

                        val relX = offset.x - chartLeft
                        val idx = if (barWidth + gap > 0) (relX / (barWidth + gap)).toInt() else -1
                        val newIdx = if (idx in bars.indices) idx else null
                        tappedIndex = if (newIdx == tappedIndex) null else newIdx
                        newIdx?.let { onBarTapped(it) }
                    }
                }
        ) {
                    val chartLeft = 36.dp.toPx()
                    val rightPad = if (hasUsageLine) 36.dp.toPx() else 4.dp.toPx()
                    val chartRight = if (isScrollable) {
                        val barW = fixedBarWidthDp.toPx()
                        val g = gapDp.toPx()
                        chartLeft + chartBarCount * (barW + g)
                    } else {
                        size.width - rightPad
                    }
                    val chartTop = 20.dp.toPx()
                    val chartBottom = size.height - 24.dp.toPx()
                    val chartWidth = chartRight - chartLeft
                    val chartHeight = chartBottom - chartTop
                    val gap = gapDp.toPx()
                    val barWidth = if (isScrollable) {
                        fixedBarWidthDp.toPx()
                    } else {
                        (chartWidth - gap * (chartBarCount - 1)) / chartBarCount
                    }

                    // Y-axis price labels (left side)
                    val yLabelPaint = android.graphics.Paint().apply {
                        color = labelColor.hashCode()
                        textSize = 9.sp.toPx()
                        textAlign = android.graphics.Paint.Align.RIGHT
                        isAntiAlias = true
                    }
                    val ySteps = 4
                    for (i in 0..ySteps) {
                        val priceVal = yMin + (yMax - yMin) * i / ySteps
                        val y = chartBottom - (chartHeight * i / ySteps)
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format(Locale.UK, "%.1f", priceVal),
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

                    // Right Y-axis labels for usage line
                    if (hasUsageLine) {
                        val rightLabelPaint = android.graphics.Paint().apply {
                            color = labelColor.hashCode()
                            textSize = 9.sp.toPx()
                            textAlign = android.graphics.Paint.Align.LEFT
                            isAntiAlias = true
                        }
                        for (i in 0..ySteps) {
                            val usageVal = usageYMin + (usageYMax - usageYMin) * i / ySteps
                            val y = chartBottom - (chartHeight * i / ySteps)
                            drawContext.canvas.nativeCanvas.drawText(
                                String.format(Locale.UK, "%.2f", usageVal),
                                chartRight + 4.dp.toPx(),
                                y + 3.dp.toPx(),
                                rightLabelPaint
                            )
                        }
                    }

                    // Zero baseline
                    val zeroFraction = if (yMax != yMin) {
                        ((0.0 - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)
                    } else 0.0
                    val zeroY = chartBottom - (chartHeight * zeroFraction).toFloat()
                    if (yMin < 0) {
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.4f),
                            start = Offset(chartLeft, zeroY),
                            end = Offset(chartRight, zeroY),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    // Draw bars
                    for (i in bars.indices) {
                        val value = bars[i].value
                        val color = barColors.getOrElse(i) { barColors.last() }
                        val isTapped = i == tappedIndex || i == selectedIndex
                        val fraction = if (yMax != yMin) {
                            ((value - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)
                        } else 0.5
                        val x = chartLeft + i * (barWidth + gap)

                        val barTop: Float
                        val barDrawHeight: Float
                        if (value >= 0 || yMin >= 0) {
                            barTop = zeroY - (chartHeight * (fraction - zeroFraction).coerceAtLeast(0.0)).toFloat()
                            barDrawHeight = zeroY - barTop
                        } else {
                            barTop = zeroY
                            barDrawHeight = (chartHeight * (zeroFraction - fraction).coerceAtLeast(0.0)).toFloat()
                        }

                        if (barDrawHeight > 0f) {
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(x, barTop),
                                size = Size(barWidth, barDrawHeight),
                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                            )
                        }

                        // Tapped bar highlight + tooltip
                        if (isTapped) {
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.3f),
                                topLeft = Offset(x, barTop),
                                size = Size(barWidth, barDrawHeight),
                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                            )

                            val tooltipText = String.format(Locale.UK, "%.1f p", value)
                            val timeText = bars[i].intervalStart.atZone(londonZone).format(timeFormatter)
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
                            val tooltipY = if (value >= 0) barTop - 8.dp.toPx() else barTop + barDrawHeight + 18.dp.toPx()
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

                    // Usage line overlay
                    if (hasUsageLine) {
                        val usageRange = (usageYMax - usageYMin).coerceAtLeast(0.01)
                        val path = Path()
                        var started = false
                        for (i in usageData.indices) {
                            val usageVal = usageData[i]
                            val fraction = ((usageVal - usageYMin) / usageRange).coerceIn(0.0, 1.0)
                            val x = chartLeft + i * (barWidth + gap) + barWidth / 2
                            val y = chartBottom - (chartHeight * fraction).toFloat()
                            if (!started) {
                                path.moveTo(x, y)
                                started = true
                            } else {
                                path.lineTo(x, y)
                            }
                        }
                        drawPath(
                            path = path,
                            color = usageLineColor,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // Usage line points
                        for (i in usageData.indices) {
                            val usageVal = usageData[i]
                            val fraction = ((usageVal - usageYMin) / usageRange).coerceIn(0.0, 1.0)
                            val x = chartLeft + i * (barWidth + gap) + barWidth / 2
                            val y = chartBottom - (chartHeight * fraction).toFloat()
                            drawCircle(
                                color = usageLineColor,
                                radius = 2.dp.toPx(),
                                center = Offset(x, y)
                            )
                        }
                    }

                    // X-axis time labels
                    val labelInterval = when {
                        chartBarCount <= 8 -> 1
                        chartBarCount <= 16 -> 2
                        else -> 3
                    }
                    val xLabelPaint = android.graphics.Paint().apply {
                        color = labelColor.hashCode()
                        textSize = 8.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    for (i in bars.indices step labelInterval) {
                        val x = chartLeft + i * (barWidth + gap) + barWidth / 2
                        // Format label with date at midnight crossings;
                        // for monthly bins (≥27 days), show abbreviated month name.
                        val durationHours = bars[i].let {
                            java.time.Duration.between(it.intervalStart, it.intervalEnd).toHours()
                        }
                        val isMonthly = durationHours >= 24 * 27
                        val isDailyOrLarger = durationHours >= 24
                        val label = if (isMonthly) {
                            bars[i].intervalStart.atZone(londonZone).format(monthFormatter)
                        } else if (isDailyOrLarger) {
                            bars[i].intervalStart.atZone(londonZone).format(dateFormatter)
                        } else {
                            val showDate = i > 0 && run {
                                val prevZoned = bars[i - 1].intervalStart.atZone(londonZone)
                                val curZoned = bars[i].intervalStart.atZone(londonZone)
                                prevZoned.toLocalDate() != curZoned.toLocalDate()
                            } || bars[i].intervalStart.atZone(londonZone).hour == 0
                            if (showDate) {
                                bars[i].intervalStart.atZone(londonZone).format(dateTimeFormatter)
                            } else {
                                bars[i].intervalStart.atZone(londonZone).format(timeFormatter)
                            }
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            x,
                            chartBottom + 14.dp.toPx(),
                            xLabelPaint
                        )
                    }
                }
    }
}
