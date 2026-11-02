package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A German study-Bible ePub shape (chapter ids `cref-C`, verse ids `vref-C-V`, classes `bvn`/`bcn`,
 * `p.ip` commentary between the scripture paragraphs, EPUB 3 endnote files), and German chapter
 * numbering for Joel and Malachi. The markup is the one GitHub issue #7 describes; every word of
 * verse text and commentary below is invented placeholder text.
 */
class GermanStudyBibleImportTest {
    private fun page(title: String, body: String): String =
        """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
           <head><title>$title</title></head><body>$body</body></html>"""

    private fun extract(vararg documents: Pair<String, String>): ExtractedBible =
        BibleTextExtractor().extract(documents.map { it.first to it.second })

    private fun text(bible: ExtractedBible, book: BookID, chapter: Int, verse: Int): String? =
        bible.verses[ref(book, chapter, verse)]?.text

    private fun chapterLabel(file: String, label: String, number: Int, name: String = "cb") =
        """<div id="cref-$number" class="bcn"><a href="$file#top_of_book"><span class="$name">$label </span>$number</a></div>"""

    private fun verse(chapter: Int, number: Int, shown: String = "$number", withId: Boolean = true) =
        if (withId) """<span id="vref-$chapter-$number" class="bvn">$shown</span>""" else """<span class="bvn">$shown</span>"""

    // The shape: Genesis, in a file named by an index, with commentary, endnote callers and alternative numbers.
    private val genesis = page(
        "Das erste Buch Mose (Genesis)",
        chapterLabel("gen_1.xhtml", "1. Mose", 1) +
            """<h2 class="s1">Erfundene Ueberschrift</h2>""" +
            """<p class="p"> ${verse(1, 1)} Erfundener Text eins. ${verse(1, 2)} Erfundener Text zwei.
               <a epub:type="noteref" href="notes_gen_1.xhtml#fn-gen-1">a</a> ${verse(1, 3, withId = false)} Text drei ohne Kennung.
               <span class="va">[9]</span> ${verse(1, 4, "4-5")} Text vier und fuenf. ${verse(1, 6, "6a")} Erster Teil
               <span class="bvn">6b</span> zweiter Teil.</p>""" +
            """<p class="ip">1,1-6 Erfundener Kommentar zu den Versen, nicht Teil des Textes.</p>""" +
            """<p class="p"> ${verse(1, 7)} Text sieben.</p>""" +
            """<p class="ip">1,7 Zweiter erfundener Kommentar.</p>""" +
            chapterLabel("gen_1.xhtml", "1. Mose", 2) +
            """<p class="p"> ${verse(2, 1)} Anfang des zweiten Kapitels.</p>""",
    )

    private val genesisNotes = page(
        "Anmerkungen",
        """<ol><li id="fn-gen-1"><p>Erfundene Querverweise 1,1.</p>
           <a role="doc-backlink" epub:type="backlink" href="gen_1.xhtml#fnref-gen-1">1</a></li></ol>""",
    )

    private val exodus = page(
        "Das zweite Buch Mose (Exodus)",
        chapterLabel("exo_2.xhtml", "2. Mose", 1) +
            """<p class="p"> ${verse(1, 1)} Erfundener Anfang des zweiten Buches.</p>""",
    )

    @Test fun readsTheChapterAndVerseIdsAndClasses() {
        val bible = extract("gen_1.xhtml" to genesis, "notes_gen_1.xhtml" to genesisNotes, "exo_2.xhtml" to exodus)
        assertEquals(listOf(BookID.GENESIS, BookID.EXODUS), bible.books)
        assertEquals("Erfundener Text eins.", text(bible, BookID.GENESIS, 1, 1))
        assertEquals("Erfundener Text zwei.", text(bible, BookID.GENESIS, 1, 2))
        assertEquals("Anfang des zweiten Kapitels.", text(bible, BookID.GENESIS, 2, 1))
        // The file's number ("exo_2") is an index, not the chapter: the label names chapter 1.
        assertEquals("Erfundener Anfang des zweiten Buches.", text(bible, BookID.EXODUS, 1, 1))
        assertNull(text(bible, BookID.EXODUS, 2, 1))
    }

