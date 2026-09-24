package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Study-Bible ePub shapes: files split into scripture, study notes, footnotes and cross references;
 * chapter numbers set as drop caps; essays boxed into the text. Ported from
 * `StudyBibleImportTests.swift`. Text is public domain or invented.
 */
class StudyBibleImportTest {
    private fun page(body: String): String =
        """<?xml version="1.0"?><html xmlns:epub="http://www.idpf.org/2007/ops"><body>$body</body></html>"""

    private fun extract(vararg documents: Pair<String, String>): ExtractedBible =
        BibleTextExtractor().extract(documents.map { it.first to page(it.second) })

    private fun text(bible: ExtractedBible, book: BookID, chapter: Int, verse: Int): String? =
        bible.verses[ref(book, chapter, verse)]?.text

    @Test fun aDropCapChapterNumberStartsVerseOne() {
        val bible = extract(
            "ruth.xhtml" to """
            <section title="Ruth"><p class="p-first"><span class="chapter-num">1</span> And it came to pass in the days
            when the judges judged. <span class="verse-num">2</span> And the name of the man was Elimelech.</p>
            <p class="p-first"><span class="chapter-num">2</span> And Naomi had a kinsman. <span class="verse-num">2</span> And Ruth said.</p></section>
            """,
        )
        assertEquals("And it came to pass in the days when the judges judged.", text(bible, BookID.RUTH, 1, 1))
        assertEquals("And Naomi had a kinsman.", text(bible, BookID.RUTH, 2, 1))
        assertEquals(listOf(1, 2), bible.verseNumbers(ChapterRef(BookID.RUTH, 2)))
    }

    @Test fun aPrintedVerseOneAfterTheDropCapIsTheSameVerse() {
        val bible = extract(
            "ruth.xhtml" to """
            <section title="Ruth"><p><span class="chapter-num">1</span> <span class="verse-num">1</span> And it came to pass.
            <span class="verse-num">2</span> And the name.</p></section>
            """,
        )
        assertEquals("And it came to pass.", text(bible, BookID.RUTH, 1, 1))
        assertEquals(listOf(1, 2), bible.verseNumbers(ChapterRef(BookID.RUTH, 1)))
    }

    @Test fun theSectionTitleNamesTheBook() {
        val bible = extract(
            "a.xhtml" to """<section title="Obadiah"><p><span class="verse-num">1</span> The vision of Obadiah.</p></section>""",
            "b.xhtml" to """<section title="Jonah"><p><span class="verse-num">1</span> Now the word of Jehovah came unto Jonah.</p></section>""",
        )
        assertEquals("The vision of Obadiah.", text(bible, BookID.OBADIAH, 1, 1))
        assertEquals("Now the word of Jehovah came unto Jonah.", text(bible, BookID.JONAH, 1, 1))
    }

    @Test fun aTitleNamingABookAlreadyReadIsASlip() {
        val bible = extract(
            "gen.xhtml" to """<section title="Genesis"><p><span class="chapter-num">1</span> In the beginning.</p></section>""",
            "ex.xhtml" to """<section title="Exodus"><p><span class="chapter-num">1</span> Now these are the names.</p></section>""",
            "ex2.xhtml" to """<section title="Genesis"><p><span class="chapter-num">2</span> And there went a man.</p></section>""",
        )
        assertEquals("And there went a man.", text(bible, BookID.EXODUS, 2, 1))
        assertTrue(bible.verseNumbers(ChapterRef(BookID.GENESIS, 2)).isEmpty())
    }

    @Test fun anIdThatContradictsTheTitleDoesNotMove() {
        val bible = extract(
            "3jn.xhtml" to """
            <section title="3 John"><div id="john3"><p><span class="verse-num">1</span> The elder unto Gaius the beloved.</p></div></section>
            """,
        )
        assertEquals("The elder unto Gaius the beloved.", text(bible, BookID.THIRD_JOHN, 1, 1))
        assertFalse(BookID.JOHN in bible.books)
    }

