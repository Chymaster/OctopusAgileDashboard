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
        TariffOption("OE-FIX-12M-26-07-29", "12M Fixed"),
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
