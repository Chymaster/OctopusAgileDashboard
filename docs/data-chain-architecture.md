# Data Chain Architecture

This document describes how data flows from source to screen in OctopusDashboard, covering both the real API path (when Octopus credentials are configured) and the demo path (when no credentials are set).

## Overview

The app operates in one of two modes, determined automatically by whether the user has configured their Octopus Energy credentials (API key, MPAN, serial number):

| Mode | Trigger | Price Source | Consumption Source | Standing Charge Source | Green Energy | Flexible Price |
|------|---------|-------------|-------------------|----------------------|-------------|---------------|
| **Real** | All three credentials saved | Octopus public API → Room | Octopus authenticated API → Room | Octopus public API → Room | Carbon Intensity API (in-memory) | Octopus public API |
| **Demo** | Any credential missing | Octopus public API → Room | DemoDataGenerator (synthetic) → Room | Octopus public API → Room | Carbon Intensity API (in-memory) | Octopus public API |

**Octopus data types use Room as the unified persistence layer**, regardless of mode. The only mode-dependent data source is **consumption** (synthetic in demo mode, real API in real mode). Prices, standing charges, green energy, and flexible price are always from real APIs.

Green energy data and the flexible tariff price are always fetched live from their respective APIs — they are not mode-dependent.

### Mode Tracking

The single source of truth for demo mode is `UserPreferencesRepository.isDemoMode: Flow<Boolean>`, derived from `hasCredentials`:

```kotlin
val hasCredentials: Flow<Boolean> = combine(apiKeyFlow, mpanFlow, serialNumberFlow) { key, mpan, serial ->
    !key.isNullOrBlank() && !mpan.isNullOrBlank() && !serial.isNullOrBlank()
}
val isDemoMode: Flow<Boolean> = hasCredentials.map { !it }
```

All code that needs to know the mode refers to `isDemoMode` (Flow contexts) or `isDemoMode.first()` (suspend contexts).

## Per-Screen Data Map

| Screen | Data Displayed | Source Chain |
|--------|---------------|-------------|
| **Home** | Current Agile price + 6h timeline | `OctopusRepository.observeAgilePrices()` (in-memory StateFlow backed by Room) |
| **Home** | Flexible tariff reference price | `OctopusRepository.fetchFlexiblePrice()` → cached in DataStore |
| **Home** | Green energy fuel mix pie | `GreenEnergyRepositoryImpl` → Carbon Intensity API (in-memory cache, 15-min TTL) |
| **Dashboard** | HalfHourPoints (price + consumption combined) | `GetDashboardDataUseCase` → `OctopusRepository.observeDashboardData()` combining agile price entities (managed by `OctopusRepositoryImpl`) + `ConsumptionCacheStore` entities |
| **Dashboard** | Standing charge cost | `OctopusRepository.observeStandingCharges()` → `StandingChargeCacheStore` |
| **Dashboard** | Usage cost, total kWh, avg/min/max price | Computed from `HalfHourPoint` list |
| **Dashboard** | Green/amber/red usage zone breakdown | Computed from points + flexible price + user thresholds |
| **Dashboard** | Flexible tariff reference price | `OctopusRepository.fetchFlexiblePrice()` → cached in DataStore |
| **Future Prices** | Half-hourly Agile prices (past + future) | `OctopusRepository` (supports infinite scroll-back via `expandAgilePriceHistoryBackward`) |
| **Future Prices** | Flexible tariff reference price | DataStore cache (set by Home or Dashboard) |
| **Settings** | Credential fields, tariff code | `UserPreferencesRepository` DataStore flows |
| **Drawer** | Demo mode indicator | `UserPreferencesRepository.isDemoMode` |

## Layered Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         UI Layer                                 │
│  HomeScreen / DashboardScreen / FuturePricesScreen / SettingsScreen│
│                     ↕ collectAsState                             │
│  HomeViewModel / DashboardViewModel / FuturePricesViewModel      │
│  (collect preferencesRepository.isDemoMode)                      │
└──────────────────────┬───────────────────────────────────────────┘
                       │ observe*() / refresh*()
┌──────────────────────▼───────────────────────────────────────────┐
│                       Domain Layer                               │
│  GetDashboardDataUseCase / RefreshDashboardDataUseCase           │
└──────────────────────┬───────────────────────────────────────────┘
                       │ delegates to
