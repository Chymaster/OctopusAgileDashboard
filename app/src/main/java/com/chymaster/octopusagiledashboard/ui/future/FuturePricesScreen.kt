package com.chymaster.octopusagiledashboard.ui.future

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.ui.components.ErrorState
import com.chymaster.octopusagiledashboard.ui.components.LoadingState
import com.chymaster.octopusagiledashboard.ui.components.SingleDatePickerDialog
import com.chymaster.octopusagiledashboard.ui.theme.PriceColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.SortedMap

private val londonZone = ZoneId.of("Europe/London")
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.UK)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuturePricesScreen(
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: FuturePricesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Group prices by date
    val groupedPrices = remember(uiState.prices) {
        uiState.prices.groupBy { price ->
            price.validFrom.atZone(londonZone).toLocalDate()
        }.toSortedMap()
    }

    // Find the index of the current 30-min slot for auto-scroll-to-now
    val today = LocalDate.now(londonZone)
    val now = Instant.now()
    val currentSlotIndex = remember(groupedPrices) {
        findCurrentSlotIndex(groupedPrices, today, now)
    }

    // Track whether the initial scroll has happened
    var hasScrolledInitially by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(groupedPrices) {
        if (!hasScrolledInitially && groupedPrices.isNotEmpty() && currentSlotIndex > 0) {
            listState.animateScrollToItem(currentSlotIndex)
            hasScrolledInitially = true
        }
    }

    // Handle scroll-to-target from date picker
    val scrollTargetDate = uiState.scrollTargetDate
    LaunchedEffect(scrollTargetDate) {
        if (scrollTargetDate != null) {
            val targetIndex = findDateSectionIndex(groupedPrices, scrollTargetDate)
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
            }
            viewModel.onScrollToTargetConsumed()
        }
    }

    // Infinite scroll-up: detect when user scrolls near the top
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (index <= 3 && !uiState.isLoadingOlder) {
                    viewModel.loadOlderPrices()
                }
            }
    }

    // Maintain scroll position when older items are prepended
    val previousItemCount = remember { mutableIntStateOf(0) }
    LaunchedEffect(groupedPrices.size) {
        val newItems = groupedPrices.values.sumOf { it.size } + groupedPrices.size
        val addedItems = newItems - previousItemCount.intValue
        if (addedItems > 0 && previousItemCount.intValue > 0) {
            listState.scrollToItem(addedItems + listState.firstVisibleItemIndex)
        }
        previousItemCount.intValue = newItems
    }

    // "Jump to now" FAB visibility
    val isNearNow by remember {
        derivedStateOf {
            val firstVisible = listState.firstVisibleItemIndex
            // Show FAB if we're more than 20 items away from the current slot
            kotlin.math.abs(firstVisible - currentSlotIndex) > 20
        }
    }

    // Trigger for "jump to now" FAB
    var scrollToNowTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrollToNowTrigger) {
        if (scrollToNowTrigger > 0 && currentSlotIndex > 0) {
            listState.animateScrollToItem(currentSlotIndex)
        }
    }

    // Date picker state
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        SingleDatePickerDialog(
            onDateSelected = { date ->
                viewModel.jumpToDate(date)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agile Prices") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.DateRange, contentDescription = "Go to date")
                    }
                    IconButton(onClick = { viewModel.onRefresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (isNearNow && groupedPrices.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { scrollToNowTrigger++ },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "Now",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    ) { paddingValues ->
        when {
            uiState.isLoading && uiState.prices.isEmpty() -> {
                LoadingState()
            }
            uiState.error != null && uiState.prices.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: "Unknown error",
                    onRetry = { viewModel.onRefresh() }
                )
            }
            else -> {
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Loading indicator at top for infinite scroll
                        if (uiState.isLoadingOlder) {
                            item(key = "loading_older") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }

                        for ((date, prices) in groupedPrices) {
                            // Date section header
                            item(key = "header_$date") {
                                val isToday = date == today
                                val isFuture = date > today
                                val label = when {
                                    isToday -> "Today"
                                    isFuture -> date.format(dateFormatter)
                                    else -> date.format(dateFormatter)
                                }
                                DateSectionHeader(
                                    label = label,
                                    isToday = isToday,
                                    isFuture = isFuture
                                )
                            }

                            // Price items
                            items(
                                items = prices,
                                key = { it.validFrom.toEpochMilli() }
                            ) { price ->
                                PriceRow(
                                    price = price,
                                    flexiblePrice = uiState.flexiblePrice,
                                    cheapPercent = uiState.cheapThresholdPercent,
                                    moderatePercent = uiState.moderateThresholdPercent
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * Find the flat list index of the current half-hour slot for auto-scroll.
 */
private fun findCurrentSlotIndex(
    groupedPrices: SortedMap<LocalDate, List<AgilePrice>>,
    today: LocalDate,
    now: Instant
): Int {
    var index = 0
    for ((date, prices) in groupedPrices) {
        index++ // section header
        for (price in prices) {
            if (price.validFrom <= now && price.validTo > now) {
                return index
            }
            index++
        }
    }
    // Fallback: find today's section header
    var fallbackIndex = 0
    for ((date, prices) in groupedPrices) {
        if (date == today) return fallbackIndex
        fallbackIndex++ // section header
        fallbackIndex += prices.size
    }
    return 0
}

/**
 * Find the flat list index of a date's section header.
 */
private fun findDateSectionIndex(
    groupedPrices: SortedMap<LocalDate, List<AgilePrice>>,
    targetDate: LocalDate
): Int {
    var index = 0
    for ((date, prices) in groupedPrices) {
        if (date == targetDate) return index
        index++ // section header
        index += prices.size
    }
    return -1
}

@Composable
private fun DateSectionHeader(
    label: String,
    isToday: Boolean,
    isFuture: Boolean
) {
    val bgColor = when {
        isToday -> MaterialTheme.colorScheme.primaryContainer
        isFuture -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
        isFuture -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

@Composable
private fun PriceRow(
    price: AgilePrice,
    flexiblePrice: Double? = null,
    cheapPercent: Int = PriceColors.DEFAULT_CHEAP_PERCENT,
    moderatePercent: Int = PriceColors.DEFAULT_MODERATE_PERCENT
) {
    val zoned = price.validFrom.atZone(londonZone)
    val color = PriceColors.priceColor(price.priceIncVat, flexiblePrice, cheapPercent, moderatePercent)
    val now = Instant.now()
    val isPast = price.validTo < now
    val isCurrent = price.validFrom <= now && price.validTo > now

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Time
            Text(
                text = zoned.format(timeFormatter),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPast) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.width(50.dp)
            )

            // Price color indicator
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Price value
            Text(
                text = String.format(Locale.UK, "%.1f p/kWh", price.priceIncVat),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isPast) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                } else {
                    color
                },
                modifier = Modifier.weight(1f)
            )

            // Current indicator
            if (isCurrent) {
                Text(
                    text = "NOW",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
