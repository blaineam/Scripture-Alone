package com.blainemiller.scripturealone.ui.reader

import androidx.compose.ui.text.font.FontFamily
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.rights.QuotationRefusal
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Kind
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.userdata.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What selecting, highlighting and note markers need from a render: verse spans and markers. */
class ChapterMarksTest {

    private val style = ReaderStyle(palette = ReaderPalette.Light)
    private val fonts = ReaderFonts(body = FontFamily.Serif)

    private val layout = ChapterLayout(
        listOf(
            ChapterLayout.Block(Kind.TITLE, fragments = listOf(ChapterLayout.Fragment(0, false, "A Psalm of David."))),
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
                    ChapterLayout.Fragment(3, true, "In Him was life"),
                ),
            ),
        ),
    )

    @Test
    fun everyVerseFragmentHasASpanCoveringItsNumberAndText() {
        val paragraphs = ChapterRenderer(style, fonts).render(ChapterRef(43, 1), layout, "").paragraphs
        val spans = paragraphs.flatMap { p -> p.verseSpans.map { p.text.text.substring(it.start, it.end) to it.key } }
        // The number, words and footnote letter belong to the verse; the joining space does not.
        assertEquals(listOf(43_001_001, 43_001_002, 43_001_002, 43_001_003), spans.map { it.second })
        assertEquals("2\u202FHe was with God.", spans[1].first)
        assertTrue(spans[0].first.startsWith("1"))
        assertTrue(spans[0].first.contains("beginning"))
        assertEquals("continued", spans[2].first)
        // Verse 0 (a psalm title) is not selectable, as on iOS.
        assertTrue(paragraphs.none { p -> p.verseSpans.any { it.key % 1_000 == 0 } })
    }

    @Test
    fun aNoteMarkerFollowsTheVersesLastFragmentOnly() {
        val ids = listOf("a", "b")
        val rendered = ChapterRenderer(style, fonts).render(ChapterRef(43, 1), layout, "", notes = mapOf(43_001_002 to ids))
        val withMarker = rendered.paragraphs.filter { p ->
            p.text.getStringAnnotations(ChapterRenderer.NOTE_TAG, 0, p.text.length).isNotEmpty()
        }
        // Verse 2 runs on into the poetry line; the marker goes after "continued", not after "God."
        assertEquals(1, withMarker.size)
        val p = withMarker.single()
        val marker = p.text.getStringAnnotations(ChapterRenderer.NOTE_TAG, 0, p.text.length).single()
        assertEquals("a,b", marker.item)
        val span = p.verseSpans.first { it.key == 43_001_002 }
        assertEquals(span.end + 1, marker.start) // after a thin space, outside the verse's span
        assertEquals(1, p.text.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, p.text.length).size)
    }

    @Test
    fun highlightsRunTogetherAcrossTheSpaceBetweenSameColouredVerses() {
        val spans = listOf(VerseSpan(1, 0, 10), VerseSpan(2, 11, 20), VerseSpan(3, 21, 30), VerseSpan(4, 31, 40))
        val runs = highlightRuns(spans, mapOf(1 to "yellow", 2 to "yellow", 3 to "blue", 4 to "orange"))
        assertEquals(listOf(Triple(0, 20, HighlightColor.YELLOW), Triple(21, 30, HighlightColor.BLUE)), runs)
    }

    @Test
    fun quotationNoticeNamesTheLimit() {
        assertEquals(
            "ESV allows up to 500 verses in one quotation. Select fewer to copy or share.",
            quotationLimitNotice(QuotationRefusal.TooManyVerses(500), "ESV"),
        )
        assertEquals("ESV can’t be quoted outside the app.", quotationLimitNotice(QuotationRefusal.NotPermitted, "ESV"))
        assertEquals(
            "CSB can’t be quoted a whole book at a time. Select less of Jude to copy or share.",
            quotationLimitNotice(QuotationRefusal.WholeBook(BookID.JUDE), "CSB"),
        )
        assertEquals(
            "ESV allows up to 50% of Jude in one quotation. Select fewer verses to copy or share.",
            quotationLimitNotice(QuotationRefusal.TooMuchOfBook(BookID.JUDE, 50), "ESV"),
        )
    }
}
