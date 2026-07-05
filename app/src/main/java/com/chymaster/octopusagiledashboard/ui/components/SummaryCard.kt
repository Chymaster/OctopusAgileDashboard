package com.chymaster.octopusagiledashboard.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SummaryCards(
    totalCost: Double?,
    totalKwh: Double?,
    avgPrice: Double?,
    minPrice: Double?,
    maxPrice: Double?,
    onTotalCostClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard(
            title = "Total Cost",
            value = if (totalCost != null) formatCost(totalCost) else "–",
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onTotalCostClick)
        )
        SummaryCard(
            title = "Usage",
            value = if (totalKwh != null) String.format(java.util.Locale.UK, "%.1f kWh", totalKwh) else "–",
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onUsageClick)
        )
        SummaryCard(
            title = "Avg Price",
            value = if (avgPrice != null) String.format(java.util.Locale.UK, "%.1f p/kWh", avgPrice) else "–",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun PriceRangeCards(
    minPrice: Double?,
    maxPrice: Double?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard(
            title = "Lowest",
            value = if (minPrice != null) String.format(java.util.Locale.UK, "%.1f p/kWh", minPrice) else "–",
            modifier = Modifier.weight(1f),
            valueColor = MaterialTheme.colorScheme.primary
        )
        SummaryCard(
            title = "Highest",
            value = if (maxPrice != null) String.format(java.util.Locale.UK, "%.1f p/kWh", maxPrice) else "–",
            modifier = Modifier.weight(1f),
            valueColor = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
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

private fun formatCost(pence: Double): String {
    return if (pence >= 100) {
        String.format(java.util.Locale.UK, "£%.2f", pence / 100.0)
    } else {
        String.format(java.util.Locale.UK, "%.1f p", pence)
    }
}
