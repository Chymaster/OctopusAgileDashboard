package com.chymaster.octopusagiledashboard.ui.tariffcomparison

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chymaster.octopusagiledashboard.core.util.DateTimeFormatters
import com.chymaster.octopusagiledashboard.ui.chart.BinnedPoint
import com.chymaster.octopusagiledashboard.ui.components.formatCost
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors

/**
 * Detail sheet (Dashboard DataPointDetailSheet-style) shown when a savings-chart bar
 * is tapped. Displays the tapped bin's average saving per kWh, total usage and total
 * saving, signed to match the saving-perspective toggle.
 */
@Composable
fun SavingsDetailSheet(
    point: BinnedPoint?,
    sign: Double,
    showHourOnly: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = point != null,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it })
    ) {
        if (point == null) return@AnimatedVisibility

        val isBinned = point.pointCount > 1
        val signedSavings = (point.totalCost ?: 0.0) * sign
        val usage = point.totalConsumption ?: 0.0
        val isSaving = signedSavings >= 0.0

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header row with time and close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        if (!showHourOnly) {
                            Text(
                                text = DateTimeFormatters.formatDate(point.intervalStart),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = if (showHourOnly) {
                                DateTimeFormatters.formatInterval(point.intervalStart, point.intervalEnd)
                            } else if (isBinned) {
                                "${DateTimeFormatters.formatDateTime(point.intervalStart)} – ${DateTimeFormatters.formatDateTime(point.intervalEnd)}"
                            } else {
                                DateTimeFormatters.formatInterval(point.intervalStart, point.intervalEnd)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Detail row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailCard(
                        title = "Avg Saving per kWh",
                        value = if (usage > 0.0) {
                            DateTimeFormatters.formatPrice(signedSavings / usage)
                        } else {
                            "–"
                        },
                        modifier = Modifier.weight(1f),
                        valueColor = if (isSaving) PriceColors.Cheap else MaterialTheme.colorScheme.error
                    )
                    DetailCard(
                        title = "Total Usage",
                        value = DateTimeFormatters.formatKwh(usage),
                        modifier = Modifier.weight(1f)
                    )
                    DetailCard(
                        title = "Total Savings",
                        value = formatCost(signedSavings),
                        modifier = Modifier.weight(1f),
                        valueColor = if (isSaving) PriceColors.Cheap else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
        }
    }
}
