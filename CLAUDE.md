# CLAUDE.md — Project Instructions

## Project
OctopusDashboard — Android app (Kotlin + Jetpack Compose).

## Skills
- **emulator** (`.claude/skills/emulator.md`): How to start the emulator, take screenshots, install the app, and interact with the device via adb.

## App Layout (essential)

Single-activity Compose: `MainActivity` → `AppNavGraph` (Compose Nav). Routes: **Home** (default), **Dashboard**, **Settings**, **Future Prices**. Modal sheets: `DataPointDetailSheet`, `CostBreakdownSheet`. Pkg `com.chymaster.octopusagiledashboard`, clean arch.
- `domain/` — models + use cases.
- `data/` — Room, Retrofit, DataStore prefs, `OctopusRepository(Impl)`, mappers.
- `core/` — Hilt `DatabaseModule`, network, util.
- `ui/` — feature folders (home/dashboard/settings/future/detail/nav) + `*ViewModel`; shared `components/`, `chart/`, `theme/`.
Home = next-slot gauge + fuel-mix pie + 24h timeline. Dashboard = cost analysis on a `TimeRangePreset`, price-based colour thresholds. Settings = Octopus API key + MPAN.
