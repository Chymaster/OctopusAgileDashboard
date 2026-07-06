# Data Chain Architecture

This document describes how data flows from source to screen in OctopusDashboard, covering both the real API path (when Octopus credentials are configured) and the demo path (when no credentials are set).

## Overview

The app operates in one of two modes, determined automatically by whether the user has configured their Octopus Energy credentials (API key, MPAN, serial number):

| Mode | Trigger | Price Source | Consumption Source |
|------|---------|-------------|-------------------|
| **Real** | All three credentials saved | Octopus authenticated API → Room | Octopus authenticated API → Room |
| **Demo** | Any credential missing | Octopus public API (unauthenticated) → DemoCacheStore | DemoDataGenerator (synthetic) → DemoCacheStore |

There is no explicit "demo mode" toggle. The mode is inferred from `UserPreferencesRepository.hasCredentials`, a `Flow<Boolean>` that emits `true` only when API key, MPAN, and serial number are all non-blank.

## Layered Architecture

```
┌─────────────────────────────────────────────────────┐
│                    UI Layer                          │
│  HomeScreen / DashboardScreen / FuturePricesScreen   │
│              ↕ collectAsState                        │
│  HomeViewModel / DashboardViewModel / FuturePricesVM │
└──────────────────────┬──────────────────────────────┘
                       │ observe*() / refresh*()
┌──────────────────────▼──────────────────────────────┐
│                  Domain Layer                        │
│  GetDashboardDataUseCase / RefreshDashboardDataUseCase│
└──────────────────────┬──────────────────────────────┘
                       │ delegates to
┌──────────────────────▼──────────────────────────────┐
│              Repository Layer                        │
│              OctopusRepositoryImpl                    │
│  ┌─────────────────┐  ┌──────────────────────────┐  │
│  │  Real API Path   │  │    Demo Data Path        │  │
│  │  OctopusApiService│  │  DemoDataGenerator       │  │
│  │       ↓          │  │       ↓                  │  │
│  │    Room DAOs      │  │  DemoCacheStore          │  │
│  └─────────────────┘  └──────────────────────────┘  │
│                       ↑                              │
│          hasCredentials.transformLatest               │
└──────────────────────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│              Preferences Layer                       │
│  UserPreferencesRepository (DataStore + Encrypted)   │
│  hasCredentials: Flow<Boolean>                       │
└──────────────────────────────────────────────────────┘
```

## Real API Data Flow

When credentials are present:

```
Octopus API (authenticated)
    │
    ▼
refreshAgilePrices() / refreshConsumption() / refreshStandingCharges()
    │  DTO → Entity (via mappers)
    ▼
Room DAOs (insertAll with REPLACE strategy)
    │  Room emits via Flow on table change
    ▼
OctopusRepositoryImpl.observe*()
    │  Entity → Domain model
    ▼
Use Cases → ViewModels → Compose UI
```

**Key characteristics:**
- Room is the single source of truth and the persistent cache
- `observe*` methods return `Flow`s backed by Room DAO queries
- `refresh*` methods fetch from the API and write to Room
- Room's `OnConflictStrategy.REPLACE` means refreshes atomically overwrite existing rows without a delete-first step (no flash of empty data)
- The `AuthInterceptor` adds `Authorization: Basic` headers only to endpoints under `/electricity-meter-points/` and `/consumption/`; public endpoints (product/tariff rates) are not authenticated

## Demo Data Flow

When credentials are absent:

```
                    ┌──────────────────┐
                    │  Public Agile API │ (unauthenticated)
                    │  (real prices)    │
                    └────────┬─────────┘
                             │
DemoDataGenerator            │
(synthetic consumption)      │
         │                   │
         ▼                   ▼
    DemoCacheStore (in-memory StateFlow cache)
    ├── prices        ← public API (real market data)
    ├── consumption   ← DemoDataGenerator (synthetic)
    └── standingCharges ← DemoDataGenerator (synthetic)
         │
         ▼
    OctopusRepositoryImpl.observe*()
         │  transformLatest(hasCredentials)
         ▼
    Use Cases → ViewModels → Compose UI
```

**Key characteristics:**
- `DemoCacheStore` is a `@Singleton` in-memory cache backed by `MutableStateFlow` — data does not persist across app restarts
- **Prices are real**: the public Octopus Agile endpoint (`/products/{product}/electricity-tariffs/{tariff}/standard-unit-rates/`) does not require authentication. The app fetches real UK market prices at seed time and on every refresh
- **Consumption is synthetic**: `DemoDataGenerator` produces deterministic half-hour consumption data using a time-of-day profile (overnight baseline, morning/evening peaks) seeded by epoch second so the same date always yields the same data
- If the public API is unreachable (offline), the price cache stays empty — no synthetic price data is ever written
- Standing charges are synthetic (fixed 50p/day) since the standing charge endpoint requires authentication

## Cache Wipe on Credential Change

When the user saves credentials in Settings (or removes them), **both caches are wiped**:

