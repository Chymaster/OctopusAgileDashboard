package com.chymaster.octopusagiledashboard.domain.tariff

import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TariffCostCalculatorTest {

    private val london = ZoneId.of("Europe/London")
    private val agile = "E-1R-AGILE-24-10-01-L"
    private val cosy = "E-1R-COSY-22-12-08-L"

    private fun date(year: Int, month: Int, day: Int): Instant =
        LocalDate.of(year, month, day).atStartOfDay(london).toInstant()

    private fun charge(tariffCode: String, from: Instant, pencePerDay: Double) = StandingCharge(
        validFrom = from,
        validTo = null,
        valueExcVat = pencePerDay * 0.95,
        valueIncVat = pencePerDay,
        tariffCode = tariffCode
    )

    @Test
    fun `seven day preset bills eight calendar days`() {
        val start = date(2026, 1, 8)
        val end = date(2026, 1, 16)
        assertEquals(8, TariffCostCalculator.dayStarts(start, end).size)
    }

    @Test
    fun `a range shorter than a day still bills once`() {
        val start = date(2026, 1, 8)
        assertEquals(1, TariffCostCalculator.dayStarts(start, start).size)
    }

    @Test
    fun `single tariff sums the latest charge per day`() {
        val days = TariffCostCalculator.dayStarts(date(2026, 1, 1), date(2026, 1, 4))
        val charges = listOf(charge(agile, date(2026, 1, 1), 50.0))
        assertEquals(150.0, TariffCostCalculator.standingChargeCostForSingleTariff(charges, days)!!, 0.001)
    }

    @Test
    fun `a mid-range switch prices each day with the tariff in force at midnight`() {
        val days = TariffCostCalculator.dayStarts(date(2026, 1, 8), date(2026, 1, 13)) // 5 days
        val charges = listOf(
            charge(agile, date(2026, 1, 1), 40.0),
            charge(cosy, date(2026, 1, 10), 60.0)
        )
        val tariffAt: (Instant) -> String? = { day -> if (day < date(2026, 1, 10)) agile else cosy }
        assertEquals(
            2 * 40.0 + 3 * 60.0,
            TariffCostCalculator.standingChargeCost(charges, days, tariffAt)!!,
            0.001
        )
    }

    @Test
    fun `a switch exactly at midnight takes effect that day`() {
        val days = TariffCostCalculator.dayStarts(date(2026, 1, 9), date(2026, 1, 11)) // 2 days
        val charges = listOf(
            charge(agile, date(2026, 1, 1), 40.0),
            charge(cosy, date(2026, 1, 10), 60.0)
        )
        val tariffAt: (Instant) -> String? = { day -> if (day < date(2026, 1, 10)) agile else cosy }
        assertEquals(
            100.0,
            TariffCostCalculator.standingChargeCost(charges, days, tariffAt)!!,
            0.001
        )
    }

    @Test
    fun `per-tariff breakdown splits the total`() {
        val days = TariffCostCalculator.dayStarts(date(2026, 1, 8), date(2026, 1, 13))
        val charges = listOf(
            charge(agile, date(2026, 1, 1), 40.0),
            charge(cosy, date(2026, 1, 10), 60.0)
        )
        val tariffAt: (Instant) -> String? = { day -> if (day < date(2026, 1, 10)) agile else cosy }
        assertEquals(
            mapOf(agile to 80.0, cosy to 180.0),
            TariffCostCalculator.standingChargeByTariff(charges, days, tariffAt)
        )
    }

    @Test
    fun `a day with no matching charge is skipped`() {
        val days = TariffCostCalculator.dayStarts(date(2026, 1, 8), date(2026, 1, 11)) // 3 days
        val charges = listOf(charge(agile, date(2026, 1, 1), 40.0))
        val tariffAt: (Instant) -> String? = { if (it < date(2026, 1, 10)) agile else cosy }
        assertEquals(
            80.0,
            TariffCostCalculator.standingChargeCost(charges, days, tariffAt)!!,
            0.001
        )
    }

    @Test
    fun `no charges yields null`() {
        val days = TariffCostCalculator.dayStarts(date(2026, 1, 8), date(2026, 1, 10))
        assertNull(TariffCostCalculator.standingChargeCostForSingleTariff(emptyList(), days))
    }

    @Test
    fun `untagged charges are accepted for any tariff`() {
        val days = TariffCostCalculator.dayStarts(date(2026, 1, 8), date(2026, 1, 10)) // 2 days
        val untagged = StandingCharge(
            validFrom = date(2026, 1, 1),
            validTo = null,
            valueExcVat = 38.0,
            valueIncVat = 40.0,
            tariffCode = null
        )
        val tariffAt: (Instant) -> String? = { cosy }
        assertEquals(
            80.0,
            TariffCostCalculator.standingChargeCost(listOf(untagged), days, tariffAt)!!,
            0.001
        )
    }

    @Test
    fun `a charge published after the first day still bills it`() {
        val days = TariffCostCalculator.dayStarts(date(2026, 1, 8), date(2026, 1, 10)) // 2 days
        val charges = listOf(charge(agile, date(2026, 1, 9), 45.0))
        assertEquals(
            90.0,
            TariffCostCalculator.standingChargeCostForSingleTariff(charges, days)!!,
            0.001
        )
    }

    @Test
    fun `DST spring-forward day is still one charge`() {
        // 29 March 2026 is the UK DST start: the 29th is only 23 hours long.
        val days = TariffCostCalculator.dayStarts(date(2026, 3, 28), date(2026, 3, 31))
        assertEquals(3, days.size)
        assertEquals(Duration.ofHours(23), Duration.between(days[1], days[2]))

        val charges = listOf(charge(agile, date(2026, 3, 1), 50.0))
        assertEquals(150.0, TariffCostCalculator.standingChargeCostForSingleTariff(charges, days)!!, 0.001)
    }

    @Test
    fun `DST fall-back day is still one charge`() {
        // 25 October 2026 is the UK DST end: the 25th is 25 hours long.
        val days = TariffCostCalculator.dayStarts(date(2026, 10, 24), date(2026, 10, 27))
        assertEquals(3, days.size)
        assertEquals(Duration.ofHours(25), Duration.between(days[1], days[2]))

        val charges = listOf(charge(agile, date(2026, 10, 1), 50.0))
        assertEquals(150.0, TariffCostCalculator.standingChargeCostForSingleTariff(charges, days)!!, 0.001)
    }
}
