package com.chymaster.octopusagiledashboard.domain.model

import java.time.Instant

/** Where the effective tariff schedule came from. */
enum class TariffSource {
    /** Detected from the Octopus account / meter point agreements. */
    AUTO,

    /** User turned off detection and pinned a single product code. */
    MANUAL_OVERRIDE,

    /** No credentials — synthetic data, single configured product. */
    DEMO,

    /** Detection unavailable; falling back to the configured product code. */
    FALLBACK
}

/**
 * One tariff agreement on a meter point: the tariff that applied from
 * [validFrom] until [validTo]. A `null` [validTo] means the agreement is still
 * in force (Octopus returns no end date for the current tariff).
 */
data class TariffAgreement(
    val tariffCode: String,   // e.g. "E-1R-COSY-22-12-08-L"
    val productCode: String,  // e.g. "COSY-22-12-08"
    val validFrom: Instant,
    val validTo: Instant? = null
)

/** A [TariffAgreement] clipped to a requested range: active over `[start, end)`. */
data class TariffSegment(
    val agreement: TariffAgreement,
    val start: Instant,
    val end: Instant
) {
    val tariffCode: String get() = agreement.tariffCode
    val productCode: String get() = agreement.productCode
}

/**
 * The ordered, non-overlapping list of tariffs that applied to a meter point.
 *
 * The effective schedule is always normalised so that every instant from the
 * first agreement onward is covered by exactly one agreement — see
 * `TariffScheduleResolver.clipToRange`.
 */
data class TariffSchedule(
    val agreements: List<TariffAgreement>,
    val source: TariffSource,
    val fetchedAt: Instant? = null,
    val mpan: String? = null,
    val accountNumber: String? = null,
    val error: String? = null
) {
    val isEmpty: Boolean get() = agreements.isEmpty()
}
