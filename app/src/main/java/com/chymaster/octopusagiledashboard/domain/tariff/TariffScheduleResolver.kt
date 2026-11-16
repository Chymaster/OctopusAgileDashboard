package com.chymaster.octopusagiledashboard.domain.tariff

import com.chymaster.octopusagiledashboard.domain.model.TariffAgreement
import com.chymaster.octopusagiledashboard.domain.model.TariffSchedule
import com.chymaster.octopusagiledashboard.domain.model.TariffSegment
import java.time.Instant

/**
 * Pure helpers that turn a list of [TariffAgreement]s into the "which tariff
 * applied when" answers the rest of the app needs.
 *
 * Coverage rules:
 *  - agreements are sorted and de-duplicated, later starts winning an overlap;
 *  - a gap between two agreements (or an expired last agreement) is covered by
 *    extending the earlier agreement forward — Octopus does not leave a
 *    meter point untariffed, and dropping the slots would silently remove
 *    usage from the totals;
 *  - nothing is invented *before* the first agreement: that is genuinely
 *    unknown history, and slots there are left unpriced.
 */
object TariffScheduleResolver {

    /** Canonical half-hour slot. */
    const val SLOT_MILLIS = 1_800_000L

    /**
     * Sorts, drops zero-length entries, collapses adjacent agreements on the
     * same tariff, and truncates overlaps (later start wins).
     */
    fun normalise(agreements: List<TariffAgreement>): List<TariffAgreement> {
        val sorted = agreements
            .filter { it.tariffCode.isNotBlank() }
            .filter { it.validTo == null || it.validTo.isAfter(it.validFrom) }
            .sortedWith(compareBy({ it.validFrom }, { it.validTo ?: Instant.MAX }))

        val out = ArrayList<TariffAgreement>(sorted.size)
        for (agreement in sorted) {
            val prev = out.lastOrNull()
            if (prev == null) {
                out.add(agreement)
                continue
            }
            if (prev.tariffCode == agreement.tariffCode) {
                // Same tariff renewed — one continuous agreement.
                out[out.lastIndex] = prev.copy(validTo = agreement.validTo)
                continue
            }
            if (prev.validTo == null || prev.validTo.isAfter(agreement.validFrom)) {
                val truncated = prev.copy(validTo = agreement.validFrom)
                if (truncated.validTo == null || truncated.validTo.isAfter(truncated.validFrom)) {
                    out[out.lastIndex] = truncated
                } else {
                    out.removeAt(out.lastIndex)
                }
            }
            out.add(agreement)
        }
        return out
    }

    /** The tariff in force at [instant], or null before the first agreement. */
    fun agreementAt(agreements: List<TariffAgreement>, instant: Instant): TariffAgreement? =
        normalise(agreements)
            .lastOrNull { !it.validFrom.isAfter(instant) }

    /**
     * Clips [schedule] to `[start, end)`. Segments tile the part of the range
     * covered by at least one agreement; earlier agreements are extended over
     * gaps and the last one is extended to [end].
     */
    fun clipToRange(schedule: TariffSchedule, start: Instant, end: Instant): List<TariffSegment> {
        if (!start.isBefore(end)) return emptyList()
        val agreements = normalise(schedule.agreements)
        if (agreements.isEmpty()) return emptyList()

        val segments = ArrayList<TariffSegment>(agreements.size)
        for ((index, agreement) in agreements.withIndex()) {
            val nextStart = agreements.getOrNull(index + 1)?.validFrom
            // Extend forward over any gap; the last agreement runs to the range end.
            val effectiveEnd = nextStart ?: end
            val segmentStart = maxOf(agreement.validFrom, start)
            val segmentEnd = minOf(effectiveEnd, end)
            if (segmentStart.isBefore(segmentEnd)) {
                segments.add(TariffSegment(agreement, segmentStart, segmentEnd))
            }
        }
        return segments
    }

    /**
     * Maps every half-hour slot start (epoch millis) in `[start, end)` to the
     * tariff code active in that slot. Slots before the first agreement are
     * absent from the map.
     */
    fun buildSlotTariffMap(
        segments: List<TariffSegment>,
        start: Instant,
        end: Instant,
        slotMillis: Long = SLOT_MILLIS
    ): Map<Long, String> {
        if (segments.isEmpty() || !start.isBefore(end)) return emptyMap()
        val map = HashMap<Long, String>()
        var t = start
        while (t.isBefore(end)) {
            val segment = segments.lastOrNull { !t.isBefore(it.start) && t.isBefore(it.end) }
            if (segment != null) map[t.toEpochMilli()] = segment.tariffCode
            t = t.plusMillis(slotMillis)
        }
        return map
    }

    /**
     * Instants at which the tariff changes inside `[start, end)` — the first
     * segment's start counts only when it falls strictly inside the range, so
     * a range that begins mid-agreement has no marker at its left edge.
     */
    fun switchInstants(segments: List<TariffSegment>, start: Instant, end: Instant): List<Instant> =
        segments
            .map { it.start }
            .filter { it.isAfter(start) && it.isBefore(end) }
            .distinct()
            .sorted()

    /** Distinct tariff codes covered by [segments], in chronological order. */
    fun distinctCodes(segments: List<TariffSegment>): List<String> =
        segments.map { it.tariffCode }.distinct()

    /** Distinct product codes covered by [segments], in chronological order. */
    fun distinctProductCodes(segments: List<TariffSegment>): List<String> =
        segments.map { it.productCode }.distinct()
}
