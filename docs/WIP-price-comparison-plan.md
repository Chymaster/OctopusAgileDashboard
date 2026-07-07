# WIP: Price Comparison Feature — Implementation Plan

> **Status:** Draft
> **Created:** 2026-07-07
> **Branch:** `feature/frontpage-rework` → will create `feature/price-comparison`

---

## 1. Overview

Allow users to compare their **actual Agile costs** against any other Octopus tariff. The app already has historic half-hourly consumption data (via the API key). We fetch the comparison tariff's half-hourly rates for the same period, multiply by the user's consumption, and display a binned "savings vs loss" bar chart.

### Core user flow

1. User taps **"Compare Tariffs"** from the drawer or Dashboard.
2. App fetches available Octopus products via `GET /v1/products/` (public, no auth).
3. User picks a product → app shows the tariff code for their GSP region.
4. App fetches that tariff's half-hourly rates for the user's consumption history window.
5. A new **Compare screen** shows a bar chart: per-day (or per-week) savings/loss vs Agile.
6. Summary card: total saved/lost, average rate difference, best/worst periods.

---

## 2. Data Layer

### 2.1 New API Endpoints

Add to `OctopusApiService.kt`:

```kotlin
// List all products (public, paginated)
@GET("products/")
suspend fun getProducts(
    @Query("brand") brand: String = "OCTOPUS_ENERGY",
    @Query("is_variable") isVariable: Boolean? = null,
    @Query("is_business") isBusiness: Boolean = false,
    @Query("page_size") pageSize: Int = 100
): PaginatedResponse<ProductDto>

@GET
suspend fun getProductsByUrl(@Url url: String): PaginatedResponse<ProductDto>

// Get half-hourly rates for any tariff (public — same shape as Agile rates)
// Already exists as getAgileRates, but it hardcodes the URL pattern.
// We can REUSE getAgileRates since the endpoint shape is identical:
//   /v1/products/{product}/electricity-tariffs/{tariff}/standard-unit-rates/
// The existing method already parameterises product & tariff.
```

**Key insight:** `getAgileRates(product, tariff, periodFrom, periodTo)` already works for *any* tariff, not just Agile. The product/tariff params are fully generic. We only need a new endpoint for listing products.

### 2.2 New DTO: `ProductDto`

```kotlin
@Serializable
data class ProductDto(
    val code: String,              // e.g. "AGILE-24-10-01"
    val direction: String,         // "IMPORT"
    @SerialName("full_name") val fullName: String,
    @SerialName("display_name") val displayName: String,
    val description: String?,
    @SerialName("is_variable") val isVariable: Boolean,
    @SerialName("is_green") val isGreen: Boolean,
    @SerialName("is_tracker") val isTracker: Boolean,
    @SerialName("is_prepay") val isPrepay: Boolean,
    @SerialName("is_business") val isBusiness: Boolean,
    val term: Int?,                // months
    @SerialName("available_from") val availableFrom: String?,
    @SerialName("available_to") val availableTo: String?,
    val links: List<LinkDto>
)

@Serializable
data class LinkDto(
    val href: String,
    val method: String,
    val rel: String
)
```

### 2.3 New Entity: `ComparisonRateEntity`

Mirror of `AgilePriceEntity` but in a separate table for the comparison tariff:

```kotlin
@Entity(
    tableName = "comparison_rates",
    indices = [Index(value = ["validFrom", "validTo"])]
)
data class ComparisonRateEntity(
    @PrimaryKey val validFrom: Long,   // epoch millis
    val validTo: Long,
    val priceExcVat: Double,
    val priceIncVat: Double,
    val tariffCode: String
)
```

**Why a separate table instead of reusing `agile_prices`?**
- The user's Agile prices are their *current* tariff — they should never be evicted by comparison data.
- Comparison data is ephemeral (user switches tariffs frequently during exploration).
- Keeps queries clean — no need to filter by tariff code everywhere.

### 2.4 New DAO: `ComparisonRateDao`

Identical structure to `AgilePriceDao`:

```kotlin
@Dao
interface ComparisonRateDao {
    @Query("SELECT * FROM comparison_rates WHERE validTo > :startMillis AND validFrom < :endMillis ORDER BY validFrom")
    fun observeRange(startMillis: Long, endMillis: Long): Flow<List<ComparisonRateEntity>>

    @Query("SELECT * FROM comparison_rates WHERE validTo > :startMillis AND validFrom < :endMillis ORDER BY validFrom")
    suspend fun queryRange(startMillis: Long, endMillis: Long): List<ComparisonRateEntity>

    @Query("DELETE FROM comparison_rates WHERE validFrom >= :startMillis AND validTo <= :endMillis")
    suspend fun deleteRange(startMillis: Long, endMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ComparisonRateEntity>)

    @Query("DELETE FROM comparison_rates")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM comparison_rates WHERE validTo > :startMillis AND validFrom < :endMillis")
    suspend fun countInRange(startMillis: Long, endMillis: Long): Int
}
```

