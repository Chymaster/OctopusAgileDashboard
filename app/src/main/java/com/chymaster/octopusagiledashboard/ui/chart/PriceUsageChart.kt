package com.chymaster.octopusagiledashboard.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore

/**
 * Dual-axis Vico chart that overlays price (bars, LEFT Y axis in pence/kWh)
 * and usage (line, RIGHT Y axis in kWh) on a shared time axis.
 *
 * Price bars are ratio-coloured against [referencePrice] (the Flexible Octopus
 * tariff): green if < 70 %, amber if 70–130 %, red if > 130 %. When
 * [referencePrice] is null the bars fall back to amber.
 */
@Composable
fun PriceUsageChart(
    points: List<HalfHourPoint>,
    referencePrice: Double?,
    onPointTapped: (BinnedPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return

    // Trim edge intervals that have no consumption data (API lag / stale 0s).
    val trimmedPoints = remember(points) { points.trimMissingConsumption() }
    if (trimmedPoints.isEmpty()) return

    val modelProducer = remember { CartesianChartModelProducer() }
    var isZoomed by remember { mutableStateOf(false) }

    val binnedPoints = remember(trimmedPoints) { binPoints(trimmedPoints) }
    val useBinned = !isZoomed && binnedPoints.size < trimmedPoints.size
    val displayPoints = if (useBinned) binnedPoints else null

    val xIndices = if (useBinned) {
        binnedPoints.indices.map { it.toDouble() }
    } else {
        trimmedPoints.indices.map { it.toDouble() }
    }

    // Per-bar y-values. Vico's line series requires non-null numbers, so we
    // substitute 0.0 for missing consumption. The line dips to the right-axis
    // baseline where there is no usage, which is honest ("no usage in this
    // slot") rather than misleading.
    val priceYs: List<Double> = remember(useBinned, displayPoints, trimmedPoints) {
        if (useBinned && displayPoints != null) {
            displayPoints.map { it.avgPrice ?: 0.0 }
        } else {
            trimmedPoints.map { it.priceIncVat ?: 0.0 }
        }
    }
    val usageYs: List<Double> = remember(useBinned, displayPoints, trimmedPoints) {
        if (useBinned && displayPoints != null) {
            displayPoints.map { it.totalConsumption ?: 0.0 }
        } else {
            trimmedPoints.map { it.consumptionKwh ?: 0.0 }
        }
    }

    // Align zero points on both Y axes so that 0 p/kWh and 0 kWh sit at the
    // same vertical position.  Without this, negative prices push the price
    // axis zero upward while usage zero stays at the bottom — making usage
    // *look* negative when it isn't.
    val (priceRangeProvider, usageRangeProvider) = remember(priceYs, usageYs) {
        computeAlignedRangeProviders(priceYs, usageYs)
    }

    LaunchedEffect(trimmedPoints, isZoomed) {
        modelProducer.runTransaction {
            columnModel { series(x = xIndices, y = priceYs) }
            lineModel { series(x = xIndices, y = usageYs) }
        }
    }

    // One LineComponent per bar, colored by the bar's price ratio. The
    // custom PerBarColumnProvider below maps entry.x.toInt() → component,
    // because Vico's built-in ColumnProvider.series only varies by series
    // index, not by individual entry.
    val perBarColumnProvider = remember(binnedPoints, referencePrice, useBinned) {
        val components: List<LineComponent> = if (useBinned && displayPoints != null) {
            displayPoints.map { bin ->
                LineComponent(
                    fill = Fill(PriceColors.priceColor(bin.avgPrice ?: 0.0, referencePrice)),
                    thickness = 8.dp,
                )
            }
        } else {
            trimmedPoints.map { p ->
                LineComponent(
                    fill = Fill(PriceColors.priceColor(p.priceIncVat ?: 0.0, referencePrice)),
                    thickness = 8.dp,
                )
            }
        }
        PerBarColumnProvider(components)
    }

    val usageLineColor = ChartColors.ConsumptionLine
    val usagePointShape = ShapeComponent(fill = Fill(usageLineColor), shape = CircleShape)
    val usagePoint = LineCartesianLayer.Point(
        component = usagePointShape,
        size = 4.dp,
    )
    val usageLine = remember(usageLineColor, usagePoint) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(usageLineColor)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.5.dp),
            pointProvider = LineCartesianLayer.PointProvider.single(usagePoint),
        )
    }
    val usageLineProvider = LineCartesianLayer.LineProvider.series(usageLine)

    // No suffix — the dual-axis chart legend already indicates units, and a
    // single suffix would incorrectly label both the price and usage series.
    val marker = rememberPriceMarker(DefaultCartesianMarker.ValueFormatter.default())
    val markerListener = remember(trimmedPoints, useBinned, displayPoints, onPointTapped) {
        object : CartesianMarkerVisibilityListener {
            override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                emitTappedTarget(targets, useBinned, displayPoints, trimmedPoints, onPointTapped)
            }
            override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                emitTappedTarget(targets, useBinned, displayPoints, trimmedPoints, onPointTapped)
            }
            override fun onHidden(marker: CartesianMarker) {}
        }
    }
    val timeFormatter = remember(trimmedPoints, useBinned, displayPoints) {
        if (useBinned && displayPoints != null) {
            ChartFormatters.binnedTimeAxisFormatter(displayPoints)
        } else {
            ChartFormatters.timeAxisFormatter(trimmedPoints)
        }
    }

    Column(modifier = modifier) {
        // Legend + zoom toggle row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendSwatch(
                color = PriceColors.Moderate,
                label = "Price (p/kWh)",
                shape = LegendShape.Square,
            )
            Spacer(Modifier.width(16.dp))
            LegendSwatch(
                color = usageLineColor,
                label = "Usage (kWh)",
                shape = LegendShape.Circle,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { isZoomed = !isZoomed },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (isZoomed)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Icon(
                    if (isZoomed) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
                    contentDescription = if (isZoomed) "Fit to screen" else "Zoom in",
                )
            }
        }

        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = perBarColumnProvider,
                    rangeProvider = priceRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.Start,
                ),
                rememberLineCartesianLayer(
                    lineProvider = usageLineProvider,
                    rangeProvider = usageRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.End,
                ),
                startAxis = VerticalAxis.rememberStart(
                    valueFormatter = ChartFormatters.priceAxisFormatter,
                ),
                endAxis = VerticalAxis.rememberEnd(
                    valueFormatter = ChartFormatters.consumptionAxisFormatter,
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = timeFormatter,
                ),
                marker = marker,
                markerVisibilityListener = markerListener,
            ),
            modelProducer = modelProducer,
            scrollState = rememberVicoScrollState(scrollEnabled = isZoomed),
            zoomState = rememberVicoZoomState(
                zoomEnabled = isZoomed,
                initialZoom = if (isZoomed) Zoom.fixed() else Zoom.Content,
                minZoom = Zoom.Content,
                maxZoom = if (isZoomed) Zoom.max(Zoom.fixed(3f), Zoom.Content) else Zoom.Content,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
        )
    }
}

