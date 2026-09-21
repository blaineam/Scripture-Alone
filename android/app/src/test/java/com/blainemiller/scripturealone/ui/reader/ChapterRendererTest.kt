package com.blainemiller.scripturealone.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Kind
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The renderer's text decisions — which characters end up red, italic, small-capped, where footnote
 * letters go — checked on the AnnotatedString itself, since that is what the offsets finally index.
 */
class ChapterRendererTest {

    private val style = ReaderStyle(palette = ReaderPalette.Dark)
    private val renderer = ChapterRenderer(style, ReaderFonts(body = FontFamily.Serif))

    /** The characters of [text] whose colour is [color]. */
    private fun AnnotatedString.charsColored(color: androidx.compose.ui.graphics.Color): String =
        spanStyles.filter { it.item.color == color }.sortedBy { it.start }.joinToString("") { text.substring(it.start, it.end) }

    @Test
    fun redSpanLandsOnTheRightWordsAfterANonBmpCharacter() {
        // Scalars: 𝔸(0) ␠(1) s(2)a(3)i(4)d(5),(6) ␠(7) “(8)F(9)o(10)l(11)l(12)o(13)w(14) m(16)e(17).”
        val text = "𝔸 said, “Follow me.”"
        val fragment = ChapterLayout.Fragment(
            verse = 19, numbered = false, text = text,
            spans = listOf(ChapterLayout.Span(start = 8, length = 12, style = ChapterLayout.Span.Style.WORDS_OF_CHRIST)),
        )
        val out = renderer.fragment(fragment, Kind.PARAGRAPH)
        assertEquals(text, out.text)
        assertEquals("“Follow me.”", out.charsColored(ReaderPalette.Dark.red))
        // Read as UTF-16, the same span would have started at "," — one unit early.
        assertFalse(out.charsColored(ReaderPalette.Dark.red).startsWith(","))
    }

    @Test
    fun redLettersOffLeavesEverythingInk() {
        val plain = ChapterRenderer(style.copy(redLetters = false), ReaderFonts(body = FontFamily.Serif))
        val fragment = ChapterLayout.Fragment(1, false, "Follow me.", listOf(ChapterLayout.Span(0, 10, ChapterLayout.Span.Style.WORDS_OF_CHRIST)))
        assertEquals("", plain.fragment(fragment, Kind.PARAGRAPH).charsColored(ReaderPalette.Dark.red))
    }