### 2.5 Database Migration

- Add `ComparisonRateEntity` to `OctopusDatabase.entities`.
- Add `comparisonRateDao()` abstract fun.
- Bump version to **3**.
- Since `fallbackToDestructiveMigration` is enabled, existing data will be wiped on upgrade (acceptable for dev; consider proper migration for production).

### 2.6 CacheStore: `ComparisonRateCacheStore`

Follow the same pattern as `ConsumptionCacheStore` / `StandingChargeCacheStore`:

```
ComparisonRateCacheStore
├── _rates: MutableStateFlow<List<ComparisonRateEntity>>
├── cachedStart / cachedEnd: tracking range
├── loadRates(start, end) — read Room or no-op if covered
├── refreshRates(productCode, tariffCode, start, end) — fetch API, persist, update cache
├── observeRates(start, end): Flow<List<ComparisonRateEntity>>
└── clear()
```

### 2.7 Repository Additions

Add to `OctopusRepository` interface:

```kotlin
// Product listing
suspend fun fetchAvailableProducts(): List<ProductDto>

// Comparison tariff rates
fun observeComparisonRates(start: Instant, end: Instant): Flow<List<ComparisonRateEntity>>
suspend fun refreshComparisonRates(productCode: String, tariffCode: String, start: Instant, end: Instant)
suspend fun loadComparisonRates(start: Instant, end: Instant)
fun clearComparisonRates()

// Comparison result (merged view)
fun observeComparisonData(start: Instant, end: Instant): Flow<List<ComparisonPoint>>
```

New domain model:

```kotlin
data class ComparisonPoint(
    val intervalStart: Instant,
    val intervalEnd: Instant,
    val agileCostIncVat: Double?,       // pence — what user actually paid
    val comparisonCostIncVat: Double?,   // pence — what they would have paid
    val savingPence: Double?,            // positive = saved with Agile, negative = would have saved with comparison
    val consumptionKwh: Double?
)
```

---

## 3. UI Layer

### 3.1 Navigation

Add new route in `Routes.kt`:

```kotlin
const val COMPARE = "compare"
```

Register in `AppNavGraph.kt`. Add drawer entry in `DrawerContent.kt`.

### 3.2 Compare Screen — `CompareScreen.kt`

Located in `ui/compare/`. Structure:

```
┌─────────────────────────────────────┐
│ ← Compare Tariffs          [Drawer] │
├─────────────────────────────────────┤
│  Comparison Tariff: [Dropdown]      │
│  ┌─────────────────────────────────┐│
│  │  Agile Octopus 24-10-01   _A   ││
│  │  vs                            ││
│  │  [Selected Tariff]         _A  ││
│  └─────────────────────────────────┘│
│                                     │
│  Date Range: [7D] [1M] [6M] [1Y]   │
│                                     │
│  ┌─────────────────────────────────┐│
│  │      SAVINGS BAR CHART         ││
│  │  ████                          ││
│  │  ████ ▓▓▓▓                     ││
│  │  ████ ▓▓▓▓ ████               ││
│  │  ████ ▓▓▓▓ ████ ▓▓▓▓         ││
│  │──████─▓▓▓▓─████─▓▓▓▓── £0 ──││
│  │  Mon  Tue  Wed  Thu  ...       ││
│  │  (green = saved, red = lost)   ││
│  └─────────────────────────────────┘│
│                                     │
│  ┌─────────────────────────────────┐│
│  │  SUMMARY CARD                  ││
│  │  Total Saved:   £12.34         ││
│  │  Avg Diff:      -2.1p/kWh      ││
│  │  Best Day:      Wed 3 Jul      ││
│  │  Worst Day:     Mon 7 Jul      ││
│  │  Agile Avg:     18.2p/kWh      ││
│  │  Compare Avg:   24.5p/kWh      ││
│  └─────────────────────────────────┘│
│                                     │
│  ┌─────────────────────────────────┐│
│  │  TARIFF INFO CARD              ││
│  │  Product: Octopus Go           ││
│  │  Type: Variable / Fixed        ││
│  │  Green: Yes ✓                  ││
│  │  Standing Charge: 48.79p/day   ││
│  └─────────────────────────────────┘│
└─────────────────────────────────────┘
```

### 3.3 CompareViewModel