```
SettingsViewModel.save()
    │
    ▼
UserPreferencesRepository.saveCredentials()
    │  persists key/MPAN/serial/GSP to DataStore + encrypted store
    │
    ▼
OctopusRepository.wipeAllCaches()
    ├── DemoCacheStore.clearAll()   (in-memory)
    └── Room: deleteAll() on all three tables
```

This is triggered inside `saveCredentials()` via `Lazy<OctopusRepository>` injection (Lazy breaks the circular dependency: `UserPreferencesRepository` → `OctopusRepository` → `UserPreferencesRepository`).

After the wipe, `hasCredentials` emits a new value. All active `observe*` flows are running `transformLatest(hasCredentials)`, which:
1. Cancels the current inner subscription (demo or real)
2. Switches to the new mode's data source
3. Auto-seeds the demo cache if switching to demo mode

## Auto-Seed on Empty Cache

Each `observe*` method in the repository follows the same pattern:

```kotlin
preferencesRepository.hasCredentials
    .distinctUntilChanged()
    .transformLatest { hasCreds ->
        if (!hasCreds) {
            ensureDemo*Seeded(start, end)  // populate if empty
            emitAll(demoCacheStore.*.map { ... })
        } else {
            emitAll(dao.observeRange(...).map { ... })
        }
    }
```

The `ensureDemo*Seeded()` helpers check if the cache is empty and populate it before the first `emitAll()`. This guarantees the first emission from the `StateFlow` is non-empty — no flicker.

- `ensureDemoPricesSeeded()` — calls the public Agile API; on failure, cache stays empty
- `ensureDemoConsumptionSeeded()` — calls `DemoDataGenerator.generateConsumptionEntities()`
- `ensureDemoStandingChargeSeeded()` — calls `DemoDataGenerator.generateStandingChargeEntity()`

## Key Design Decisions

### Why `transformLatest` instead of `flatMapLatest`?

`transformLatest` provides a `FlowCollector` with `emitAll`, letting us run synchronous operations (like seeding the cache) before subscribing to the inner flow. `flatMapLatest` only returns a `Flow` — there's no hook to run code between the outer emission and the inner subscription.

### Why `distinctUntilChanged()` on `hasCredentials`?

`hasCredentials` is derived from combining three flows (`apiKeyFlow`, `mpanFlow`, `serialNumberFlow`). A single `saveCredentials()` call can trigger multiple emissions. `distinctUntilChanged()` collapses rapid-fire emissions of the same boolean value, preventing the inner flow from being cancelled and restarted unnecessarily.

### Why `Lazy<OctopusRepository>` in `UserPreferencesRepository`?

`OctopusRepositoryImpl` depends on `UserPreferencesRepository` (for credential flows). Adding a reverse dependency (`UserPreferencesRepository` → `OctopusRepository`) creates a circular singleton initialization. `dagger.Lazy<T>` defers the lookup until `.get()` is called at runtime, breaking the cycle. Hilt handles this natively.

### Why does `refreshAgilePrices` write to both DemoCacheStore AND Room in demo mode?

Room write is a fallback. If the app switches from demo to real mode (user adds credentials), the Room tables already contain recent public API prices. The real-mode `insertAll` with `REPLACE` strategy will overwrite them by primary key with properly tariffed data. Without the Room write, switching modes would show empty data until the real refresh completes.

### Why are demo prices real but consumption synthetic?

The Octopus Agile prices endpoint is public (unauthenticated) and returns real UK market data. The consumption endpoint requires authentication with the user's actual meter credentials. In demo mode we have no real meter, so consumption must be synthetic.

## Component Reference

| Component | File | Role |
|-----------|------|------|
| `OctopusApiService` | `data/remote/api/OctopusApiService.kt` | Retrofit interface for Octopus REST API |
| `OctopusRepositoryImpl` | `data/repository/OctopusRepositoryImpl.kt` | Central orchestrator: routes observe/refresh between demo and real paths |
| `DemoDataGenerator` | `core/util/DemoDataGenerator.kt` | Pure generator for synthetic consumption, prices, standing charges |
| `DemoCacheStore` | `data/local/DemoCacheStore.kt` | In-memory `@Singleton` cache with `MutableStateFlow` lists |
| Room Database | `data/local/OctopusDatabase.kt` | Persistent cache for real data (agile_prices, consumption, standing_charges tables) |
| `UserPreferencesRepository` | `data/prefs/UserPreferencesRepository.kt` | DataStore-based preferences; source of `hasCredentials` flow |
| `SecureApiKeyStore` | `data/prefs/SecureApiKeyStore.kt` | EncryptedSharedPreferences wrapper for the API key |
| `AuthInterceptor` | `core/network/AuthInterceptor.kt` | OkHttp interceptor; adds Basic auth only to meter/consumption endpoints |
| `DashboardViewModel` | `ui/dashboard/DashboardViewModel.kt` | Observes dashboard data, manages range selection and UI state |
| `HomeViewModel` | `ui/home/HomeViewModel.kt` | Observes current Agile prices and grid carbon intensity |
| `FuturePricesViewModel` | `ui/future/FuturePricesViewModel.kt` | Observes upcoming Agile prices |
| `SettingsViewModel` | `ui/settings/SettingsViewModel.kt` | Manages credential input and save/test flow |
