package com.chymaster.octopusagiledashboard.domain.model

import java.time.Instant

data class AgilePrice(
    val validFrom: Instant,
    val validTo: Instant,
    val priceExcVat: Double,   // pence per kWh
    val priceIncVat: Double,   // pence per kWh
    /** Tariff this rate belongs to (null for legacy/unspecified rates). */
    val tariffCode: String? = null
)