private fun emitTappedTarget(
    targets: List<CartesianMarker.Target>,
    useBinned: Boolean,
    displayPoints: List<BinnedPoint>?,
    rawPoints: List<HalfHourPoint>,
    onPointTapped: (BinnedPoint?) -> Unit,
) {
    val index = targets.firstOrNull()?.x?.toInt() ?: return
    if (useBinned && displayPoints != null) {
        onPointTapped(displayPoints.getOrNull(index))
    } else {
        val p = rawPoints.getOrNull(index)
        onPointTapped(p?.let {
            BinnedPoint(it.intervalStart, it.intervalEnd, it.priceIncVat, it.consumptionKwh, it.costIncVat, 1)
        })
    }
}

/**
 * Per-bar [ColumnCartesianLayer.ColumnProvider] that returns a distinct
 * [LineComponent] for each entry, looked up by the entry's x index. Vico's
 * built-in [ColumnCartesianLayer.ColumnProvider.series] only varies components
 * by series index, not by entry, so this custom provider is the simplest way
 * to colour each price bar by its individual value.
 */
private class PerBarColumnProvider(
    private val components: List<LineComponent>,
) : ColumnCartesianLayer.ColumnProvider {
    override fun getColumn(
        entry: ColumnCartesianLayerModel.Entry,
        extraStore: ExtraStore,
    ): LineComponent = components.getOrElse(entry.x.toInt()) { components.last() }

    override fun getWidestSeriesColumn(
        seriesKey: Any,
        seriesIndex: Int,
        extraStore: ExtraStore,
    ): LineComponent = components.first()
}

private enum class LegendShape { Square, Circle }

@Composable
private fun LegendSwatch(
    color: Color,
    label: String,
    shape: LegendShape,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (shape) {
            LegendShape.Square -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
            LegendShape.Circle -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Computes two [CartesianLayerRangeProvider]s — one for the price bars (left
 * axis) and one for the usage line (right axis) — whose Y ranges place zero
 * at the same vertical position on both axes.
 *
 * When energy prices go negative the default auto-scaling pushes the price
 * axis zero upward while the usage axis zero stays at the bottom — making
 * non-negative usage *look* negative.  This function fixes that by computing
 * a shared "zero fraction" and deriving both axis ranges from it.
 *
 * If the price data is entirely non-negative the axes already align naturally,
 * so we fall back to [CartesianLayerRangeProvider.auto] for both.
 */
private fun computeAlignedRangeProviders(
    priceYs: List<Double>,
    usageYs: List<Double>,
): Pair<CartesianLayerRangeProvider, CartesianLayerRangeProvider> {
    if (priceYs.isEmpty() || usageYs.isEmpty()) {
        val auto = CartesianLayerRangeProvider.auto()
        return auto to auto
    }

    val rawPriceMin = priceYs.min()
    val rawPriceMax = priceYs.max()
    val rawUsageMax = usageYs.max()

    // Price axis: ensure zero is always included with a small buffer.
    val priceRange = (rawPriceMax - rawPriceMin).coerceAtLeast(0.1)
    val pricePad = priceRange * 0.1
    val priceMin = (rawPriceMin - pricePad).coerceAtMost(0.0)
    val priceMax = (rawPriceMax + pricePad).coerceAtLeast(0.0)

    // Where does zero sit on the price axis?  0 = bottom, 1 = top.
    val priceZeroFraction = if (priceMin < 0 && priceMax > 0) {
        -priceMin / (priceMax - priceMin)
    } else {
        // Price is entirely ≥ 0 — axes already align at the bottom edge.
        val auto = CartesianLayerRangeProvider.auto()
        return auto to auto
    }

    // Usage axis: extend below zero so its zero fraction matches the price axis.
    val usagePad = (rawUsageMax * 0.1).coerceAtLeast(0.05)
    val usageMax = rawUsageMax + usagePad
    val usageMin = if (usageMax > 0) {
        -usageMax * priceZeroFraction / (1.0 - priceZeroFraction)
    } else {
        0.0
    }

    return CartesianLayerRangeProvider.fixed(minY = priceMin, maxY = priceMax) to
        CartesianLayerRangeProvider.fixed(minY = usageMin, maxY = usageMax)
}
