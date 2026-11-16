package com.chymaster.octopusagiledashboard.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {

    /**
     * Synthetic credentials, assembled at runtime so that no API-key-shaped
     * literal lives in the repository (secret scanners, including GitHub's push
     * protection, match on the literal pattern regardless of intent).
     */
    private val fakeKey = "sk_" + "live_" + "abc123DEF456ghi789"

    @Test
    fun `redacts the API key in the kraken token mutation`() {
        val body =
            """{"query":"mutation {\n    obtainKrakenToken(input: { APIKey: "$fakeKey" }) {\n        token\n    }\n}"}"""
        val redacted = LogRedactor.redact(body)

        assertFalse(redacted.contains(fakeKey))
        assertTrue(redacted.contains("obtainKrakenToken"))
    }

    @Test
    fun `redacts access and refresh tokens in the token response`() {
        val jwt = "eyJhbGciOiJSUzI1NiIsImtpZCI6InFRb3hIbzhiTF92WiJ9.eyJzdWIiOiJrcmFrZW4ifQ.c2lnbmF0dXJl"
        val refresh = "2da67005a2d692e050684e8f7fc44a366f617e673fca77ada71a1123222fc614"
        val body =
            """{"data":{"obtainKrakenToken":{"token":"$jwt","refreshToken":"$refresh"}}}"""
        val redacted = LogRedactor.redact(body)

        assertFalse(redacted.contains(jwt))
        assertFalse(redacted.contains(refresh))
        assertTrue(redacted.contains("obtainKrakenToken"))
        assertTrue(redacted.contains(LogRedactor.PLACEHOLDER))
    }

    @Test
    fun `redacts basic auth credentials`() {
        val redacted = LogRedactor.redact("Authorization: Basic c2tfbGl2ZV9hYmNkZWZnaGlqayE=")
        assertFalse(redacted.contains("c2tfbGl2ZV9hYmNkZWZnaGlqayE="))
        assertTrue(redacted.contains("Basic [REDACTED]"))
    }

    @Test
    fun `redacts bearer tokens`() {
        val redacted = LogRedactor.redact("Authorization: Bearer abcdefghijklmnop123")
        assertFalse(redacted.contains("abcdefghijklmnop123"))
        assertTrue(redacted.contains("Bearer [REDACTED]"))
    }

    @Test
    fun `redacts a bare api key`() {
        val redacted = LogRedactor.redact("Retrying with $fakeKey now")
        assertFalse(redacted.contains(fakeKey))
        assertTrue(redacted.contains("Retrying with [REDACTED] now"))
    }

    @Test
    fun `redacts a bare jwt`() {
        val redacted = LogRedactor.redact("token eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.abcDEF123")
        assertFalse(redacted.contains("eyJhbGciOiJSUzI1NiJ9"))
    }

    @Test
    fun `leaves non-secret traffic readable`() {
        val line =
            """<-- 200 https://api.octopus.energy/v1/products/AGILE-24-10-01/electricity-tariffs/E-1R-AGILE-24-10-01-C/standard-unit-rates/ (77ms)"""
        assertEquals(line, LogRedactor.redact(line))

        val rate = """{"value_inc_vat":34.9,"valid_from":"2026-11-15T11:30:00Z"}"""
        assertEquals(rate, LogRedactor.redact(rate))
    }

    @Test
    fun `is idempotent`() {
        val once = LogRedactor.redact("APIKey: \"$fakeKey\"")
        assertEquals(once, LogRedactor.redact(once))
    }
}
