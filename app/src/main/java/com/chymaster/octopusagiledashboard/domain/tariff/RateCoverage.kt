package com.chymaster.octopusagiledashboard.domain.tariff

/**
 * Decides what has to be loaded for a window of cached rate rows.
 *
 * Agile-style tariffs publish exactly one row per half-hour slot, so their
 * coverage can be checked by counting rows. Cosy / Flexible-style tariffs
 * publish one row per *price period* (a few hours, or a whole day), so row
 * count says nothing about coverage — what matters is whether the periods run
 * unbroken to the end of the window.
 *
 * This distinction matters because Octopus publishes those tariffs' future
 * periods incrementally: a fetch can return today's rows and the first period
 * of tomorrow, and the rest of tomorrow appears later. Treating "some rows
 * exist" as "the window is cached" freezes the newest days on whatever period
 * happened to be published last (e.g. Cosy showing its standard rate all day,
 * with no Cosy or peak windows).
 */
object RateCoverage {

    /** One rate row's validity window, in epoch millis. */
    data class Period(val start: Long, val end: Long)

    /** What still has to be fetched for a window, given the rows already cached. */
    enum class Load {
        /** The cached rows cover the whole window. */
        NONE,

        /** Fetch the whole window — the cached rows are missing a tail or have a hole. */
        FETCH_WINDOW,

        /** A slot-by-slot series with some slots missing — fetch just the gaps. */
        FILL_GAPS
    }

    /**
     * Chooses the fetch needed so [periods] describe the tariff's rates up to
     * [endMillis].
     *
     * @param expectedSlotCount half-hour slots in the window.
     */
    fun plan(
        periods: List<Period>,
        endMillis: Long,
        expectedSlotCount: Int,
        slotMillis: Long = TariffScheduleResolver.SLOT_MILLIS
    ): Load {
        if (expectedSlotCount <= 0) return Load.NONE
        if (periods.isEmpty()) return Load.FETCH_WINDOW
        return if (isSlotBySlotSeries(periods, slotMillis)) {
            val distinctSlots = periods.map { it.start }.distinct().size
            if (distinctSlots >= expectedSlotCount) Load.NONE else Load.FILL_GAPS
        } else {
            if (tilesThrough(periods, endMillis)) Load.NONE else Load.FETCH_WINDOW
        }
    }

    /** True when every row is about one slot long — an Agile-style series. */
    fun isSlotBySlotSeries(
        periods: List<Period>,
        slotMillis: Long = TariffScheduleResolver.SLOT_MILLIS
    ): Boolean = periods.isNotEmpty() && periods.all { it.end - it.start <= 2 * slotMillis }

    /**
     * True when [periods] leave no gap between the first published period and
     * [endMillis]. A gap *before* the first period is allowed: nothing is
     * published there (the tariff had not started), and those slots are left
     * unpriced rather than borrowing a neighbouring period.
     */
    fun tilesThrough(periods: List<Period>, endMillis: Long): Boolean {
        var cursor: Long? = null
        for (period in periods.sortedBy { it.start }) {
            val coveredTo = cursor
            if (coveredTo != null) {
                if (period.end <= coveredTo) continue      // fully overlapped / duplicate
                if (period.start > coveredTo) return false // interior gap
            }
            cursor = period.end
            if (cursor >= endMillis) return true
        }
        return false
    }
}
