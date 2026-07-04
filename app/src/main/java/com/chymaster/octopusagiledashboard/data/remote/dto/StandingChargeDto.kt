package com.chymaster.octopusagiledashboard.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StandingChargeDto(
    @SerialName("value_exc_vat") val valueExcVat: Double,
    @SerialName("value_inc_vat") val valueIncVat: Double,
    @SerialName("valid_from") val validFrom: String,
    @SerialName("valid_to") val validTo: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null
)
