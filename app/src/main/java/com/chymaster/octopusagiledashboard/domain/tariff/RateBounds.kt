package com.chymaster.octopusagiledashboard.domain.tariff

/**
 * End of a rate whose `valid_to` the Octopus API omitted because the period is
 * still open in force — e.g. Flexible's current quarterly unit rate.
 *
 * Stored as epoch millis so the cache's NOT NULL `validTo` column needs no
 * migration; interval comparisons treat it as "covers every later slot".
 * `Instant.MAX` is deliberately not used: its epoch millis overflow.
 */
const val OPEN_ENDED_MILLIS: Long = Long.MAX_VALUE
