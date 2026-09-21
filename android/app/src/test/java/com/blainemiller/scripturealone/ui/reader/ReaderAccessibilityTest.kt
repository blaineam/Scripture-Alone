package com.blainemiller.scripturealone.ui.reader

import androidx.compose.ui.text.font.FontFamily
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Block
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Footnote
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Fragment
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Kind
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What TalkBack reads for a rendered chapter: headings, and verses one node each. */
class ReaderAccessibilityTest {

    private val john3 = ChapterRef(43, 3)
    private fun key(verse: Int) = 43_003_000 + verse

    private val layout = ChapterLayout(
        listOf(
            Block(Kind.HEADING, text = "Jesus and Nicodemus"),
            Block(Kind.PARALLEL, text = "(John 7:50–52)"),
            Block(
                Kind.PARAGRAPH,
                fragments = listOf(
                    Fragment(1, true, "Now there was a man of the Pharisees, named Nicodemus.", footnotes = listOf(Footnote(7, "Or ruler"))),
                    Fragment(2, true, "The same came unto him by night."),
                ),
            ),
            Block(Kind.POETRY1, fragments = listOf(Fragment(2, false, "and said unto him, Rabbi"))),
            Block(Kind.MAJOR_SECTION, text = "Book Two"),
        ),
    )

    private fun render(style: ReaderStyle = ReaderStyle(palette = ReaderPalette.Light), notes: Map<Int, List<String>> = emptyMap()) =
        ChapterRenderer(style, ReaderFonts(body = FontFamily.Serif)).render(john3, layout, "Public domain.", notes)

    @Test
    fun theChapterTitleIsOneHeadingAndTheNumeralIsNotReadSeparately() {
        val paragraphs = render().paragraphs
        assertEquals(ParagraphRole.HEADING, paragraphs[0].role)
        assertEquals("John 3", paragraphs[0].accessibilityLabel)
        assertEquals(ParagraphRole.HIDDEN, paragraphs[1].role)
    }

    @Test
    fun sectionHeadingsAreHeadingsButAParallelLineIsNot() {
        val paragraphs = render().paragraphs
        val heading = paragraphs.first { it.text.text == "Jesus and Nicodemus" }
        assertEquals(ParagraphRole.HEADING, heading.role)
        assertEquals(ParagraphRole.TEXT, paragraphs.first { it.text.text == "(John 7:50–52)" }.role)
        // A major section is printed in capitals and read as written.
        val major = paragraphs.first { it.text.text == "BOOK TWO" }
        assertEquals(ParagraphRole.HEADING, major.role)
        assertEquals("Book Two", major.accessibilityLabel)
    }

    @Test
    fun eachVerseReadsAsItsNumberAndWordsWithoutFootnoteLetters() {
        val prose = render().paragraphs.first { it.verseSpans.size == 2 }
        val runs = ReaderAccessibility.runs(prose)
        assertEquals(listOf(key(1), key(2)), runs.map { it.key })
        assertEquals("Verse 1. Now there was a man of the Pharisees, named Nicodemus.", ReaderAccessibility.label(runs[0]))
        assertEquals("Verse 2. The same came unto him by night.", ReaderAccessibility.label(runs[1]))
        assertEquals(listOf("a"), runs[0].footnotes.map { it.letter })
        assertEquals("Or ruler", runs[0].footnotes.single().text)
        assertEquals("a", prose.text.text.substring(runs[0].footnotes.single().offset, runs[0].footnotes.single().offset + 1))
    }

    @Test
    fun aVerseContinuedOnAPoetryLineReadsAsItsWords() {
        val line = render().paragraphs.first { it.text.text.startsWith("and said") }
        val run = ReaderAccessibility.runs(line).single()
        assertEquals(key(2), run.key)
        assertEquals("and said unto him, Rabbi", ReaderAccessibility.label(run))
    }

    @Test
    fun withVerseNumbersOffTheLabelStillSaysTheVerse() {
        val prose = render(ReaderStyle(verseNumbers = false, footnotes = false, palette = ReaderPalette.Light)).paragraphs.first { it.verseSpans.size == 2 }
        val runs = ReaderAccessibility.runs(prose)
        assertEquals("Verse 1. Now there was a man of the Pharisees, named Nicodemus.", ReaderAccessibility.label(runs[0]))
        assertEquals(emptyList<ReaderAccessibility.Footnote>(), runs[0].footnotes)
    }

    @Test
    fun theNoteMarkerIsAnActionOnTheVerseItFollows() {
        val rendered = render(notes = mapOf(key(2) to listOf("n1", "n2")))
        val prose = ReaderAccessibility.runs(rendered.paragraphs.first { it.verseSpans.size == 2 })
        // Verse 2's marker follows its last fragment, the poetry line — not the prose paragraph.
        assertEquals(emptyList<String>(), prose[1].noteIds)
        val line = ReaderAccessibility.runs(rendered.paragraphs.first { it.text.text.startsWith("and said") }).single()
        assertEquals(listOf("n1", "n2"), line.noteIds)
        assertEquals("and said unto him, Rabbi", line.text)
    }

    @Test
    fun stateSaysTheHighlightTheNoteAndListen() {
        val marks = VerseMarks(highlights = mapOf(key(1) to "green"), speaking = key(1))
        assertEquals("Highlighted green, Has a note, Being read aloud", ReaderAccessibility.state(key(1), marks, hasNote = true))
        assertNull(ReaderAccessibility.state(key(2), marks, hasNote = false))
    }

    @Test
    fun theNextChapterLinkSaysWhereItGoes() {
        val next = render().paragraphs.first { it.action == ReaderAction.NEXT_CHAPTER }
        assertEquals("Next chapter, John 4", next.accessibilityLabel)
    }
}
