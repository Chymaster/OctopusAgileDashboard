package com.chymaster.octopusagiledashboard.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BarYRangeTest {

    private fun bar(
        value: Double,
        green: Double = 0.0,
        amber: Double = 0.0,
        red: Double = 0.0,
    ) = ChartBar(
        label = "",
        value = value,
        intervalStart = Instant.EPOCH,
        intervalEnd = Instant.EPOCH,
        greenSegment = green,
        amberSegment = amber,
        redSegment = red,
    )

    @Test
    fun `mixed sign zone bar fits both sides of zero`() {
        // green −0.50 (negative cheap-zone cost), amber +1.50 → net +1.00.
        val (yMin, yMax) = computeBarYRange(
            listOf(bar(value = 1.0, green = -0.5, amber = 1.5))
        )
        assertTrue("yMin $yMin must cover the −0.5 extent", yMin <= -0.5)
        assertTrue("yMax $yMax must cover the +1.5 extent", yMax >= 1.5)
        assertTrue("zero must be inside the range", yMin < 0.0 && yMax > 0.0)
    }

    @Test
    fun `all negative series keeps baseline visible`() {
        val (yMin, yMax) = computeBarYRange(
            listOf(
                bar(value = -1.0, green = -1.0),
                bar(value = -2.0, green = -2.0),
            )
        )
        assertTrue("yMax must be > 0 so the baseline is not pinned to the top, got $yMax", yMax > 0.0)
        assertTrue(yMin < 0.0)
    }

    @Test
    fun `all positive zone bars start at zero`() {
        val (yMin, yMax) = computeBarYRange(
            listOf(
                bar(value = 2.0, amber = 2.0),
                bar(value = 1.0, red = 1.0),
            )
        )
        assertEquals(0.0, yMin, 1e-9)
        assertEquals(2.0 * 1.1, yMax, 1e-9)
    }

    @Test
    fun `non-zone negative values keep zero inside`() {
        val (yMin, yMax) = computeBarYRange(
            listOf(
                bar(value = -5.0),
                bar(value = -1.0),
            )
        )
        assertEquals(-5.0 * 1.1, yMin, 1e-9)
        assertTrue("yMax must be > 0, got $yMax", yMax > 0.0)
    }

    @Test
    fun `empty bars return safe range`() {
        val (yMin, yMax) = computeBarYRange(emptyList())
        assertEquals(0.0, yMin, 1e-9)
        assertTrue(yMax > 0.0)
    }
}