    @Test fun aVerseSpanWithoutAnIdIsStillAVerse() {
        val bible = extract("gen_1.xhtml" to genesis)
        assertEquals("Text drei ohne Kennung.", text(bible, BookID.GENESIS, 1, 3))
        assertEquals(listOf(1, 2, 3, 4, 6, 7), bible.verseNumbers(ChapterRef(BookID.GENESIS, 1)))
    }

    @Test fun aBridgedVerseKeepsItsFirstNumberAndSplitVersesJoin() {
        val bible = extract("gen_1.xhtml" to genesis)
        assertEquals("Text vier und fuenf.", text(bible, BookID.GENESIS, 1, 4))
        assertEquals(ref(BookID.GENESIS, 1, 4), bible.bridgedVerses[ref(BookID.GENESIS, 1, 5)])
        assertEquals("Erster Teil zweiter Teil.", text(bible, BookID.GENESIS, 1, 6))
    }

    @Test fun alternativeNumberingIsNotText() {
        val bible = extract("gen_1.xhtml" to genesis)
        assertFalse(bible.verses.values.any { it.text.contains("[9]") })
        assertEquals("Text drei ohne Kennung.", text(bible, BookID.GENESIS, 1, 3))
    }

    @Test fun theChapterLabelIsNotVerseText() {
        val bible = extract("gen_1.xhtml" to genesis)
        assertFalse(bible.verses.values.any { it.text.contains("Mose") })
        assertEquals("Text sieben.", text(bible, BookID.GENESIS, 1, 7))
    }

    @Test fun commentaryIsAStudyNoteNotVerseText() {
        val bible = extract("gen_1.xhtml" to genesis)
        assertFalse(bible.verses.values.any { it.text.contains("Kommentar") })
        val notes = bible.study.orderedNotes
        assertEquals(2, notes.size)
        // The note covers the passage read since the previous one, and the reference it opens with goes.
        assertEquals(ref(BookID.GENESIS, 1, 1), notes[0].start)
        assertEquals(ref(BookID.GENESIS, 1, 6), notes[0].end)
        assertEquals("Erfundener Kommentar zu den Versen, nicht Teil des Textes.", notes[0].text)
        assertEquals(ref(BookID.GENESIS, 1, 7), notes[1].start)
        assertEquals(ref(BookID.GENESIS, 1, 7), notes[1].end)
    }

    @Test fun endnoteFilesAreNotScripture() {
        val bible = extract("gen_1.xhtml" to genesis, "notes_gen_1.xhtml" to genesisNotes)
        assertFalse(bible.verses.values.any { it.text.contains("Querverweise") })
        assertEquals(listOf(BookID.GENESIS), bible.books)
    }

    @Test fun commentaryWithoutStudyMaterialIsDropped() {
        val bible = BibleTextExtractor(BibleTextExtractor.Options(studyMaterial = false)).extract(listOf("gen_1.xhtml" to genesis))
        assertTrue(bible.study.isEmpty)
        assertFalse(bible.verses.values.any { it.text.contains("Kommentar") })
    }

    @Test fun aPsalmHeadingIsVerseOne() {
        val bible = extract(
            "psa_9.xhtml" to page(
                "Die Psalmen",
                chapterLabel("psa_9.xhtml", "Psalm", 3) +
                    """<p class="d">${verse(3, 1)} Ein erfundenes Lied.</p><p class="q1">${verse(3, 2)} Erfundene Zeile.</p>""",
            ),
        )
        assertEquals("Ein erfundenes Lied.", text(bible, BookID.PSALMS, 3, 1))
        assertEquals("Erfundene Zeile.", text(bible, BookID.PSALMS, 3, 2))
    }

    // MARK: - Labels

