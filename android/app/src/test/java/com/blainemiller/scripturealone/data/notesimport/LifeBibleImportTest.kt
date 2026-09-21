package com.blainemiller.scripturealone.data.notesimport

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A port of `LifeBibleImportTests.swift`: the Life Bible (formerly Tecarta) export, read back.
 *
 * The fixtures are written to the shape of a real export — non-breaking space between book and
 * chapter, the translation trailing the reference, `<br>` between everything — because that shape is
 * the whole difficulty.
 */
class LifeBibleImportTest {

    private fun ref(book: BookID, chapter: Int, verse: Int) = VerseRef(book.number, chapter, verse)

    private fun html(body: String) =
        "<!DOCTYPE html><html><head><style>body {margin: 2em;}</style></head><body>$body</body></html>"

    /** U+00A0, which Life Bible puts between a book and its chapter. */
    private val nbsp = Char(0xA0)

    /**
     * The separator really is U+00A0. Splitting on a normal space finds nothing, which would make every
     * reference in the file unresolvable — so this is asserted first and on its own.
     */
    @Test fun theBookAndChapterAreSeparatedByANonBreakingSpace() {
        val reference = requireNotNull(LifeBibleImport.reference("Genesis${nbsp}3:1 NKJV"))
        assertEquals(ref(BookID.GENESIS, 3, 1), reference.range?.start)
        assertEquals("NKJV", reference.translation)
        // And the same string with an ordinary space still works, in case they ever change it.
        assertEquals(ref(BookID.GENESIS, 3, 1), LifeBibleImport.reference("Genesis 3:1 NKJV")?.range?.start)
    }

    @Test fun numberedBooksAndVersionsWithDigitsSurvive() {
        val chronicles = requireNotNull(LifeBibleImport.reference("1 Chronicles${nbsp}29:14"))
        assertEquals(ref(BookID.FIRST_CHRONICLES, 29, 14), chronicles.range?.start)
        assertNull(chronicles.translation) // saves carry no translation

        val nasb = requireNotNull(LifeBibleImport.reference("Genesis${nbsp}28:1 NASB95"))
        assertEquals(ref(BookID.GENESIS, 28, 1), nasb.range?.start)
        assertEquals("NASB95", nasb.translation)
    }

    /** A reference this app cannot place must not be approximated into one it can. */
    @Test fun whatCannotBeResolvedIsReportedRatherThanGuessed() {
        assertNull(LifeBibleImport.reference("Enchiridion${nbsp}4:2 XYZ"))
        assertNull(LifeBibleImport.reference("Some Devotional, para. 4"))
    }

    @Test fun highlightsCarryTheirVerseAndNearestColor() {
        val file = html(
            "Genesis${nbsp}3:1 NKJV  #ffc9e4<br>Genesis${nbsp}17:16 CSB  #fff193<br>" +
                "Genesis${nbsp}22:1 CSB  #b3e487<br>John${nbsp}3:16 CSB  #cae1fe<br>" +
                "underline Psalms${nbsp}23:1 CSB  #999999  words: 2-5<br>",
        )
        val result = ImportedNotes()
        LifeBibleImport.readHighlights(file, result)

        assertEquals(5, result.highlights.size)
        assertTrue(result.unresolved.isEmpty())
        assertEquals(ref(BookID.GENESIS, 3, 1), result.highlights[0].verse)
        assertEquals("pink", result.highlights[0].color)
        assertEquals("yellow", result.highlights[1].color)
        assertEquals("green", result.highlights[2].color)
        assertEquals("blue", result.highlights[3].color)
        // Underlines and word offsets are styles this app has no equivalent for. The highlight still
        // arrives — losing it would be the worse trade — in the nearest color it has.
        assertEquals(ref(BookID.PSALMS, 23, 1), result.highlights[4].verse)
        assertTrue(result.highlights[4].color in ImportedNotes.highlightColorNames)
    }

    /**
     * Life Bible's palette is pale where this app's is saturated, so these must be matched by hue.
     * Matching by RGB distance files their blue under purple — the wrong colour, silently.
     */
    @Test fun eachOfLifeBiblesPaletteColorsLandsOnItsOwnHue() {
        assertEquals("blue", LifeBibleImport.nearestColor("#cae1fe"))
        assertEquals("yellow", LifeBibleImport.nearestColor("#fff193"))
        assertEquals("pink", LifeBibleImport.nearestColor("#ffc9e4"))
        assertEquals("green", LifeBibleImport.nearestColor("#b3e487"))
        // Grey has no hue to match; it goes to the palest colour rather than an arbitrary one.
        assertEquals("purple", LifeBibleImport.nearestColor("#999999"))
        // A colour nobody's palette has still lands somewhere sensible.
        assertEquals("pink", LifeBibleImport.nearestColor("#ff0000"))
    }

