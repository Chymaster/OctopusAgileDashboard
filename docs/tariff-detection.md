# Tariff Detection & Tariff-Aware Costs

The app prices usage with the tariff that was **actually in force at each
half-hour slot**, not with one globally configured product. A user who moved
from Agile to Cosy two months ago sees Agile rates for the older part of a
6-month range and Cosy rates for the recent part, on the Dashboard, the standing
charge and the Tariff Comparison baseline.

## Where the tariff history comes from

`GET /v1/accounts/{account_number}/` returns, per electricity meter point, an
`agreements` array of `{ tariff_code, valid_from, valid_to }` (`valid_to` is null
for the agreement in force now). Note that `GET /electricity-meter-points/{mpan}/`
does **not** include agreements — it is kept only as a fallback.

`TariffScheduleRepository` (`data/repository/TariffScheduleRepository.kt`) owns
detection:

- The account number is read from prefs. If it was never saved (credentials
  entered before detection existed) the viewer's accounts are discovered with
  `{ viewer { accounts { number } } }`, and each account is probed for the meter
  point matching the configured MPAN.
- Only the **import** meter point (`is_export == false`) is used, matched on the
  last 13 digits of the MPAN.
- The result is persisted as JSON in DataStore (`tariff_schedule_json`) together
  with the MPAN/account it was detected for, so it survives restarts and works
  offline. A schedule whose identity no longer matches the saved credentials is
  discarded.
- Re-detection happens when the cached copy is older than 12 h, when the current
  agreement's `valid_to` has passed (a switch happened), or when Settings runs
  "Detect again". Failures are throttled to one attempt every two minutes.

## The effective schedule

`TariffScheduleRepository.state` is a **derived** flow:

```
combine(persistedDetection, autoDetectTariff, isDemoMode, tariffConfig, mpan)
```

| Situation | Source | Agreements |
|---|---|---|
| Detected from the account | `AUTO` | The full history |
| "Detect my tariff automatically" off | `MANUAL_OVERRIDE` | One open-ended agreement from the Settings product code |
| No credentials | `DEMO` | One open-ended agreement (synthetic prices) |
| Detection unavailable | `FALLBACK` | One open-ended agreement + an error shown in Settings |

Because the state is derived, toggling the override needs no cache wipe: price
reads are tariff-scoped, so rows belonging to an unselected tariff are simply
never read.

## Coverage rules (`TariffScheduleResolver`)

- Agreements are sorted, and on overlap the later start wins.
- A gap between two agreements — and an expired last agreement — is covered by
  extending the earlier agreement forward. Octopus does not leave a meter point
  untariffed, and dropping the slots would silently remove usage from the totals.
- Nothing is invented **before** the first agreement: those slots stay unpriced
  and a range entirely before the first agreement shows an empty state.
- A slot's tariff is the one containing the slot start, so a switch at 12:17
  means the 12:00 slot is still the old tariff and 12:30 onward the new one.

## Prices (`OctopusRepositoryImpl`)

`getAgilePrices(start, end)` clips the schedule to the range and, per segment
(per tariff), loads rates with the tiered strategy: in-memory coverage → Room
coverage → API. Rates are fetched over `[segment.start − 25 h, segment.end)` so
a daily rate that started before the range still prices its first slot, and are
stored keyed by `(validFrom, tariffCode)`.

Room coverage is judged per tariff shape. A half-hourly series (Agile) is
complete when every slot is present. A daily / multi-period series (Cosy,
Flexible) instead has to **tile the window with no gap** up to its end
(`RateCoverage.tilesThrough`) — counting rows says nothing there, and Octopus
publishes future periods in batches. Treating "some rows exist" as complete
would freeze the newest days on whatever period happened to be published last
(e.g. Cosy showing its standard rate all day, with no Cosy or peak windows), so
a window whose periods stop early is refetched.

The returned series is **normalised to one rate per half-hour slot**
(`SlotRateSelector.selectSlotRates`), carrying the tariff code that produced it.
Rate lookup uses "the greatest `validFrom` ≤ slot" for that slot's tariff,
deliberately ignoring `validTo` — the mapper synthesises a 30-minute end for
open-ended daily rates. A slot whose tariff has no published rate is omitted;
the neighbouring tariff's rate is never borrowed.

Room schema v3 (from v2) is migrated, not dropped: `agile_prices` and
`standing_charges` gain a composite key `(validFrom, tariffCode)`. The
consumption table is preserved because it is the user's only offline copy of
usage history.

## Standing charges

`StandingChargeCacheStore` fetches the charge of every tariff in force over the
range (with a 2-day lookback) and computes the total **per London calendar day**:
each day uses the charge of the tariff active at that day's 00:00
(`TariffCostCalculator`). A run shorter than a day still bills one day.

## UI

- Dashboard: a chip under the range selector names the tariff in force now, with
  an `auto` / `manual` / `demo` / `not detected` hint; when the selected range
  spans a switch a caption lists the tariffs in range, and the charts draw a
  dashed marker at the first bar priced with the new tariff.
- Home: the gauge is labelled with the detected tariff name.
- Future Prices: the title is the detected tariff (`Cosy Octopus Prices`), with
  "Agile Prices" kept for Agile users.
- Settings: a Tariff section shows the detected history (read-only, refreshable)
  and the switch that turns detection off in favour of a manual product code.
- Tariff Comparison: the baseline is the real history, so the comparison reads
  e.g. "Comparing Agile Octopus → Cosy Octopus vs Flexible Octopus".

## Not covered

Economy 7 / `2R` two-register tariffs: codes are parsed generically, but only
`standard-unit-rates` is queried, so such a tariff is priced with its standard
rate. This matches the previous behaviour.