    @Test fun readsTheIdsAndGermanNames() {
        assertEquals(ScriptureLabels.Identifier.ChapterVerse(35, 1), ScriptureLabels.identifier("vref-35-1"))
        assertEquals(ScriptureLabels.Identifier.ChapterNumber(35), ScriptureLabels.identifier("cref-35"))
        assertEquals(ScriptureLabels.Place(BookID.GENESIS, 35), ScriptureLabels.heading("1. Mose 35"))
        assertEquals(ScriptureLabels.Place(BookID.FIRST_KINGS, null), ScriptureLabels.heading("1. Könige"))
        assertEquals(ScriptureLabels.Place(BookID.REVELATION, null), ScriptureLabels.heading("Offenbarung"))
        assertEquals(ScriptureLabels.Place(BookID.ESTHER, 2), ScriptureLabels.heading("Ester 2"))
        assertEquals(ScriptureLabels.Place(BookID.GENESIS, null), ScriptureLabels.fileStem("1_Mose"))
        // A section heading is still not a book.
        assertNull(ScriptureLabels.heading("Der Tag des Herrn")?.book)
    }

    // MARK: - German Joel and Malachi

    private val joelGerman = page(
        "Joel",
        chapterLabel("joe_1.xhtml", "Joel", 1) +
            """<p class="p">${verse(1, 1)} Erfundener Joel eins.</p>""" +
            chapterLabel("joe_1.xhtml", "Joel", 2) +
            """<p class="p">${verse(2, 1)} Erfundener Joel zwei eins. ${verse(2, 2)} Zwei zwei.
               ${verse(2, 26)} Zwei sechsundzwanzig. ${verse(2, 27)} Zwei siebenundzwanzig.</p>""" +
            chapterLabel("joe_1.xhtml", "Joel", 3) +
            """<h2 class="s1">Erfundene Ueberschrift</h2>
               <p class="p">${verse(3, 1)} Drei eins. ${verse(3, 2)} Drei zwei. ${verse(3, 5)} Drei fuenf.</p>""" +
            """<p class="ip">3,1-5 Erfundener Kommentar zu Joel drei.</p>""" +
            chapterLabel("joe_1.xhtml", "Joel", 4) +
            """<p class="p">${verse(4, 1)} Vier eins. ${verse(4, 2)} Vier zwei.</p>""",
    )

    private val malachiGerman = page(
        "Maleachi",
        chapterLabel("mal_1.xhtml", "Maleachi", 1) +
            """<p class="p">${verse(1, 1)} Erfundener Maleachi eins.</p>""" +
            chapterLabel("mal_1.xhtml", "Maleachi", 3) +
            """<p class="p">${verse(3, 1)} Drei eins. ${verse(3, 18)} Drei achtzehn.
               ${verse(3, 19)} Drei neunzehn. ${verse(3, 22)} Drei zweiundzwanzig. ${verse(3, 24)} Drei vierundzwanzig.</p>""",
    )

    @Test fun germanJoelIsRenumberedToTheKJVDivision() {
        val bible = extract("joe_1.xhtml" to joelGerman)
        assertEquals("Zwei sechsundzwanzig.", text(bible, BookID.JOEL, 2, 26))
        assertEquals("Zwei siebenundzwanzig.", text(bible, BookID.JOEL, 2, 27))
        // German 3:1-5 = KJV 2:28-32.
        assertEquals("Drei eins.", text(bible, BookID.JOEL, 2, 28))
        assertEquals("Drei zwei.", text(bible, BookID.JOEL, 2, 29))
        assertEquals("Drei fuenf.", text(bible, BookID.JOEL, 2, 32))
        // German 4 = KJV 3, and nothing is merged with it.
        assertEquals("Vier eins.", text(bible, BookID.JOEL, 3, 1))
        assertEquals("Vier zwei.", text(bible, BookID.JOEL, 3, 2))
        assertEquals(listOf(1, 2), bible.verseNumbers(ChapterRef(BookID.JOEL, 3)))
        assertNull(bible.verses[ref(BookID.JOEL, 4, 1)])
        assertEquals(3, bible.chapterOrder.count { it.book == BookID.JOEL })
    }

