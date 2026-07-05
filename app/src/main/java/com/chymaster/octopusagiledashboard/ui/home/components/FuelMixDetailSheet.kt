package com.chymaster.octopusagiledashboard.ui.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.sqrt
import com.chymaster.octopusagiledashboard.core.util.DateTimeFormatters
import com.chymaster.octopusagiledashboard.domain.model.FuelMix
import com.chymaster.octopusagiledashboard.domain.model.GreenEnergyData
import com.chymaster.octopusagiledashboard.domain.model.LOW_CARBON_FUELS
import com.chymaster.octopusagiledashboard.ui.theme.BrandColors

/**
 * Detail sheet for the grid-mix pie. Shows a full multi-fuel donut chart
 * (the Home screen shows only the two-way low/high carbon split) followed by
 * a compact grouped legend. Triggered by tapping the [FuelMixPieChart].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelMixDetailSheet(
    data: GreenEnergyData,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedFuel by remember { mutableStateOf<FuelMix?>(null) }

    val lowCarbonFuels = data.fuelMix
        .filter { it.fuel in LOW_CARBON_FUELS && it.percentage > 0.0 }
        .sortedByDescending { it.percentage }
    val highCarbonFuels = data.fuelMix
        .filter { it.fuel !in LOW_CARBON_FUELS && it.percentage > 0.0 }
        .sortedByDescending { it.percentage }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header row: title on the left, close button on the right.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Grid Mix",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "As of ${DateTimeFormatters.formatTime(data.fetchedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Two-line summary that mirrors the Home pie's high/low split,
            // so the sheet always answers "how green is the grid right now?"
            // at a glance before the user looks at the full donut.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SummaryStat(
                    label = "Low carbon",
                    percent = data.lowCarbonPercentage,
                    color = BrandColors.LowCarbonGreen,
                    modifier = Modifier.weight(1f)
                )
                SummaryStat(
                    label = "High carbon",
                    percent = data.highCarbonPercentage,
                    color = BrandColors.HighCarbon,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Full multi-fuel donut. This is the "pie chart too" — it gives
            // the same per-fuel breakdown the Home screen used to render
            // before we simplified it, but with room for every slice.
            // Tap a slice to see which fuel it represents.
            DetailedFuelMixPieChart(
                fuelMix = data.fuelMix,
                selectedFuel = selectedFuel,
                onSliceSelected = { fuel -> selectedFuel = fuel },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            FuelGroupSection(
                title = "Low Carbon",
                accent = BrandColors.LowCarbonGreen,
                fuels = lowCarbonFuels
            )

            Spacer(modifier = Modifier.height(12.dp))

            FuelGroupSection(
                title = "High Carbon",
                accent = BrandColors.HighCarbon,
                fuels = highCarbonFuels
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Low carbon includes nuclear, wind, solar, hydro, biomass and imports.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SummaryStat(
    label: String,
    percent: Double,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = String.format(java.util.Locale.UK, "%.1f%%", percent),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailedFuelMixPieChart(
    fuelMix: List<FuelMix>,
    selectedFuel: FuelMix?,
    onSliceSelected: (FuelMix?) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = remember(fuelMix) {
        fuelMix.map { entry -> FUEL_COLORS[entry.fuel] ?: Color(0xFFBDBDBD) }
    }

    // Pre-compute arc angles so the tap detector can identify which slice
    // was hit without recomputing every time.
    val arcRanges = remember(fuelMix) {
        var startAngle = -90f
        fuelMix.map { entry ->
            val sweep = (entry.percentage / 100f * 360f).toFloat()
            val range = startAngle to (startAngle + sweep)
            startAngle += sweep
            range
        }
    }

    val labelText = selectedFuel?.let {
        "${it.fuel.replaceFirstChar { c -> c.uppercase() }}  ${String.format(java.util.Locale.UK, "%.1f%%", it.percentage)}"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(fuelMix) {
                    detectTapGestures { offset ->
                        val strokeWidthPx = 30.dp.toPx()
                        val minSide = minOf(size.width, size.height).toFloat()
                        val diameter = minSide - strokeWidthPx
                        val radius = diameter / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)

                        // Distance from center of donut.
                        val dx = offset.x - center.x
                        val dy = offset.y - center.y
                        val dist = sqrt(dx * dx + dy * dy)

                        // Only register taps within the donut ring
                        // (between inner and outer edge of the stroke).
                        if (dist < radius - strokeWidthPx / 2f ||
                            dist > radius + strokeWidthPx / 2f
                        ) {
                            onSliceSelected(null) // tapped outside the ring
                            return@detectTapGestures
                        }

                        // Convert tap position to angle in degrees
                        // (same coordinate system: -90° is 12 o'clock).
                        val angle = Math.toDegrees(
                            atan2(dy.toDouble(), dx.toDouble())
                        ).toFloat()
                        val normalisedAngle = if (angle < -90f) angle + 360f else angle

                        // Find which slice this angle falls into.
                        val tapped = fuelMix.indices.firstOrNull { i ->
                            val (start, end) = arcRanges[i]
                            normalisedAngle in start..end && fuelMix[i].percentage > 0.0
                        }
                        if (tapped != null) {
                            // Toggle off if tapping the same slice again.
                            onSliceSelected(
                                if (fuelMix[tapped] == selectedFuel) null
                                else fuelMix[tapped]
                            )
                        } else {
                            onSliceSelected(null)
                        }
                    }
                }
        ) {
            val strokeWidth = 30.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)

            // Walk the slice list in API order, accumulating the start
            // angle. Any zero-percent entry is skipped so the donut stays
            // gapless.
            var startAngle = -90f
            for (i in fuelMix.indices) {
                val sweepAngle = (fuelMix[i].percentage / 100f * 360f).toFloat()
                if (sweepAngle > 0f) {
                    val isSelected = fuelMix[i] == selectedFuel
                    drawArc(
                        color = colors[i].let { c ->
                            if (selectedFuel != null && !isSelected) c.copy(alpha = 0.35f)
                            else c
                        },
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(
                            width = if (isSelected) strokeWidth + 4.dp.toPx()
                            else strokeWidth
                        )
                    )
                }
                startAngle += sweepAngle
            }
        }

        // Show fuel name in the centre when a slice is selected,
        // otherwise fall back to the tap hint.
        if (labelText != null) {
            Text(
                text = labelText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = FUEL_COLORS[selectedFuel!!.fuel] ?: MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                text = "Tap a slice",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FuelGroupSection(
    title: String,
    accent: Color,
    fuels: List<FuelMix>,
    contentPadding: PaddingValues = PaddingValues(vertical = 4.dp)
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (fuels.isEmpty()) {
            Text(
                text = "No fuels in this group",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(contentPadding)
            )
        } else {
            fuels.forEachIndexed { index, fuel ->
                FuelRow(fuel = fuel)
                if (index < fuels.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun FuelRow(fuel: FuelMix) {
    val color = FUEL_COLORS[fuel.fuel] ?: Color(0xFFBDBDBD)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = fuel.fuel.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format(java.util.Locale.UK, "%.1f%%", fuel.percentage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
