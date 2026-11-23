# CLAUDE.md — Project Instructions

## Project
Dashboard for Octopus Agile — Android app (Kotlin + Jetpack Compose).

## Skills
- **emulator** (`.claude/skills/emulator.md`): How to start the emulator, take screenshots, install the app, and interact with the device via adb.
- **testing** (`.claude/skills/testing.md`): Running unit tests (JAVA_HOME), and how to write them — put decisions in pure `domain/` functions, cover the failure and its inverse, verify the test fails on the old code.
- **release** (`.claude/skills/release.md`): Merge → tag → Play listing → signed AAB. **debug-api** (`.claude/skills/debug-api.md`): curl the Octopus API with real credentials.

## Fixing a bug
A fix is not done until a unit test reproduces the reported failure and fails
against the old code — see `.claude/skills/testing.md`. If the broken logic is a
branch inside a repository or ViewModel, extract it into a pure `domain/`
function and test that (there is no mocking library in this project).

## App Layout (essential)

Single-activity Compose: `MainActivity` → `AppNavGraph` (Compose Nav). Routes: **Home** (default), **Dashboard**, **Settings**, **Future Prices**. Modal sheets: `DataPointDetailSheet`, `CostBreakdownSheet`. Pkg `com.chymaster.octopusagiledashboard`, clean arch.
- `domain/` — models + use cases.
- `data/` — Room, Retrofit, DataStore prefs, `OctopusRepository(Impl)`, mappers.
- `core/` — Hilt `DatabaseModule`, network, util.
- `ui/` — feature folders (home/dashboard/settings/future/detail/nav) + `*ViewModel`; shared `components/`, `chart/`, `theme/`.
Home = next-slot gauge + fuel-mix pie + 24h timeline. Dashboard = cost analysis on a `TimeRangePreset`, price-based colour thresholds. Settings = Octopus API key + MPAN.
