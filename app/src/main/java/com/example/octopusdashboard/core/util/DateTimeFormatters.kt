package com.example.octopusdashboard.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateTimeFormatters {

    private val londonZone = ZoneId.of("Europe/London")

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.UK)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.UK)
    private val fullDateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.UK)
    private val intervalFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    fun formatTime(instant: Instant): String {
        return instant.atZone(londonZone).format(timeFormatter)
    }

    fun formatDate(instant: Instant): String {
        return instant.atZone(londonZone).format(dateFormatter)
    }

    fun formatDateTime(instant: Instant): String {
        return instant.atZone(londonZone).format(dateTimeFormatter)
    }

    fun formatFullDateTime(instant: Instant): String {
        return instant.atZone(londonZone).format(fullDateTimeFormatter)
    }

    fun formatInterval(start: Instant, end: Instant): String {
        return "${start.atZone(londonZone).format(intervalFormatter)} – ${end.atZone(londonZone).format(intervalFormatter)}"
    }

    fun formatPrice(pence: Double): String {
        return String.format(Locale.UK, "%.2f p/kWh", pence)
    }

    fun formatCost(pence: Double): String {
        return if (pence >= 100) {
            String.format(Locale.UK, "£%.2f", pence / 100.0)
        } else {
            String.format(Locale.UK, "%.2f p", pence)
        }
    }

    fun formatKwh(kwh: Double): String {
        return String.format(Locale.UK, "%.3f kWh", kwh)
    }

    fun localDateToInstantStart(date: LocalDate): Instant {
        return date.atStartOfDay(londonZone).toInstant()
    }

    fun localDateToInstantEnd(date: LocalDate): Instant {
        return date.plusDays(1).atStartOfDay(londonZone).toInstant()
    }
}
