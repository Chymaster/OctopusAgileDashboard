package com.chymaster.octopusagiledashboard.domain.model

/**
 * A tariff the user can compare their current plan against.
 *
 * [id] is the Octopus product code (e.g. "VAR-22-11-01"), which together with
 * the user's GSP region determines the tariff code used by the rates API.
 */
data class TariffOption(
    val id: String,
    val displayName: String
)