```kotlin
data class CompareUiState(
    val availableProducts: List<ProductDto> = emptyList(),
    val selectedProduct: ProductDto? = null,
    val comparisonPoints: List<ComparisonPoint> = emptyList(),
    val chartBins: List<ComparisonBin> = emptyList(),     // binned for bar chart
    val selectedRange: DateRangeSelection = DateRangeSelection.Preset(TimeRangePreset.SEVEN_DAYS),
    val totalSaving: Double = 0.0,        // pence
    val avgAgilePrice: Double = 0.0,      // p/kWh
    val avgComparisonPrice: Double = 0.0, // p/kWh
    val bestBin: ComparisonBin? = null,
    val worstBin: ComparisonBin? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class ComparisonBin(
    val label: String,           // e.g. "Mon 7 Jul" or "Week 1"
    val savingPence: Double,     // net saving (positive = Agile cheaper)
    val agileCostPence: Double,
    val comparisonCostPence: Double,
    val consumptionKwh: Double
)
```

Binning strategy:
- **7D range** → bin by day (7 bars)
- **1M range** → bin by day (30 bars, scrollable chart)
- **6M range** → bin by week (~26 bars)
- **1Y range** → bin by month (12 bars)

### 3.4 Tariff Selection Bottom Sheet

A modal bottom sheet or dropdown showing:
- Filtered to: `isBusiness = false`, `brand = OCTOPUS_ENERGY`, `direction = IMPORT`
- Grouped by type: Variable, Fixed, Tracker, Agile, Prepay
- Each item shows: display name, product code, green badge, term
- On selection: derives tariff code for user's GSP, fetches rates

### 3.5 Comparison Bar Chart

Reuse the existing chart infrastructure in `ui/chart/`. The bar chart needs:
- Bars above zero line = Agile saved money (green)
- Bars below zero line = Agile cost more (red/amber)
- Zero line clearly marked
- Tap on bar → show detail (agile cost, comparison cost, consumption, saving)
- Consider using `BarChart` from the existing chart component library or Compose Canvas

---

## 4. Implementation Steps (Ordered)

### Phase 1: Data Foundation
1. **Add `ProductDto`** to `data/remote/dto/`
2. **Add `getProducts()` endpoint** to `OctopusApiService`
3. **Add `ComparisonRateEntity`** to `data/local/entity/`
4. **Add `ComparisonRateDao`** to `data/local/dao/`
5. **Update `OctopusDatabase`** — register new entity + DAO, bump version
6. **Update `DatabaseModule`** — provide `ComparisonRateDao`
7. **Add `ComparisonRateCacheStore`** following existing CacheStore pattern
8. **Add `ComparisonPoint` domain model** to `domain/model/`
9. **Extend `OctopusRepository` interface + impl** with comparison methods

### Phase 2: ViewModel + Screen
10. **Create `CompareViewModel`** with Hilt injection
11. **Create `CompareScreen` composable**
12. **Create `ComparisonBarChart`** component (or adapt existing)
13. **Create tariff selection UI** (bottom sheet or dropdown)
14. **Create `CompareSummaryCard`** composable

### Phase 3: Navigation Integration
15. **Add `Routes.COMPARE`**
16. **Register in `AppNavGraph`**
17. **Add drawer entry** in `DrawerContent`
18. **Wire navigation** from Dashboard (optional "Compare" action)

### Phase 4: Polish
19. **Demo mode support** — generate synthetic comparison data
20. **Error handling** — network errors, empty data, API rate limits
21. **Loading states** — skeleton/shimmer while fetching comparison rates
22. **Caching strategy** — cache comparison rates per product to avoid re-fetching

---

## 5. Bonus: Usage Pattern Detection & Load Shifting

> This is a stretch goal — implement after the core comparison feature is working.

### 5.1 Pattern Detection

Analyse consumption data to identify recurring usage spikes:

```kotlin
data class UsagePattern(
    val id: String,
    val name: String,                // "Cooking", "Heating", "Laundry" — auto-detected or user-labelled
    val typicalStartHour: Int,       // e.g. 18
    val typicalDurationSlots: Int,   // e.g. 4 half-hours = 2 hours
    val avgConsumptionKwh: Double,
    val frequency: PatternFrequency, // DAILY, WEEKDAYS, WEEKLY, etc.
    val confidence: Double           // 0.0–1.0
)
```

Detection approach:
- **Sliding window peak detection**: identify contiguous half-hour slots where consumption > 2× the rolling average.
- **Clustering by time-of-day**: group peaks that occur at similar times across days.
- **Duration estimation**: merge adjacent high-consumption slots into a single "event".
- **Frequency analysis**: count how many days the pattern repeats to determine frequency.

### 5.2 Cost Impact Analysis

