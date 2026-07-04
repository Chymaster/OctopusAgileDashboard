package com.chymaster.octopusagiledashboard.ui.theme

import androidx.compose.ui.graphics.Color

object PriceColors {
    // Default percentage thresholds relative to Flexible Octopus Price
    const val DEFAULT_CHEAP_PERCENT = 70     // Below 70% → Green
    const val DEFAULT_MODERATE_PERCENT = 120 // 70%–120% → Amber
    // Above 120% → Red

    val Cheap = Color(0xFF2E7D32)      // Green
    val Moderate = Color(0xFFF9A825)   // Amber
    val Expensive = Color(0xFFC62828)  // Red

    /**
     * Returns a colour for [price] based on its ratio to [referencePrice]
     * (the Flexible Octopus tariff price).
     *
     * - Below [cheapPercent] % of reference → Green
     * - [cheapPercent] %–[moderatePercent] % of reference → Amber
     * - Above [moderatePercent] % of reference → Red
     *
     * Falls back to Amber when [referencePrice] is null or zero.
     */
    fun priceColor(
        price: Double,
        referencePrice: Double?,
        cheapPercent: Int = DEFAULT_CHEAP_PERCENT,
        moderatePercent: Int = DEFAULT_MODERATE_PERCENT
    ): Color {
        if (referencePrice == null || referencePrice == 0.0) return Moderate
        val ratio = price / referencePrice
        val cheapFactor = cheapPercent / 100.0
        val moderateFactor = moderatePercent / 100.0
        return when {
            ratio < cheapFactor -> Cheap
            ratio <= moderateFactor -> Moderate
            else -> Expensive
        }
    }

    /** Compute the absolute price thresholds from a reference price. */
    fun cheapThreshold(referencePrice: Double, cheapPercent: Int = DEFAULT_CHEAP_PERCENT): Double =
        referencePrice * cheapPercent / 100.0

    fun moderateThreshold(referencePrice: Double, moderatePercent: Int = DEFAULT_MODERATE_PERCENT): Double =
        referencePrice * moderatePercent / 100.0
}
