package com.chymaster.octopusagiledashboard.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TariffCodesTest {

    @Test
    fun `parses a standard agile tariff code`() {
        val parsed = TariffCodes.parse("E-1R-AGILE-24-10-01-L")
        assertEquals("AGILE-24-10-01", parsed?.productCode)
        assertEquals("L", parsed?.gsp)
        assertFalse(parsed!!.isExport)
    }

    @Test
    fun `parses product codes that contain hyphens`() {
        val parsed = TariffCodes.parse("E-1R-AGILE-OUTGOING-19-05-13-A")
        assertEquals("AGILE-OUTGOING-19-05-13", parsed?.productCode)
        assertEquals("A", parsed?.gsp)
    }

    @Test
    fun `parses a fixed historical product code`() {
        val parsed = TariffCodes.parse("E-1R-OE-FIX-12M-25-09-09-L")
        assertEquals("OE-FIX-12M-25-09-09", parsed?.productCode)
    }

    @Test
    fun `recognises export tariffs`() {
        val parsed = TariffCodes.parse("E-1E-OUTGOING-19-05-13-L")
        assertTrue(parsed!!.isExport)
    }

    @Test
    fun `parses multi-register tariffs`() {
        val parsed = TariffCodes.parse("E-2R-VAR-22-11-01-L")
        assertEquals("VAR-22-11-01", parsed?.productCode)
    }

    @Test
    fun `is case insensitive`() {
        assertEquals("COSY-22-12-08", TariffCodes.parse("e-1r-cosy-22-12-08-l")?.productCode)
    }

    @Test
    fun `rejects malformed codes`() {
        assertNull(TariffCodes.parse(""))
        assertNull(TariffCodes.parse("AGILE-24-10-01"))
        assertNull(TariffCodes.parse("E-1R--L"))
        assertNull(TariffCodes.parse("X-1R-AGILE-24-10-01-L"))
        assertNull(TariffCodes.parse("E-1R-AGILE-24-10-01"))
    }

    @Test
    fun `build strips the underscore from the gsp`() {
        assertEquals("E-1R-COSY-22-12-08-L", TariffCodes.build("COSY-22-12-08", "_L"))
        assertEquals("E-1R-COSY-22-12-08-L", TariffCodes.build("COSY-22-12-08", "L"))
    }

    @Test
    fun `build round trips through parse`() {
        val code = TariffCodes.build("AGILE-24-10-01", "_A")
        val parsed = TariffCodes.parse(code)
        assertEquals("AGILE-24-10-01", parsed?.productCode)
        assertEquals("A", parsed?.gsp)
    }

    @Test
    fun `productCodeOf falls back to the raw code`() {
        assertEquals("AGILE-24-10-01", TariffCodes.productCodeOf("E-1R-AGILE-24-10-01-L"))
        assertEquals("garbage", TariffCodes.productCodeOf("garbage"))
    }

    @Test
    fun `gsp helpers normalise both directions`() {
        assertEquals("L", TariffCodes.normalizeGsp("_L"))
        assertEquals("_L", TariffCodes.withUnderscoreGsp("L"))
        assertEquals("_L", TariffCodes.withUnderscoreGsp("_L"))
    }
}
