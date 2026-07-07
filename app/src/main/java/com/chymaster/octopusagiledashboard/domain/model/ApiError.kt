package com.chymaster.octopusagiledashboard.domain.model

/**
 * Structured error hierarchy for API and network failures.
 *
 * Each subtype maps to a specific failure mode so callers can
 * distinguish transient errors (rate-limit, service unavailable)
 * from permanent ones (bad credentials, missing data).
 */
sealed class ApiError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** HTTP 401 — bad or missing credentials. */
    data class Unauthorized(
        override val message: String = "Invalid API key or credentials"
    ) : ApiError(message)

    /** HTTP 429 — rate limited. */
    data class RateLimited(
        val retryAfterSeconds: Long? = null,
        override val message: String = "Too many requests. Please try again later."
    ) : ApiError(message)

    /** HTTP 503 — server temporarily unavailable. */
    data class ServiceUnavailable(
        override val message: String = "Service temporarily unavailable"
    ) : ApiError(message)

    /** Any other non-2xx HTTP status. */
    data class HttpError(
        val code: Int,
        override val message: String = "Server error ($code)"
    ) : ApiError(message)

    /** Network-level failure (timeout, DNS, no connectivity). */
    data class NetworkError(
        override val message: String = "Network error. Check your connection.",
        override val cause: Throwable? = null
    ) : ApiError(message, cause)

    /** Data was expected but not found. */
    data class NoDataError(
        override val message: String = "No data available for the requested period"
    ) : ApiError(message)

    companion object {
        /** Map an HTTP response code to the appropriate [ApiError]. */
        fun fromHttpCode(code: Int): ApiError = when (code) {
            401 -> Unauthorized()
            429 -> RateLimited()
            503 -> ServiceUnavailable()
            else -> HttpError(code)
        }
    }
}

/**
 * Map any throwable to a user-friendly message string.
 * Prefer specific [ApiError] subtypes for accurate messages.
 */
fun Throwable.toUserMessage(): String = when (this) {
    is ApiError.Unauthorized -> message
    is ApiError.RateLimited -> retryAfterSeconds?.let {
        "Too many requests. Try again in ${it}s."
    } ?: message
    is ApiError.ServiceUnavailable -> message
    is ApiError.NetworkError -> message
    is ApiError.HttpError -> "Server error ($code). Please try again."
    is ApiError.NoDataError -> message
    else -> message ?: "An unexpected error occurred."
}
