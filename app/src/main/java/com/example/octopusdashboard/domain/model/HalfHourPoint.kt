package com.example.octopusdashboard.domain.model

import java.time.Instant

/**
 * Merged data point for a single half-hour interval.
 * Combines Agile price and consumption data for chart display.
 */
data class HalfHourPoint(
    val intervalStart: Instant,
    val intervalEnd: Instant,
    val priceIncVat: Double?,      // pence per kWh (null if no price data)
    val consumptionKwh: Double?,   // kWh (null if no consumption data)
    val costIncVat: Double?        // pence (null if either price or consumption missing)
)
