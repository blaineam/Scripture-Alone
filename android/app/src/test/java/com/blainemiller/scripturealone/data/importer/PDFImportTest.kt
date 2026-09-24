package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.canon.BookID
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The PDF reader's rules, each judged against the file's own text rather than any one layout. Ported
 * from `PDFImportTests.swift`, with pages of invented glyphs for the layout this reader rebuilds itself,
 * and small PDFs written in the test for the text layer and the refusals. Text is public domain or
 * invented.
 */
class PDFImportTest {
    private fun run(text: String, size: Double = 10.0) = PDFBibleReader.Run(text, size)

    // MARK: - The book's own vocabulary

    @Test fun aLineBreakInsideAWordJoinsAndOneBetweenWordsSpaces() {
        // "Pharisees" appears whole elsewhere in the text; "werecreated" never does.
        val lexicon = Lexicon(listOf(run("the Pharisees came and the Pharisees asked them\n"), run("things were created by him and all\n")))
        val resolved = lexicon.resolveLineBreaks(listOf(run("sent from the Phari\n"), run("sees to ask. All things were\n"), run("created by him\n")))
        val text = resolved.joinToString("") { it.text }
        assertTrue(text, text.contains("Pharisees to ask"))
        assertTrue(text, text.contains("were created"))
    }

    @Test fun aHyphenAtALineEndIsKeptInACompound() {
        val lexicon = Lexicon(listOf(run("a three-year-old cow and a goat\n")))
        val resolved = lexicon.resolveLineBreaks(listOf(run("a three-year-\n"), run("old ram\n")))
        assertTrue(resolved.joinToString("") { it.text }.startsWith("a three-year-old ram"))
    }

    @Test fun aHyphenTheTypesetterAddedGoes() {
        val lexicon = Lexicon(listOf(run("then the Pharisees came to him\n")))
        val resolved = lexicon.resolveLineBreaks(listOf(run("and the Phari-\n"), run("sees said\n")))
        assertTrue(resolved.joinToString("") { it.text }.startsWith("and the Pharisees said"))
    }

    @Test fun aNameThatOpensCompoundsKeepsItsHyphen() {
        val lexicon = Lexicon(listOf(run("from Beth-shemesh to Beth-horon they went to Haran and stayed\n"), run("a three-year-old and a year-old lamb\n")))
        val resolved = lexicon.resolveLineBreaks(listOf(run("as far as Beth-\n"), run("haran and\n")))
        assertTrue(resolved.joinToString("") { it.text }.startsWith("as far as Beth-haran"))
        // "Year" opens compounds too, but "lings" is no word: the typesetter broke "yearlings".
        val yearlings = lexicon.resolveLineBreaks(listOf(run("seven year-\n"), run("lings and\n")))
        assertTrue(yearlings.joinToString("") { it.text }.startsWith("seven yearlings"))
    }

    @Test fun aWordBrokenAtAnFLigatureIsRejoined() {
        val book = List(6) { run("they brought an off ering and an off ering of grain to the altar\n") } +
            run("and he gave it off the altar to them\n")
        val lexicon = Lexicon(book)
        assertEquals("an offering", lexicon.repairingLigatures("an off ering"))
        assertEquals("take it off the altar", lexicon.repairingLigatures("take it off the altar"))
    }

    // MARK: - Small rules

    @Test fun versesMustFollowOn() {
        assertTrue(PDFBibleReader.followsOn(8, 7))
        assertTrue(PDFBibleReader.followsOn(10, 7)) // a translation omits a verse
        assertTrue(PDFBibleReader.followsOn(1, 0))
        assertFalse(PDFBibleReader.followsOn(75, 31)) // "75 feet" in the text
        assertFalse(PDFBibleReader.followsOn(3, 12))
    }

