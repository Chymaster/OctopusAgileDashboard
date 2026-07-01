package com.example.octopusdashboard.domain.model

import java.time.LocalDate

enum class TimeRangePreset(val label: String, val days: Long) {
    TODAY("Today", 1),
    THREE_DAYS("3D", 3),
    SEVEN_DAYS("7D", 7),
    ONE_MONTH("1M", 30),
    SIX_MONTHS("6M", 182),
    ONE_YEAR("1Y", 365)
}

data class CustomDateRange(
    val startDate: LocalDate,
    val endDate: LocalDate
)

sealed interface DateRangeSelection {
    data class Preset(val preset: TimeRangePreset) : DateRangeSelection
    data class Custom(val range: CustomDateRange) : DateRangeSelection
}
