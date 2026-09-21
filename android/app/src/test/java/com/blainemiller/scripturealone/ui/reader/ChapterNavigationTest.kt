package com.blainemiller.scripturealone.ui.reader

import androidx.compose.ui.text.font.FontFamily
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Kind
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the reader needs from a render to navigate: where each verse begins (scrolling to a verse,
 * saving the position) and each footnote letter's note (the popover).
 */
class ChapterNavigationTest {

    private val style = ReaderStyle(palette = ReaderPalette.Light)
    private val fonts = ReaderFonts(body = FontFamily.Serif)

    private val layout = ChapterLayout(
        listOf(
            ChapterLayout.Block(Kind.HEADING, text = "The Word Became Flesh"),
            ChapterLayout.Block(
                Kind.PARAGRAPH,
                fragments = listOf(
                    ChapterLayout.Fragment(1, true, "In the beginning", footnotes = listOf(ChapterLayout.Footnote(3, "Or at first"))),
                    ChapterLayout.Fragment(2, true, "He was with God."),
                ),
            ),
            ChapterLayout.Block(
                Kind.POETRY1,
                fragments = listOf(
                    ChapterLayout.Fragment(2, false, "continued"),
                    ChapterLayout.Fragment(4, true, "In Him was life", footnotes = listOf(ChapterLayout.Footnote(15, "Or through"))),
                ),
            ),
        ),
    )

    @Test
    fun paragraphsKnowWhereTheirVersesBegin() {
        val paragraphs = ChapterRenderer(style, fonts).render(ChapterRef(43, 1), layout, "").paragraphs
        val body = paragraphs.filter { it.verses.isNotEmpty() }
        assertEquals(listOf(listOf(43_001_001, 43_001_002), listOf(43_001_004)), body.map { p -> p.verses.map { it.key } })
        // Verse 2 begins after "1 In the beginning" and the joining space — at its own number.
        val first = body[0]
        assertEquals("2", first.text.text.substring(first.verses[1].offset, first.verses[1].offset + 1))
        // An unnumbered continuation is not a verse start; verse 4 begins after it.
        val second = body[1]
        assertEquals("4", second.text.text.substring(second.verses[0].offset, second.verses[0].offset + 1))
        // Headings and the header/footer carry none.
        assertEquals(0, paragraphs.first().verses.size)
    }

    @Test
    fun verseByVerseLinesEachStartAVerse() {
        val paragraphs = ChapterRenderer(style.copy(layout = ReadingLayout.VERSES), fonts)
            .render(ChapterRef(43, 1), layout, "").paragraphs
        val starts = paragraphs.flatMap { p -> p.verses.map { it.key to it.offset } }
        assertEquals(listOf(43_001_001 to 0, 43_001_002 to 0, 43_001_004 to 0), starts)
    }

    @Test
    fun locateFindsTheVerseOrTheNearestBeforeIt() {
        val rendered = ChapterRenderer(style, fonts).render(ChapterRef(43, 1), layout, "")
        val index = rendered.paragraphs.indexOfFirst { p -> p.verses.any { it.key == 43_001_004 } }
        // After the unnumbered "continued " that opens the line.
        assertEquals(index to "continued ".length, rendered.locate(43_001_004))
        // Verse 3 isn't printed (it's inside verse 2's fragments here): verse 2 is the nearest before it.
        val two = rendered.locate(43_001_002)
        assertEquals(two, rendered.locate(43_001_003))
        // A verse past the end lands on the last one; before the first, nothing.
        assertEquals(rendered.locate(43_001_004), rendered.locate(43_001_050))
        assertNull(rendered.locate(43_000_999))
    }

    @Test
    fun footnoteLettersCarryTheirNotes() {
        val paragraphs = ChapterRenderer(style, fonts).render(ChapterRef(43, 1), layout, "").paragraphs
        val notes = paragraphs.flatMap { p ->
            p.text.getStringAnnotations(ChapterRenderer.FOOTNOTE_TAG, 0, p.text.length).map { p.text.text.substring(it.start, it.end) to it.item }
        }
        assertEquals(listOf("a" to "Or at first", "b" to "Or through"), notes)
    }

    @Test
    fun hiddenFootnotesLeaveNothingToTap() {
        val paragraphs = ChapterRenderer(style.copy(footnotes = false), fonts).render(ChapterRef(43, 1), layout, "").paragraphs
        assertEquals(0, paragraphs.sumOf { it.text.getStringAnnotations(ChapterRenderer.FOOTNOTE_TAG, 0, it.text.length).size })
    }
}
