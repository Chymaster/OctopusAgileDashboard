package com.chymaster.octopusagiledashboard.data.mapper

import com.chymaster.octopusagiledashboard.data.local.entity.AgilePriceEntity
import com.chymaster.octopusagiledashboard.data.remote.dto.AgileRateDto
import com.chymaster.octopusagiledashboard.domain.model.AgilePrice
import com.chymaster.octopusagiledashboard.domain.tariff.OPEN_ENDED_MILLIS
import java.time.Instant
import java.time.OffsetDateTime

// Octopus omits valid_to for the period still in force (e.g. Flexible's
// current quarterly rate). That is an open-ended period, not a 30-minute one:
// keep it open so it prices every slot until a later period supersedes it.
private fun parseRateEnd(validTo: String?): Instant {
    if (validTo != null) return OffsetDateTime.parse(validTo).toInstant()
    return Instant.ofEpochMilli(OPEN_ENDED_MILLIS)
}

fun AgileRateDto.toEntity(tariffCode: String): AgilePriceEntity {
    val validFromInstant = OffsetDateTime.parse(validFrom).toInstant()
    val validToInstant = parseRateEnd(validTo)
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
        priceIncVat = priceIncVat,
        tariffCode = tariffCode
    )
}

fun AgileRateDto.toDomain(tariffCode: String? = null): AgilePrice {
    val validFromInstant = OffsetDateTime.parse(validFrom).toInstant()
    val validToInstant = parseRateEnd(validTo)
    return AgilePrice(
        validFrom = validFromInstant,
        validTo = validToInstant,
        priceExcVat = valueExcVat,
        priceIncVat = valueIncVat,
        tariffCode = tariffCode
    )
}
