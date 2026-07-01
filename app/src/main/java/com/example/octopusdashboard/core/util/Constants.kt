package com.example.octopusdashboard.core.util

object Constants {
    const val BASE_URL = "https://api.octopus.energy/v1/"
    const val DEFAULT_PRODUCT_CODE = "AGILE-24-10-01"
    const val FLEXIBLE_PRODUCT_CODE = "VAR-22-11-01"
    const val CARBON_INTENSITY_BASE_URL = "https://api.carbonintensity.org.uk/"

    // GSP group letters (Grid Supply Point regions)
    val GSP_GROUPS = listOf(
        "_A", "_B", "_C", "_D", "_E", "_F", "_G", "_H",
        "_J", "_K", "_L", "_M", "_N", "_P"
    )

    // DataStore preferences file
    const val PREFERENCES_NAME = "octopus_preferences"
}
