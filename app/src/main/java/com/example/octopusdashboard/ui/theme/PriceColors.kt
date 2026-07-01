package com.example.octopusdashboard.ui.theme

import androidx.compose.ui.graphics.Color

object PriceColors {
    const val CHEAP_THRESHOLD = 10.0
    const val MODERATE_THRESHOLD = 20.0
    const val EXPENSIVE_THRESHOLD = 30.0

    val Cheap = Color(0xFF2E7D32)      // Green
    val Moderate = Color(0xFFF9A825)   // Amber
    val Expensive = Color(0xFFE65100)  // Orange
    val VeryExpensive = Color(0xFFC62828) // Red

    fun priceColor(price: Double): Color = when {
        price <= CHEAP_THRESHOLD -> Cheap
        price <= MODERATE_THRESHOLD -> Moderate
        price <= EXPENSIVE_THRESHOLD -> Expensive
        else -> VeryExpensive
    }
}
