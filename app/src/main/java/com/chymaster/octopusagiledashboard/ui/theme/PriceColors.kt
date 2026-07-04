package com.chymaster.octopusagiledashboard.ui.theme

import androidx.compose.ui.graphics.Color

object PriceColors {
    // Percentage thresholds relative to Flexible Octopus Price
    const val CHEAP_FACTOR = 0.70    // Below 70% → Green
    const val MODERATE_FACTOR = 1.20 // 70%–120% → Amber
    // Above 120% → Red

    val Cheap = Color(0xFF2E7D32)      // Green
    val Moderate = Color(0xFFF9A825)   // Amber
    val Expensive = Color(0xFFC62828)  // Red

    /**
     * Returns a colour for [price] based on its ratio to [referencePrice]
     * (the Flexible Octopus tariff price).
     *
     * - Below 70 % of reference → Green
     * - 70 %–120 % of reference → Amber
     * - Above 120 % of reference → Red
     *
     * Falls back to Amber when [referencePrice] is null or zero.
     */
    fun priceColor(price: Double, referencePrice: Double?): Color {
        if (referencePrice == null || referencePrice == 0.0) return Moderate
        val ratio = price / referencePrice
        return when {
            ratio < CHEAP_FACTOR -> Cheap
            ratio <= MODERATE_FACTOR -> Moderate
            else -> Expensive
        }
    }

    /** Compute the absolute price thresholds from a reference price. */
    fun cheapThreshold(referencePrice: Double): Double = referencePrice * CHEAP_FACTOR
    fun moderateThreshold(referencePrice: Double): Double = referencePrice * MODERATE_FACTOR
}
