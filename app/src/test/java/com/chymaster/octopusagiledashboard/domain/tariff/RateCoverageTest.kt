package com.chymaster.octopusagiledashboard.domain.tariff

import com.chymaster.octopusagiledashboard.domain.tariff.RateCoverage.Load
import com.chymaster.octopusagiledashboard.domain.tariff.RateCoverage.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateCoverageTest {

    private val slot = 1_800_000L                       // half-hour slot
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val midnight = 1_700_000_000_000L           // arbitrary aligned instant
    private val slotsPerDay = (day / slot).toInt()      // 48

    // ── plan(): the fetch decision that broke Future Prices ──────────────

    @Test
    fun `partial multi-period cache is refetched rather than treated as complete`() {
        // The reported bug: Octopus published yesterday plus only tomorrow's
        // first standard period (00:00–04:00). Slot selection then extended
        // that period forward, so Cosy showed 28.8p all day with no Cosy or
        // peak windows on the days after the tariff switch.
        val cached = listOf(
            Period(midnight - day + 4 * hour, midnight - day + 7 * hour),
            Period(midnight - day + 7 * hour, midnight),
            Period(midnight, midnight + 4 * hour)   // tail missing
        )
        assertEquals(Load.FETCH_WINDOW, RateCoverage.plan(cached, midnight + day, slotsPerDay))
    }

    @Test
    fun `multi-period cache reaching the window end needs no fetch`() {
        val cached = listOf(
            Period(midnight, midnight + 4 * hour),           // Cosy
            Period(midnight + 4 * hour, midnight + 7 * hour), // Cosy
            Period(midnight + 7 * hour, midnight + 13 * hour),// standard
            Period(midnight + 13 * hour, midnight + 16 * hour),// Cosy
            Period(midnight + 16 * hour, midnight + 19 * hour),// peak
            Period(midnight + 19 * hour, midnight + 22 * hour),// standard
            Period(midnight + 22 * hour, midnight + day)      // Cosy
        )
        assertEquals(Load.NONE, RateCoverage.plan(cached, midnight + day, slotsPerDay))
    }

    @Test
    fun `multi-period cache with an interior hole is refetched`() {
        val cached = listOf(
            Period(midnight, midnight + 4 * hour),
            Period(midnight + 7 * hour, midnight + day) // 04:00–07:00 missing
        )
        assertEquals(Load.FETCH_WINDOW, RateCoverage.plan(cached, midnight + day, slotsPerDay))
    }

    @Test
    fun `multi-period series that starts before the window needs no fetch`() {
        // The 25 h fetch lookback stores a period that began before the window;
        // only the tail matters.
        val cached = listOf(Period(midnight - hour, midnight + day))
        assertEquals(Load.NONE, RateCoverage.plan(cached, midnight + day, slotsPerDay))
    }

    @Test
    fun `empty cache fetches the window`() {
        assertEquals(Load.FETCH_WINDOW, RateCoverage.plan(emptyList(), midnight + day, slotsPerDay))
    }

    @Test
    fun `an empty window needs no fetch`() {
        assertEquals(Load.NONE, RateCoverage.plan(emptyList(), midnight, 0))
    }

    @Test
    fun `complete half-hourly cache needs no fetch`() {
        val agile = (0 until slotsPerDay).map { Period(midnight + it * slot, midnight + (it + 1) * slot) }
        assertEquals(Load.NONE, RateCoverage.plan(agile, midnight + day, slotsPerDay))
    }

    @Test
    fun `partial half-hourly cache fills gaps instead of refetching the window`() {
        // Agile publishes slot by slot, so a hole is a refresh, not a
        // re-download — and a fully populated series must not be refetched.
        val agile = (0 until slotsPerDay)
            .filter { it !in 10..20 }
            .map { Period(midnight + it * slot, midnight + (it + 1) * slot) }
        assertEquals(Load.FILL_GAPS, RateCoverage.plan(agile, midnight + day, slotsPerDay))
    }

    @Test
    fun `duplicate half-hourly rows do not fake completeness`() {
        val agile = (0 until 10).flatMap { i ->
            listOf(
                Period(midnight + i * slot, midnight + (i + 1) * slot),
                Period(midnight + i * slot, midnight + (i + 1) * slot) // duplicate
            )
        }
        assertEquals(Load.FILL_GAPS, RateCoverage.plan(agile, midnight + day, slotsPerDay))
    }

    @Test
    fun `a mixed series is treated as multi-period and must tile the window`() {
        // Defensive: if a tariff ever returns both slot-sized and long rows,
        // counting is meaningless, so coverage must be judged by tiling.
        val mixed = listOf(
            Period(midnight, midnight + slot),         // slot-sized
            Period(midnight + slot, midnight + day)    // long
        )
        assertEquals(Load.NONE, RateCoverage.plan(mixed, midnight + day, slotsPerDay))
    }

    // ── tilesThrough(): the underlying coverage rule ─────────────────────

    @Test
    fun `periods tiling through the window end are complete`() {
        val periods = listOf(
            Period(midnight, midnight + 4 * hour),
            Period(midnight + 4 * hour, midnight + 7 * hour),
            Period(midnight + 7 * hour, midnight + day)
        )
        assertTrue(RateCoverage.tilesThrough(periods, midnight + day))
        assertTrue(RateCoverage.tilesThrough(periods, midnight + 10 * hour))
    }

    @Test
    fun `a window ending after the last published period is incomplete`() {
        val periods = listOf(
            Period(midnight - day, midnight),
            Period(midnight, midnight + 4 * hour)
        )
        assertFalse(RateCoverage.tilesThrough(periods, midnight + day))
    }

    @Test
    fun `an interior gap is incomplete`() {
        val periods = listOf(
            Period(midnight, midnight + 4 * hour),
            Period(midnight + 7 * hour, midnight + day)
        )
        assertFalse(RateCoverage.tilesThrough(periods, midnight + day))
    }

    @Test
    fun `a gap before the first published period is allowed`() {
        val periods = listOf(Period(midnight + 12 * hour, midnight + day))
        assertTrue(RateCoverage.tilesThrough(periods, midnight + day))
    }

    @Test
    fun `overlapping and duplicate rows do not break coverage`() {
        val periods = listOf(
            Period(midnight + 4 * hour, midnight + 7 * hour),
            Period(midnight, midnight + 4 * hour),
            Period(midnight + 4 * hour, midnight + 7 * hour), // duplicate
            Period(midnight + 6 * hour, midnight + day)       // overlap
        )
        assertTrue(RateCoverage.tilesThrough(periods, midnight + day))
    }

    @Test
    fun `no periods are never complete`() {
        assertFalse(RateCoverage.tilesThrough(emptyList(), midnight))
    }
}
