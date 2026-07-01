package com.example.octopusdashboard.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgileRateDto(
    @SerialName("value_exc_vat") val valueExcVat: Double,
    @SerialName("value_inc_vat") val valueIncVat: Double,
    @SerialName("valid_from") val validFrom: String,
    // Octopus returns null for the in-progress rate (it has no end yet)
    @SerialName("valid_to") val validTo: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null
)
