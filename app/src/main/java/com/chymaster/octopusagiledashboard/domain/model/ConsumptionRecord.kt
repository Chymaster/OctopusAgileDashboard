package com.chymaster.octopusagiledashboard.domain.model

import java.time.Instant

data class ConsumptionRecord(
    val intervalStart: Instant,
    val intervalEnd: Instant,
    val consumption: Double,   // kWh
    val costIncVat: Double? = null  // pence, computed from price × consumption
)
