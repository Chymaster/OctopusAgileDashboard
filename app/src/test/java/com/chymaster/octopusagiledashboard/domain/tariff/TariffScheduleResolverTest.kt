package com.chymaster.octopusagiledashboard.domain.tariff

import com.chymaster.octopusagiledashboard.domain.model.TariffAgreement
import com.chymaster.octopusagiledashboard.domain.model.TariffSchedule
import com.chymaster.octopusagiledashboard.domain.model.TariffSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TariffScheduleResolverTest {

    private val london = ZoneId.of("Europe/London")

    private fun date(year: Int, month: Int, day: Int): Instant =
        LocalDate.of(year, month, day).atStartOfDay(london).toInstant()

    private fun dateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(london).toInstant()

    private fun agreement(
        product: String,
        from: Instant,
        to: Instant? = null
    ) = TariffAgreement(
        tariffCode = "E-1R-$product-L",
        productCode = product,
        validFrom = from,
        validTo = to
    )

    private fun schedule(vararg agreements: TariffAgreement) =
        TariffSchedule(agreements.toList(), TariffSource.AUTO)

    @Test
    fun `normalise sorts by start`() {
        val later = agreement("COSY-22-12-08", date(2026, 3, 1))
        val earlier = agreement("AGILE-24-10-01", date(2026, 1, 1), date(2026, 3, 1))
        assertEquals(listOf(earlier, later), TariffScheduleResolver.normalise(listOf(later, earlier)))
    }

    @Test
    fun `normalise truncates an overlap so the later start wins`() {
        val first = agreement("AGILE-24-10-01", date(2026, 1, 1), date(2026, 3, 1))
        val second = agreement("COSY-22-12-08", date(2026, 2, 1))
        val result = TariffScheduleResolver.normalise(listOf(first, second))
        assertEquals(date(2026, 2, 1), result[0].validTo)
        assertEquals("COSY-22-12-08", result[1].productCode)
    }

    @Test
    fun `normalise merges a same-tariff renewal`() {
        val first = agreement("AGILE-24-10-01", date(2026, 1, 1), date(2026, 2, 1))
        val renewal = agreement("AGILE-24-10-01", date(2026, 2, 1), date(2026, 3, 1))
        val result = TariffScheduleResolver.normalise(listOf(first, renewal))
        assertEquals(1, result.size)
        assertEquals(date(2026, 3, 1), result[0].validTo)
    }

    @Test
    fun `normalise drops zero-length agreements`() {
        val zero = agreement("AGILE-24-10-01", date(2026, 1, 1), date(2026, 1, 1))
        assertTrue(TariffScheduleResolver.normalise(listOf(zero)).isEmpty())
    }

    @Test
    fun `clipToRange splits at the switch`() {
        val schedule = schedule(
            agreement("AGILE-24-10-01", date(2026, 1, 1), date(2026, 3, 1)),
            agreement("COSY-22-12-08", date(2026, 3, 1))
        )
        val segments = TariffScheduleResolver.clipToRange(
            schedule,
            date(2026, 2, 1),
            date(2026, 4, 1)
        )
        assertEquals(2, segments.size)
        assertEquals("AGILE-24-10-01", segments[0].productCode)
        assertEquals(date(2026, 2, 1), segments[0].start)
        assertEquals(date(2026, 3, 1), segments[0].end)
        assertEquals("COSY-22-12-08", segments[1].productCode)
        assertEquals(date(2026, 3, 1), segments[1].start)
        assertEquals(date(2026, 4, 1), segments[1].end)
    }

    @Test
    fun `clipToRange returns nothing before the first agreement`() {
        val schedule = schedule(agreement("AGILE-24-10-01", date(2026, 3, 1)))
        val segments = TariffScheduleResolver.clipToRange(
            schedule,
            date(2026, 1, 1),
            date(2026, 2, 1)
        )
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `clipToRange extends an earlier agreement over a gap`() {
        val schedule = schedule(
            agreement("AGILE-24-10-01", date(2026, 1, 1), date(2026, 2, 1)),
            agreement("COSY-22-12-08", date(2026, 3, 1))
        )
        val segments = TariffScheduleResolver.clipToRange(
            schedule,
            date(2026, 1, 15),
            date(2026, 3, 15)
        )
        assertEquals(2, segments.size)
        assertEquals(date(2026, 3, 1), segments[0].end)
        assertEquals("AGILE-24-10-01", segments[0].productCode)
    }

    @Test
    fun `clipToRange extends an expired last agreement to the range end`() {
        val schedule = schedule(
            agreement("AGILE-24-10-01", date(2026, 1, 1), date(2026, 2, 1))
        )
        val segments = TariffScheduleResolver.clipToRange(
            schedule,
            date(2026, 1, 1),
            date(2026, 4, 1)
        )
        assertEquals(1, segments.size)
        assertEquals(date(2026, 4, 1), segments[0].end)
    }

    @Test
    fun `clipToRange clips an open-ended current agreement`() {
        val schedule = schedule(agreement("COSY-22-12-08", date(2026, 1, 1)))
        val segments = TariffScheduleResolver.clipToRange(
            schedule,
            date(2026, 2, 1),
            date(2026, 3, 1)
        )
        assertEquals(1, segments.size)
        assertEquals(date(2026, 2, 1), segments[0].start)
        assertEquals(date(2026, 3, 1), segments[0].end)
    }

    @Test
    fun `clipToRange tolerates an empty schedule`() {
        val empty = TariffSchedule(emptyList(), TariffSource.FALLBACK)
        assertTrue(TariffScheduleResolver.clipToRange(empty, date(2026, 1, 1), date(2026, 2, 1)).isEmpty())
    }

    @Test
    fun `slot map follows a mid-day switch`() {
        val schedule = schedule(
            agreement("AGILE-24-10-01", date(2026, 1, 1)),
            agreement("COSY-22-12-08", dateTime(2026, 1, 15, 12, 17))
        )
        val start = dateTime(2026, 1, 15, 11, 0)
        val end = dateTime(2026, 1, 15, 13, 0)
        val segments = TariffScheduleResolver.clipToRange(schedule, start, end)
        val map = TariffScheduleResolver.buildSlotTariffMap(segments, start, end)

        assertEquals("E-1R-AGILE-24-10-01-L", map[dateTime(2026, 1, 15, 12, 0).toEpochMilli()])
        assertEquals("E-1R-COSY-22-12-08-L", map[dateTime(2026, 1, 15, 12, 30).toEpochMilli()])
        assertEquals(4, map.size)
    }

    @Test
    fun `switch instants exclude the range start`() {
        val schedule = schedule(
            agreement("AGILE-24-10-01", date(2026, 1, 1), date(2026, 3, 1)),
            agreement("COSY-22-12-08", date(2026, 3, 1))
        )
        val start = date(2026, 3, 1)
        val end = date(2026, 4, 1)
        val segments = TariffScheduleResolver.clipToRange(schedule, start, end)
        assertTrue(TariffScheduleResolver.switchInstants(segments, start, end).isEmpty())

        val wider = TariffScheduleResolver.clipToRange(schedule, date(2026, 2, 1), end)
        assertEquals(
            listOf(date(2026, 3, 1)),
            TariffScheduleResolver.switchInstants(wider, date(2026, 2, 1), end)
        )
    }

    @Test
    fun `agreementAt picks the last agreement at or before the instant`() {
        val agreements = listOf(
            agreement("AGILE-24-10-01", date(2026, 1, 1), date(2026, 3, 1)),
            agreement("COSY-22-12-08", date(2026, 3, 1))
        )
        assertEquals("AGILE-24-10-01", TariffScheduleResolver.agreementAt(agreements, date(2026, 2, 1))?.productCode)
        assertEquals("COSY-22-12-08", TariffScheduleResolver.agreementAt(agreements, date(2026, 5, 1))?.productCode)
        assertNull(TariffScheduleResolver.agreementAt(agreements, date(2025, 12, 1)))
    }

    @Test
    fun `distinct codes keep chronological order`() {
        val schedule = schedule(
            agreement("AGILE-24-10-01", date(2026, 1, 1), date(2026, 3, 1)),
            agreement("COSY-22-12-08", date(2026, 3, 1), date(2026, 5, 1)),
            agreement("AGILE-24-10-01", date(2026, 5, 1))
        )
        val segments = TariffScheduleResolver.clipToRange(schedule, date(2026, 1, 1), date(2026, 6, 1))
        assertEquals(
            listOf("E-1R-AGILE-24-10-01-L", "E-1R-COSY-22-12-08-L"),
            TariffScheduleResolver.distinctCodes(segments)
        )
    }
}
