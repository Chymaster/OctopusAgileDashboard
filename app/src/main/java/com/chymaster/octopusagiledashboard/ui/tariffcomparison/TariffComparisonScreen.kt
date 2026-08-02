package com.chymaster.octopusagiledashboard.ui.tariffcomparison

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chymaster.octopusagiledashboard.domain.model.CustomDateRange
import com.chymaster.octopusagiledashboard.domain.model.DateRangeSelection
import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import com.chymaster.octopusagiledashboard.domain.model.TariffComparison
import com.chymaster.octopusagiledashboard.domain.model.TariffOption
import com.chymaster.octopusagiledashboard.domain.model.TimeRangePreset
import com.chymaster.octopusagiledashboard.ui.chart.CanvasBarChart
import com.chymaster.octopusagiledashboard.ui.chart.ChartBar
import com.chymaster.octopusagiledashboard.ui.chart.binPoints
import com.chymaster.octopusagiledashboard.ui.chart.binPointsByCalendarDay
import com.chymaster.octopusagiledashboard.ui.chart.binPointsByCalendarMonth
import com.chymaster.octopusagiledashboard.ui.chart.binPointsByHourOfDay
import com.chymaster.octopusagiledashboard.ui.components.CustomDatePickerDialog
import com.chymaster.octopusagiledashboard.ui.components.ErrorState
import com.chymaster.octopusagiledashboard.ui.components.LoadingState
import com.chymaster.octopusagiledashboard.ui.components.RangeSelector
import com.chymaster.octopusagiledashboard.ui.components.StatCard
import com.chymaster.octopusagiledashboard.ui.components.formatCost
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TariffComparisonScreen(
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TariffComparisonViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }
    var chartGroup by remember { mutableStateOf(SavingsGraph.OVER_TIME) }
    // Which way the saving is signed. "What I am saving" = selected − current
    // (default); "How much will I save" = current − selected (negated).
    var savingPerspective by rememberSaveable {
        mutableStateOf(SavingPerspective.WHAT_I_AM_SAVING)
    }
    // +1 keeps current − selected; −1 flips to selected − current.
    val savingSign = if (savingPerspective == SavingPerspective.WHAT_I_AM_SAVING) -1.0 else 1.0

    // Surface transient errors (e.g. while stale data is still shown) via snackbar.
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            if (uiState.comparison != null) {
                snackbarHostState.showSnackbar(it)
                viewModel.clearError()
            }
        }
    }

    if (showDatePicker) {
        CustomDatePickerDialog(
            onRangeSelected = { startDate, endDate ->
                viewModel.onRangeSelected(
                    DateRangeSelection.Custom(CustomDateRange(startDate, endDate))
                )
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tariff Comparison") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.onRefresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            RangeSelector(
                selectedRange = uiState.selectedRange,
                onRangeSelected = viewModel::onRangeSelected,
                onCustomRangeClick = { showDatePicker = true }
            )

            TariffSelectorRow(
                tariffs = uiState.curatedTariffs,
                selectedId = uiState.selectedTariff.id,
                onTariffSelected = viewModel::onTariffSelected,
                onMoreClick = viewModel::onMoreTariffsClick
            )

            when {
                uiState.isLoading && uiState.comparison == null -> {
                    LoadingState(modifier = Modifier.weight(1f))
                }
                uiState.error != null && uiState.comparison == null -> {
                    ErrorState(
                        message = uiState.error ?: "Unknown error",
                        onRetry = { viewModel.onRefresh() },
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    val comparison = uiState.comparison
                    if (comparison == null) {
                        ErrorState(
                            message = uiState.error ?: "No comparison data",
                            onRetry = { viewModel.onRefresh() },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            // Saving perspective toggle — flips the sign of the
                            // headline saving and the savings charts.
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                SavingPerspective.entries.forEachIndexed { index, perspective ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = SavingPerspective.entries.size
                                        ),
                                        onClick = { savingPerspective = perspective },
                                        selected = savingPerspective == perspective
                                    ) {
                                        Text(
                                            text = when (perspective) {
                                                SavingPerspective.HOW_MUCH_WILL_I_SAVE -> "How much will I save"
                                                SavingPerspective.WHAT_I_AM_SAVING -> "What I am saving"
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            ComparisonStatRow(
                                comparison = comparison,
                                saving = comparison.totalSaving?.times(savingSign),
                                onTotalSavingClick = viewModel::onTogglePlanDetail,
                                onUsageClick = viewModel::onTogglePlanDetail
                            )

                            if (uiState.showPlanDetail) {
                                PlanComparisonSheet(
                                    visible = true,
                                    comparison = comparison,
                                    saving = comparison.totalSaving?.times(savingSign),
                                    onDismiss = viewModel::onTogglePlanDetail
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Chart group selector
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                SavingsGraph.entries.forEachIndexed { index, group ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = SavingsGraph.entries.size
                                        ),
                                        onClick = { chartGroup = group },
                                        selected = chartGroup == group
                                    ) {
                                        Text(
                                            text = when (group) {
                                                SavingsGraph.OVER_TIME -> "Savings over time"
                                                SavingsGraph.BY_HOUR -> "24h breakdown"
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            if (uiState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(8.dp)
                                        .size(24.dp)
                                )
                            }

                            SavingsChart(
                                savingsPoints = comparison.savingsPoints,
                                selectedRange = uiState.selectedRange,
                                chartGroup = chartGroup,
                                sign = savingSign
                            )
                        }
                    }
                }
            }
        }
    }

    if (uiState.showTariffPicker) {
        TariffPickerSheet(
            availableTariffs = uiState.availableTariffs,
            historicalTariffs = uiState.historicalTariffs,
            isLoading = uiState.isProductsLoading,
            error = uiState.productsError,
            selectedId = uiState.selectedTariff.id,
            onSelect = viewModel::onTariffSelected,
            onRetry = viewModel::loadAvailableTariffs,
            onDismiss = viewModel::onDismissTariffPicker
        )
    }
}

/** Two stat boxes: total saving and total usage, both opening the plan detail sheet. */
@Composable
private fun ComparisonStatRow(
    comparison: TariffComparison,
    saving: Double?,
    onTotalSavingClick: () -> Unit,
    onUsageClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(
            title = "Total Saving",
            value = saving?.let(::formatCost) ?: "–",
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onTotalSavingClick),
            valueColor = if ((saving ?: 0.0) >= 0.0) {
                PriceColors.Cheap
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        StatCard(
            title = "Total Usage",
            value = String.format(java.util.Locale.UK, "%.1f kWh", comparison.totalKwh),
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onUsageClick)
        )
    }
    Text(
        text = "Comparing ${comparison.currentTariffName} vs ${comparison.selectedTariffName}" +
            " · ${comparison.comparedSlotCount}/${comparison.totalSlotCount} slots compared",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

/** Horizontally-scrollable quick-pick tariff chips + a "More…" chip. */
@Composable
private fun TariffSelectorRow(
    tariffs: List<TariffOption>,
    selectedId: String,
    onTariffSelected: (TariffOption) -> Unit,
    onMoreClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tariffs.forEach { option ->
            FilterChip(
                selected = option.id == selectedId,
                onClick = { onTariffSelected(option) },
                label = { Text(option.displayName) }
            )
        }
        FilterChip(
            selected = tariffs.none { it.id == selectedId },
            onClick = onMoreClick,
            label = { Text("More…") }
        )
    }
}

/** The savings chart, binned either over time (Dashboard-style) or by hour-of-day. */
@Composable
private fun SavingsChart(
    savingsPoints: List<HalfHourPoint>,
    selectedRange: DateRangeSelection,
    chartGroup: SavingsGraph,
    sign: Double
) {
    // Same binning flags as the Dashboard: calendar-day for 7D, calendar-month
    // for 6M/1Y, auto ≤20 bars otherwise.
    val useCalendarMonthBinning = when (val sel = selectedRange) {
        is DateRangeSelection.Preset ->
            sel.preset == TimeRangePreset.SIX_MONTHS || sel.preset == TimeRangePreset.ONE_YEAR
        else -> false
    }
    val useCalendarDayBinning = when (val sel = selectedRange) {
        is DateRangeSelection.Preset -> sel.preset == TimeRangePreset.SEVEN_DAYS
        else -> false
    }

    val graph1Binned = remember(savingsPoints, useCalendarMonthBinning, useCalendarDayBinning) {
        when {
            useCalendarDayBinning -> binPointsByCalendarDay(savingsPoints)
            useCalendarMonthBinning -> binPointsByCalendarMonth(savingsPoints)
            else -> binPoints(savingsPoints)
        }
    }
    val graph2Binned = remember(savingsPoints) { binPointsByHourOfDay(savingsPoints) }
    val binned = if (chartGroup == SavingsGraph.OVER_TIME) graph1Binned else graph2Binned

    val bars = remember(binned, sign) {
        binned.map { bin ->
            ChartBar(
                label = "",   // CanvasBarChart derives x-labels from intervalStart
                value = (bin.totalCost ?: 0.0) / 100.0 * sign,   // pence → £
                intervalStart = bin.intervalStart,
                intervalEnd = bin.intervalEnd,
                unitLabel = "£"
            )
        }
    }
    val barColors = remember(bars) {
        bars.map { if (it.value >= 0.0) PriceColors.Cheap else PriceColors.Expensive }
    }
    // Pad the range so positive/negative bars sit comfortably around the zero baseline.
    val yMax = remember(bars) { maxOf(bars.maxOfOrNull { it.value } ?: 0.0, 1.0) * 1.1 }
    val yMin = remember(bars) {
        val min = bars.minOfOrNull { it.value } ?: 0.0
        if (min < 0.0) min * 1.1 else 0.0
    }

    if (bars.isEmpty()) {
        Text(
            text = "No savings data for this range",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        )
    } else {
        CanvasBarChart(
            bars = bars,
            barColors = barColors,
            yMin = yMin,
            yMax = yMax,
            // Fit-mode for both graphs so they fill the screen width. The
            // 24-bar hour-of-day view bins into fixed hourly slots, so it
            // renders fit-mode with thinner bars rather than scrolling.
            isScrollable = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )
    }
}

/** "More…" bottom sheet listing current + historical import products, with search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TariffPickerSheet(
    availableTariffs: List<TariffOption>,
    historicalTariffs: List<TariffOption>,
    isLoading: Boolean,
    error: String?,
    selectedId: String,
    onSelect: (TariffOption) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val current = availableTariffs.filter { matchesQuery(it, query) }
    val historical = historicalTariffs.filter { matchesQuery(it, query) }
    val hasResults = current.isNotEmpty() || historical.isNotEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Couldn't load tariffs",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search tariffs…") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true
                    )
                    if (!hasResults) {
                        Text(
                            text = if (query.isBlank()) {
                                "No tariffs available"
                            } else {
                                "No matching tariffs"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                        ) {
                            if (current.isNotEmpty()) {
                                item(key = "current-header") {
                                    PickerSectionHeader("Currently available")
                                }
                                items(current, key = { it.id }) { option ->
                                    TariffPickerRow(option, selectedId, onSelect)
                                }
                            }
                            if (historical.isNotEmpty()) {
                                item(key = "historical-header") {
                                    PickerSectionHeader("Historical (no longer on sale)")
                                }
                                items(historical, key = { it.id }) { option ->
                                    TariffPickerRow(option, selectedId, onSelect)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** Section header inside the tariff picker. */
@Composable
private fun PickerSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

/** One tappable tariff row in the picker. */
@Composable
private fun TariffPickerRow(
    option: TariffOption,
    selectedId: String,
    onSelect: (TariffOption) -> Unit
) {
    ListItem(
        headlineContent = {
            Text(option.displayName, fontWeight = FontWeight.Medium)
        },
        supportingContent = {
            Text(
                text = option.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            if (option.id == selectedId) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        modifier = Modifier.clickable { onSelect(option) }
    )
}

/** Search predicate: matches display name or product code, case-insensitive. */
private fun matchesQuery(option: TariffOption, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return option.displayName.contains(q, ignoreCase = true) ||
        option.id.contains(q, ignoreCase = true)
}

private enum class SavingsGraph { OVER_TIME, BY_HOUR }

/**
 * Which sign the "saving" numbers use. The use case computes
 * current − selected ("How much will I save"); "What I am saving" is the
 * negation (selected − current).
 */
private enum class SavingPerspective { HOW_MUCH_WILL_I_SAVE, WHAT_I_AM_SAVING }
