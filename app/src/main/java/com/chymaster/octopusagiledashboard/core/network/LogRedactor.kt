package com.chymaster.octopusagiledashboard.core.network

/**
 * Redacts secrets from OkHttp's logging output.
 *
 * `HttpLoggingInterceptor` at `BODY` level prints request and response bodies
 * verbatim, which for this app means the Kraken token mutation
 * (`APIKey: "sk_live_…"`), the token response (access + refresh tokens) and any
 * `Basic`/`Bearer` credentials. Those must never reach logcat.
 *
 * [redact] is pure so it can be unit tested; the logging interceptor applies it
 * to every line it emits.
 */
object LogRedactor {

    const val PLACEHOLDER = "[REDACTED]"

    /** Each rule keeps the surrounding label so the log stays readable. */
    private class Rule(
        val pattern: Regex,
        val format: (MatchResult) -> String
    )

    private val RULES: List<Rule> = listOf(
        // Octopus API keys, e.g. the form shown in Settings ("sk_live_" + token)
        Rule(Regex("""sk_(?:live|test)_[A-Za-z0-9]+""")) { PLACEHOLDER },
        // Kraken mutation argument: APIKey: "sk_live_…"
        Rule(Regex("""(APIKey\s*:\s*")[^"]*(")""", RegexOption.IGNORE_CASE)) { m ->
            m.groupValues[1] + PLACEHOLDER + m.groupValues[2]
        },
        // GraphQL token fields: "token":"eyJ…", "refreshToken":"2da67…"
        Rule(Regex("""("(?:refresh)?token"\s*:\s*")[^"]*(")""", RegexOption.IGNORE_CASE)) { m ->
            m.groupValues[1] + PLACEHOLDER + m.groupValues[2]
        },
        // Bare JWTs (Bearer tokens, tokens pasted into bodies)
        Rule(Regex("""eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}""")) {
            PLACEHOLDER
        },
        // HTTP auth schemes: "Authorization: Basic abc…", "Bearer eyJ…"
        Rule(Regex("""\b(Basic|Bearer)\s+[A-Za-z0-9+/=._-]{8,}""")) { m ->
            m.groupValues[1] + " " + PLACEHOLDER
        }
    )

    /**
     * Returns [message] with every recognised credential replaced by
     * [PLACEHOLDER]. Non-secret content (URLs, rates, DSL structure) is kept so
     * the log stays useful.
     */
    fun redact(message: String): String {
        var result = message
        for (rule in RULES) {
            result = rule.pattern.replace(result, rule.format)
        }
        return result
    }
}
