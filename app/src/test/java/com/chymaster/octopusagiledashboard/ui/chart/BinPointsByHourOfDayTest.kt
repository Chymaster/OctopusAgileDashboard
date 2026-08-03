package com.chymaster.octopusagiledashboard.ui.chart

import com.chymaster.octopusagiledashboard.domain.model.HalfHourPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BinPointsByHourOfDayTest {

    private val london = ZoneId.of("Europe/London")

    private fun point(hour: Int, minute: Int, consumption: Double?, cost: Double?): HalfHourPoint {
        val start = LocalDate.of(2024, 6, 15).atTime(hour, minute).atZone(london).toInstant()
        return HalfHourPoint(
            intervalStart = start,
            intervalEnd = start.plusSeconds(1800),
            priceIncVat = null,
            consumptionKwh = consumption,
            costIncVat = cost,
        )
    }

    @Test
    fun `empty list returns empty list`() {
        assertEquals(emptyList<BinnedPoint>(), binPointsByHourOfDay(emptyList()))
    }

    @Test
    fun `returns 24 hourly bins on the 2000-01-01 reference date`() {
        val bins = binPointsByHourOfDay(
            listOf(point(0, 0, consumption = 1.0, cost = 10.0))
        )
        assertEquals(24, bins.size)
        assertEquals(Instant.parse("2000-01-01T00:00:00Z"), bins[0].intervalStart)
        bins.forEach { bin ->
            assertEquals(Duration.ofHours(1), Duration.between(bin.intervalStart, bin.intervalEnd))
            assertNull(bin.avgPrice)
        }
        assertEquals(Instant.parse("2000-01-02T00:00:00Z"), bins[23].intervalEnd)
    }

    @Test
    fun `sums consumption and cost per hour and nulls empty hours`() {
        val bins = binPointsByHourOfDay(
            listOf(
                point(10, 0, consumption = 1.2, cost = 50.0),
                point(10, 30, consumption = 2.3, cost = 70.0),
            )
        )
        val ten = bins[10]
        assertEquals(3.5, ten.totalConsumption!!, 1e-9)
        assertEquals(120.0, ten.totalCost!!, 1e-9)
        assertEquals(2, ten.pointCount)
        assertNull("hour 9 has no points", bins[9].totalConsumption)
        assertNull("hour 9 has no cost", bins[9].totalCost)
    }

    @Test
    fun `null consumption contributes zero but keeps the hour non-null`() {
        val bins = binPointsByHourOfDay(
            listOf(point(10, 0, consumption = null, cost = 5.0))
        )
        assertEquals(0.0, bins[10].totalConsumption!!, 1e-9)
        assertEquals(5.0, bins[10].totalCost!!, 1e-9)
    }

    @Test
    fun `spreads points across distinct hours`() {
        val bins = binPointsByHourOfDay(
            listOf(
                point(0, 0, consumption = 1.0, cost = 10.0),
                point(12, 30, consumption = 2.0, cost = 20.0),
                point(23, 0, consumption = 3.0, cost = 30.0),
            )
        )
        assertEquals(1.0, bins[0].totalConsumption!!, 1e-9)
        assertEquals(2.0, bins[12].totalConsumption!!, 1e-9)
        assertEquals(3.0, bins[23].totalConsumption!!, 1e-9)
        (0 until 24)
            .filterNot { it == 0 || it == 12 || it == 23 }
            .forEach { hour -> assertNull("hour $hour should be empty", bins[hour].totalConsumption) }
        assertTrue(bins.all { it.pointCount >= 0 })
    }
}
