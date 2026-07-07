package com.chymaster.octopusagiledashboard.domain.model

/**
 * User-selectable bin durations for chart aggregation.
 * Each entry defines a label for display and the bin width in seconds.
 */
enum class BinDuration(val label: String, val durationSeconds: Long) {
    THIRTY_MINUTES("30m", 30 * 60L),
    ONE_HOUR("1h", 60 * 60L),
    TWO_HOURS("2h", 2 * 3600L),
    FOUR_HOURS("4h", 4 * 3600L),
    TWELVE_HOURS("12h", 12 * 3600L),
    ONE_DAY("1d", 24 * 3600L),
    THREE_DAYS("3d", 72 * 3600L),
    SEVEN_DAYS("7d", 168 * 3600L),
    FOURTEEN_DAYS("14d", 336 * 3600L),
    ONE_MONTH("1M", 30 * 24 * 3600L),
    TWO_MONTHS("2M", 60 * 24 * 3600L),
    SIX_MONTHS("6M", 182 * 24 * 3600L),
    ONE_YEAR("1Y", 365 * 24 * 3600L),
    TWO_YEARS("2Y", 730 * 24 * 3600L),
    FIVE_YEARS("5Y", 1825 * 24 * 3600L);
}
