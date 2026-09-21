package com.blainemiller.scripturealone.data.online

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Kind
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Reading a chapter from a publisher's API with its structure intact. One test per test in
 * `OnlinePassageHTMLTests.swift`, same fixtures, same expectations.
 *
 * **The fixtures carry no licensed text.** They reproduce each service's *markup* — the thing under
 * test — around wording that is public domain or invented. A chapter of the ESV or the CSB pasted
 * into a public AGPL repository would be redistribution, and no test is worth that. The real captured
 * responses are checked too, by the last two tests, from a directory outside the repository.
 */
class OnlinePassageHTMLTest {

    private fun chapter(book: BookID, chapter: Int) = ChapterRef(book.number, chapter)

    // ---- Crossway's ESV markup ----

    /**
     * Red letters. This is the whole reason the app asks for HTML rather than text — the plain text
     * endpoint marks the words of Christ not at all.
     */
    @Test fun esvWordsOfChristBecomeRedRanges() {
        val html = "<p class=\"starts-chapter\"><b class=\"chapter-num\">3:1&nbsp;</b>A ruler came by night. " +
            "<b class=\"verse-num woc\">2&nbsp;</b><span class=\"woc\">Truly, I say to you.</span> " +
            "<b class=\"verse-num\">3&nbsp;</b>And he answered him.</p>"
        val passage = ESVPassageHTML.parse(html, chapter(BookID.JOHN, 3))
        assertEquals(3, passage.verses.size)
        assertTrue(passage.verses[0].red.isEmpty())
        assertEquals("Truly, I say to you.", passage.verses[1].text)
        assertEquals(1, passage.verses[1].red.size)

        // The range must actually cover the quoted words in that verse's own text.
        val verse = passage.verses[1]
        assertTrue(verse.red[0].isValid(verse.text))
        assertEquals("Truly, I say to you.", verse.red[0].substring(verse.text))
        assertTrue(passage.verses[2].red.isEmpty())
    }

    @Test fun esvPoetryBecomesLinesAndAPsalmTitle() {
        val html = "<h4 class=\"psalm-title\">A Psalm of David.</h4>\n" +
            "<p class=\"block-indent\"><span class=\"begin-line-group\"></span>\n" +
            "<span class=\"line\"><b class=\"chapter-num\">23:1&nbsp;</b>&nbsp;&nbsp;The first line.</span><br />" +
            "<span class=\"indent line\"><b class=\"verse-num inline\">2&nbsp;</b>&nbsp;&nbsp;&nbsp;&nbsp;An indented line.</span><br />" +
            "<span class=\"line\">&nbsp;&nbsp;A second line of verse two.</span>\n" +
            "<span class=\"end-line-group\"></span></p>"
        val passage = ESVPassageHTML.parse(html, chapter(BookID.PSALMS, 23))
        val kinds = passage.blocks.map { it.kind }
        assertTrue(Kind.TITLE in kinds)
        assertTrue(Kind.POETRY1 in kinds)
        assertTrue(Kind.POETRY2 in kinds)
        // The superscription is a title, not verse text — it must not become part of verse 1.
        assertEquals("The first line.", passage.verses.firstOrNull()?.text)
        // Verse 2 spans two poetry lines and reads as one verse.
        assertEquals("An indented line. A second line of verse two.", passage.verses.lastOrNull()?.text)
    }

    /** Runs of non-breaking spaces are indentation, and the reader does its own indenting. */
    @Test fun esvIndentationDoesNotSurviveAsText() {
        val html = "<p><b class=\"verse-num\">1&nbsp;</b>&nbsp;&nbsp;&nbsp;Spaced&nbsp;&nbsp;out.</p>"
        val passage = ESVPassageHTML.parse(html, chapter(BookID.JOHN, 1))
        assertEquals("Spaced out.", passage.verses.firstOrNull()?.text)
    }

