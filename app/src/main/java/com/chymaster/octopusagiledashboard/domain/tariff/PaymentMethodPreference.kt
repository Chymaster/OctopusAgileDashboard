package com.chymaster.octopusagiledashboard.domain.tariff

/**
 * Collapses published rates that differ only by payment method.
 *
 * Octopus publishes e.g. Flexible twice per period — `DIRECT_DEBIT` (the price
 * the customer actually pays) and `NON_DIRECT_DEBIT` — with the same
 * `valid_from`. The domain model does not carry the payment method, so the
 * direct-debit row has to win before the rate is cached; otherwise whichever
 * row the API listed last becomes the price. Rows without a payment method
 * (Agile, Cosy) are kept as they are.
 */
object PaymentMethodPreference {

    const val DIRECT_DEBIT = "DIRECT_DEBIT"

    /**
     * One item per [rateStart], preferring [DIRECT_DEBIT], then an item with no
     * payment method, then the first. First-appearance order is preserved.
     */
    fun <T, K> keepPreferred(
        items: List<T>,
        rateStart: (T) -> K,
        paymentMethod: (T) -> String?
    ): List<T> {
        if (items.isEmpty()) return items
        val byStart = LinkedHashMap<K, T>()
        for (item in items) {
            val key = rateStart(item)
            val existing = byStart[key]
            byStart[key] = if (existing == null) item else preferred(existing, item, paymentMethod)
        }
        return byStart.values.toList()
    }

    private fun <T> preferred(a: T, b: T, paymentMethod: (T) -> String?): T = when {
        paymentMethod(a) == DIRECT_DEBIT -> a
        paymentMethod(b) == DIRECT_DEBIT -> b
        paymentMethod(a) == null -> a
        paymentMethod(b) == null -> b
        else -> a
    }
}