    @Test fun aTitleHoldingTheChapterNumberIsNotAHeading() {
        val bible = extract(
            "ps.xhtml" to """
            <section title="Psalms"><p class="psalm-title"><span class="chapter-num">1</span> Blessed is the man</p>
            <p class="poetry">That walketh not in the counsel of the wicked. <span class="verse-num">2</span> But his delight.</p>
            <p class="psalm-acrostic-title">ALEPH</p>
            <p class="psalm-title-2"><span class="chapter-num">2</span> Why do the nations rage?</p></section>
            """,
        )
        assertEquals("Blessed is the man That walketh not in the counsel of the wicked.", text(bible, BookID.PSALMS, 1, 1))
        assertEquals("Why do the nations rage?", text(bible, BookID.PSALMS, 2, 1))
        assertTrue(bible.blocks(ChapterRef(BookID.PSALMS, 2)).any { it.heading == "ALEPH" })
    }

    @Test fun aHeadingBelongsToTheChapterItIntroduces() {
        val bible = extract(
            "gen.xhtml" to """
            <section title="Genesis"><p class="heading">The Creation</p>
            <p><span class="chapter-num">1</span> In the beginning. <span class="verse-num">2</span> And the earth.</p>
            <p class="heading">The Garden</p>
            <p><span class="chapter-num">2</span> And the heavens were finished.</p></section>
            """,
        )
        assertEquals("The Creation", bible.blocks(ChapterRef(BookID.GENESIS, 1)).first().heading)
        assertTrue(bible.blocks(ChapterRef(BookID.GENESIS, 1)).all { it.heading != "The Garden" })
        assertEquals("The Garden", bible.blocks(ChapterRef(BookID.GENESIS, 2)).first().heading)
    }

    @Test fun aChapterNumberSetBeforeTheLastVerseOfTheChapterBefore() {
        val bible = extract(
            "jn.xhtml" to """
            <section title="John"><p><span class="chapter-num">7</span> After these things. <span class="verse-num">2</span> Now the feast.</p>
            <p><span class="chapter-num">8</span><span class="verse-num">3</span> And they went every man unto his own house:
            <span class="verse-num">1</span> but Jesus went unto the mount of Olives. <span class="verse-num">2</span> And early.</p></section>
            """,
        )
        assertEquals("And they went every man unto his own house:", text(bible, BookID.JOHN, 7, 3))
        assertEquals(listOf(1, 2), bible.verseNumbers(ChapterRef(BookID.JOHN, 8)))
    }

    @Test fun notesPagesAndIntroductionsAddNoText() {
        val bible = extract(
            "rom.xhtml" to """<section title="Romans"><p><span class="chapter-num">1</span> Paul, a servant. <span class="verse-num">2</span> Which he promised.</p></section>""",
            "rom_notes.xhtml" to """
                <section title="Romans"><p class="notesubhead">FOOTNOTES FOR ROMANS 1</p>
                <p class="note"><a href="rom.xhtml#nb1" id="nc1">1</a> Or <i>bondservant</i> <sup>2</sup></p>
                <p class="note"><a href="rom.xhtml#nb2" id="nc2">2</a> Or <i>beforehand</i></p></section>
                """,
            "intro.xhtml" to """<section title="Introduction"><p>Author. The apostle wrote this letter.</p></section>""",
        )
        assertEquals("Which he promised.", text(bible, BookID.ROMANS, 1, 2))
        assertEquals(2, bible.verseCount)
    }

    @Test fun essaysCrossReferencesCaptionsAndNavigationAreNotScripture() {
        val bible = extract(
            "rom.xhtml" to """
            <section title="Romans"><p><span class="chapter-num">1</span> Paul, <span class="crossref"><a epub:type="noteref" href="#cr1">a</a></span> a servant.</p>
            <div class="gbox"><p class="theohead">GRACE</p><p class="theobody">An essay about grace.</p></div>
            <p class="image"><img src="map.jpg"/><span>A map of Rome</span></p>
            <p><span class="verse-num">2</span> Which he promised.</p>
            <p class="centerr"><a href="1corintro.xhtml">Book of 1 Corinthians ⇨</a></p>
            <aside epub:type="footnote" id="cr1"><p>a Acts 9:15</p></aside></section>
            """,
        )
        assertEquals("Paul, a servant.", text(bible, BookID.ROMANS, 1, 1))
        assertEquals("Which he promised.", text(bible, BookID.ROMANS, 1, 2))
        val footnotes = bible.blocks(ChapterRef(BookID.ROMANS, 1)).flatMap { it.fragments }.flatMap { it.footnotes }
        assertTrue(footnotes.isEmpty())
    }