    @Test fun runningHeadsAndSlugsAreFurniture() {
        assertTrue(PDFBibleReader.isRunningHead("GENESIS 2-3 2"))
        assertTrue(PDFBibleReader.isRunningHead("NUMbERS 2-3 114")) // small capitals, extracted
        assertFalse(PDFBibleReader.isRunningHead("THE CREATION"))
        assertTrue(PDFBibleReader.isPrinterSlug("Bible.indb 11 10/26/17 8:59 PM"))
        assertTrue(PDFBibleReader.isPrinterSlug("10/26/17 8:59 PM"))
        assertFalse(PDFBibleReader.isPrinterSlug("In the beginning"))
    }

    @Test fun smallCapitalsAndSoftHyphensAreTypesetting() {
        assertEquals("the LORD your GOD", PDFBibleReader.bodyText("the L\u00ADord your G\u00ADod"))
        assertEquals("confessed", PDFBibleReader.bodyText("\u00ADcon\u00ADfessed"))
    }

    @Test fun noteReferencesAndCallers() {
        assertTrue(PDFBibleReader.isReference("15:4"))
        assertTrue(PDFBibleReader.isReference("Ps 3:2 Or"))
        assertFalse(PDFBibleReader.isReference("Or created"))
        assertTrue(PDFBibleReader.isCallerLetters("a"))
        assertFalse(PDFBibleReader.isCallerLetters("and"))
    }

    // MARK: - Pages of glyphs

    /** Sets glyphs as a page would draw them: each letter half an em wide, a space a quarter. */
    private class Page {
        val glyphs = ArrayList<PdfGlyph>()

        fun text(text: String, x: Float, baseline: Float, size: Float = 10f, font: String = "Serif", wide: Set<Int> = emptySet()): Float {
            var at = x
            for ((index, c) in text.withIndex()) {
                if (c == ' ') {
                    at += size * 0.25f
                    continue
                }
                val width = if (index in wide) size * 0.62f else size * 0.5f
                glyphs.add(PdfGlyph(c.toString(), at, baseline, width, size * 0.7f, size, font))
                at += width
            }
            return at
        }
    }

    private class Pages(private val pages: List<List<PdfGlyph>>) : PdfTextSource {
        override val pageCount: Int get() = pages.size
        override val title: String? get() = null
        override fun glyphs(index: Int): List<PdfGlyph> = pages[index]
        override fun close() = Unit
    }

    /** A page of Genesis: a title, a drop cap, verse numbers in their own face, a heading, a caller and its note. */
    private fun genesis(): List<PdfGlyph> {
        val page = Page()
        page.text("GENESIS", 60f, 40f, size = 20f, font = "Sans-Bold")
        page.text("1", 40f, 88f, size = 30f, font = "Serif-Bold")
        page.text("In the beginning God created the", 62f, 70f)
        page.text("heavens and the earth.", 62f, 82f)
        var x = page.text("2", 40f, 94f, font = "Sans-Bold")
        x = page.text(" Now the earth was formless", x + 2.5f, 94f)
        page.text("a", x + 0.5f, 90f, size = 6f, font = "Sans")
        // "Lord" in small capitals: the r as wide as the o.
        x = page.text("and the ", 40f, 106f)
        x = page.text("Lord", x, 106f, wide = setOf(2))
        page.text(" was there.", x, 106f)
        page.text("The Light", 40f, 122f, size = 8.5f, font = "Sans-Semibold")
        x = page.text("3", 40f, 134f, font = "Sans-Bold")
        page.text(" Then God said, “Let there be light.”", x + 2.5f, 134f)
        // The note at the foot of the page.
        x = page.text("a", 40f, 300f, size = 4f, font = "Sans")
        x = page.text("1:2", x + 1f, 300f, size = 7f, font = "Sans-Bold")
        page.text(" Or empty", x + 1f, 300f, size = 7f, font = "Sans")
        return page.glyphs
    }

    @Test fun aPageOfGlyphsReadsAsVerses() {
        val bible = PDFBibleReader(BibleTextExtractor.Options()).extract(Pages(listOf(genesis())))
        assertEquals("In the beginning God created the heavens and the earth.", bible.verses[ref(BookID.GENESIS, 1, 1)]?.text)
        assertEquals("Now the earth was formless and the LORD was there.", bible.verses[ref(BookID.GENESIS, 1, 2)]?.text)
        assertEquals("Then God said, “Let there be light.”", bible.verses[ref(BookID.GENESIS, 1, 3)]?.text)
        val blocks = bible.blocks(ChapterRef(BookID.GENESIS, 1))
        assertTrue(blocks.any { it.heading == "The Light" })
        assertEquals(listOf("Or empty"), blocks.flatMap { it.fragments }.flatMap { it.footnotes }.map { it.text })
    }

