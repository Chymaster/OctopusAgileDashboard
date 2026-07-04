package com.chymaster.octopusagiledashboard.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chymaster.octopusagiledashboard.domain.model.CustomDateRange
import com.chymaster.octopusagiledashboard.domain.model.DateRangeSelection
import com.chymaster.octopusagiledashboard.ui.chart.ChartMode
import com.chymaster.octopusagiledashboard.ui.chart.PriceLineChart
import com.chymaster.octopusagiledashboard.ui.components.CustomDatePickerDialog
import com.chymaster.octopusagiledashboard.ui.components.ErrorState
import com.chymaster.octopusagiledashboard.ui.components.LoadingState
import com.chymaster.octopusagiledashboard.ui.components.PriceRangeCards
import com.chymaster.octopusagiledashboard.ui.components.RangeSelector
import com.chymaster.octopusagiledashboard.ui.components.SummaryCards
import com.chymaster.octopusagiledashboard.ui.detail.DataPointDetailSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenFuturePrices: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }
    var chartMode by remember { mutableStateOf(ChartMode.COST) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    if (showDatePicker) {
        CustomDatePickerDialog(
            onRangeSelected = { startDate, endDate ->
                viewModel.onRangeSelected(DateRangeSelection.Custom(CustomDateRange(startDate, endDate)))
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Octopus Agile") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenFuturePrices) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "Future Prices")
                    }
                    IconButton(onClick = { viewModel.onRefresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Range selector
            RangeSelector(
                selectedRange = uiState.selectedRange,
                onRangeSelected = viewModel::onRangeSelected,
                onCustomRangeClick = { showDatePicker = true }
            )

            // Setup prompt when credentials are not configured
            if (!uiState.hasCredentials) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Set up your Octopus Energy account to see consumption data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = onOpenSettings,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Settings")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                uiState.isLoading && uiState.points.isEmpty() -> {
                    LoadingState()
                }
                uiState.error != null && uiState.points.isEmpty() -> {
                    ErrorState(
                        message = uiState.error ?: "Unknown error",
                        onRetry = { viewModel.onRefresh() }
                    )
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.onRefresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            val selectedPoint = uiState.selectedBinnedPoint

                            // Summary cards with floating detail overlay
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    SummaryCards(
                                        totalCost = uiState.totalCost,
                                        totalKwh = uiState.totalKwh,
                                        avgPrice = uiState.avgPrice,
                                        minPrice = uiState.minPrice,
                                        maxPrice = uiState.maxPrice
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    PriceRangeCards(
                                        minPrice = uiState.minPrice,
                                        maxPrice = uiState.maxPrice
                                    )
                                }
                                DataPointDetailSheet(
                                    point = selectedPoint,
                                    onDismiss = { viewModel.onPointTapped(null) }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Chart mode selector
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                ChartMode.entries.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = ChartMode.entries.size
                                        ),
                                        onClick = { chartMode = mode },
                                        selected = chartMode == mode
                                    ) {
                                        Text(
                                            when (mode) {
                                                ChartMode.PRICE -> "Price"
                                                ChartMode.CONSUMPTION -> "Usage"
                                                ChartMode.COST -> "Cost"
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Chart
                            if (uiState.points.isNotEmpty()) {
                                PriceLineChart(
                                    points = uiState.points,
                                    chartMode = chartMode,
                                    onPointTapped = viewModel::onPointTapped,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
