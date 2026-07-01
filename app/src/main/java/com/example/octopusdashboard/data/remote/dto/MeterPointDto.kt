package com.example.octopusdashboard.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MeterPointDto(
    @SerialName("gsp") val gsp: String,
    @SerialName("mpan") val mpan: String
)
