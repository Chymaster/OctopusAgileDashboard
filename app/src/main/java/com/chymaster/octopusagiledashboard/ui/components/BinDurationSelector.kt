package com.chymaster.octopusagiledashboard.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chymaster.octopusagiledashboard.domain.model.BinDuration

@Composable
fun BinDurationSelector(
    selectedBin: BinDuration,
    onBinSelected: (BinDuration) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BinDuration.entries.forEach { bin ->
            FilterChip(
                selected = bin == selectedBin,
                onClick = { onBinSelected(bin) },
                label = { Text(bin.label) }
            )
        }
    }
}