    @Test fun translationFootnotesStayAndStudyNotesGo() {
        val bible = extract(
            "rom.xhtml" to """
            <section title="Romans"><p><span class="chapter-num">1</span><a epub:type="noteref" href="#vc1">[✞]</a> Paul, a servant<span class="note"><a epub:type="noteref" href="#nc1">1</a></span> of Christ.</p>
            <aside epub:type="footnote" id="vc1"><p><b>ROMANS 1:1 Paul.</b> Ancient letters began with a formula.</p></aside>
            <aside epub:type="footnote" id="nc1"><p>1 Or <i>bondservant</i></p></aside></section>
            """,
        )
        val notes = bible.blocks(ChapterRef(BookID.ROMANS, 1)).flatMap { it.fragments }.flatMap { it.footnotes }.map { it.text }
        assertEquals(listOf("Or bondservant"), notes)
        assertEquals("Paul, a servant of Christ.", text(bible, BookID.ROMANS, 1, 1))
    }

    @Test fun anUnresolvedShortCallerIsDropped() {
        assertEquals(null, Assembler.footnoteBody(null, "a"))
        assertEquals(null, Assembler.footnoteBody("", "[✞]"))
        assertEquals("Or brothers", Assembler.footnoteBody("1 Or brothers", "1"))
        assertEquals(null, Assembler.footnoteBody("3:16 For God so loved", "b"))
    }

    @Test fun boldNumbersAreVersesWhenNothingBetterIsThere() {
        val bible = extract(
            "gal.xhtml" to """
            <section title="Galatians"><p><span class="chapter-num">1</span> Paul, an apostle, <span class="b">2</span> and all the brethren.
            <span class="b">3</span> Grace to you.</p></section>
            """,
        )
        assertEquals(listOf(1, 2, 3), bible.verseNumbers(ChapterRef(BookID.GALATIANS, 1)))
        assertEquals("and all the brethren.", text(bible, BookID.GALATIANS, 1, 2))
    }

    @Test fun aConcordanceIsNotScripture() {
        val bible = extract(
            "rev.xhtml" to """<section title="Revelation"><p><span class="chapter-num">1</span> The Revelation of Jesus Christ.</p></section>""",
            "conc.xhtml" to """
                <section title="Concordance"><p>ABIDE <b>Ps</b> <b>15</b>:1 who shall a in your tent • <b>John</b> <b>15</b>:4 A in me</p>
                <p>ABRAHAM <b>Gen</b> <b>17</b>:5 your name shall be A • <b>Rom</b> <b>4</b>:3 A believed God</p></section>
                """,
        )
        assertEquals("The Revelation of Jesus Christ.", text(bible, BookID.REVELATION, 1, 1))
        assertEquals(1, bible.verseCount)
    }

    @Test fun indentedPoetryIsTheSecondLevel() {
        val bible = extract(
            "ps.xhtml" to """
            <section title="Psalms"><p class="poetry"><span class="chapter-num">1</span> Blessed is the man</p>
            <p class="poetry-indent">That walketh not in the counsel of the wicked.</p></section>
            """,
        )
        assertEquals(
            listOf(ExtractedBlock.Kind.POETRY1, ExtractedBlock.Kind.POETRY2),
            bible.blocks(ChapterRef(BookID.PSALMS, 1)).map { it.kind },
        )
    }

    @Test fun speakerAndAcrosticClassesAreHeadings() {
        val bible = extract(
            "song.xhtml" to """
            <section title="Song of Solomon"><p class="speaker">The Bride</p>
            <p><span class="chapter-num">1</span> The song of songs, which is Solomon’s.</p></section>
            """,
        )
        assertEquals("The Bride", bible.blocks(ChapterRef(BookID.SONG_OF_SOLOMON, 1)).first().heading)
        assertEquals("The song of songs, which is Solomon’s.", text(bible, BookID.SONG_OF_SOLOMON, 1, 1))
    }

