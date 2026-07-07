package com.chymaster.octopusagiledashboard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.chymaster.octopusagiledashboard.ui.components.ErrorState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    onBack: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Status") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.error != null) {
            ErrorState(
                message = uiState.error ?: "Unknown error",
                onRetry = { viewModel.loadStatus() },
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                DataAvailabilityCard(
                    title = "Consumption Data",
                    totalRecords = uiState.consumption.totalRecords,
                    earliestMillis = uiState.consumption.earliestMillis,
                    latestMillis = uiState.consumption.latestMillis
                )

                DataAvailabilityCard(
                    title = "Price Data",
                    totalRecords = uiState.prices.totalRecords,
                    earliestMillis = uiState.prices.earliestMillis,
                    latestMillis = uiState.prices.latestMillis
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DataAvailabilityCard(
    title: String,
    totalRecords: Int,
    earliestMillis: Long?,
    latestMillis: Long?
) {
    val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
        .withZone(ZoneId.systemDefault())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (totalRecords == 0) {
                Text(
                    text = "No data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                StatusRow(label = "Total records", value = formatNumber(totalRecords))

                if (earliestMillis != null) {
                    StatusRow(
                        label = "Earliest",
                        value = dateFormatter.format(Instant.ofEpochMilli(earliestMillis))
                    )
                }

                if (latestMillis != null) {
                    StatusRow(
                        label = "Latest",
                        value = dateFormatter.format(Instant.ofEpochMilli(latestMillis))
                    )
                }

                if (earliestMillis != null && latestMillis != null) {
                    val days = ((latestMillis - earliestMillis) / (1000 * 60 * 60 * 24)).toInt()
                    StatusRow(label = "Span", value = "$days days")
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private fun formatNumber(n: Int): String {
    return if (n >= 1000) {
        val whole = n / 1000
        val frac = (n % 1000) / 100
        if (frac > 0) "${whole}.${frac}k" else "${whole}k"
    } else {
        n.toString()
    }
}
