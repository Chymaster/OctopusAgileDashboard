package com.example.octopusdashboard.domain.model

import java.time.Instant

data class ConsumptionRecord(
    val intervalStart: Instant,
    val intervalEnd: Instant,
    val consumption: Double,   // kWh
    val costIncVat: Double? = null  // pence, computed from price × consumption
)