┌──────────────────────▼───────────────────────────────────────────┐
│                   Repository Layer                                │
│                   OctopusRepositoryImpl                           │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  Unified Cache Stores (in-memory StateFlow + Room backup)   │ │
│  │  Agile prices (inline)      ← prices (StateFlow + Room)    │ │
│  │  ConsumptionCacheStore      ← consumption                   │ │
│  │  StandingChargeCacheStore   ← standing charges              │ │
│  └─────────────────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  GreenEnergyRepositoryImpl  ← grid fuel mix (no Room)       │ │
│  │  fetchFlexiblePrice()       ← live API call (cached in DS)  │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────────┐
│                   Preferences Layer                               │
│  UserPreferencesRepository (DataStore + Encrypted)                │
│  isDemoMode: Flow<Boolean>  ← single source of truth             │
│  hasCredentials: Flow<Boolean>                                    │
│  cachedFlexiblePrice / cachedFlexiblePriceTimestamp               │
└──────────────────────────────────────────────────────────────────┘
```

## Unified Cache Store Pattern

Two of the three Octopus data types (consumption, standing charges) use a dedicated `@Singleton` cache store that:

1. Maintains an in-memory `MutableStateFlow<List<Entity>>` as the reactive source of truth
2. Tracks the cached time range (`cachedStart`/`cachedEnd`) to avoid redundant fetches
3. Branches internally on `isDemoMode` to route to the correct data source
4. Persists to Room for cross-restart caching
5. Provides `observeRange()` (domain objects) and `observeRangeEntities()` (raw entities) as reactive Flows
6. Provides `loadRange()`, `refreshFromApi()`, and `clear()` as imperative mutation methods

### Agile Prices (managed inline by OctopusRepositoryImpl)

```
Public Agile API → agilePriceDao.insertAll() → in-memory StateFlow → UI
```

- Agile prices are managed directly by `OctopusRepositoryImpl` using an in-memory `MutableStateFlow<List<AgilePriceEntity>>` backed by Room.
- Prices are **always fetched from the real Octopus public API** (unauthenticated), regardless of demo mode. The API requires no credentials.
- Default tariff config (`AGILE-24-10-01` + GSP `_L`) is used when no credentials are set.
- Supports `expandAgilePriceHistoryBackward()` for infinite scroll-up in the Future Prices screen.

### ConsumptionCacheStore (synthetic demo / real API)

```
Demo mode:  DemoDataGenerator.generateConsumptionEntities() → consumptionDao.insertAll() → in-memory StateFlow → UI
Real mode:  Octopus Usage API (authenticated) → consumptionDao.insertAll() → in-memory StateFlow → UI
```

- Demo mode generates **synthetic** consumption data using a time-of-day household profile. Data is written to Room for persistence.
- Real mode fetches from the authenticated Octopus usage API (requires mpan + serial). Follows pagination links.
- `clear()` wipes both the in-memory cache and Room (`consumptionDao.deleteAll()`).

### StandingChargeCacheStore (always real API)

```
Public Standing Charge API → standingChargeDao.insertAll() → in-memory StateFlow → UI
```

- Both modes fetch **real** standing charges from the public (unauthenticated) Octopus endpoint.
- In demo mode, uses default tariff config (`Constants.DEFAULT_PRODUCT_CODE` + `Constants.DEFAULT_GSP`) since no credentials are set.
- Data persists in Room across app restarts.

### GreenEnergyRepositoryImpl (always live API, no Room)

```
UK Carbon Intensity API (https://api.carbonintensity.org.uk/generation) → in-memory cache → UI
```

- Fetches real-time UK grid generation mix (wind, solar, nuclear, gas, coal, etc.).
- **No Room persistence** — in-memory cache only, with 15-minute TTL.
- On API failure, returns stale cached data if available.
- Refreshed every 15 minutes by `HomeViewModel.startGreenEnergyRefreshLoop()`.
- Not mode-dependent — same real data in both demo and real mode.

### Flexible Tariff Price (live API, DataStore cache)

```
Octopus public API (flexible product tariff) → DataStore cache → UI
```

- `OctopusRepository.fetchFlexiblePrice()` fetches the current flexible tariff rate from the Octopus public API.
- Uses cascading time windows (tightest first) to find the in-progress rate.
- Cached in `UserPreferencesRepository` DataStore with a 30-day TTL.
- Used by both Home and Dashboard screens as the reference price for green/amber/red zone coloring.
- Not mode-dependent — same real data in both demo and real mode.

## Real API Data Flow

When credentials are present:

```
Octopus API (authenticated for consumption, public for prices/standing charges)
    │
    ▼
Cache Store.refreshFromApi()
    │  DTO → Entity (via mappers)
    ▼
Room DAOs (insertAll with REPLACE strategy)
    │  mergeAndEmit() updates in-memory StateFlow
    ▼
Cache Store.observeRange()
    │  Entity → Domain model
    ▼
OctopusRepositoryImpl → Use Cases → ViewModels → Compose UI
```

**Key characteristics:**
- Room is the persistent cache; the in-memory `StateFlow` is the reactive source of truth
- `observe*` methods return `Flow`s backed by the in-memory cache (filtered by time range)
- `refresh*` methods fetch from the API, write to Room, and reload the in-memory cache
- Room's `OnConflictStrategy.REPLACE` means refreshes atomically overwrite existing rows
- The `AuthInterceptor` adds `Authorization: Basic` headers only to endpoints under `/electricity-meter-points/` and `/consumption/`; public endpoints are not authenticated

## Demo Data Flow

When credentials are absent:

```
DemoDataGenerator               Public Octopus APIs                    Carbon Intensity API
(synthetic consumption)         (real prices + standing charges)       (real grid mix)
         │                              │                                    │
         ▼                              ▼                                    ▼
    ┌─────────────────────────────────────────────────┐              ┌──────────────┐
    │  ConsumptionCacheStore    ← synthetic data      │              │  In-memory   │
    │  OctopusRepositoryImpl    ← real API prices     │              │  cache only  │
    │  StandingChargeCacheStore ← real standing charges│             │  (15m TTL)   │
    │  (all: in-memory StateFlow, backed by Room)     │              └──────────────┘
    └─────────────────────────────────────────────────┘
         │
         ▼
    OctopusRepositoryImpl.observe*()
         │
         ▼
    Use Cases → ViewModels → Compose UI
```

**Key characteristics:**
- **Only consumption is synthetic**: generated by `DemoDataGenerator` with a deterministic time-of-day household profile (requires authenticated API with real meter credentials, so must be synthetic)
- **Prices are real**: fetched from the public Octopus Agile endpoint using default tariff config — no credentials needed
- **Standing charges are real**: fetched from the public Octopus endpoint using default tariff config
- **Green energy is real**: fetched from the UK Carbon Intensity API (same as real mode)
- **Flexible price is real**: fetched from the Octopus public API (same as real mode)
- All Octopus cache stores write to Room for persistence across app restarts
- If the public API is unreachable (offline), cached data from Room is used; if Room is also empty, the cache stays empty

## Cache Wipe on Credential Change

When the user saves credentials in Settings (or removes them), **all Octopus caches are wiped**:

```
SettingsViewModel.save()
    │
    ▼
UserPreferencesRepository.saveCredentials()
    │  persists key/MPAN/serial/GSP to DataStore + encrypted store
    │
    ▼
OctopusRepository.wipeAllCaches()
    ├── Agile prices: clear StateFlow + cachedPricesStart/End + agilePriceDao.deleteAll()
    ├── ConsumptionCacheStore.clear()      (in-memory + Room)
    └── StandingChargeCacheStore.clear()   (in-memory + Room)
```

This is triggered inside `saveCredentials()` via `Lazy<OctopusRepository>` injection (Lazy breaks the circular dependency).

**Not wiped:** Green energy in-memory cache and flexible price DataStore cache. These are not mode-dependent and contain real data regardless.

After the wipe, `isDemoMode` emits a new value. ViewModels re-trigger data loading, which calls `loadRange()` on the cache stores. The cache stores check `isDemoMode.first()` at execution time and route to the correct data source.

## Use Case Layer

| Use Case | Invoked By | What It Does |
|----------|-----------|-------------|
| `GetDashboardDataUseCase` | `DashboardViewModel.loadData()` | Returns `repository.observeDashboardData(start, end)` — a combined Flow of prices + consumption as `HalfHourPoint`s |
| `RefreshDashboardDataUseCase` | `DashboardViewModel.onRefresh()` / `loadData()` | Calls `refreshAgilePrices` + `refreshConsumption` + `refreshStandingCharges` in parallel. Prices are required; consumption and standing charges are optional (won't fail the refresh). |
| `TestConnectionUseCase` | `SettingsViewModel.testConnection()` | Calls `repository.testConnection()` → `apiService.getMeterPoint(mpan)` to verify credentials |

## Key Design Decisions

### Why a single `isDemoMode` flow?

All mode-dependent logic derives from one `Flow<Boolean>` in `UserPreferencesRepository`. This eliminates scattered `!hasCredentials` computations and makes the mode tracking explicit and centralized.

### Why do all cache stores write to Room?

Writing demo data to Room means the app can show data immediately on restart without re-generating. It also means the cache store's `loadRange()` can check Room first (fast) before hitting the API or generator (slower).

### Why `Lazy<OctopusRepository>` in `UserPreferencesRepository`?

`OctopusRepositoryImpl` depends on `UserPreferencesRepository`. Adding a reverse dependency creates a circular singleton initialization. `dagger.Lazy<T>` defers the lookup until `.get()` is called at runtime, breaking the cycle.

### Why is only consumption synthetic in demo mode?

The Octopus Agile prices and standing charges endpoints are both public (unauthenticated), so they are always fetched from the real API regardless of mode. Consumption, however, requires authentication with the user's actual meter credentials (MPAN + serial number), so it must be synthetic in demo mode. `DemoDataGenerator` provides a deterministic time-of-day household consumption profile for this purpose.

### Why is green energy not in Room?

The UK Carbon Intensity API returns a snapshot of the current grid mix. Historical data is not meaningful for the fuel mix pie chart, so a 15-minute in-memory cache is sufficient. Adding Room persistence would add complexity without benefit.

### Why is the flexible price cached in DataStore instead of Room?

The flexible price is a single `Double` value (the current rate), not a time series. DataStore is the natural home for simple key-value preferences. The 30-day TTL prevents stale prices from persisting indefinitely.

## Component Reference

| Component | File | Role |
|-----------|------|------|
| `OctopusApiService` | `data/remote/api/OctopusApiService.kt` | Retrofit interface for Octopus REST API |
| `CarbonIntensityApiService` | `data/remote/api/CarbonIntensityApiService.kt` | Retrofit interface for UK Carbon Intensity API (`https://api.carbonintensity.org.uk/`) |
| `OctopusRepositoryImpl` | `data/repository/OctopusRepositoryImpl.kt` | Central orchestrator: delegates to unified cache stores, builds HalfHourPoints |
| `GreenEnergyRepositoryImpl` | `data/repository/GreenEnergyRepositoryImpl.kt` | Fetches grid generation mix with 15-min in-memory cache |
| `OctopusRepositoryImpl` (agile prices) | `data/repository/OctopusRepositoryImpl.kt` | Agile price management — in-memory StateFlow + Room, always real public API |
| `ConsumptionCacheStore` | `data/local/ConsumptionCacheStore.kt` | Unified consumption cache — synthetic demo or real authenticated API, backed by Room + in-memory StateFlow |
| `StandingChargeCacheStore` | `data/local/StandingChargeCacheStore.kt` | Unified standing charge cache — always real public API, backed by Room + in-memory StateFlow |
| `DemoDataGenerator` | `core/util/DemoDataGenerator.kt` | Pure generator for synthetic consumption entities (demo mode only) |
| `DemoCacheStore` | `data/local/DemoCacheStore.kt` | **No-op placeholder** — consumption migrated to `ConsumptionCacheStore`, standing charges to `StandingChargeCacheStore` |
| Room Database | `data/local/OctopusDatabase.kt` | Persistent cache (agile_prices, consumption, standing_charges tables) |
| `UserPreferencesRepository` | `data/prefs/UserPreferencesRepository.kt` | DataStore-based preferences; source of `isDemoMode`, `hasCredentials`, flexible price cache, thresholds |
| `SecureApiKeyStore` | `data/prefs/SecureApiKeyStore.kt` | EncryptedSharedPreferences wrapper for the API key |
| `AuthInterceptor` | `core/network/AuthInterceptor.kt` | OkHttp interceptor; adds Basic auth only to meter/consumption endpoints |
| `GetDashboardDataUseCase` | `domain/usecase/GetDashboardDataUseCase.kt` | Returns combined prices + consumption Flow as HalfHourPoints |
| `RefreshDashboardDataUseCase` | `domain/usecase/RefreshDashboardDataUseCase.kt` | Parallel refresh of prices (required) + consumption + standing charges (optional) |
| `DashboardViewModel` | `ui/dashboard/DashboardViewModel.kt` | Observes dashboard data, manages range selection, computes cost/usage stats and zone breakdown |
| `HomeViewModel` | `ui/home/HomeViewModel.kt` | Observes current Agile prices, flexible price, and green energy data |
| `FuturePricesViewModel` | `ui/future/FuturePricesViewModel.kt` | Observes upcoming Agile prices with infinite scroll-back support |
| `SettingsViewModel` | `ui/settings/SettingsViewModel.kt` | Manages credential input and save/test flow |