    @Test fun chaptersDoNotRunBackwardsOnceMarked() {
        val bible = extract(
            "gen.xhtml" to """
            <section title="Genesis"><p><span class="chapter-num">1</span> In the beginning. <span class="verse-num">2</span> And the earth.</p>
            <p><span class="chapter-num">2</span> Thus the heavens.</p>
            <p><span class="chapter-num">1</span> <span class="verse-num">2</span> And on the seventh day.</p></section>
            """,
        )
        assertEquals("Thus the heavens. And on the seventh day.", text(bible, BookID.GENESIS, 2, 1) + " " + text(bible, BookID.GENESIS, 2, 2))
        assertEquals("And the earth.", text(bible, BookID.GENESIS, 1, 2))
    }

    // MARK: Red letters

    @Test fun classesTheStylesheetColorsRedAreWordsOfChrist() {
        val css = """
            /* words of Christ */
            span.sgc-7 { color: #c8102e; }
            .quiet, p.note { color: #5a5a5a }
            .jesus{color:rgb(190, 20, 20) !important}
            div .nested { color: red }
            """
        assertEquals(setOf("sgc-7", "jesus"), DocumentScanner.redClasses(listOf(css)))
        val scanner = DocumentScanner(BibleTextExtractor.Options(), setOf("sgc-7"))
        val assembler = Assembler(BibleTextExtractor.Options())
        assembler.consume(
            scanner.scan(
                page(
                    """
                    <section title="John"><p><span class="chapter-num">14</span> Jesus saith unto him, <span class="sgc-7">I am the way.</span></p></section>
                    """,
                ),
            ),
            "jn.xhtml",
        )
        assembler.finish()
        val verse = required(assembler.bible.verses[ref(BookID.JOHN, 14, 1)])
        assertEquals(1, verse.red.size)
    }

    @Test fun inlineRedStyleIsWordsOfChrist() {
        assertTrue(DocumentScanner.isRed("font-weight:bold; color: #C00"))
        assertTrue(DocumentScanner.isRed("color:darkred"))
        assertFalse(DocumentScanner.isRed("background-color: red"))
        assertFalse(DocumentScanner.isRed("color: #5a5a5a"))
    }

    // MARK: Quality

    @Test fun cleanTextIsTold() {
        assertTrue(ImportQuality.looksClean("In the beginning God created the heaven and the earth."))
        assertFalse(ImportQuality.looksClean("In the begin\u00ADning"))
        assertFalse(ImportQuality.looksClean("the earth.And God"))
        assertFalse(ImportQuality.looksClean("the  earth"))
        assertFalse(ImportQuality.looksClean("the heavenAnd the earth"))
        assertFalse(ImportQuality.looksClean(""))
    }

    @Test fun aPoorReadIsRefusedAndAShortOneIsNotJudged() {
        // 250 verses in 25 chapters, every chapter with a hole: continuity 0.
        val broken = (1..25).map { chapter ->
            "c$chapter.xhtml" to page(
                """<section title="Psalms"><p><span class="chapter-num">$chapter</span> Praise.""" +
                    (3..11).joinToString("") { """ <span class="verse-num">$it</span> Praise ye Jehovah.""" } + "</p></section>",
            )
        }
        val bible = BibleTextExtractor().extract(broken)
        val report = ImportCoverageReport(bible)
        assertEquals(50, report.quality.score)
        assertFalse(report.quality.isAcceptable)
        val identity = ImportedTranslationIdentity("IMPORT-POOR", "Poor", "PR", "© Example")
        assertThrowsImport<BibleImportError.PoorQuality>(BibleImportError.PoorQuality(50)) {
            ImportedBibleBuilder.write(bible, identity, java.io.File(ImportFixtures.scratchDirectory(), "poor.sqlite"), ImportFixtures.jdbcWriter)
        }
        // Two chapters is too little to judge.
        val short = BibleTextExtractor().extract(broken.take(2))
        assertTrue(ImportCoverageReport(short).quality.isAcceptable)
    }
}
