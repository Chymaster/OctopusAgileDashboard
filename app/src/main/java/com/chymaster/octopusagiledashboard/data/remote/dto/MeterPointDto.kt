package com.chymaster.octopusagiledashboard.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /v1/electricity-meter-points/{mpan}/`.
 *
 * Only the tariff-relevant fields are modelled; the `agreements` array is the
 * fallback source of the user's tariff history when the account endpoint is
 * unavailable.
 */
@Serializable
data class MeterPointDto(
    @SerialName("gsp") val gsp: String,
    @SerialName("mpan") val mpan: String,
    @SerialName("is_export") val isExport: Boolean = false,
    @SerialName("agreements") val agreements: List<TariffAgreementDto> = emptyList()
)
