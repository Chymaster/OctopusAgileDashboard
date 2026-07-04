package com.chymaster.octopusagiledashboard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    viewModel: AdvancedSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar("Settings saved")
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Price Colour Thresholds",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Customise when prices are shown as green (cheap), amber (moderate), or red (expensive) based on their percentage of the Flexible Octopus price.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Cheap (green) threshold
            ThresholdSlider(
                label = "Green → Amber boundary",
                description = "Prices below this % of the flexible price are shown as green (cheap)",
                value = uiState.cheapPercent,
                valueRange = 30..150,
                onValueChange = viewModel::onCheapPercentChange,
                colorLabel = "Green"
            )

            // Moderate (amber) threshold
            ThresholdSlider(
                label = "Amber → Red boundary",
                description = "Prices above this % of the flexible price are shown as red (expensive)",
                value = uiState.moderatePercent,
                valueRange = 80..250,
                onValueChange = viewModel::onModeratePercentChange,
                colorLabel = "Red"
            )

            // Summary
            Text(
                text = buildString {
                    append("Current zones: ")
                    append("Green < ${uiState.cheapPercent}%")
                    append(" · ")
                    append("Amber ${uiState.cheapPercent}%–${uiState.moderatePercent}%")
                    append(" · ")
                    append("Red > ${uiState.moderatePercent}%")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = viewModel::resetToDefaults,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset to defaults")
                }

                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isSaving
                ) {
                    Text("Save")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ThresholdSlider(
    label: String,
    description: String,
    value: Int,
    valueRange: IntRange,
    onValueChange: (Int) -> Unit,
    colorLabel: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "$value%",
                style = MaterialTheme.typography.bodyLarge,
                color = when (colorLabel) {
                    "Green" -> PriceColors.Cheap
                    "Red" -> PriceColors.Expensive
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = (valueRange.last - valueRange.first) / 5 - 1,
            modifier = Modifier.fillMaxWidth()
        )

        // Min/max labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${valueRange.first}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${valueRange.last}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
