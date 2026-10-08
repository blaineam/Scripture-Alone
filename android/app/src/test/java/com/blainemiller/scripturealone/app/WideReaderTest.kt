package com.blainemiller.scripturealone.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The two-column reader (1.1.4) on a tablet held sideways and on a phone held sideways: the chapter
 * set in spreads, the bottom bar's arrows labelled Previous/Next Page while there are spreads to turn,
 * and changing the chapter only past the last one — then, in the scrolling reader (Columns on Wide
 * Screens off), the arrows change chapters again.
 */
@RunWith(AndroidJUnit4::class)
class WideReaderTest : AppTest() {
    @get:Rule override val rule = createEmptyComposeRule()

    private fun columns() = rule.onAllNodesWithTag("reader.columns").fetchSemanticsNodes().isNotEmpty()

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun aTabletReadsInSpreadsAndTheArrowsTurnThem() {
        launch()
        assertTrue("a wide window sets the chapter in columns", columns())
        assertShown("the arrows turn pages", desc("Next Page", substring = false))
        assertFalse("on the first spread there is no previous page", desc("Previous Page", substring = false).exists())
        assertShown("on the first spread, back is the previous chapter", desc("Previous Chapter", substring = false))

        // Turn to the last spread: the next arrow then names the chapter.
        var turns = 0
        while (desc("Next Page", substring = false).exists() && turns < 20) {
            tapDesc("Next Page", substring = false)
            settle(1_000)
            turns++
        }
        assertTrue("John 1 runs to more than one spread", turns > 0)
        assertShown("past the first spread, a previous page", desc("Previous Page", substring = false))
        assertShown("on the last spread the arrow is the next chapter", desc("Next Chapter", substring = false))
        assertShown("still John 1", desc("currently John 1"))

        tapDesc("Next Chapter", substring = false)
        settle(1_000)
        assertShown("John 2", desc("currently John 2"))
        assertShown("John 2 opens at its first spread", desc("Next Page", substring = false))
        assertFalse(desc("Previous Page", substring = false).exists())
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun aPhoneHeldSidewaysReadsInColumns() {
        launch()
        assertTrue("a phone on its side sets the chapter in columns", columns())
        assertShown("John 1:1", desc("Verse 1. In the beginning was the Word"))
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun columnsOffTheTabletScrollsAndTheArrowsChangeChapters() {
        launch()
        assertTrue(columns())
        tapDesc("Appearance", substring = false)
        tapText("Columns on Wide Screens")
        back()
        assertFalse("the scrolling reader", columns())
        assertFalse(desc("Next Page", substring = false).exists())
        tapDesc("Next Chapter", substring = false)
        assertShown("John 2", desc("currently John 2"))
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun aPhoneUprightScrolls() {
        launch()
        assertFalse("a phone held upright reads in one scrolling column", columns())
        assertShown("the arrows change chapters", desc("Next Chapter", substring = false))
    }
}