    @Test
    fun suppliedWordsAreItalicAtScalarOffsets() {
        val fragment = ChapterLayout.Fragment(
            4, false, "🙏 These are the generations",
            listOf(ChapterLayout.Span(8, 3, ChapterLayout.Span.Style.SUPPLIED)),
        )
        val out = renderer.fragment(fragment, Kind.PARAGRAPH)
        val italic = out.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }.joinToString("") { out.text.substring(it.start, it.end) }
        assertEquals("are", italic)
    }

    @Test
    fun theDivineNameBecomesSmallCapsButGodAsAWordDoesNot() {
        val fragment = ChapterLayout.Fragment(1, false, "The LORD God, the LORD’S house; GODLY")
        val out = renderer.fragment(fragment, Kind.PARAGRAPH)
        // "LORD" → "L" + small-cap "ord"; "GODLY" is not the divine name.
        assertEquals("The Lord God, the Lord’S house; GODLY", out.text)
        val smallCaps = out.spanStyles.filter { it.item.fontFeatureSettings == "smcp" }.map { out.text.substring(it.start, it.end) }
        assertEquals(listOf("ord", "ord"), smallCaps)
    }

    @Test
    fun theDivineNameInItalicUsesReducedCapitalsBecauseTheItalicHasNoSmallCaps() {
        val fragment = ChapterLayout.Fragment(1, false, "A prayer to the LORD.")
        val out = renderer.fragment(fragment, Kind.TITLE)
        assertEquals("A prayer to the LORD.", out.text)
        val reduced = out.spanStyles.filter { it.item.fontSize.value < style.size }.map { out.text.substring(it.start, it.end) }
        assertEquals(listOf("ORD"), reduced)
    }

    @Test
    fun verseNumberAndFootnoteLettersAreInsertedWithoutDisturbingSpans() {
        // Footnote after 𝔸 (scalar 1 → UTF-16 2) and at the end; the red span covers "b c".
        val fragment = ChapterLayout.Fragment(
            16, true, "𝔸 b c",
            spans = listOf(ChapterLayout.Span(2, 3, ChapterLayout.Span.Style.WORDS_OF_CHRIST)),
            footnotes = listOf(ChapterLayout.Footnote(1, "one"), ChapterLayout.Footnote(5, "two")),
        )
        val out = renderer.fragment(fragment, Kind.PARAGRAPH)
        assertEquals("16\u202F𝔸a b cb", out.text)
        assertEquals("b c", out.charsColored(ReaderPalette.Dark.red))
        val secondary = out.charsColored(ReaderPalette.Dark.secondary)
        assertEquals("ab", secondary)
        assertEquals("16\u202F", out.charsColored(ReaderPalette.Dark.accent))
    }

    @Test
    fun paragraphKindsCarryTheSwiftIndentsAndSpacing() {
        val layout = ChapterLayout(
            listOf(
                ChapterLayout.Block(Kind.HEADING, "The Seventh Day"),
                ChapterLayout.Block(Kind.PARAGRAPH, fragments = listOf(ChapterLayout.Fragment(1, true, "And the heavens"))),
                ChapterLayout.Block(Kind.STANZA_BREAK),
                ChapterLayout.Block(Kind.POETRY1, fragments = listOf(ChapterLayout.Fragment(2, true, "Line one"))),
                ChapterLayout.Block(Kind.POETRY2, fragments = listOf(ChapterLayout.Fragment(2, false, "line two"))),
                ChapterLayout.Block(Kind.SELAH, fragments = listOf(ChapterLayout.Fragment(2, false, "Selah"))),
            ),
        )
        val paragraphs = renderer.render(ChapterRef(1, 2), layout, "Public domain").paragraphs
        val s = style.size
        // Header: book caption, chapter number.
        assertEquals("GENESIS", paragraphs[0].text.text)
        assertEquals("2", paragraphs[1].text.text)
        assertEquals(s * 1.1f, paragraphs[1].spaceAfter, 0.001f)
        val heading = paragraphs[2]
        assertEquals(s * 1.1f, heading.spaceBefore, 0.001f)
        assertEquals(s * 0.35f, heading.spaceAfter, 0.001f)
        val prose = paragraphs[3]
        assertEquals(s * 1.25f, prose.firstLineIndent, 0.001f)
        assertEquals(s * ChapterRenderer.NATURAL_LINE_HEIGHT * 1.35f, prose.lineHeight, 0.001f)
        val q1 = paragraphs[4]
        assertEquals("the stanza break adds space before", s * 0.7f, q1.spaceBefore, 0.001f)
        assertEquals(s * 1.25f, q1.firstLineIndent, 0.001f)
        assertEquals(s * 1.25f * 2.5f, q1.restLineIndent, 0.001f)
        assertEquals(s * 0.12f, q1.spaceAfter, 0.001f)
        val q2 = paragraphs[5]
        assertEquals(0f, q2.spaceBefore, 0.001f)
        assertEquals(s * 1.25f * 2.5f, q2.firstLineIndent, 0.001f)
        assertEquals(s * 1.25f * 3.5f, q2.restLineIndent, 0.001f)
        assertEquals(TextAlign.End, paragraphs[6].align)
        // Footer: next chapter, copyright.
        assertEquals("Genesis 3  →", paragraphs[7].text.text)
        assertEquals(ReaderAction.NEXT_CHAPTER, paragraphs[7].action)
        assertEquals("Public domain", paragraphs.last().text.text)
    }

    @Test
    fun verseByVerseStartsALineAtEachNumberedFragment() {
        val layout = ChapterLayout(
            listOf(
                ChapterLayout.Block(Kind.POETRY1, fragments = listOf(ChapterLayout.Fragment(1, true, "The LORD is my shepherd;"))),
                ChapterLayout.Block(Kind.POETRY2, fragments = listOf(ChapterLayout.Fragment(1, false, "I shall not want."))),
                ChapterLayout.Block(Kind.POETRY1, fragments = listOf(ChapterLayout.Fragment(2, true, "He makes me lie down"))),
            ),
        )
        val verses = ChapterRenderer(style.copy(layout = ReadingLayout.VERSES), ReaderFonts(body = FontFamily.Serif))
            .render(ChapterRef(19, 23), layout, "").paragraphs
            .drop(2).dropLast(2) // header, footer
        assertEquals(listOf("1\u202FThe Lord is my shepherd; I shall not want.", "2\u202FHe makes me lie down"), verses.map { it.text.text })
        assertEquals(style.size * 1.5f, verses[0].restLineIndent, 0.001f)
    }

    @Test
    fun singleChapterBooksShowNoChapterNumber() {
        val out = renderer.render(ChapterRef(65, 1), ChapterLayout(emptyList()), "")
        assertEquals("JUDE", out.paragraphs[0].text.text)
        assertEquals("", out.paragraphs[1].text.text)
        assertEquals("Revelation 1  →", out.paragraphs[2].text.text)
    }

    @Test
    fun footnoteLettersRunInReadingOrderAcrossTheChapter() {
        val layout = ChapterLayout(
            listOf(
                ChapterLayout.Block(
                    Kind.PARAGRAPH,
                    fragments = listOf(
                        ChapterLayout.Fragment(1, false, "ab", footnotes = listOf(ChapterLayout.Footnote(1, "x"), ChapterLayout.Footnote(2, "y"))),
                        ChapterLayout.Fragment(2, false, "cd", footnotes = listOf(ChapterLayout.Footnote(0, "z"))),
                    ),
                ),
            ),
        )
        val body = renderer.render(ChapterRef(1, 1), layout, "").paragraphs[2].text.text
        assertEquals("aabb ccd", body)
    }
}
