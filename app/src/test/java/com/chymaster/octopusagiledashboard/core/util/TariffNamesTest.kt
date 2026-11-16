package com.chymaster.octopusagiledashboard.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TariffNamesTest {

    @Test
    fun `uses the curated display name when known`() {
        assertEquals("Agile Octopus", TariffNames.displayName("AGILE-24-10-01"))
        assertEquals("Cosy Octopus", TariffNames.displayName("COSY-22-12-08"))
        assertEquals("Flexible Octopus", TariffNames.displayName("VAR-22-11-01"))
    }

    @Test
    fun `uses historical names when known`() {
        assertEquals(
            "Octopus 12M Fixed (Sep 2025)",
            TariffNames.displayName("OE-FIX-12M-25-09-09")
        )
    }

    @Test
    fun `prettifies unknown product codes`() {
        assertEquals("Agile Outgoing 19 05 13", TariffNames.displayName("AGILE-OUTGOING-19-05-13"))
    }

    @Test
    fun `joins distinct tariffs in order`() {
        assertEquals(
            "Agile Octopus → Cosy Octopus",
            TariffNames.joinedDisplayName(listOf("AGILE-24-10-01", "COSY-22-12-08"))
        )
    }

    @Test
    fun `joins a single tariff to its own name`() {
        assertEquals("Agile Octopus", TariffNames.joinedDisplayName(listOf("AGILE-24-10-01")))
    }

    @Test
    fun `blank input yields blanks`() {
        assertEquals("", TariffNames.displayName(""))
        assertEquals("", TariffNames.joinedDisplayName(emptyList()))
    }
}