    @Test fun twoColumnsReadLeftThenRight() {
        val page = Page()
        page.text("RUTH", 150f, 40f, size = 20f, font = "Sans-Bold")
        // The page draws row by row across both columns; the left column is read first.
        val left = listOf("1 In the days when", "the judges ruled,", "there was a famine.", "2 A man went to", "sojourn in Moab.", "3 And the man died.")
        val right = listOf("4 And they took wives", "of the women of Moab.", "5 And they both died", "also, and the woman", "was left alone. 6 Then", "she arose to return.")
        for (row in left.indices) {
            for ((column, lines) in listOf(left, right).withIndex()) {
                val x0 = if (column == 0) 40f else 190f
                val line = lines[row]
                val number = line.takeWhile { it.isDigit() }
                val y = 70f + row * 12f
                if (number.isNotEmpty()) {
                    val x = page.text(number, x0, y, font = "Sans-Bold")
                    page.text(line.drop(number.length), x + 2.5f, y)
                } else {
                    val split = line.indexOf(" 6 ")
                    if (split < 0) {
                        page.text(line, x0, y)
                    } else {
                        var x = page.text(line.substring(0, split), x0, y)
                        x = page.text("6", x + 2.5f, y, font = "Sans-Bold")
                        page.text(line.substring(split + 2), x, y)
                    }
                }
            }
        }
        val bible = PDFBibleReader(BibleTextExtractor.Options()).extract(Pages(listOf(page.glyphs)))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), bible.verseNumbers(ChapterRef(BookID.RUTH, 1)))
        assertEquals("In the days when the judges ruled, there was a famine.", bible.verses[ref(BookID.RUTH, 1, 1)]?.text)
        assertEquals("And they both died also, and the woman was left alone.", bible.verses[ref(BookID.RUTH, 1, 5)]?.text)
    }

    // MARK: - Real PDFs

    private fun pdf(named: String, protection: StandardProtectionPolicy? = null): File {
        val file = File(ImportFixtures.scratchDirectory(), named)
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                fun line(text: String, font: PDType1Font, size: Float, x: Float, y: Float) {
                    stream.beginText()
                    stream.setFont(font, size)
                    stream.newLineAtOffset(x, y)
                    stream.showText(text)
                    stream.endText()
                }
                line("GENESIS", PDType1Font.HELVETICA_BOLD, 20f, 60f, 740f)
                line("1", PDType1Font.TIMES_BOLD, 30f, 40f, 680f)
                // The drop cap spans the first two lines, its top level with the first.
                line("In the beginning God created the", PDType1Font.TIMES_ROMAN, 10f, 62f, 692f)
                line("heavens and the earth.", PDType1Font.TIMES_ROMAN, 10f, 62f, 680f)
                line("2", PDType1Font.HELVETICA_BOLD, 10f, 40f, 668f)
                line("And the earth was waste and void.", PDType1Font.TIMES_ROMAN, 10f, 48f, 668f)
                line("3", PDType1Font.HELVETICA_BOLD, 10f, 40f, 656f)
                line("And God said, Let there be light.", PDType1Font.TIMES_ROMAN, 10f, 48f, 656f)
            }
            if (protection != null) document.protect(protection)
            document.save(file)
        }
        return file
    }

    @Test fun aRealPdfReadsThroughTheImporter() {
        val (bible, preview) = ImportFixtures.importer().read(pdf("genesis.pdf"))
        assertEquals(ImportedFileFormat.PDF, preview.format)
        assertEquals(1, preview.documentCount)
        assertEquals("In the beginning God created the heavens and the earth.", bible.verses[ref(BookID.GENESIS, 1, 1)]?.text)
        assertEquals("And God said, Let there be light.", bible.verses[ref(BookID.GENESIS, 1, 3)]?.text)
    }

    @Test fun aPasswordLockedPdfIsRefused() {
        val policy = StandardProtectionPolicy("owner", "user", AccessPermission())
        assertThrowsImport<BibleImportError.ProtectedByDRM>(BibleImportError.ProtectedByDRM(DRMEvidence.PDF_PASSWORD)) {
            ImportFixtures.importer().preview(pdf("locked.pdf", policy))
        }
    }

    @Test fun aPdfWhoseOwnerForbidsCopyingIsRefused() {
        val permissions = AccessPermission().apply { setCanExtractContent(false) }
        val policy = StandardProtectionPolicy("owner", "", permissions)
        assertThrowsImport<BibleImportError.ProtectedByDRM>(BibleImportError.ProtectedByDRM(DRMEvidence.PDF_COPY_PROTECTED)) {
            ImportFixtures.importer().read(pdf("no-copying.pdf", policy))
        }
    }

    @Test fun aPdfIsKnownByItsContentNotItsName() {
        assertTrue(BibleFileImporter.isPDF(pdf("named.zip")))
        assertFalse(BibleFileImporter.isPDF(ImportFixtures.write(ImportFixtures.zip(listOf(ImportFixtures.ZipEntry("a.txt", "x"))), "a.pdf")))
    }

    @Test fun aVersePrintedWithoutItsNumberAfterAnOmittedVerseIsRecovered() {
        // An omitted verse printed only as a footnote marker, the next verse's words running on
        // after it with no number.
        val bible = ExtractedBible()
        val chapter = ChapterRef(BookID.ACTS, 24)
        val six = "He even tried to desecrate the temple, and so we apprehended him. By examining him yourself you will be able to discern the truth."
        val marker = SwiftText.scalarCount("He even tried to desecrate the temple, and so we apprehended him.")
        bible.append(
            ExtractedBlock(
                ExtractedBlock.Kind.PARAGRAPH,
                fragments = listOf(
                    ExtractedFragment(6, true, six, footnotes = listOf(ExtractedFootnote(marker, "Other mss add verse 7"))),
                    ExtractedFragment(9, true, "The Jews also joined in the attack."),
                ),
            ),
            chapter,
        )
        bible.appendVerseText(six, emptyList(), ref(BookID.ACTS, 24, 6))
        bible.appendVerseText("The Jews also joined in the attack.", emptyList(), ref(BookID.ACTS, 24, 9))

        bible.recoverVersesAfterOmissions()

        assertEquals("He even tried to desecrate the temple, and so we apprehended him.", bible.verses[ref(BookID.ACTS, 24, 6)]?.text)
        assertEquals("By examining him yourself you will be able to discern the truth.", bible.verses[ref(BookID.ACTS, 24, 8)]?.text)
        assertNull(bible.verses[ref(BookID.ACTS, 24, 7)])
        val fragments = bible.blocks(chapter).flatMap { it.fragments }
        assertEquals(listOf(6, 8, 9), fragments.map { it.verse })
        assertTrue(fragments[1].numbered)
    }

    @Test fun aFootnoteMidSentenceIsNotTakenForAMissingVerse() {
        val bible = ExtractedBible()
        val chapter = ChapterRef(BookID.ACTS, 24)
        val six = "He even tried to desecrate the temple and so we apprehended him before he could flee."
        bible.append(
            ExtractedBlock(
                ExtractedBlock.Kind.PARAGRAPH,
                fragments = listOf(ExtractedFragment(6, true, six, footnotes = listOf(ExtractedFootnote(27, "Or profane")))),
            ),
            chapter,
        )
        bible.appendVerseText(six, emptyList(), ref(BookID.ACTS, 24, 6))
        bible.appendVerseText("The Jews also joined in.", emptyList(), ref(BookID.ACTS, 24, 9))

        bible.recoverVersesAfterOmissions()

        assertNull(bible.verses[ref(BookID.ACTS, 24, 8)])
        assertEquals(six, bible.verses[ref(BookID.ACTS, 24, 6)]?.text)
    }
}
