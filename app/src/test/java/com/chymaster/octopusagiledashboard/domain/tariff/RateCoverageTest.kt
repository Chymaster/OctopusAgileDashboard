package com.chymaster.octopusagiledashboard.domain.tariff

import com.chymaster.octopusagiledashboard.domain.tariff.RateCoverage.Period
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateCoverageTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val midnight = 1_700_000_000_000L // arbitrary aligned instant

    @Test
    fun `periods tiling through the window end are complete`() {
        val periods = listOf(
            Period(midnight, midnight + 4 * hour),          // 00:00–04:00
            Period(midnight + 4 * hour, midnight + 7 * hour), // 04:00–07:00 (Cosy)
            Period(midnight + 7 * hour, midnight + day)      // 07:00–24:00
        )
        assertTrue(RateCoverage.tilesThrough(periods, midnight + day))
        assertTrue(RateCoverage.tilesThrough(periods, midnight + 10 * hour))
    }

    @Test
    fun `a window ending after the last published period is incomplete`() {
        // The reported bug: the cache stops at tomorrow's first standard
        // period, and every later slot silently reuses it.
        val periods = listOf(
            Period(midnight - day, midnight),           // yesterday
            Period(midnight, midnight + 4 * hour)       // 00:00–04:00 today
        )
        assertFalse(RateCoverage.tilesThrough(periods, midnight + day))
    }

    @Test
    fun `an interior gap is incomplete`() {
        val periods = listOf(
            Period(midnight, midnight + 4 * hour),
            Period(midnight + 7 * hour, midnight + day) // 04:00–07:00 missing
        )
        assertFalse(RateCoverage.tilesThrough(periods, midnight + day))
    }

    @Test
    fun `a gap before the first published period is allowed`() {
        // The tariff did not exist before 12:00; those slots are unpriced,
        // but the periods that do exist reach the window end.
        val periods = listOf(
            Period(midnight + 12 * hour, midnight + day)
        )
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
