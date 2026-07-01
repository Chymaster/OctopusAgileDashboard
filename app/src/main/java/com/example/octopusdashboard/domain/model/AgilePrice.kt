package com.example.octopusdashboard.domain.model

import java.time.Instant

data class AgilePrice(
    val validFrom: Instant,
    val validTo: Instant,
    val priceExcVat: Double,   // pence per kWh
    val priceIncVat: Double    // pence per kWh
)
