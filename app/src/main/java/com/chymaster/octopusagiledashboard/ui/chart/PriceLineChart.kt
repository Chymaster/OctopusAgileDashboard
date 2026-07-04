package com.chymaster.octopusagiledashboard.ui.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.LayeredComponent
import com.patrykandpatrick.vico.compose.common.MarkerCornerBasedShape
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import java.util.Locale

enum class ChartMode {
    PRICE, CONSUMPTION, COST
}

@Composable
fun PriceLineChart(
    points: List<HalfHourPoint>,
    chartMode: ChartMode,
    onPointTapped: (BinnedPoint?) -> Unit,
    modifier: Modifier = Modifier
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    var isZoomed by remember { mutableStateOf(false) }

    // Bin data when compacted (not zoomed), use raw data when zoomed
    val binnedPoints = remember(points) { binPoints(points) }
    val useBinned = !isZoomed && binnedPoints.size < points.size
    val displayPoints = if (useBinned) binnedPoints else null

    val xIndices = if (useBinned) {
        binnedPoints.indices.map { it.toDouble() }
    } else {
        points.indices.map { it.toDouble() }
    }

    LaunchedEffect(points, chartMode, isZoomed) {
        if (points.isEmpty()) return@LaunchedEffect

        modelProducer.runTransaction {
            columnModel {
                if (useBinned && displayPoints != null) {
                    when (chartMode) {
                        ChartMode.PRICE -> {
                            series(x = xIndices, y = displayPoints.map { it.avgPrice ?: 0.0 })
                        }
                        ChartMode.CONSUMPTION -> {
                            series(x = xIndices, y = displayPoints.map { it.totalConsumption ?: 0.0 })
                        }
                        ChartMode.COST -> {
                            series(x = xIndices, y = displayPoints.map { (it.totalCost ?: 0.0) / 100.0 })
                        }
                    }
                } else {
                    when (chartMode) {
                        ChartMode.PRICE -> {
                            series(x = xIndices, y = points.map { it.priceIncVat ?: 0.0 })
                        }
                        ChartMode.CONSUMPTION -> {
                            series(x = xIndices, y = points.map { it.consumptionKwh ?: 0.0 })
                        }
                        ChartMode.COST -> {
                            series(x = xIndices, y = points.map { (it.costIncVat ?: 0.0) / 100.0 })
                        }
                    }
                }
            }
        }
    }

    // Column styling
    val priceColumn = rememberLineComponent(
        fill = Fill(ChartColors.PriceLine),
        thickness = 8.dp
    )
    val consumptionColumn = rememberLineComponent(
        fill = Fill(ChartColors.ConsumptionLine),
        thickness = 8.dp
    )
    val costColumn = rememberLineComponent(
        fill = Fill(Color(0xFFE65100)),
        thickness = 8.dp
    )

    val columnProvider = remember(chartMode) {
        when (chartMode) {
            ChartMode.PRICE -> ColumnCartesianLayer.ColumnProvider.series(priceColumn)
            ChartMode.CONSUMPTION -> ColumnCartesianLayer.ColumnProvider.series(consumptionColumn)
            ChartMode.COST -> ColumnCartesianLayer.ColumnProvider.series(costColumn)
        }
    }

    val markerValueFormatter = when (chartMode) {
        ChartMode.PRICE -> ChartFormatters.priceMarkerFormatter
        ChartMode.CONSUMPTION -> ChartFormatters.consumptionMarkerFormatter
        ChartMode.COST -> DefaultCartesianMarker.ValueFormatter.default(prefix = "£", suffix = "")
    }
    val marker = rememberPriceMarker(markerValueFormatter)
    val markerListener = remember(points, useBinned, displayPoints, onPointTapped) {
        object : CartesianMarkerVisibilityListener {
            override fun onShown(
                marker: CartesianMarker,
                targets: List<CartesianMarker.Target>
            ) {
                val index = targets.firstOrNull()?.x?.toInt() ?: return
                if (useBinned && displayPoints != null) {
                    onPointTapped(displayPoints.getOrNull(index))
                } else {
                    val p = points.getOrNull(index)
                    onPointTapped(p?.let {
                        BinnedPoint(it.intervalStart, it.intervalEnd, it.priceIncVat, it.consumptionKwh, it.costIncVat, 1)
                    })
                }
            }
            override fun onUpdated(
                marker: CartesianMarker,
                targets: List<CartesianMarker.Target>
            ) {
                val index = targets.firstOrNull()?.x?.toInt() ?: return
                if (useBinned && displayPoints != null) {
                    onPointTapped(displayPoints.getOrNull(index))
                } else {
                    val p = points.getOrNull(index)
                    onPointTapped(p?.let {
                        BinnedPoint(it.intervalStart, it.intervalEnd, it.priceIncVat, it.consumptionKwh, it.costIncVat, 1)
                    })
                }
            }
            override fun onHidden(marker: CartesianMarker) {}
        }
    }
    val timeFormatter = remember(points, useBinned, displayPoints) {
        if (useBinned && displayPoints != null) {
            ChartFormatters.binnedTimeAxisFormatter(displayPoints)
        } else {
            ChartFormatters.timeAxisFormatter(points)
        }
    }

    val axisFormatter = when (chartMode) {
        ChartMode.PRICE -> ChartFormatters.priceAxisFormatter
        ChartMode.CONSUMPTION -> ChartFormatters.consumptionAxisFormatter
        ChartMode.COST -> CartesianValueFormatter { _, value, _ ->
            String.format(Locale.UK, "£%.2f", value)
        }
    }

    Column(modifier = modifier) {
        // Zoom toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = { isZoomed = !isZoomed },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (isZoomed)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Icon(
                    if (isZoomed) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
                    contentDescription = if (isZoomed) "Fit to screen" else "Zoom in"
                )
            }
        }

        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(columnProvider = columnProvider),
                startAxis = VerticalAxis.rememberStart(valueFormatter = axisFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = timeFormatter),
                marker = marker,
                markerVisibilityListener = markerListener,
            ),
            modelProducer = modelProducer,
            scrollState = rememberVicoScrollState(scrollEnabled = isZoomed),
            zoomState = rememberVicoZoomState(
                zoomEnabled = isZoomed,
                initialZoom = if (isZoomed) Zoom.fixed() else Zoom.Content,
                minZoom = Zoom.Content,
                maxZoom = if (isZoomed) Zoom.max(Zoom.fixed(3f), Zoom.Content) else Zoom.Content
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        )
    }
}

@Composable
internal fun rememberPriceMarker(
    valueFormatter: DefaultCartesianMarker.ValueFormatter
): CartesianMarker? {
    val labelBackgroundShape = MarkerCornerBasedShape(CircleShape)
    val labelBackground = rememberShapeComponent(
        fill = Fill(ChartColors.Marker),
        shape = labelBackgroundShape,
    )
    val label = rememberTextComponent(
        style = TextStyle(
            color = Color.White,
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
        ),
        padding = Insets(8.dp, 4.dp),
        background = labelBackground,
        minWidth = TextComponent.MinWidth.fixed(40.dp),
    )
    val indicatorFrontComponent = rememberShapeComponent(Fill(ChartColors.Marker), CircleShape)
    val guideline = rememberAxisGuidelineComponent()
    return rememberDefaultCartesianMarker(
        label = label,
        labelPosition = DefaultCartesianMarker.LabelPosition.Top,
        valueFormatter = valueFormatter,
        indicator = { color ->
            LayeredComponent(
                back = ShapeComponent(Fill(color.copy(alpha = 0.15f)), CircleShape),
                front = indicatorFrontComponent,
            )
        },
        guideline = guideline,
    )
}
