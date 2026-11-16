package com.chymaster.octopusagiledashboard.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChartMarkersTest {

    private val base = Instant.parse("2026-01-15T00:00:00Z")

    private fun minutes(m: Long): Instant = base.plusSeconds(m * 60)

    private fun halfHourIntervals(count: Int): List<Pair<Instant, Instant>> =
        (0 until count).map { minutes(it * 30L) to minutes(it * 30L + 30) }

    @Test
    fun `switch inside a raw half-hour bar marks that bar`() {
        val intervals = halfHourIntervals(8) // 00:00 .. 04:00
        // Switch at 01:17 — the 01:00 bar ends at 01:30, after the switch.
        assertEquals(listOf(2), markerBarIndices(intervals, listOf(minutes(77))))
    }

    @Test
    fun `switch on a bar boundary marks the first bar of the new tariff`() {
        val intervals = halfHourIntervals(8)
        assertEquals(listOf(2), markerBarIndices(intervals, listOf(minutes(60))))
    }

    @Test
    fun `switch before the range produces no marker`() {
        val intervals = halfHourIntervals(8)
        assertTrue(markerBarIndices(intervals, listOf(minutes(-60))).isEmpty())
        assertTrue(markerBarIndices(intervals, listOf(minutes(0))).isEmpty())
    }

    @Test
    fun `switch after the range produces no marker`() {
        val intervals = halfHourIntervals(4) // 00:00 .. 02:00
        assertTrue(markerBarIndices(intervals, listOf(minutes(240))).isEmpty())
    }

    @Test
    fun `switch inside a binned bar marks that bin`() {
        val intervals = listOf(
            minutes(0) to minutes(1440),      // day 1
            minutes(1440) to minutes(2880)    // day 2
        )
        assertEquals(listOf(1), markerBarIndices(intervals, listOf(minutes(2000))))
    }

    @Test
    fun `multiple switches map to increasing distinct bars`() {
        val intervals = halfHourIntervals(16)
        val markers = markerBarIndices(intervals, listOf(minutes(70), minutes(75), minutes(300)))
        assertEquals(listOf(2, 10), markers)
    }

    @Test
    fun `empty inputs produce no markers`() {
        assertTrue(markerBarIndices(emptyList(), listOf(minutes(10))).isEmpty())
        assertTrue(markerBarIndices(halfHourIntervals(2), emptyList()).isEmpty())
    }

    @Test
    fun `chart markers carry their labels`() {
        val intervals = halfHourIntervals(8)
        val markers = buildChartMarkers(intervals, listOf(minutes(77))) { "Cosy" }
        assertEquals(1, markers.size)
        assertEquals(2, markers.single().barIndex)
        assertEquals("Cosy", markers.single().label)
    }
}
