package com.chymaster.octopusagiledashboard.ui.chart

import java.time.Instant

/**
 * A vertical marker on a chart, anchored to a bar. Used to show the points at
 * which the user's tariff changed.
 */
data class ChartMarker(
    /** Index into the chart's bar list. */
    val barIndex: Int,
    val label: String? = null
)

/**
 * Maps tariff-switch instants onto bar indices: the marker goes on the first
 * bar whose interval ends after the switch, i.e. the first bar that is priced
 * with the new tariff. Switches at or before the first bar's start produce no
 * marker (the whole chart is already the new tariff); switches at or after the
 * last bar's end produce none either.
 *
 * @param intervals `(start, end)` of every bar, in chart order.
 */
fun markerBarIndices(
    intervals: List<Pair<Instant, Instant>>,
    switches: List<Instant>
): List<Int> {
    if (intervals.isEmpty() || switches.isEmpty()) return emptyList()
    val rangeStart = intervals.first().first
    return switches
        .asSequence()
        .filter { it.isAfter(rangeStart) }
        .mapNotNull { switchInstant ->
            intervals.indexOfFirst { it.second.isAfter(switchInstant) }
                .takeIf { it >= 0 }
        }
        .distinct()
        .sorted()
        .toList()
}

/** Builds the [ChartMarker]s for a chart, optionally labelling each switch. */
fun buildChartMarkers(
    intervals: List<Pair<Instant, Instant>>,
    switches: List<Instant>,
    labelFor: (Instant) -> String? = { null }
): List<ChartMarker> {
    if (intervals.isEmpty() || switches.isEmpty()) return emptyList()
    val rangeStart = intervals.first().first
    val seen = HashSet<Int>()
    val markers = mutableListOf<ChartMarker>()
    for (switchInstant in switches.sorted()) {
        if (!switchInstant.isAfter(rangeStart)) continue
        val index = intervals.indexOfFirst { it.second.isAfter(switchInstant) }
        if (index < 0 || !seen.add(index)) continue
        markers.add(ChartMarker(barIndex = index, label = labelFor(switchInstant)))
    }
    return markers
}
