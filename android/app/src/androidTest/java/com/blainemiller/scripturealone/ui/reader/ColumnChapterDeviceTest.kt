package com.blainemiller.scripturealone.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Block
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Footnote
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Fragment
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Kind
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The column reader on a device: a paragraph is measured for pagination exactly as it is then drawn
 * (or a line could be cut at a column's foot), and the spreads turn, report the verse at their head,
 * hand over to the next chapter past the last, and show TalkBack only the verses in view.
 */
@RunWith(AndroidJUnit4::class)
class ColumnChapterDeviceTest {

    @get:Rule val rule = createComposeRule()

    private val style = ReaderStyle(palette = ReaderPalette.Light)
    private val words = "Blessed be the God and Father of our Lord, who has blessed us in the heavenly realms with every spiritual blessing."

    /** A chapter of [verses] verses, four to a paragraph, a heading every twelve, a footnote and a note on verse 2. */
    private fun chapter(verses: Int): RenderedChapter {
        val blocks = (1..verses).chunked(4).flatMapIndexed { i, chunk ->
            listOfNotNull(
                if (i % 3 == 0) Block(Kind.HEADING, text = "Section ${i / 3 + 1}") else null,
                Block(
                    Kind.PARAGRAPH,
                    fragments = chunk.map { v ->
                        Fragment(v, true, "$words ($v)", footnotes = if (v == 2) listOf(Footnote(5, "Or praised")) else emptyList())
                    },
                ),
            )
        }
        return ChapterRenderer(style, ReaderFonts(body = FontFamily.Serif))
            .render(ChapterRef(49, 1), ChapterLayout(blocks), "Public domain.", notes = mapOf(49_001_002 to listOf("n1")), compactHeader = true)
    }

    @Test
    fun aParagraphIsMeasuredExactlyAsItIsDrawn() {
        val rendered = chapter(8)
        val paragraph = rendered.paragraphs.first { p -> p.verseSpans.any { it.key == 49_001_002 } }
        var drawn: TextLayoutResult? = null
        var measured: TextLayoutResult? = null
        rule.setContent {
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val measurer = rememberTextMeasurer()
            val width = with(density) { 330.dp.roundToPx() }
            measured = remember { measureParagraph(measurer, paragraph, style.palette, style.size, width, density, direction) }
            Box(Modifier.width(330.dp)) {
                Paragraph(
                    paragraph, style.palette, onAction = {}, onLayout = { drawn = it }, marks = VerseMarks(), markerSize = style.size,
                    onVerseTap = {}, onVerseLongPress = {}, notesFor = { emptyList() }, onOpenNote = {},
                )
            }
        }
        rule.waitForIdle()
        val a = drawn!!
        val b = measured!!
        assertTrue("the paragraph should wrap", a.lineCount > 2)
        assertEquals(a.lineCount, b.lineCount)
        for (line in 0 until a.lineCount) {
            assertEquals(a.getLineStart(line), b.getLineStart(line))
            assertEquals(a.getLineBottom(line), b.getLineBottom(line), 0.01f)
        }
    }

    /** Lets a turn's animation run to its end. */
    private fun turned() {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    @Test
    fun spreadsTurnReportTheirHeadAndHandOverToTheNextChapter() {
        val rendered = chapter(40)
        val spreads = Spreads()
        val tops = mutableListOf<Int>()
        val swipes = mutableListOf<Boolean>()
        rule.setContent {
            // A phone held sideways: 914 × 411 dp, whatever way the test device is held.
            Box(Modifier.requiredSize(914.dp, 411.dp)) {
                ColumnChapter(
                    rendered, columns = 2, compactHeight = true, spreads = spreads,
                    marks = VerseMarks(), markerSize = style.size,
                    onVerseTap = {}, onVerseLongPress = {}, notesFor = { emptyList() }, onOpenNote = {},
                    palette = style.palette, scrollTarget = null, onScrolledToTarget = {},
                    onTopVerse = { tops += it }, onSwipe = { swipes += it }, onAction = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(spreads.active)
        assertTrue("40 verses need several spreads, got ${spreads.count}", spreads.count > 2)
        assertEquals(0, spreads.page)
        // A verse that runs over a column's foot has a node in each column, over its lines there.
        rule.onAllNodesWithContentDescription("Verse 1. $words (1)", substring = true).onFirst().assertIsDisplayed()

        rule.runOnIdle { spreads.turn(forward = true) }
        turned()
        assertEquals(1, spreads.page)
        assertTrue("the second spread reports a later verse: $tops", tops.last() > 49_001_001)
        // Verse 1 is no longer in view, so TalkBack has no node for it.
        val left = rule.onAllNodesWithContentDescription("Verse 1. $words (1)", substring = true).fetchSemanticsNodes()
        assertEquals("verse 1 still has nodes: ${left.map { it.boundsInRoot }}", 0, left.size)

        rule.runOnIdle { spreads.turn(forward = false) }
        turned()
        assertEquals(0, spreads.page)
        rule.runOnIdle { spreads.turn(forward = false) }
        turned()
        assertEquals("before the first spread: the previous chapter", listOf(false), swipes)

        var turns = 0
        while (spreads.page < spreads.count - 1 && turns++ < 50) {
            rule.runOnIdle { spreads.turn(forward = true) }
            turned()
        }
        assertEquals("no chapter change on the way to the last spread", listOf(false), swipes)
        assertEquals(spreads.count - 1, spreads.page)
        rule.runOnIdle { spreads.turn(forward = true) }
        turned()
        assertEquals("past the last spread: the next chapter", listOf(false, true), swipes)
    }

    @Test
    fun aVerseToShowOpensItsSpread() {
        val rendered = chapter(40)
        val spreads = Spreads()
        var consumed = false
        rule.setContent {
            Box(Modifier.requiredSize(914.dp, 411.dp)) {
                ColumnChapter(
                    rendered, columns = 2, compactHeight = true, spreads = spreads,
                    marks = VerseMarks(), markerSize = style.size,
                    onVerseTap = {}, onVerseLongPress = {}, notesFor = { emptyList() }, onOpenNote = {},
                    palette = style.palette, scrollTarget = 49_001_036, onScrolledToTarget = { consumed = true },
                    onTopVerse = {}, onSwipe = {}, onAction = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(consumed)
        assertTrue("verse 36 is past the first spread", spreads.page > 0)
        rule.onAllNodesWithContentDescription("Verse 36. $words (36)", substring = true).onFirst().assertExists()
    }
}
