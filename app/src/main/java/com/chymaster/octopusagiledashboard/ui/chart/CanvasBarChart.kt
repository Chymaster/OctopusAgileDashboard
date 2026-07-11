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
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors
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
    /** Per-zone consumption segments for stacked-bar rendering. */
    val greenSegment: Double = 0.0,
    val amberSegment: Double = 0.0,
    val redSegment: Double = 0.0,
    /** Unit label for tooltips (e.g. "kWh", "£"). */
    val unitLabel: String = "kWh",
) {
    /** True when at least one zone segment carries non-zero consumption. */
    val hasZoneBreakdown: Boolean
        get() = greenSegment > 0.0 || amberSegment > 0.0 || redSegment > 0.0
}

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

                    // Detect if bars carry zone-breakdown data (→ kWh) or plain values (→ price/cost).
                    val isZoneChart = bars.any { it.hasZoneBreakdown }

                    // Y-axis labels (left side)
                    val yLabelPaint = android.graphics.Paint().apply {
                        color = labelColor.hashCode()
                        textSize = 9.sp.toPx()
                        textAlign = android.graphics.Paint.Align.RIGHT
                        isAntiAlias = true
                    }
                    val yLabelFormat = if (isZoneChart) "%.2f" else "%.1f"
                    val ySteps = 4
                    for (i in 0..ySteps) {
                        val yVal = yMin + (yMax - yMin) * i / ySteps
                        val y = chartBottom - (chartHeight * i / ySteps)
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format(Locale.UK, yLabelFormat, yVal),
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

                    // Right Y-axis labels for overlay line
                    if (hasUsageLine) {
                        val rightLabelPaint = android.graphics.Paint().apply {
                            color = labelColor.hashCode()
                            textSize = 9.sp.toPx()
                            textAlign = android.graphics.Paint.Align.LEFT
                            isAntiAlias = true
                        }
                        val rightLabelFormat = if (isZoneChart) "%.1f" else "%.2f"
                        for (i in 0..ySteps) {
                            val usageVal = usageYMin + (usageYMax - usageYMin) * i / ySteps
                            val y = chartBottom - (chartHeight * i / ySteps)
                            drawContext.canvas.nativeCanvas.drawText(
                                String.format(Locale.UK, rightLabelFormat, usageVal),
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
                        val bar = bars[i]
                        val value = bar.value
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
                            if (bar.hasZoneBreakdown) {
                                // Draw stacked green/amber/red segments
                                val segmentTotal = bar.greenSegment + bar.amberSegment + bar.redSegment
                                if (segmentTotal > 0.0) {
                                    val segments = listOf(
                                        Triple(bar.greenSegment, PriceColors.Cheap, false),
                                        Triple(bar.amberSegment, PriceColors.Moderate, false),
                                        Triple(bar.redSegment, PriceColors.Expensive, true),
                                    )
                                    var segTop = barTop
                                    for ((segVal, segColor, isLast) in segments) {
                                        if (segVal <= 0.0) continue
                                        val segHeight = ((segVal / segmentTotal) * barDrawHeight.toDouble())
                                            .toFloat().coerceAtLeast(0.5f)
                                        val cornerRadius = if (isLast && segTop <= barTop + 1f) {
                                            // Only one segment or top segment — round top corners
                                            CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                        } else {
                                            CornerRadius(0f, 0f)
                                        }
                                        drawRoundRect(
                                            color = segColor,
                                            topLeft = Offset(x, segTop),
                                            size = Size(barWidth, segHeight),
                                            cornerRadius = cornerRadius
                                        )
                                        segTop += segHeight
                                    }
                                }
                            } else {
                                // Single-color bar (backward-compatible with PriceLineChart)
                                val color = barColors.getOrElse(i) { barColors.last() }
                                drawRoundRect(
                                    color = color,
                                    topLeft = Offset(x, barTop),
                                    size = Size(barWidth, barDrawHeight),
                                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                )
                            }
                        }

                        // Tapped bar highlight + tooltip
                        if (isTapped) {
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.3f),
                                topLeft = Offset(x, barTop),
                                size = Size(barWidth, barDrawHeight),
                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                            )

                            val tooltipX = x + barWidth / 2
                            val tooltipY = if (value >= 0) barTop - 8.dp.toPx() else barTop + barDrawHeight + 18.dp.toPx()

                            if (bar.hasZoneBreakdown) {
                                // Zone-breakdown tooltip: consumption + zone breakdown
                                val totalText = String.format(Locale.UK, "%.2f %s", value, bar.unitLabel)
                                val zoneText = String.format(
                                    Locale.UK, "G:%.2f  A:%.2f  R:%.2f",
                                    bar.greenSegment, bar.amberSegment, bar.redSegment
                                )
                                val timeText = bar.intervalStart.atZone(londonZone).format(timeFormatter)

                                val tooltipPaint = android.graphics.Paint().apply {
                                    this.color = android.graphics.Color.WHITE
                                    textSize = 10.sp.toPx()
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    isAntiAlias = true
                                    isFakeBoldText = true
                                }
                                val zonePaint = android.graphics.Paint().apply {
                                    this.color = android.graphics.Color.argb(255, 200, 200, 200)
                                    textSize = 8.sp.toPx()
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    isAntiAlias = true
                                }
                                val timePaint = android.graphics.Paint(tooltipPaint).apply {
                                    this.color = android.graphics.Color.LTGRAY
                                    textSize = 8.sp.toPx()
                                    isFakeBoldText = false
                                }
                                val bgPaint = android.graphics.Paint().apply {
                                    this.color = android.graphics.Color.argb(200, 40, 40, 40)
                                    isAntiAlias = true
                                }

                                val maxLineWidth = maxOf(
                                    tooltipPaint.measureText(totalText),
                                    zonePaint.measureText(zoneText),
                                    timePaint.measureText(timeText)
                                )
                                val bgRect = android.graphics.RectF(
                                    tooltipX - maxLineWidth / 2 - 6.dp.toPx(),
                                    tooltipY - 28.dp.toPx(),
                                    tooltipX + maxLineWidth / 2 + 6.dp.toPx(),
                                    tooltipY + 12.dp.toPx()
                                )
                                drawContext.canvas.nativeCanvas.drawRoundRect(bgRect, 4.dp.toPx(), 4.dp.toPx(), bgPaint)
                                drawContext.canvas.nativeCanvas.drawText(totalText, tooltipX, tooltipY - 16.dp.toPx(), tooltipPaint)
                                drawContext.canvas.nativeCanvas.drawText(zoneText, tooltipX, tooltipY - 6.dp.toPx(), zonePaint)
                                drawContext.canvas.nativeCanvas.drawText(timeText, tooltipX, tooltipY + 6.dp.toPx(), timePaint)
                            } else {
                                // Original tooltip for single-color bars
                                val tooltipText = String.format(Locale.UK, "%.1f %s", value, bar.unitLabel)
                                val timeText = bar.intervalStart.atZone(londonZone).format(timeFormatter)
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
