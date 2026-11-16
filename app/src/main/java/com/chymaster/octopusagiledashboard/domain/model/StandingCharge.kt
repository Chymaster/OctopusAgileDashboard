package com.chymaster.octopusagiledashboard.domain.model

import java.time.Instant

data class StandingCharge(
    val validFrom: Instant,
    val validTo: Instant?,
    val valueExcVat: Double,  // pence per day
    val valueIncVat: Double,  // pence per day
    /** Tariff this charge belongs to (null for legacy/unspecified charges). */
    val tariffCode: String? = null
)