    @Test fun verseNotesKeepTheirReferenceAndTheirText() {
        val file = html(
            "<p>Genesis${nbsp}2:18 CSB<br>God's design for marriage <br>Line two</p>" +
                "<p>Genesis${nbsp}22:1 CSB<br>How to pass a test:<br>Will you trust God's will.<br><br>" +
                "Abraham obeyed God</p>",
        )
        val result = ImportedNotes()
        LifeBibleImport.readVerseNotes(file, result)

        assertEquals(2, result.verseNotes.size)
        assertEquals(ref(BookID.GENESIS, 2, 18), result.verseNotes[0].range?.start)
        assertEquals("CSB", result.verseNotes[0].translation)
        assertEquals("God's design for marriage\nLine two", result.verseNotes[0].body)
        // The author's own paragraph break survives: `<br><br>` is how they separated two thoughts,
        // and flattening it would rewrite their note.
        assertTrue(result.verseNotes[1].body.contains("Will you trust God's will.\n\nAbraham obeyed God"))
    }

    @Test fun savedVersesComeAcross() {
        val result = ImportedNotes()
        LifeBibleImport.readSaves(html("1 John${nbsp}1:1<br>Philippians${nbsp}4:6<br>"), result)
        assertEquals(2, result.saved.size)
        assertEquals(ref(BookID.FIRST_JOHN, 1, 1), result.saved[0].start)
        assertEquals(ref(BookID.PHILIPPIANS, 4, 6), result.saved[1].start)
    }

    @Test fun aJournalEntryTakesItsTitleFromItsFirstLine() {
        val result = ImportedNotes()
        LifeBibleImport.readJournal(
            html("The Doctrine of God. <br>The Bible assumes the existence of God"),
            leaf = "the-doctrine-of-god.html", folders = emptyList(), result = result,
        )
        val entry = requireNotNull(result.journals.firstOrNull())
        assertNull(entry.range)
        // The slug lost the capitals; the body's own first line kept them.
        assertEquals("The Doctrine of God", entry.title)
        assertTrue(entry.body.contains("The Bible assumes the existence of God"))
    }

    @Test fun entitiesAndTagsBecomeOrdinaryText() {
        val text = LifeBibleImport.text("Grace &amp; peace<br><b>bold</b> &rsquo;tis<br>&nbsp;")
        assertEquals("Grace & peace\nbold ’tis", text)
    }

    /**
     * Found in a real note, on screen, after the first import ran: the export writes a slash as
     * `&#47;`, so a sermon dated 06/11/22 arrived as `06&#47;11&#47;22`. A fixed list of named entities
     * let it through, which is why numeric references are decoded generally.
     */
    @Test fun numericEntitiesAreDecodedToo() {
        assertEquals("Disciples Church 06/11/22", LifeBibleImport.text("Disciples Church 06&#47;11&#47;22"))
        assertEquals("God's design", LifeBibleImport.text("God&#39;s design"))
        assertEquals("hex 'quoted'", LifeBibleImport.text("hex &#x27;quoted&#x27;"))
        // A literal, escaped entity in someone's note must not be decoded a second time.
        assertEquals("type &#39; for an apostrophe", LifeBibleImport.text("type &amp;#39; for an apostrophe"))
    }

    // MARK: The real export

    /**
     * Blaine's own export, if it is on this machine — outside the repository, because it is years of
     * personal study notes and belongs in version control less than almost anything.
     *
     * It asserts nothing about the contents, only that the whole archive parses and that nothing was
     * quietly dropped.
     */
    private val realExport = File(System.getProperty("user.home"), ".scripture-alone-import/LifeBibleData.zip")

    @Test fun theRealExportParsesWithNothingLeftBehind() {
        if (!realExport.exists()) return
        val result = LifeBibleImport.read(realExport.readBytes())

        assertTrue("verse notes: ${result.verseNotes.size}", result.verseNotes.size > 400)
        assertTrue("highlights: ${result.highlights.size}", result.highlights.size > 250)
        assertFalse(result.journals.isEmpty())
        assertFalse(result.saved.isEmpty())
        // Every note must have landed on a verse, and every note must have text.
        assertTrue(result.verseNotes.all { it.range != null && it.body.isNotEmpty() })
        assertTrue(result.journals.all { it.title.isNotEmpty() && it.body.isNotEmpty() })
        // Markup must not have leaked into anybody's notes.
        val entity = Regex("&#?[a-zA-Z0-9]+;")
        for (note in result.verseNotes + result.journals) {
            assertFalse(note.body.contains("<br>"))
            assertFalse("undecoded entity in ${note.title}", entity.containsMatchIn(note.body))
        }
        assertTrue("unresolved: ${result.unresolved.take(5)}", result.unresolved.isEmpty())
    }
}
