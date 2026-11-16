package com.chymaster.octopusagiledashboard.data.mapper

import com.chymaster.octopusagiledashboard.core.util.TariffCodes
import com.chymaster.octopusagiledashboard.data.remote.dto.AccountDto
import com.chymaster.octopusagiledashboard.data.remote.dto.AccountMeterPointDto
import com.chymaster.octopusagiledashboard.data.remote.dto.TariffAgreementDto
import com.chymaster.octopusagiledashboard.domain.model.TariffAgreement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime

/** DTO → domain. Returns null for tariff codes that cannot be parsed. */
fun TariffAgreementDto.toDomain(): TariffAgreement? {
    val parsed = TariffCodes.parse(tariffCode) ?: return null
    val from = runCatching { OffsetDateTime.parse(validFrom).toInstant() }.getOrNull() ?: return null
    val to = validTo?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
    return TariffAgreement(
        tariffCode = parsed.tariffCode,
        productCode = parsed.productCode,
        validFrom = from,
        validTo = to
    )
}

fun List<TariffAgreementDto>.toDomainAgreements(): List<TariffAgreement> =
    mapNotNull { it.toDomain() }
        .sortedBy { it.validFrom }

/**
 * Picks the import meter point the app is configured for.
 *
 * MPANs are compared on their last 13 digits because Octopus returns them in
 * several formats (with or without spaces / a leading `S`). An export meter
 * point is never selected: consumption costs must be priced on the import
 * supply.
 */
fun AccountDto.selectMeterPoint(mpan: String?): AccountMeterPointDto? {
    val all = properties.flatMap { it.electricityMeterPoints }
    if (all.isEmpty()) return null

    val importPoints = all.filter { !it.isExport }
    val candidates = importPoints.ifEmpty { all }
    val target = mpan?.let(::normaliseMpan)

    if (target != null) {
        candidates.firstOrNull { normaliseMpan(it.mpan) == target }?.let { return it }
        val last13 = target.takeLast(13)
        candidates.firstOrNull { normaliseMpan(it.mpan).takeLast(13) == last13 }?.let { return it }
    }
    return importPoints.firstOrNull()
}

private fun normaliseMpan(mpan: String): String =
    mpan.filter { it.isDigit() }.ifEmpty { mpan.trim().uppercase() }

// ── Persistence ─────────────────────────────────────────────────────

@Serializable
internal data class PersistedTariffAgreement(
    val tariffCode: String,
    val productCode: String,
    val validFrom: Long,
    val validTo: Long? = null
)

@Serializable
internal data class PersistedTariffSchedule(
    val agreements: List<PersistedTariffAgreement>
)

object TariffAgreementMapper {

    fun encode(agreements: List<TariffAgreement>, json: Json): String =
        json.encodeToString(
            PersistedTariffSchedule.serializer(),
            PersistedTariffSchedule(
                agreements = agreements.map {
                    PersistedTariffAgreement(
                        tariffCode = it.tariffCode,
                        productCode = it.productCode,
                        validFrom = it.validFrom.toEpochMilli(),
                        validTo = it.validTo?.toEpochMilli()
                    )
                }
            )
        )

    /** Decodes a persisted schedule; null when absent or corrupt. */
    fun decode(raw: String?, json: Json): List<TariffAgreement>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(PersistedTariffSchedule.serializer(), raw)
        }.getOrNull()?.agreements?.map {
            TariffAgreement(
                tariffCode = it.tariffCode,
                productCode = it.productCode,
                validFrom = Instant.ofEpochMilli(it.validFrom),
                validTo = it.validTo?.let(Instant::ofEpochMilli)
            )
        }?.sortedBy { it.validFrom }
    }
}