    /**
     * A words-of-Christ span ending in whitespace at a verse boundary — closed by its own tag, or
     * still open when the next verse number arrives — must survive the fragment's trailing trim.
     * Closing it before trimming left it one scalar past the verse text, and `finish()` dropped it.
     */
    @Test fun esvRedLettersEndingInWhitespaceSurviveTheVerseBoundary() {
        val closed = "<p><b class=\"verse-num\">1&nbsp;</b>He said, <span class=\"woc\">Follow me. </span>" +
            "<b class=\"verse-num\">2&nbsp;</b>And they went.</p>"
        val passage = ESVPassageHTML.parse(closed, chapter(BookID.JOHN, 1))
        assertEquals(2, passage.verses.size)
        val first = passage.verses.first()
        assertEquals("He said, Follow me.", first.text)
        val red = first.red.firstOrNull()
        assertTrue("the red span was dropped", red != null && red.isValid(first.text))
        assertEquals("Follow me.", red!!.substring(first.text))
        assertTrue(passage.verses[1].red.isEmpty())

        val open = "<p><b class=\"verse-num\">1&nbsp;</b>He said, <span class=\"woc\">Follow me. " +
            "<b class=\"verse-num\">2&nbsp;</b>Come and see.</span> And they went.</p>"
        val carried = ESVPassageHTML.parse(open, chapter(BookID.JOHN, 1))
        assertEquals(2, carried.verses.size)
        for ((verse, quoted) in carried.verses.zip(listOf("Follow me.", "Come and see."))) {
            val range = verse.red.firstOrNull()
            assertTrue("${verse.ref} lost its red letters", range != null && range.isValid(verse.text))
            assertEquals(quoted, range!!.substring(verse.text))
        }
    }

    // ---- API.Bible markup ----

    @Test fun apiBibleWordsOfJesusBecomeRedRanges() {
        val html = "<p class=\"p\"><span data-number=\"16\" data-sid=\"JHN 3:16\" class=\"v\">16</span>" +
            "<span class=\"wj\">For God loved the world.</span></p>"
        val passage = APIBiblePassageHTML.parse(html, chapter(BookID.JOHN, 3))
        val verse = passage.verses.first()
        assertEquals(VerseRef(BookID.JOHN.number, 3, 16), verse.ref)
        assertEquals("For God loved the world.", verse.text)
        // Swift falls back to an empty NSRange, which slices to ""; a missing range fails either way.
        val range = verse.red.firstOrNull() ?: Utf16Range(0, 0)
        assertTrue(range.isValid(verse.text))
        assertEquals("For God loved the world.", range.substring(verse.text))
    }

    @Test fun apiBibleRedLettersEndingInWhitespaceSurvive() {
        val html = "<p class=\"p\"><span data-number=\"16\" data-sid=\"JHN 3:16\" class=\"v\">16</span>" +
            "<span class=\"wj\">For God loved the world. </span></p>" +
            "<p class=\"p\"><span data-number=\"17\" data-sid=\"JHN 3:17\" class=\"v\">17</span>Next. </p>"
        val passage = APIBiblePassageHTML.parse(html, chapter(BookID.JOHN, 3))
        val verse = passage.verses.first()
        assertEquals("For God loved the world.", verse.text)
        val range = verse.red.firstOrNull()
        assertTrue("the red span was dropped", range != null && range.isValid(verse.text))
        assertEquals("For God loved the world.", range!!.substring(verse.text))
    }

    /**
     * A heading's words go on the block, the way the bundled stores keep them — not in fragments,
     * which the layout writer never writes for a heading.
     */
    @Test fun apiBibleHeadingsCarryTheirWordsOnTheBlock() {
        val html = "<p class=\"s1\">A Heading</p><p class=\"p\">" +
            "<span data-number=\"1\" data-sid=\"JHN 1:1\" class=\"v\">1</span>In the beginning.</p>"
        val passage = APIBiblePassageHTML.parse(html, chapter(BookID.JOHN, 1))
        val heading = passage.blocks.first()
        assertEquals(Kind.HEADING, heading.kind)
        assertEquals("A Heading", heading.text)
        assertTrue(heading.fragments.isEmpty())
    }

    /** The markers are USFM, which is the vocabulary this app's own layout already speaks. */
    @Test fun apiBibleClassesMapOntoTheAppsBlockKinds() {
        val html = "<p class=\"s1\">The Good Shepherd</p><p class=\"d\">A psalm of David.</p>" +
            "<p class=\"q1\"><span data-number=\"1\" data-sid=\"PSA 23:1\" class=\"v\">1</span>The first line; </p>" +
            "<p data-vid=\"PSA 23:1\" class=\"q\">the rest of verse one. </p>" +
            "<p class=\"b\"></p>" +
            "<p class=\"q1\"><span data-number=\"2\" data-sid=\"PSA 23:2\" class=\"v\">2</span>Verse two. </p>"
        val passage = APIBiblePassageHTML.parse(html, chapter(BookID.PSALMS, 23))
        val kinds = passage.blocks.map { it.kind }
        assertTrue(Kind.HEADING in kinds)
        assertTrue(Kind.TITLE in kinds)
        assertTrue(Kind.POETRY1 in kinds)
        assertTrue(Kind.STANZA_BREAK in kinds)
        // `data-vid` is how an unnumbered continuation line says which verse it belongs to. Without
        // honouring it that line would be attributed to no verse at all.
        assertEquals("The first line; the rest of verse one.", passage.verses.firstOrNull()?.text)
        assertEquals(2, passage.verses.size)
    }

