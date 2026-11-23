package com.chymaster.octopusagiledashboard.data.mapper

import com.chymaster.octopusagiledashboard.data.remote.dto.AgileRateDto
import com.chymaster.octopusagiledashboard.domain.tariff.OPEN_ENDED_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AgilePriceMapperTest {

    @Test
    fun `a missing valid_to becomes an open ended rate`() {
        // Flexible/quarterly publishes the period in force without an end.
        // Inventing a 30-minute end hid the rate from every later slot.
        val dto = AgileRateDto(
            valueExcVat = 26.5956,
            valueIncVat = 26.5956,
            validFrom = "2026-09-30T23:00:00Z",
            validTo = null
        )
        assertEquals(OPEN_ENDED_MILLIS, dto.toEntity("E-1R-VAR-22-11-01-C").validTo)
        assertEquals(
            Instant.ofEpochMilli(OPEN_ENDED_MILLIS),
            dto.toDomain("E-1R-VAR-22-11-01-C").validTo
        )
    }

    @Test
    fun `a published valid_to is kept verbatim`() {
        val dto = AgileRateDto(
            valueExcVat = 43.194,
            valueIncVat = 43.194,
            validFrom = "2026-11-23T16:00:00Z",
            validTo = "2026-11-23T19:00:00Z"
        )
        assertEquals(
            Instant.parse("2026-11-23T19:00:00Z").toEpochMilli(),
            dto.toEntity("E-1R-COSY-22-12-08-C").validTo
        )
        assertEquals(
            Instant.parse("2026-11-23T16:00:00Z"),
            dto.toEntity("E-1R-COSY-22-12-08-C").let { Instant.ofEpochMilli(it.validFrom) }
        )
    }
}
