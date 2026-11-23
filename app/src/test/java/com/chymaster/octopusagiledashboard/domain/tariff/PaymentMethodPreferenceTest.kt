package com.chymaster.octopusagiledashboard.domain.tariff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentMethodPreferenceTest {

    private data class Row(val validFrom: String, val method: String?, val price: Double)

    private fun keep(rows: List<Row>): List<Row> =
        PaymentMethodPreference.keepPreferred(
            rows,
            rateStart = { it.validFrom },
            paymentMethod = { it.method }
        )

    @Test
    fun `direct debit wins regardless of API order`() {
        // Flexible returns both rates for the same period. Room's REPLACE kept
        // whichever the API listed last, so the non-DD (28.09p) row won.
        val rows = listOf(
            Row("period-1", "NON_DIRECT_DEBIT", 28.0896),
            Row("period-1", "DIRECT_DEBIT", 26.5956)
        )
        val kept = keep(rows)
        assertEquals(1, kept.size)
        assertEquals(26.5956, kept.single().price, 0.0)
    }

    @Test
    fun `direct debit remains the winner when it comes first`() {
        val rows = listOf(
            Row("period-1", "DIRECT_DEBIT", 26.5956),
            Row("period-1", "NON_DIRECT_DEBIT", 28.0896)
        )
        assertEquals(26.5956, keep(rows).single().price, 0.0)
    }

    @Test
    fun `an unlabelled row beats a non direct debit row`() {
        val rows = listOf(
            Row("period-1", "NON_DIRECT_DEBIT", 28.0896),
            Row("period-1", null, 27.0)
        )
        assertEquals(27.0, keep(rows).single().price, 0.0)
    }

    @Test
    fun `distinct periods are all kept in first appearance order`() {
        val rows = listOf(
            Row("period-2", null, 20.0),
            Row("period-1", null, 10.0)
        )
        assertEquals(listOf(20.0, 10.0), keep(rows).map { it.price })
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(keep(emptyList()).isEmpty())
    }
}