    @Test fun germanJoelKeepsItsBlocksAndStudyNotesInStep() {
        val bible = extract("joe_1.xhtml" to joelGerman)
        val chapter2 = bible.blocks(ChapterRef(BookID.JOEL, 2))
        assertEquals(listOf(1, 2, 26, 27, 28, 29, 32), chapter2.flatMap { it.fragments }.map { it.verse })
        // The heading that stood above German 3:1 travels with it.
        assertEquals("Erfundene Ueberschrift", chapter2.first { it.kind.isHeading }.heading)
        assertEquals(listOf(1, 2), bible.blocks(ChapterRef(BookID.JOEL, 3)).flatMap { it.fragments }.map { it.verse })
        val note = bible.study.orderedNotes.single()
        assertEquals(ref(BookID.JOEL, 2, 28), note.start)
        assertEquals(ref(BookID.JOEL, 2, 32), note.end)
    }

    @Test fun germanMalachiChapterThreeSplitsIntoThreeAndFour() {
        val bible = extract("mal_1.xhtml" to malachiGerman)
        assertEquals("Drei eins.", text(bible, BookID.MALACHI, 3, 1))
        assertEquals("Drei achtzehn.", text(bible, BookID.MALACHI, 3, 18))
        assertEquals("Drei neunzehn.", text(bible, BookID.MALACHI, 4, 1))
        assertEquals("Drei zweiundzwanzig.", text(bible, BookID.MALACHI, 4, 4))
        assertEquals("Drei vierundzwanzig.", text(bible, BookID.MALACHI, 4, 6))
        assertNull(bible.verses[ref(BookID.MALACHI, 3, 19)])
        // The block that held 3:1-24 is two paragraphs now, one in each chapter.
        assertEquals(listOf(1, 18), bible.blocks(ChapterRef(BookID.MALACHI, 3)).flatMap { it.fragments }.map { it.verse })
        assertEquals(listOf(1, 4, 6), bible.blocks(ChapterRef(BookID.MALACHI, 4)).flatMap { it.fragments }.map { it.verse })
    }

    @Test fun anEnglishNumberedJoelAndMalachiAreUntouched() {
        val bible = extract(
            "joe.xhtml" to page(
                "Joel",
                chapterLabel("joe.xhtml", "Joel", 2) + """<p>${verse(2, 28)} Erfunden achtundzwanzig. ${verse(2, 32)} Erfunden zweiunddreissig.</p>""" +
                    chapterLabel("joe.xhtml", "Joel", 3) + """<p>${verse(3, 1)} Erfunden drei eins.</p>""",
            ),
            "mal.xhtml" to page(
                "Maleachi",
                chapterLabel("mal.xhtml", "Maleachi", 3) + """<p>${verse(3, 1)} Eins. ${verse(3, 18)} Achtzehn.</p>""" +
                    chapterLabel("mal.xhtml", "Maleachi", 4) + """<p>${verse(4, 1)} Vier eins. ${verse(4, 6)} Vier sechs.</p>""",
            ),
        )
        assertEquals("Erfunden achtundzwanzig.", text(bible, BookID.JOEL, 2, 28))
        assertEquals("Erfunden drei eins.", text(bible, BookID.JOEL, 3, 1))
        assertEquals("Vier sechs.", text(bible, BookID.MALACHI, 4, 6))
        assertEquals("Achtzehn.", text(bible, BookID.MALACHI, 3, 18))
        assertNotNull(bible.verses[ref(BookID.MALACHI, 4, 1)])
    }

    @Test fun theVersificationTable() {
        assertEquals(2 to 28, GermanVersification.english(BookID.JOEL, 3, 1))
        assertEquals(2 to 32, GermanVersification.english(BookID.JOEL, 3, 5))
        assertEquals(3 to 21, GermanVersification.english(BookID.JOEL, 4, 21))
        assertEquals(2 to 27, GermanVersification.english(BookID.JOEL, 2, 27))
        assertEquals(3 to 18, GermanVersification.english(BookID.MALACHI, 3, 18))
        assertEquals(4 to 1, GermanVersification.english(BookID.MALACHI, 3, 19))
        assertEquals(4 to 6, GermanVersification.english(BookID.MALACHI, 3, 24))
        assertEquals(1 to 5, GermanVersification.english(BookID.GENESIS, 1, 5))
    }
}
