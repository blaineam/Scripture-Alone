package com.blainemiller.scripturealone.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * A round screen's framing (Play's Wear OS quality check, "text or controls are cut off by the screen
 * edges"): wherever running text can be seen, the circle is at least as wide as the line.
 */
class RoundFrameTest {
    /** The circle's width, as a fraction of the diameter, at [y] from the top. */
    private fun chord(y: Float): Float = 2 * sqrt(0.25f - (0.5f - y) * (0.5f - y))

    @Test fun aLineBelowTheCoveredBandFitsTheCircle() {
        val line = 1 - 2 * RoundFrame.TEXT_INSET
        val band = RoundFrame.cutBand()
        assertEquals("the band ends exactly where the line fits", line, chord(band), 0.001f)
        var y = band
        while (y <= 1 - band) {
            assertTrue("a line at $y fits", chord(y) >= line - 0.001f)
            y += 0.01f
        }
    }

    @Test fun theListStartsAndEndsClearOfTheFade() {
        assertTrue(RoundFrame.clear() > RoundFrame.cutBand())
        // Most of the screen is still for reading.
        assertTrue(1 - 2 * RoundFrame.clear() > 0.55f)
        assertTrue(1 - 2 * RoundFrame.TEXT_INSET > 0.7f)
    }

    @Test fun aWiderMarginNeedsANarrowerBand() {
        assertTrue(RoundFrame.cutBand(0.2f) < RoundFrame.cutBand(0.1f))
        assertEquals(0.5f, RoundFrame.cutBand(0f), 0.001f)
    }

    @Test fun theChapterButtonsStayInsideTheTextMarginOnARoundScreen() {
        for (width in listOf(192, 204, 227)) {
            val grid = RoundFrame.chapterGrid(round = true, screenWidthDp = width)
            val row = grid.button.value * grid.columns + RoundFrame.GridGap.value * (grid.columns - 1)
            assertTrue("$width dp: the row fits", row <= width * (1 - 2 * RoundFrame.TEXT_INSET) + 0.01f)
            assertTrue("$width dp: still a finger's width", grid.button.value >= 40f)
        }
        assertEquals(4, RoundFrame.chapterGrid(round = false, screenWidthDp = 180).columns)
    }
}
