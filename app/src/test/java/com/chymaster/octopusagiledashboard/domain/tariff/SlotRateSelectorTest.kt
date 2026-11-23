package com.chymaster.octopusagiledashboard.domain.tariff

import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SlotRateSelectorTest {

    private val london = ZoneId.of("Europe/London")
    private val agile = "E-1R-AGILE-24-10-01-L"
    private val cosy = "E-1R-COSY-22-12-08-L"

    private fun date(year: Int, month: Int, day: Int): Instant =
        LocalDate.of(year, month, day).atStartOfDay(london).toInstant()

    private fun dateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(london).toInstant()

    private fun rate(
        tariffCode: String,
        from: Instant,
        price: Double,
        durationMinutes: Long = 30
    ) = AgilePrice(
        validFrom = from,
        validTo = from.plusSeconds(durationMinutes * 60),
        priceExcVat = price * 0.95,
        priceIncVat = price,
        tariffCode = tariffCode
    )

    @Test
    fun `single tariff slot map applies the last rate at or before each slot`() {
        val start = date(2026, 1, 15)
        val rates = listOf(
            rate(agile, start, 10.0, durationMinutes = 120),
            rate(agile, start.plusSeconds(1800), 20.0, durationMinutes = 120)
        )
        val map = SlotRateSelector.buildSlotPriceMap(rates, start, start.plusSeconds(7200))
        assertEquals(4, map.size)
        assertEquals(10.0, map[start.toEpochMilli()]!!, 0.001)
        assertEquals(20.0, map[start.plusSeconds(3600).toEpochMilli()]!!, 0.001)
    }

    @Test
    fun `a daily rate prices every slot that day`() {
        val start = date(2026, 1, 15)
        // Flexible-style tariff: one rate valid from midnight for the whole day.
        val rates = listOf(rate(cosy, start, 24.5, durationMinutes = 24 * 60))
        val slotTariffs = (0 until 48).associate {
            (start.toEpochMilli() + it * 1_800_000L) to cosy
        }
        val selected = SlotRateSelector.selectSlotRates(rates, slotTariffs)
        assertEquals(48, selected.size)
        assertTrue(selected.all { it.priceIncVat == 24.5 })
    }

    @Test
    fun `lookback rate prices the first slot of the range`() {
        val start = date(2026, 1, 15)
        // Rate began the day before the requested range.
        val rates = listOf(rate(cosy, start.minusSeconds(86_400), 30.0, durationMinutes = 48 * 60))
        val slotTariffs = (0 until 2).associate {
            (start.toEpochMilli() + it * 1_800_000L) to cosy
        }
        val selected = SlotRateSelector.selectSlotRates(rates, slotTariffs)
        assertEquals(2, selected.size)
        assertTrue(selected.all { it.priceIncVat == 30.0 })
    }

    @Test
    fun `rates are never borrowed across a tariff switch`() {
        val start = dateTime(2026, 1, 15, 12, 0)
        val rates = listOf(rate(agile, start, 15.0))
        val slotTariffs = mapOf(
            start.toEpochMilli() to agile,
            start.plusSeconds(1800).toEpochMilli() to cosy // Cosy has no rate at/before this slot
        )
        val selected = SlotRateSelector.selectSlotRates(rates, slotTariffs)
        assertEquals(1, selected.size)
        assertEquals(agile, selected.single().tariffCode)
    }

    @Test
    fun `each tariff's own rates are used across a switch`() {
        val switch = dateTime(2026, 1, 15, 12, 0)
        val rates = listOf(
            rate(agile, switch.minusSeconds(3600), 15.0, durationMinutes = 60),
            rate(cosy, switch.minusSeconds(1800), 25.0, durationMinutes = 60)
        )
        val slotTariffs = mapOf(
            switch.minusSeconds(1800).toEpochMilli() to agile,
            switch.toEpochMilli() to cosy
        )
        val selected = SlotRateSelector.selectSlotRates(rates, slotTariffs)
        assertEquals(2, selected.size)
        assertEquals(15.0, selected.first().priceIncVat, 0.001)
        assertEquals(25.0, selected.last().priceIncVat, 0.001)
        assertEquals(cosy, selected.last().tariffCode)
    }

    @Test
    fun `selected rates carry the slot interval`() {
        val start = date(2026, 1, 15)
        val rates = listOf(rate(agile, start, 12.0, durationMinutes = 120))
        val selected = SlotRateSelector.selectSlotRates(rates, mapOf(start.toEpochMilli() to agile))
        val point = selected.single()
        assertEquals(start, point.validFrom)
        assertEquals(start.plusSeconds(1800), point.validTo)
    }

    @Test
    fun `empty inputs produce empty results`() {
        val start = date(2026, 1, 15)
        assertTrue(SlotRateSelector.selectSlotRates(emptyList(), emptyMap()).isEmpty())
        assertTrue(SlotRateSelector.selectSlotRates(listOf(rate(agile, start, 1.0)), emptyMap()).isEmpty())
        assertTrue(SlotRateSelector.buildSlotPriceMap(emptyList(), start, start.plusSeconds(3600)).isEmpty())
    }

    @Test
    fun `slot price map and slot rate selection agree`() {
        val start = date(2026, 1, 15)
        val rates = listOf(
            rate(agile, start, 10.0),
            rate(agile, start.plusSeconds(1800), 18.0)
        )
        val slotTariffs = (0 until 3).associate {
            (start.toEpochMilli() + it * 1_800_000L) to agile
        }
        val viaSelection = SlotRateSelector.buildScheduleSlotPriceMap(rates, slotTariffs)
        val viaSingle = SlotRateSelector.buildSlotPriceMap(rates, start, start.plusSeconds(5400))
        assertEquals(viaSingle, viaSelection)
    }

    @Test
    fun `rates without a tariff code are ignored`() {
        val start = date(2026, 1, 15)
        val untagged = AgilePrice(start, start.plusSeconds(1800), 5.0, 5.0)
        val selected = SlotRateSelector.selectSlotRates(
            listOf(untagged),
            mapOf(start.toEpochMilli() to agile)
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `slot without any rate is omitted`() {
        val start = date(2026, 1, 15)
        val rates = listOf(rate(agile, start.plusSeconds(1800), 10.0))
        val slotTariffs = mapOf(start.toEpochMilli() to agile)
        assertNull(SlotRateSelector.selectSlotRates(rates, slotTariffs).firstOrNull())
    }

    @Test
    fun `slots after the last published period are left unpriced`() {
        // The reported bug: a 3-hour rate was repeated for every later slot,
        // so a whole future screen showed the cheap band. Once the period ends
        // the slots it does not cover must be omitted.
        val start = date(2026, 1, 15)
        val rates = listOf(rate(cosy, start, 14.0, durationMinutes = 180))
        val slotTariffs = (0 until 10).associate {
            (start.toEpochMilli() + it * 1_800_000L) to cosy
        }
        val selected = SlotRateSelector.selectSlotRates(rates, slotTariffs)
        assertEquals(6, selected.size) // 13:00–16:00 only
        assertEquals(start.plusSeconds(5 * 1_800), selected.last().validFrom)
    }

    @Test
    fun `slot price map does not extend the last period`() {
        val start = date(2026, 1, 15)
        val map = SlotRateSelector.buildSlotPriceMap(
            listOf(rate(cosy, start, 14.0, durationMinutes = 60)),
            start,
            start.plusSeconds(4 * 3600)
        )
        assertEquals(2, map.size) // 00:00 and 00:30 only
    }

    @Test
    fun `an open ended rate prices every later slot`() {
        // Flexible/quarterly: valid_to was never published.
        val start = date(2026, 1, 15)
        val open = AgilePrice(
            validFrom = start.minusSeconds(86_400),
            validTo = Instant.ofEpochMilli(OPEN_ENDED_MILLIS),
            priceExcVat = 26.6,
            priceIncVat = 26.6,
            tariffCode = cosy
        )
        val slotTariffs = (0 until 8).associate {
            (start.toEpochMilli() + it * 1_800_000L) to cosy
        }
        val selected = SlotRateSelector.selectSlotRates(listOf(open), slotTariffs)
        assertEquals(8, selected.size)
        assertTrue(selected.all { it.priceIncVat == 26.6 })
    }
}
