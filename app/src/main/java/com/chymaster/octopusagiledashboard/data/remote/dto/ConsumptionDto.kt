package com.chymaster.octopusagiledashboard.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConsumptionDto(
    @SerialName("consumption") val consumption: Double,
    @SerialName("interval_start") val intervalStart: String,
    @SerialName("interval_end") val intervalEnd: String
)
