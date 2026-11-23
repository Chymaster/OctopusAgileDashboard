package com.chymaster.octopusagiledashboard.domain.tariff

import com.chymaster.octopusagiledashboard.domain.tariff.RateCoverage.Load
import com.chymaster.octopusagiledashboard.domain.tariff.RateCoverage.Period
import org.junit.Assert.assertEquals
import org.junit.Test

class RateCoverageTest {

    private val slot = 1_800_000L                       // half-hour slot
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val midnight = 1_700_000_000_000L           // arbitrary aligned instant
    private val slotsPerDay = (day / slot).toInt()      // 48

    // ── plan(): what to fetch, and from where ────────────────────────────

    @Test
    fun `partial multi-period cache resumes from the last cached period`() {
        // The reported bug: the cache stops at today's first standard period.
        // The rest of the window must be fetched from there — not ignored, and
        // not downloaded from the start again.
        val cached = listOf(
            Period(midnight - day + 4 * hour, midnight - day + 7 * hour),
            Period(midnight - day + 7 * hour, midnight),
            Period(midnight, midnight + 4 * hour)   // tail missing
        )
        assertEquals(
            Load.Fetch(midnight + 4 * hour),
            RateCoverage.plan(cached, midnight, midnight + day, slotsPerDay)
        )
    }

    @Test
    fun `multi-period cache reaching the window end needs no fetch`() {
        val cached = listOf(
            Period(midnight, midnight + 4 * hour),            // Cosy
            Period(midnight + 4 * hour, midnight + 7 * hour), // Cosy
            Period(midnight + 7 * hour, midnight + 13 * hour),// standard
            Period(midnight + 13 * hour, midnight + 16 * hour),// Cosy
            Period(midnight + 16 * hour, midnight + 19 * hour),// peak
            Period(midnight + 19 * hour, midnight + 22 * hour),// standard
            Period(midnight + 22 * hour, midnight + day)      // Cosy
        )
        assertEquals(Load.None, RateCoverage.plan(cached, midnight, midnight + day, slotsPerDay))
    }

    @Test
    fun `multi-period cache with an interior hole resumes from the hole`() {
        val cached = listOf(
            Period(midnight, midnight + 4 * hour),
            Period(midnight + 7 * hour, midnight + day) // 04:00–07:00 missing
        )
        assertEquals(
            Load.Fetch(midnight + 4 * hour),
            RateCoverage.plan(cached, midnight, midnight + day, slotsPerDay)
        )
    }

    @Test
    fun `an open ended period completes the window`() {
        // Flexible/quarterly: valid_to was never published, so the rate is in
        // force indefinitely and nothing needs fetching.
        val cached = listOf(Period(midnight - 30 * day, OPEN_ENDED_MILLIS))
        assertEquals(Load.None, RateCoverage.plan(cached, midnight, midnight + day, slotsPerDay))
    }

    @Test
    fun `a series that starts before the window needs no fetch`() {
        // The overlap read stores a period that began before the window;
        // only the tail matters.
        val cached = listOf(Period(midnight - hour, midnight + day))
        assertEquals(Load.None, RateCoverage.plan(cached, midnight, midnight + day, slotsPerDay))
    }

    @Test
    fun `empty cache fetches the whole window`() {
        assertEquals(
            Load.Fetch(midnight),
            RateCoverage.plan(emptyList(), midnight, midnight + day, slotsPerDay)
        )
    }

    @Test
    fun `an empty window needs no fetch`() {
        assertEquals(Load.None, RateCoverage.plan(emptyList(), midnight, midnight, 0))
    }

    @Test
    fun `complete half-hourly cache needs no fetch`() {
        val agile = (0 until slotsPerDay).map { Period(midnight + it * slot, midnight + (it + 1) * slot) }
        assertEquals(Load.None, RateCoverage.plan(agile, midnight, midnight + day, slotsPerDay))
    }

    @Test
    fun `partial half-hourly cache fills gaps instead of refetching the window`() {
        // Agile publishes slot by slot, so a hole is a refresh, not a
        // re-download — and a fully populated series must not be refetched.
        val agile = (0 until slotsPerDay)
            .filter { it !in 10..20 }
            .map { Period(midnight + it * slot, midnight + (it + 1) * slot) }
        assertEquals(Load.FillGaps, RateCoverage.plan(agile, midnight, midnight + day, slotsPerDay))
    }

    @Test
    fun `duplicate half-hourly rows do not fake completeness`() {
        val agile = (0 until 10).flatMap { i ->
            listOf(
                Period(midnight + i * slot, midnight + (i + 1) * slot),
                Period(midnight + i * slot, midnight + (i + 1) * slot) // duplicate
            )
        }
        assertEquals(Load.FillGaps, RateCoverage.plan(agile, midnight, midnight + day, slotsPerDay))
    }

    @Test
    fun `hourly periods are multi-period, not a slot-by-slot series`() {
        // 24 one-hour periods must not be read as an Agile series that is
        // missing half its slots — that would fetch 24 times a day.
        val hourly = (0 until 24).map { Period(midnight + it * hour, midnight + (it + 1) * hour) }
        assertEquals(Load.None, RateCoverage.plan(hourly, midnight, midnight + day, slotsPerDay))
    }

    @Test
    fun `a leading row does not hide a one-slot hole`() {
        // The overlap read returns the period covering the window start. It
        // prices only that first slot and must not count towards the window.
        val rows = buildList {
            add(Period(midnight - hour, midnight))
            for (i in 0 until slotsPerDay) {
                if (i == 10) continue // one missing slot
                add(Period(midnight + i * slot, midnight + (i + 1) * slot))
            }
        }
        assertEquals(Load.FillGaps, RateCoverage.plan(rows, midnight, midnight + day, slotsPerDay))
    }

    @Test
    fun `a mixed series is treated as multi-period and must tile the window`() {
        // Defensive: if a tariff ever returns both slot-sized and long rows,
        // counting is meaningless, so coverage must be judged by tiling.
        val mixed = listOf(
            Period(midnight, midnight + slot),         // slot-sized
            Period(midnight + slot, midnight + day)    // long
        )
        assertEquals(Load.None, RateCoverage.plan(mixed, midnight, midnight + day, slotsPerDay))
    }

    @Test
    fun `isSlotBySlotSeries accepts only slot-sized rows`() {
        assertEquals(true, RateCoverage.isSlotBySlotSeries(listOf(Period(midnight, midnight + slot))))
        assertEquals(false, RateCoverage.isSlotBySlotSeries(listOf(Period(midnight, midnight + hour))))
        assertEquals(false, RateCoverage.isSlotBySlotSeries(emptyList()))
    }
}
