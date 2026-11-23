package com.chymaster.octopusagiledashboard.domain.tariff

/**
 * Decides what still has to be loaded for a window of cached rate rows.
 *
 * Agile-style tariffs publish exactly one row per half-hour slot, so coverage
 * can be checked by counting rows and a hole is a gap to fill. Multi-period
 * tariffs (Cosy, fixed, Flexible/quarterly) publish one row per price period —
 * a few hours, or a whole quarter — so what matters is whether the periods run
 * unbroken to the end of the window. When they stop short, the fetch resumes
 * from the first uncovered instant (the cache's "available through" date)
 * instead of downloading the whole window again.
 *
 * An open-ended period ([OPEN_ENDED_MILLIS]) is in force indefinitely, so it
 * covers every later slot and the window is complete.
 */
object RateCoverage {

    /** One rate row's validity window, in epoch millis. */
    data class Period(val start: Long, val end: Long)

    /** What to load for a window, given the rows already cached. */
    sealed interface Load {
        /** The cached rows cover the whole window. */
        data object None : Load

        /** Fetch `[fromMillis, end)` — the cached rows stop short or have a hole. */
        data class Fetch(val fromMillis: Long) : Load

        /** A slot-by-slot series with some slots missing — fetch just the gaps. */
        data object FillGaps : Load
    }

    /**
     * Chooses the load needed so [periods] describe the tariff's rates up to
     * [endMillis].
     *
     * @param expectedSlotCount half-hour slots in the window.
     */
    fun plan(
        periods: List<Period>,
        startMillis: Long,
        endMillis: Long,
        expectedSlotCount: Int,
        slotMillis: Long = TariffScheduleResolver.SLOT_MILLIS
    ): Load {
        if (expectedSlotCount <= 0) return Load.None
        if (periods.isEmpty()) return Load.Fetch(startMillis)
        // Only rows that begin inside the window describe its shape; the row
        // covering the window start (returned by the overlap read) prices its
        // first slot and must not flip an Agile series into "multi-period".
        val inWindow = periods.filter { it.start >= startMillis && it.start < endMillis }
        return if (isSlotBySlotSeries(inWindow, slotMillis)) {
            if (inWindow.map { it.start }.distinct().size >= expectedSlotCount) Load.None
            else Load.FillGaps
        } else {
            multiPeriodLoad(periods, startMillis, endMillis)
        }
    }

    /** True when every row is one slot long — an Agile-style series. */
    fun isSlotBySlotSeries(
        periods: List<Period>,
        slotMillis: Long = TariffScheduleResolver.SLOT_MILLIS
    ): Boolean = periods.isNotEmpty() && periods.all { it.end - it.start <= slotMillis }

    private fun multiPeriodLoad(
        periods: List<Period>,
        startMillis: Long,
        endMillis: Long
    ): Load {
        val sorted = periods.sortedBy { it.start }
        // A gap before the first published period is allowed: nothing has been
        // published there (the tariff had not started), and those slots stay
        // unpriced rather than borrowing a neighbouring period.
        var cursor = maxOf(startMillis, sorted.first().start)
        for (period in sorted) {
            if (period.end <= cursor) continue          // fully overlapped / duplicate
            if (period.start > cursor) return Load.Fetch(cursor) // interior hole or tail
            cursor = period.end
            if (cursor >= endMillis) return Load.None
        }
        return Load.Fetch(cursor)
    }
}
