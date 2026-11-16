package com.chymaster.octopusagiledashboard.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One tariff agreement on a meter point, as returned inside
 * `GET /v1/accounts/{account}/` and `GET /v1/electricity-meter-points/{mpan}/`.
 * `valid_to` is null for the agreement currently in force.
 */
@Serializable
data class TariffAgreementDto(
    @SerialName("tariff_code") val tariffCode: String,
    @SerialName("valid_from") val validFrom: String,
    @SerialName("valid_to") val validTo: String? = null
)