For each detected pattern, calculate:
- Current cost (at the times the user actually uses energy)
- Optimal cost (if shifted to the cheapest contiguous window of the same duration within ±6 hours)
- Potential saving per occurrence and per month

### 5.3 UI: "Smart Shift" Suggestions

A section on the Compare screen or a dedicated card:

```
┌─────────────────────────────────────┐
│  💡 SHIFT SUGGESTIONS              │
│                                     │
│  🔥 Evening Cooking (6–8pm)        │
│  You use ~2.5 kWh here daily.      │
│  Shifting to 2–4pm would save      │
│  ~£3.20/month on Agile.            │
│  [Apply to Schedule] [Dismiss]     │
│                                     │
│  🧺 Weekend Laundry (10am–12pm)    │
│  You use ~1.8 kWh here weekly.     │
│  Shifting to overnight would save  │
│  ~£0.80/month.                     │
│  [Apply to Schedule] [Dismiss]     │
└─────────────────────────────────────┘
```

### 5.4 Implementation Considerations

- Requires at least 2–4 weeks of consumption data for reliable detection.
- Peak detection threshold should be configurable or adaptive.
- User should be able to label/merge/split detected patterns.
- "Apply to Schedule" could integrate with smart home APIs (future enhancement).
- This feature is **advisory only** — the app doesn't control any devices.

---

## 6. Open Questions

1. **Tariff code derivation for non-Agile products**: The current code derives tariff code as `E-1R-{product}-{gsp}`. Verify this holds for all tariff types (some may use `2R` for Economy 7).
2. **Standing charge inclusion**: Should the comparison include standing charge differences, or only unit rate? (Standing charges can differ significantly between tariffs.)
3. **Rate limiting**: The products endpoint returns ~100 items per page. Should we paginate or just show the first page?
4. **Dual-rate tariffs**: Economy 7/10 tariffs have separate day/night rates. The existing `standard-unit-rates` endpoint may not work for these — may need `day-unit-rates` + `night-unit-rates`.
5. **Historical rate availability**: How far back do non-Agile tariff rates go? Agile has ~2 years of history; fixed tariffs may not.
6. **Chart library**: Use the existing custom Compose Canvas charts, or adopt a library like Vico for bar charts?

---

## 7. Files to Create/Modify

### New Files
| File | Purpose |
|------|---------|
| `data/remote/dto/ProductDto.kt` | Product list DTO |
| `data/local/entity/ComparisonRateEntity.kt` | Room entity for comparison rates |
| `data/local/dao/ComparisonRateDao.kt` | Room DAO |
| `data/local/ComparisonRateCacheStore.kt` | In-memory cache + Room backing |
| `data/mapper/ComparisonRateMapper.kt` | DTO ↔ Entity ↔ Domain mappers |
| `domain/model/ComparisonPoint.kt` | Merged comparison data point |
| `domain/model/UsagePattern.kt` | Detected usage pattern (bonus) |
| `domain/model/Product.kt` | Domain model for product/tariff |
| `ui/compare/CompareScreen.kt` | Main comparison screen |
| `ui/compare/CompareViewModel.kt` | ViewModel |
| `ui/compare/ComparisonBarChart.kt` | Bar chart component |
| `ui/compare/TariffSelectorSheet.kt` | Tariff selection bottom sheet |
| `ui/compare/CompareSummaryCard.kt` | Summary statistics card |

### Modified Files
| File | Change |
|------|--------|
| `data/remote/api/OctopusApiService.kt` | Add `getProducts()` endpoint |
| `data/local/OctopusDatabase.kt` | Register new entity + DAO, bump version |
| `core/di/DatabaseModule.kt` | Provide `ComparisonRateDao` |
| `data/repository/OctopusRepository.kt` | Add comparison methods |
| `data/repository/OctopusRepositoryImpl.kt` | Implement comparison methods |
| `ui/nav/Routes.kt` | Add `COMPARE` route |
| `ui/nav/AppNavGraph.kt` | Register Compare screen |
| `ui/nav/DrawerContent.kt` | Add "Compare Tariffs" entry |
| `core/util/Constants.kt` | Add any comparison-related constants |

---

## 8. Dependencies & Risks

| Risk | Mitigation |
|------|------------|
| Products API may return stale/deprecated tariffs | Filter by `available_to == null` (currently available) |
| Non-Agile tariffs may not have half-hourly rates | Fallback: use the `standard_unit_rate` from product details as a flat rate |
| Large data volumes (1 year × 48 slots/day = 17,520 rows) | Paginate API calls; Room handles this fine |
| Chart performance with many bars | Bin by week/month for longer ranges; use LazyRow for scrollable charts |
| Room schema migration | Using destructive migration (existing pattern); document data loss |
