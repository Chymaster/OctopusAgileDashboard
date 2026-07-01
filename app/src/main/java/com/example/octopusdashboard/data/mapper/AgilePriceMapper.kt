package com.example.octopusdashboard.data.mapper

import com.example.octopusdashboard.data.local.entity.AgilePriceEntity
import com.example.octopusdashboard.data.remote.dto.AgileRateDto
import com.example.octopusdashboard.domain.model.AgilePrice
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Octopus rate slots are always 30 minutes; used as a fallback when the API
// omits valid_to (which it does for the currently in-progress rate).
private const val RATE_SLOT_SECONDS = 1800L

private fun parseRateEnd(validTo: String?, validFromInstant: Instant): Instant {
    if (validTo != null) return OffsetDateTime.parse(validTo).toInstant()
    return validFromInstant.plusSeconds(RATE_SLOT_SECONDS)
}

fun AgileRateDto.toEntity(tariffCode: String): AgilePriceEntity {
    val validFromInstant = OffsetDateTime.parse(validFrom).toInstant()
    val validToInstant = parseRateEnd(validTo, validFromInstant)
    return AgilePriceEntity(
        validFrom = validFromInstant.toEpochMilli(),
        validTo = validToInstant.toEpochMilli(),
        priceExcVat = valueExcVat,
        priceIncVat = valueIncVat,
        tariffCode = tariffCode
    )
}

fun AgilePriceEntity.toDomain(): AgilePrice {
    return AgilePrice(
        validFrom = Instant.ofEpochMilli(validFrom),
        validTo = Instant.ofEpochMilli(validTo),
        priceExcVat = priceExcVat,
        priceIncVat = priceIncVat
    )
}

fun AgileRateDto.toDomain(): AgilePrice {
    val validFromInstant = OffsetDateTime.parse(validFrom).toInstant()
    val validToInstant = parseRateEnd(validTo, validFromInstant)
    return AgilePrice(
        validFrom = validFromInstant,
        validTo = validToInstant,
        priceExcVat = valueExcVat,
        priceIncVat = valueIncVat
    )
}
