package com.chymaster.octopusagiledashboard.core.util

import com.chymaster.octopusagiledashboard.domain.model.TariffOption

object Constants {
    const val BASE_URL = "https://api.octopus.energy/v1/"
    const val DEFAULT_PRODUCT_CODE = "AGILE-24-10-01"
    const val FLEXIBLE_PRODUCT_CODE = "VAR-22-11-01"
    const val CARBON_INTENSITY_BASE_URL = "https://api.carbonintensity.org.uk/"

    /**
     * Curated shortlist of common import tariffs shown as quick-pick chips on
     * the Tariff Comparison screen. All product codes verified against the
     * public Octopus API. A "More…" chip opens a picker that pulls the full
     * product catalogue from `GET /v1/products/` instead.
     */
    val COMMON_TARIFFS = listOf(
        TariffOption(FLEXIBLE_PRODUCT_CODE, "Flexible Octopus"),
        TariffOption(DEFAULT_PRODUCT_CODE, "Agile Octopus"),
        TariffOption("COSY-22-12-08", "Cosy Octopus"),
        TariffOption("GO-VAR-22-10-14", "Octopus Go"),
        // Octopus Tracker is not on the current products catalogue; the SILVER
        // family is its product code family, and SILVER-23-12-06 still serves
        // live daily rates. Curated here so it stays a one-tap quick pick.
        TariffOption("SILVER-23-12-06", "Octopus Tracker"),
    )

    /**
     * Historical import tariffs no longer on sale but still queryable via the
     * public rates API. The products catalogue endpoint only lists tariffs
     * currently on sale — there is no endpoint that lists archived products —
     * so this shortlist is curated from historical product snapshots and
     * verified against the live API. Filtered to tariffs whose `available_to`
     * is within the last year so their rate history is recent enough to
     * compare over the app's ranges. Display names append the launch month to
     * disambiguate the repeated official names (e.g. three "Octopus 12M Fixed"
     * products).
     */
    val HISTORICAL_TARIFFS = listOf(
        TariffOption("OE-FIX-12M-25-09-09", "Octopus 12M Fixed (Sep 2025)"),
        TariffOption("OE-FIX-12M-25-11-25", "Octopus 12M Fixed (Nov 2025)"),
        TariffOption("OE-FIX-12M-26-06-06", "Octopus 12M Fixed (Jun 2026)"),
        TariffOption("COSY-FIX-12M-25-09-24", "Cosy Octopus 12M Fixed (Sep 2025)"),
        TariffOption("COSY-FIX-12M-26-03-23", "Cosy Octopus 12M Fixed (Mar 2026)"),
        TariffOption("GO-FIX-12M-25-08-29", "Octopus Go 12M Fixed (Aug 2025)"),
        TariffOption("GO-FIX-12M-26-04-18", "Octopus Go 12M Fixed (Apr 2026)"),
        TariffOption("INTELLI-FIX-12M-25-08-29", "Intelligent Octopus Go 12M Fixed (Aug 2025)"),
        TariffOption("INTELLI-FIX-12M-26-04-18", "Intelligent Octopus Go 12M Fixed (Apr 2026)"),
        TariffOption("INTELLI-VAR-24-10-29", "Intelligent Octopus Go"),
        TariffOption("INTELLI-VAR-OEV-24-07-17", "Intelligent Octopus Go - EV Saver"),
    )

    // Default GSP region: _L = London (UK Power Networks)
    const val DEFAULT_GSP = "_L"

    // GSP group letters (Grid Supply Point regions)
    val GSP_GROUPS = listOf(
        "_A", "_B", "_C", "_D", "_E", "_F", "_G", "_H",
        "_J", "_K", "_L", "_M", "_N", "_P"
    )

    // DataStore preferences file
    const val PREFERENCES_NAME = "octopus_preferences"
}
