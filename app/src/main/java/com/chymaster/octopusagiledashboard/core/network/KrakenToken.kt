package com.chymaster.octopusagiledashboard.core.network

/**
 * Holds a Kraken GraphQL token obtained from the [obtainKrakenToken]
 * mutation.
 *
 * @property token The short-lived Bearer access token.
 * @property refreshToken Optional refresh token — present when the
 *   token was obtained via API key, absent when obtained via a
 *   refresh token exchange.
 * @property refreshExpiresIn Epoch **seconds** when the refresh
 *   token expires. The access token itself has a fixed 1-hour TTL
 *   (the API doesn't return an explicit TTL for it).
 * @property obtainedAt Epoch **millis** when this token was obtained
 *   (used to calculate the access-token expiry locally).
 */
data class KrakenToken(
    val token: String,
    val refreshToken: String?,
    val refreshExpiresIn: Long,
    val obtainedAt: Long = System.currentTimeMillis()
) {
    /** True when the access token is within 5 minutes of the 1-hour TTL. */
    fun isAccessTokenExpired(): Boolean {
        val ageMillis = System.currentTimeMillis() - obtainedAt
        return ageMillis >= ACCESS_TOKEN_VALIDITY_MILLIS
    }

    /** True when the refresh token has passed its server-reported expiry. */
    fun isRefreshTokenExpired(): Boolean {
        val refreshExpiresMs = refreshExpiresIn * 1000L
        return System.currentTimeMillis() >= refreshExpiresMs
    }

    companion object {
        /** 55 minutes — 1 hour minus a 5-minute safety buffer. */
        private const val ACCESS_TOKEN_VALIDITY_MILLIS = 55L * 60 * 1000
    }
}
