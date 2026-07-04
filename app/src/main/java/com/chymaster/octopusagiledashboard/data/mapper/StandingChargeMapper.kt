package com.chymaster.octopusagiledashboard.data.mapper

import com.chymaster.octopusagiledashboard.data.local.entity.StandingChargeEntity
import com.chymaster.octopusagiledashboard.data.remote.dto.StandingChargeDto
import com.chymaster.octopusagiledashboard.domain.model.StandingCharge
import java.time.Instant
import java.time.OffsetDateTime

// Standing charges don't have half-hour slots like rates, but we use a large
// fallback when valid_to is missing (open-ended tariff) so the charge covers
// any future query range.  10 years is well beyond any realistic horizon.
private const val STANDING_CHARGE_FALLBACK_SECONDS = 10L * 365 * 24 * 3600

private fun parseStandingChargeEnd(validTo: String?, validFromInstant: Instant): Instant {
    if (validTo != null) return OffsetDateTime.parse(validTo).toInstant()
    return validFromInstant.plusSeconds(STANDING_CHARGE_FALLBACK_SECONDS)
}

fun StandingChargeDto.toEntity(tariffCode: String): StandingChargeEntity {
    val validFromInstant = OffsetDateTime.parse(validFrom).toInstant()
    val validToInstant = parseStandingChargeEnd(validTo, validFromInstant)
    return StandingChargeEntity(
        validFrom = validFromInstant.toEpochMilli(),
        validTo = validToInstant.toEpochMilli(),
        valueExcVat = valueExcVat,
        valueIncVat = valueIncVat,
        tariffCode = tariffCode
    )
}

fun StandingChargeEntity.toDomain(): StandingCharge {
    return StandingCharge(
        validFrom = Instant.ofEpochMilli(validFrom),
        validTo = Instant.ofEpochMilli(validTo),
        valueExcVat = valueExcVat,
        valueIncVat = valueIncVat
    )
}
