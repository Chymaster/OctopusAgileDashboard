package com.chymaster.octopusagiledashboard.domain.tariff

/**
 * Decides whether cached rate rows fully describe a time window.
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
