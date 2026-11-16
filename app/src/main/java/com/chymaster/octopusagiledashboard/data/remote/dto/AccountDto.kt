package com.chymaster.octopusagiledashboard.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /v1/accounts/{account_number}/` — only the tariff-relevant slice is
 * modelled: the account number and its electricity meter points with their
 * tariff agreements.
 */
@Serializable
data class AccountDto(
    @SerialName("number") val number: String,
    @SerialName("properties") val properties: List<AccountPropertyDto> = emptyList()
)

@Serializable
data class AccountPropertyDto(
    @SerialName("electricity_meter_points")
    val electricityMeterPoints: List<AccountMeterPointDto> = emptyList()
)

@Serializable
data class AccountMeterPointDto(
    @SerialName("mpan") val mpan: String,
    @SerialName("is_export") val isExport: Boolean = false,
    @SerialName("agreements") val agreements: List<TariffAgreementDto> = emptyList()
)
