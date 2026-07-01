package com.example.octopusdashboard.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CarbonIntensityResponse(
    val data: CarbonIntensityData
)

@Serializable
data class CarbonIntensityData(
    val from: String,
    val to: String,
    val generationmix: List<FuelMixEntry>
)

@Serializable
data class FuelMixEntry(
    val fuel: String,
    val perc: Double
)