    @Test fun apiBibleHeadingsAreNotVerseText() {
        val html = "<p class=\"s1\">A Heading</p><p class=\"p\">" +
            "<span data-number=\"1\" data-sid=\"JHN 1:1\" class=\"v\">1</span>In the beginning.</p>"
        val passage = APIBiblePassageHTML.parse(html, chapter(BookID.JOHN, 1))
        assertEquals(1, passage.verses.size)
        assertEquals("In the beginning.", passage.verses[0].text)
        assertFalse(passage.verses[0].text.contains("Heading"))
    }

    // ---- The real responses ----

    /**
     * Captured from both services and kept outside the repository, because they are licensed text.
     * Present on the machine that captured them; skipped (as an assumption, so it shows) elsewhere.
     */
    private val captures = File(System.getProperty("user.home"), ".scripture-alone-import/online")

    private fun capture(name: String): String? = File(captures, name).takeIf { it.isFile }?.readText()

    @Test fun theRealESVResponsesParse() {
        val john = capture("esv-JHN3.html")
        val psalm = capture("esv-PSA23.html")
        val matthew = capture("esv-MAT5.html")
        assumeTrue("no captured ESV responses in $captures", john != null && psalm != null && matthew != null)

        val john3 = ESVPassageHTML.parse(john!!, chapter(BookID.JOHN, 3))
        assertEquals(36, john3.verses.size)
        assertTrue(john3.verses.any { it.red.isNotEmpty() })
        // Every red range must be a valid range in the verse it belongs to.
        for (verse in john3.verses) for (range in verse.red) assertTrue(range.isValid(verse.text))
        // No markup may reach a reader.
        for (verse in john3.verses) {
            assertFalse(verse.text.contains("<"))
            assertFalse(verse.text.contains("&nbsp;"))
        }

        val psalm23 = ESVPassageHTML.parse(psalm!!, chapter(BookID.PSALMS, 23))
        assertEquals(6, psalm23.verses.size)
        assertTrue(psalm23.blocks.any { it.kind == Kind.TITLE })
        assertTrue(psalm23.blocks.any { it.kind == Kind.POETRY1 })

        // The Sermon on the Mount is nearly all red.
        val matthew5 = ESVPassageHTML.parse(matthew!!, chapter(BookID.MATTHEW, 5))
        assertEquals(48, matthew5.verses.size)
        val reddened = matthew5.verses.count { it.red.isNotEmpty() }
        assertTrue("only $reddened of ${matthew5.verses.size} verses carry red", reddened > 40)
    }

    @Test fun theRealAPIBibleResponsesParse() {
        val john = capture("apibible-JHN3.html")
        val psalm = capture("apibible-PSA23.html")
        assumeTrue("no captured API.Bible responses in $captures", john != null && psalm != null)

        val john3 = APIBiblePassageHTML.parse(john!!, chapter(BookID.JOHN, 3))
        assertEquals(36, john3.verses.size)
        assertTrue(john3.verses.any { it.red.isNotEmpty() })
        for (verse in john3.verses) {
            assertFalse(verse.text.contains("<"))
            for (range in verse.red) assertTrue(range.isValid(verse.text))
        }

        val psalm23 = APIBiblePassageHTML.parse(psalm!!, chapter(BookID.PSALMS, 23))
        assertEquals(6, psalm23.verses.size)
        assertTrue(psalm23.blocks.any { it.kind == Kind.TITLE })
        assertTrue(psalm23.blocks.any { it.kind == Kind.POETRY1 })
        // Every verse must have landed somewhere; a dropped continuation line is silent data loss.
        assertTrue(psalm23.verses.all { it.text.isNotEmpty() })
    }
}
