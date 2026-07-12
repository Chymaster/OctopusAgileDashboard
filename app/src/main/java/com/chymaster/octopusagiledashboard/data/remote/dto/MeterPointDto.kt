package com.chymaster.octopusagiledashboard.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MeterDto(
    @SerialName("serial_number") val serialNumber: String
)

@Serializable
data class MeterPointDto(
    @SerialName("gsp") val gsp: String,
    @SerialName("mpan") val mpan: String,
    @SerialName("meters") val meters: List<MeterDto> = emptyList()
)
