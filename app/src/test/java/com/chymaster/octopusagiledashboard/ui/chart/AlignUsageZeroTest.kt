package com.chymaster.octopusagiledashboard.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Test

class AlignUsageZeroTest {

    @Test
    fun `primary zero at the bottom keeps usage axis from zero`() {
        assertEquals(0.0, alignUsageZeroMin(zeroFraction = 0.0, usageYMax = 10.0), 1e-9)
    }

    @Test
    fun `primary zero centred puts usage zero centred too`() {
        assertEquals(-10.0, alignUsageZeroMin(zeroFraction = 0.5, usageYMax = 10.0), 1e-9)
    }

    @Test
    fun `primary zero a third up drops usage axis proportionally`() {
        assertEquals(-5.0, alignUsageZeroMin(zeroFraction = 1.0 / 3.0, usageYMax = 10.0), 1e-9)
    }

    @Test
    fun `primary zero at the top clamps usage axis fully negative`() {
        assertEquals(-10.0, alignUsageZeroMin(zeroFraction = 1.0, usageYMax = 10.0), 1e-9)
    }

    @Test
    fun `negative fraction is treated as bottom`() {
        assertEquals(0.0, alignUsageZeroMin(zeroFraction = -0.2, usageYMax = 10.0), 1e-9)
    }

    @Test
    fun `mapping holds for every reachable fraction`() {
        // f == 1.0 is unreachable in the real chart (yMax > 0 > yMin always,
        // so the primary zero fraction is strictly below 1); the clamp branch
        // is covered separately above.
        for (i in 0 until 20) {
            val f = i / 20.0
            val min = alignUsageZeroMin(zeroFraction = f, usageYMax = 10.0)
            val actualFraction = (0.0 - min) / (10.0 - min)
            assertEquals("fraction $f", f, actualFraction, 1e-9)
        }
    }
}
