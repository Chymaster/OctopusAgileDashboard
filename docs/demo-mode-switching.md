# Demo Mode ↔ Real Mode Switching

This document describes how the app transitions between demo mode and real mode, what data sources change, and what triggers (active and reactive) fire during the switch.

---

## State: Where Is Demo Mode Tracked?

There is **no explicit "demo mode" boolean** stored anywhere. Instead, it is derived:

```
UserPreferencesRepository (data/prefs/UserPreferencesRepository.kt:77-82)

hasCredentials = combine(apiKeyFlow, mpanFlow, serialNumberFlow) { key, mpan, serial ->
    !key.isNullOrBlank() && !mpan.isNullOrBlank() && !serial.isNullOrBlank()
}

isDemoMode = hasCredentials.map { !it }
```

All three credentials (API key, MPAN, serial number) must be non-blank for `hasCredentials = true` / `isDemoMode = false`.

Credential storage:
- **API key** → `SecureApiKeyStore` (EncryptedSharedPreferences, Android Keystore-backed)
- **MPAN, serial number, GSP, product code** → Jetpack DataStore Preferences

---

## Trigger: What Causes the Switch?

The switch happens when the user saves credentials from the **Settings screen**.

```
SettingsViewModel.save()                          (ui/settings/SettingsViewModel.kt:122)
  └─ preferencesRepository.saveCredentials(...)   (data/prefs/UserPreferencesRepository.kt:158)
       ├─ secureApiKeyStore.saveApiKey(apiKey)
       ├─ dataStore.edit { MPAN, SERIAL_NUMBER, GSP, PRODUCT_CODE }
       └─ octopusRepository.wipeAllCaches()       ← active trigger
```

There is no "switch to real mode" command. The mode flips implicitly the moment all three credential flows emit non-null values. The `hasCredentials` combine re-evaluates, `isDemoMode` emits `false`, and all downstream collectors react.

---

## Active Trigger: Cache Wipe on Credential Save

`saveCredentials()` actively calls `wipeAllCaches()` (line 169), which clears:

| Cache | What gets wiped |
|---|---|
| Agile prices (OctopusRepositoryImpl) | In-memory `MutableStateFlow` + Room `agile_prices` table |
| `ConsumptionCacheStore` | In-memory flow + Room `consumption` table |
| `StandingChargeCacheStore` | In-memory flow + Room `standing_charge` table |

This ensures no stale data from the previous mode flashes on screen.

---

## Reactive: Who Listens to `isDemoMode`?

| Consumer | File | What it does on mode change |
|---|---|---|
| `DashboardViewModel` | `ui/dashboard/DashboardViewModel.kt:91-99` | Updates UI state, **cancels & restarts `loadData()`** — the only ViewModel that fully reloads on mode switch |
| `HomeViewModel` | `ui/home/HomeViewModel.kt:56-59` | Updates `hasCredentials` / `isDemoMode` in UI state only — does **not** trigger a reload |
| `AppNavGraph` | `ui/nav/AppNavGraph.kt` | Passes `isDemoMode` to `DrawerContent` for the info icon badge |
| `DashboardScreen` | `ui/dashboard/DashboardScreen.kt` | Shows/hides `DemoModeBanner` |
| `DrawerContent` | `ui/nav/DrawerContent.kt` | Shows/hides info icon next to "Dashboard" |

---

## Data Source Branching: What Changes at the Cache Layer?

Cache stores are **not reactive** to mode changes. Each method checks `preferencesRepository.isDemoMode.first()` at call time (point-in-time read, not a subscription).

### Agile Prices (`OctopusRepositoryImpl`, managed inline)

```
loadAgilePrices(start, end):
  isDemo? ──yes──→ DemoDataGenerator.generateAgilePriceEntities(start, end)
              │     → write to Room → mergeAndEmitAgilePrices()
              │
              no──→ read from Room
                    → if empty, fetch from Octopus public API (no auth needed)
                    → mergeAndEmitAgilePrices()

refreshAgilePrices(start, end):
  isDemo? ──yes──→ DemoDataGenerator.generateAgilePriceEntities(start, end)   ← NO API CALL
              │     → write to Room → mergeAndEmitAgilePrices()
              │
              no──→ fetch from Octopus public API → write to Room
                    → read from Room → mergeAndEmitAgilePrices()
```

### ConsumptionCacheStore (`data/local/ConsumptionCacheStore.kt:78+`)

```
loadRange(start, end):
  isDemo? ──yes──→ DemoDataGenerator.generateConsumptionEntities(start, end)
              │     → write to Room → emit
              │
              no──→ read from Room
                    → if empty, fetch from Octopus authenticated usage API
                    → emit
```

### StandingChargeCacheStore

Always uses the real Octopus public API — **no demo mode branching**. Standing charges are fetched identically regardless of mode.

---

## Complete Flow: User Saves Credentials

```
1.  User enters API key + MPAN + serial in Settings, taps Save
2.  SettingsViewModel.save() → saveCredentials()
3.  Credentials persisted to DataStore + EncryptedSharedPreferences
4.  wipeAllCaches() clears all in-memory flows and Room tables
5.  apiKeyFlow, mpanFlow, serialNumberFlow emit new values
6.  hasCredentials combine re-evaluates → emits true
7.  isDemoMode emits false
8.  ── Reactive consumers fire ──
    ├─ DashboardViewModel: cancels old dataJob, starts new loadData()
    │    └─ observes cache stores (now empty) → triggers refresh
    │         └─ cache stores check isDemoMode.first() → false → fetch from real API
    │              └─ real data flows into UI
    ├─ HomeViewModel: updates UI flags (no reload)
    ├─ DemoModeBanner disappears
    └─ Drawer info icon disappears
```

---

## Reverse: Real → Demo (Clearing Credentials)

Clearing credentials (deleting API key, MPAN, or serial) causes `hasCredentials` to emit `false`, `isDemoMode` to emit `true`. The same reactive chain fires — DashboardViewModel reloads, cache stores switch back to `DemoDataGenerator`.

---

## Key Architectural Observations

1. **Derived, not stored**: Demo mode is always computed as `!hasCredentials`. There's no way for them to get out of sync.

2. **Active + reactive hybrid**: The cache wipe is an *active* side-effect inside `saveCredentials()`. The ViewModel reload is *reactive* via Flow collection.

3. **Cache stores are call-site checked**: They don't subscribe to mode changes — they read `isDemoMode.first()` each time they're called. The reactivity comes from the ViewModel layer restarting its data-loading coroutine.

4. **HomeViewModel does NOT reload on mode switch**: Only DashboardViewModel cancels and restarts. HomeViewModel relies on its initial `loadAllData()` from init and manual `onRefresh()`.

5. **DemoDataGenerator is deterministic**: Synthetic data is seeded by epoch-second, so the same instant always produces the same data. Sentinel identifiers (`DEMO_MPAN`, `DEMO_SERIAL`, `DEMO_TARIFF`) tag demo entities.
