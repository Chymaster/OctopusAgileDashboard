package com.example.octopusdashboard.ui.components

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
import com.example.octopusdashboard.domain.model.DateRangeSelection
import com.example.octopusdashboard.domain.model.TimeRangePreset

@Composable
fun RangeSelector(
    selectedRange: DateRangeSelection,
    onRangeSelected: (DateRangeSelection) -> Unit,
    onCustomRangeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TimeRangePreset.entries.forEach { preset ->
            val isSelected = selectedRange is DateRangeSelection.Preset &&
                selectedRange.preset == preset
            FilterChip(
                selected = isSelected,
                onClick = { onRangeSelected(DateRangeSelection.Preset(preset)) },
                label = { Text(preset.label) }
            )
        }

        val isCustomSelected = selectedRange is DateRangeSelection.Custom
        FilterChip(
            selected = isCustomSelected,
            onClick = onCustomRangeClick,
            label = { Text("Custom") }
        )
    }
}
