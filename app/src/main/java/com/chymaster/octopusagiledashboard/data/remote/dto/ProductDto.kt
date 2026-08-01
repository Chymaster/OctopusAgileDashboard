package com.chymaster.octopusagiledashboard.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A tariff product from the public `GET /v1/products/` endpoint.
 *
 * Only the fields used by the tariff comparison picker are modelled — the
 * rest are ignored by the `ignoreUnknownKeys` JSON config.
 */
@Serializable
data class ProductDto(
    @SerialName("code") val code: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("direction") val direction: String? = null,          // "IMPORT" | "EXPORT"
    @SerialName("is_prepay") val isPrepay: Boolean? = null,
    @SerialName("brand") val brand: String? = null,                  // "OCTOPUS_ENERGY"
    @SerialName("available_from") val availableFrom: String? = null,
    @SerialName("available_to") val availableTo: String? = null
)
