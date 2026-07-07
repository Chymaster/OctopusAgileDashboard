package com.chymaster.octopusagiledashboard.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import com.chymaster.octopusagiledashboard.ui.chart.PriceUsageChart
import com.chymaster.octopusagiledashboard.ui.components.CustomDatePickerDialog
import com.chymaster.octopusagiledashboard.ui.components.DemoModeBanner
import com.chymaster.octopusagiledashboard.ui.components.ErrorState
import com.chymaster.octopusagiledashboard.ui.components.PriceRangeCards
import com.chymaster.octopusagiledashboard.ui.components.RangeSelector
import com.chymaster.octopusagiledashboard.ui.components.SummaryCards
import com.chymaster.octopusagiledashboard.ui.detail.CostBreakdownSheet
import com.chymaster.octopusagiledashboard.ui.detail.DataPointDetailSheet
import com.chymaster.octopusagiledashboard.ui.detail.UsageZoneBreakdownSheet

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
    var chartGroup by remember { mutableStateOf(ChartGroup.PRICE_USAGE) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    LaunchedEffect(uiState.flexiblePriceError) {
        uiState.flexiblePriceError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFlexiblePriceError()
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
                title = { Text("Dashboard for Octopus Agile") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "Select custom date range")
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

            // Demo mode banner when credentials are not configured
            if (uiState.isDemoMode) {
                DemoModeBanner(onOpenSettings = onOpenSettings)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Top-of-screen loading indicator — visible while any
            // repository method is still loading or the flow hasn't
            // returned real data yet.
            if (uiState.isChartLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(32.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            when {
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
                                        maxPrice = uiState.maxPrice,
                                        onTotalCostClick = { viewModel.onToggleCostBreakdown() },
                                        onUsageClick = { viewModel.onToggleUsageBreakdown() }
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    PriceRangeCards(
                                        minPrice = uiState.minPrice,
                                        maxPrice = uiState.maxPrice
                                    )
                                }
                                // Overlay loading spinner when transitioning between ranges
                                if (uiState.isLoading && uiState.totalCost == null) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(32.dp)
                                    )
                                }
                                DataPointDetailSheet(
                                    point = selectedPoint,
                                    onDismiss = { viewModel.onPointTapped(null) }
                                )
                            }

                            // Cost breakdown rendered as a separate row so it has
                            // its own height and doesn't get clipped by the parent Box.
                            if (uiState.showCostBreakdown) {
                                CostBreakdownSheet(
                                    visible = true,
                                    usageCost = uiState.usageCost,
                                    standingChargeCost = uiState.standingChargeCost,
                                    totalCost = uiState.totalCost,
                                    onDismiss = { viewModel.onToggleCostBreakdown() }
                                )
                            }

                            // Usage zone breakdown
                            if (uiState.showUsageBreakdown) {
                                UsageZoneBreakdownSheet(
                                    visible = true,
                                    greenKwh = uiState.greenUsageKwh,
                                    amberKwh = uiState.amberUsageKwh,
                                    redKwh = uiState.redUsageKwh,
                                    onDismiss = { viewModel.onToggleUsageBreakdown() }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Chart group selector
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                ChartGroup.entries.forEachIndexed { index, group ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = ChartGroup.entries.size
                                        ),
                                        onClick = { chartGroup = group },
                                        selected = chartGroup == group
                                    ) {
                                        Text(
                                            when (group) {
                                                ChartGroup.PRICE_USAGE -> "Price & Usage"
                                                ChartGroup.COST -> "Cost"
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Chart — always render with displayChartPoints so
                            // the selected time range is visible even before
                            // the Octopus usage API returns data.
                            if (uiState.displayChartPoints.isNotEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    when (chartGroup) {
                                        ChartGroup.PRICE_USAGE -> PriceUsageChart(
                                            points = uiState.displayChartPoints,
                                            referencePrice = uiState.flexiblePrice,
                                            onPointTapped = viewModel::onPointTapped,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                        ChartGroup.COST -> PriceLineChart(
                                            points = uiState.displayChartPoints,
                                            chartMode = ChartMode.COST,
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
    }
}

/**
 * Top-level chart selection on the Dashboard. The "Price & Usage" view shows
 * a dual-axis chart (price bars on the left axis, usage line on the right).
 * The "Cost" view shows the existing single-series cost column chart.
 */
private enum class ChartGroup { PRICE_USAGE, COST }
