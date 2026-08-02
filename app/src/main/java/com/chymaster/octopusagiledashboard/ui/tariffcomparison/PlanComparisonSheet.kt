package com.chymaster.octopusagiledashboard.ui.tariffcomparison

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chymaster.octopusagiledashboard.domain.model.TariffComparison
import com.chymaster.octopusagiledashboard.ui.components.formatCost
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors

/**
 * Inline sheet (Dashboard CostBreakdownSheet-style) shown under the stat row when
 * Total Saving / Total Usage is tapped. Compares the current and selected plans:
 * per-plan usage cost, standing charge and total, plus the total usage (kWh) and
 * the total saving (signed per the saving-perspective toggle).
 */
@Composable
fun PlanComparisonSheet(
    visible: Boolean,
    comparison: TariffComparison,
    saving: Double?,
    onDismiss: () -> Unit
) {
    if (!visible) return

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
            // Header row with title and close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Plan Comparison",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Current vs Selected plan cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlanBreakdownCard(
                    title = "Current Plan",
                    planName = comparison.currentTariffName,
                    usageCost = comparison.usageCostCurrent,
                    standingCharge = comparison.standingChargeCurrent,
                    totalCost = comparison.totalCostCurrent,
                    modifier = Modifier.weight(1f)
                )
                PlanBreakdownCard(
                    title = "Selected Plan",
                    planName = comparison.selectedTariffName,
                    usageCost = comparison.usageCostSelected,
                    standingCharge = comparison.standingChargeSelected,
                    totalCost = comparison.totalCostSelected,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Total usage row
            SummaryRow(
                label = "Total Usage",
                value = String.format(java.util.Locale.UK, "%.1f kWh", comparison.totalKwh)
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Total saving row — same sign/colour logic as the stat card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Total Saving",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = saving?.let(::formatCost) ?: "–",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if ((saving ?: 0.0) >= 0.0) {
                        PriceColors.Cheap
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}

/** One plan's cost breakdown: name + usage / standing / total rows. */
@Composable
private fun PlanBreakdownCard(
    title: String,
    planName: String,
    usageCost: Double?,
    standingCharge: Double?,
    totalCost: Double?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = planName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            BreakdownRow("Usage", usageCost?.let(::formatCost) ?: "–")
            BreakdownRow("Standing", standingCharge?.let(::formatCost) ?: "–")
            BreakdownRow("Total", totalCost?.let(::formatCost) ?: "–", bold = true)
        }
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    value: String,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
