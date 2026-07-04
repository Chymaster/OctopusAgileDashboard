package com.chymaster.octopusagiledashboard.domain.model

import java.time.Instant

data class GreenEnergyData(
    val lowCarbonPercentage: Double,
    val fuelMix: List<FuelMix>,
    val fetchedAt: Instant
)

data class FuelMix(
    val fuel: String,
    val percentage: Double
)

/** Fuels classified as low-carbon in UK grid context. */
val LOW_CARBON_FUELS = setOf("wind", "solar", "nuclear", "hydro", "biomass", "imports")
