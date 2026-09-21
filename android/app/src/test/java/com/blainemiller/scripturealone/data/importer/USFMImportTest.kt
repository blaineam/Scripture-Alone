package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The USFM front-end — the shape eBible.org publishes. Ported one-for-one from `USFMImportTests.swift`.
 * Every fixture is ASV (1901, public domain) or invented for the test.
 */
class USFMImportTest {
    companion object {
        val genesis = """
            \id GEN Antique Standard Bible
            \h Genesis
            \toc2 Genesis
            \mt1 The First Book of Moses
            \c 1
            \s1 The Creation
            \p
            \v 1 In the beginning God created the heavens and the earth.
            \v 2 And the earth was waste and void; and darkness was upon the face of the deep:
            and the Spirit of God moved upon the face of the waters.
            \s2 The First Day
            \q1
            \v 3 And God said, Let there be light:\f + \fr 1:3 \ft Or \fqa Let light be\f* and there was light.
            \q2
            \v 4 And God saw the light, that it was good.
            \b
            \m
            \v 5 And God called the light Day.
        """.trimIndent()

        val psalm23 = """
            \id PSA
            \c 23
            \d A Psalm of David.
            \q1
            \v 1 \nd Jehovah\nd* is my shepherd; I shall \add not\add* want.
            \q2
            \v 2 He \w maketh|strong="H7257"\w* me to lie down in green pastures.
            \q1
            \v 3 He restoreth my soul:\x + \xo 23:3 \xt Ps 19:7\x* he guideth me in the paths of righteousness.
        """.trimIndent()

        val matthew5 = """
            \id MAT
            \c 5
            \p
            \v 1 And seeing the multitudes, he went up into the mountain.
            \p
            \v 3 \wj Blessed are the poor in spirit:
            \v 4 Blessed are they that mourn:\wj*
            \v 5 Blessed are the meek.
        """.trimIndent()
    }

    private fun extract(books: List<String>): ExtractedBible =
        USFMImporter().extract(books.mapIndexed { index, usfm -> "$index.usfm" to usfm })

    private fun fragments(bible: ExtractedBible, chapter: ChapterRef) = bible.blocks(chapter).flatMap { it.fragments }

    // MARK: - Markers

    @Test fun readsChaptersVersesAndParagraphs() {
        val bible = extract(listOf(genesis))
        assertEquals(listOf(BookID.GENESIS), bible.books)
        assertEquals(listOf(1, 2, 3, 4, 5), bible.verseNumbers(ChapterRef(BookID.GENESIS, 1)))
        assertEquals("In the beginning God created the heavens and the earth.", bible.verses[ref(BookID.GENESIS, 1, 1)]?.text)
        // A verse wrapped across source lines is one row, joined with a single space.
        assertEquals(
            "And the earth was waste and void; and darkness was upon the face of the deep: " +
                "and the Spirit of God moved upon the face of the waters.",
            bible.verses[ref(BookID.GENESIS, 1, 2)]?.text,
        )
        assertEquals(VerseMarkupShape.USFM_MARKERS, bible.shapesByDocument["0.usfm"])
        // File metadata (\id, \h, \toc2, \mt1) never reaches the text.
        assertFalse(bible.verses.values.any { it.text.contains("First Book of Moses") })
    }

    @Test fun readsSectionHeadingsAndPoetryLevels() {
        val bible = extract(listOf(genesis))
        val blocks = bible.blocks(ChapterRef(BookID.GENESIS, 1))
        assertEquals(ExtractedBlock.Kind.HEADING, blocks.first().kind)
        assertEquals("The Creation", blocks.first().heading)
        assertTrue(blocks.any { it.kind == ExtractedBlock.Kind.SUBHEADING && it.heading == "The First Day" })
        assertTrue(blocks.any { it.kind == ExtractedBlock.Kind.POETRY1 })
        assertTrue(blocks.any { it.kind == ExtractedBlock.Kind.POETRY2 })
        assertTrue(blocks.any { it.kind == ExtractedBlock.Kind.STANZA_BREAK })
        assertTrue(blocks.any { it.kind == ExtractedBlock.Kind.CONTINUATION })
    }

    @Test fun keepsFootnotesAttachedToTheirVerse() {
        val bible = extract(listOf(genesis))
        val fragment = required(fragments(bible, ChapterRef(BookID.GENESIS, 1)).firstOrNull { it.verse == 3 })
        val note = required(fragment.footnotes.firstOrNull())
        // \fr (the reference) and the caller are dropped; \ft and \fqa are kept.
        assertEquals("Or Let light be", note.text)
        assertEquals(SwiftText.scalarCount("And God said, Let there be light:"), note.position)
        assertEquals("And God said, Let there be light: and there was light.", bible.verses[ref(BookID.GENESIS, 1, 3)]?.text)
    }

    @Test fun dropsCrossReferencesWithoutLeakingTheirText() {
        val bible = extract(listOf(psalm23))
        val verse = required(bible.verses[ref(BookID.PSALMS, 23, 3)])
        assertEquals("He restoreth my soul: he guideth me in the paths of righteousness.", verse.text)
        val fragment = required(fragments(bible, ChapterRef(BookID.PSALMS, 23)).firstOrNull { it.verse == 3 })
        assertTrue(fragment.footnotes.isEmpty())
    }

    @Test fun stripsCharacterMarkupButKeepsItsStyle() {
        val bible = extract(listOf(psalm23))
        assertEquals("Jehovah is my shepherd; I shall not want.", bible.verses[ref(BookID.PSALMS, 23, 1)]?.text)
        // \w ... |strong="…" must not leak its attributes.
        assertEquals("He maketh me to lie down in green pastures.", bible.verses[ref(BookID.PSALMS, 23, 2)]?.text)
        val fragment = required(fragments(bible, ChapterRef(BookID.PSALMS, 23)).firstOrNull { it.verse == 1 })
        val scalars = fragment.text.codePoints().toArray()
        fun styled(style: StyledSpan.Style): List<String> = fragment.spans.filter { it.style == style }.map {
            String(scalars, it.start, it.length)
        }
        assertEquals(listOf("Jehovah"), styled(StyledSpan.Style.SMALL_CAPS))
        assertEquals(listOf("not"), styled(StyledSpan.Style.SUPPLIED))
    }

    @Test fun psalmTitlesAreUnnumbered() {
        val bible = extract(listOf(psalm23))
        val title = required(bible.blocks(ChapterRef(BookID.PSALMS, 23)).firstOrNull { it.kind == ExtractedBlock.Kind.TITLE })
        assertEquals(listOf("A Psalm of David."), title.fragments.map { it.text })
        assertTrue(title.fragments.all { !it.numbered })
        // The superscription is not verse 1's text.
        assertEquals(false, bible.verses[ref(BookID.PSALMS, 23, 1)]?.text?.contains("A Psalm of David"))
        // …and verse 1 still prints its number, on the line that follows.
        val lines = bible.blocks(ChapterRef(BookID.PSALMS, 23)).filter { it.kind != ExtractedBlock.Kind.TITLE }
        val first = required(lines.flatMap { it.fragments }.firstOrNull { it.verse == 1 })
        assertTrue(first.numbered)
    }

    @Test fun redLettersRunAcrossAVerseBoundary() {
        val bible = extract(listOf(matthew5))
        for (verse in listOf(3, 4)) {
            val row = required(bible.verses[ref(BookID.MATTHEW, 5, verse)])
            val span = required("verse $verse lost its red letters", row.red.firstOrNull())
            assertEquals(0, span.start)
            assertEquals(SwiftText.scalarCount(row.text), span.length)
        }
        // The style closes with \wj*, so verse 5 is not red.
        assertEquals(true, bible.verses[ref(BookID.MATTHEW, 5, 5)]?.red?.isEmpty())
        assertEquals(true, bible.verses[ref(BookID.MATTHEW, 5, 1)]?.red?.isEmpty())
    }

    @Test fun redLettersCanBeTurnedOff() {
        val bible = USFMImporter(BibleTextExtractor.Options(redLetters = false)).extract(listOf("mat.usfm" to matthew5))
        assertEquals("Blessed are the poor in spirit:", bible.verses[ref(BookID.MATTHEW, 5, 3)]?.text)
        assertEquals(true, bible.verses[ref(BookID.MATTHEW, 5, 3)]?.red?.isEmpty())
    }

    // MARK: - Verse numbering decisions

    @Test fun aBridgedVerseIsStoredUnderItsFirstNumber() {
        val bible = extract(
            listOf(
                """
                \id JON
                \c 1
                \p
                \v 1-2 Now the word of Jehovah came unto Jonah, saying, Arise, go to Nineveh.
                \v 3 But Jonah rose up to flee.
                """.trimIndent(),
            ),
        )
        assertEquals(true, bible.verses[ref(BookID.JONAH, 1, 1)]?.text?.startsWith("Now the word of Jehovah"))
        assertNull(bible.verses[ref(BookID.JONAH, 1, 2)])
        assertEquals(ref(BookID.JONAH, 1, 1), bible.bridgedVerses[ref(BookID.JONAH, 1, 2)])
        // A combined verse is not a gap.
        val report = ImportCoverageReport(bible)
        assertEquals(true, report.books.firstOrNull()?.chaptersWithGaps?.isEmpty())
        assertTrue(report.notes.any { it.message.contains("printed combined") })
    }

    @Test fun aPartialVerseKeepsOneRowAndOneNumber() {
        val bible = extract(
            listOf(
                """
                \id JOL
                \c 2
                \p
                \v 28a And it shall come to pass afterward,
                \v 28b that I will pour out my Spirit upon all flesh.
                """.trimIndent(),
            ),
        )
        assertEquals(
            "And it shall come to pass afterward, that I will pour out my Spirit upon all flesh.",
            bible.verses[ref(BookID.JOEL, 2, 28)]?.text,
        )
        val fragments = fragments(bible, ChapterRef(BookID.JOEL, 2)).filter { it.verse == 28 }
        assertEquals(2, fragments.size)
        assertEquals(1, fragments.count { it.numbered })
    }

    @Test fun reportsVersesThatRanBackwards() {
        val bible = extract(
            listOf(
                """
                \id OBA
                \c 1
                \p
                \v 1 The vision of Obadiah.
                \v 3 The pride of thy heart hath deceived thee.
                \v 2 Behold, I have made thee small among the nations.
                """.trimIndent(),
            ),
        )
        assertTrue(ChapterRef(BookID.OBADIAH, 1) in bible.outOfOrderChapters)
        val report = ImportCoverageReport(bible)
        assertTrue(report.problems.any { it.contains("out of order") })
    }

    // MARK: - Books

    @Test fun readsAMultiBookZipInCanonicalOrder() {
        val data = ImportFixtures.usfmZip(
            listOf(
                "70-JHNasb.usfm" to "\\id JHN\n\\c 1\n\\p\n\\v 1 In the beginning was the Word.\n",
                "01-GENasb.usfm" to genesis,
                "19-PSAasb.usfm" to psalm23,
            ),
        )
        val pkg = USFMPackage(data)
        assertEquals(3, pkg.files.size)
        val bible = USFMImporter().extract(pkg)
        assertEquals(listOf(BookID.GENESIS, BookID.PSALMS, BookID.JOHN), bible.books)
        assertEquals(ChapterRef(BookID.GENESIS, 1), bible.chapterOrder.firstOrNull())
        assertEquals("In the beginning was the Word.", bible.verses[ref(BookID.JOHN, 1, 1)]?.text)
    }

    @Test fun reportsAnUnknownBookCodeRatherThanGuessing() {
        val bible = extract(
            listOf(
                "\\id ZZZ Some Apocryphal Book\n\\c 1\n\\p\n\\v 1 Invented text.\n",
                "\\id GEN\n\\c 1\n\\p\n\\v 1 In the beginning God created.\n",
            ),
        )
        assertEquals(listOf(BookID.GENESIS), bible.books)
        assertTrue(bible.notes.any { it.message.contains("unknown book code “ZZZ”") })
        assertEquals(1, bible.verses.size)
    }

    @Test fun mapsEveryCanonicalCode() {
        for (book in BookID.entries) {
            assertEquals("${book.code} did not map back to ${book.displayName}", book, USFMBookParser.book(book.code))
        }
        assertEquals("1JN", USFMBookParser.bookCode("\\id 1JN - Antique\n\\c 1\n"))
        assertEquals(BookID.FIRST_JOHN, USFMBookParser.book("1JN"))
    }

    @Test fun failsCleanlyWhenNothingIsScripture() {
        val data = ImportFixtures.usfmZip(listOf("notes.usfm" to "\\id ZZZ\n\\c 1\n\\p\n\\v 1 Nothing canonical.\n"))
        assertThrowsImport<BibleImportError.NoScriptureFound>(BibleImportError.NoScriptureFound()) {
            USFMImporter().extract(USFMPackage(data))
        }
    }

    @Test fun refusesAProtectedUSFMArchive() {
        val entries = listOf(
            ImportFixtures.ZipEntry("01-GEN.usfm", genesis),
            ImportFixtures.ZipEntry("META-INF/encryption.xml", "<encryption/>"),
        )
        val data = ImportFixtures.zip(entries)
        assertThrowsImport<BibleImportError.ProtectedByDRM>(BibleImportError.ProtectedByDRM(DRMEvidence.ENCRYPTION_MANIFEST)) {
            USFMPackage(data)
        }
    }

    // MARK: - Metadata

    @Test fun carriesTheCopyrightPageIntoTheIdentity() {
        val data = ImportFixtures.usfmZip(
            listOf("01-GEN.usfm" to genesis),
            copyright = "Copyright © 2026 Example Press. Released into the Public Domain.",
        )
        val pkg = USFMPackage(data)
        assertEquals("Copyright © 2026 Example Press. Released into the Public Domain.", pkg.metadata.copyright)
        assertEquals("Public domain", pkg.metadata.license)
        val identity = ImportedTranslationIdentity.suggested(pkg.metadata)
        assertEquals("Copyright © 2026 Example Press. Released into the Public Domain.", identity.copyright)
        assertEquals("Public domain", identity.license)
        assertEquals("Imported USFM", identity.source)
    }

    @Test fun readsDigitalBibleLibraryMetadata() {
        val metadataXML = """
            <?xml version="1.0" encoding="utf-8"?>
            <DBLMetadata id="engasb" type="text">
              <identification>
                <name>Antique Standard Bible</name>
                <abbreviation>asb</abbreviation>
              </identification>
              <language><iso>eng</iso></language>
              <copyright><fullStatement><statementContent type="xhtml">
                <p>Public Domain</p>
              </statementContent></fullStatement></copyright>
            </DBLMetadata>
        """.trimIndent()
        val data = ImportFixtures.usfmZip(listOf("01-GEN.usfm" to genesis), copyright = null, metadataXML = metadataXML)
        val pkg = USFMPackage(data)
        assertEquals("Antique Standard Bible", pkg.metadata.title)
        assertEquals("asb", pkg.metadata.abbreviation)
        assertEquals("eng", pkg.metadata.language)
        assertEquals("Public Domain", pkg.metadata.copyright)
        val identity = ImportedTranslationIdentity.suggested(pkg.metadata)
        assertEquals("Antique Standard Bible", identity.name)
        assertEquals("ASB", identity.abbreviation)
        assertEquals("Public domain", identity.license)
    }

    @Test fun aLicenceLineIsRequiredBeforeAnythingIsWritten() {
        val data = ImportFixtures.usfmZip(listOf("01-GEN.usfm" to genesis), copyright = null)
        val file = ImportFixtures.write(data, "translation_usfm.zip")
        val importer = ImportFixtures.importer()
        assertFalse(importer.preview(file).hasCopyright)
        assertThrowsImport<BibleImportError.MissingCopyright>(BibleImportError.MissingCopyright()) {
            importer.importBible(file, directory = ImportFixtures.scratchDirectory())
        }
    }

    // MARK: - Coverage

    @Test fun reportsPartialCoverage() {
        val bible = extract(
            listOf(
                """
                \id GEN
                \c 1
                \p
                \v 1 One.
                \v 2 Two.
                \c 4
                \p
                \v 2 Two.
                """.trimIndent(),
            ),
        )
        val report = ImportCoverageReport(bible)
        val genesis = required(report.books.firstOrNull())
        assertEquals(2, genesis.chaptersFound)
        assertEquals(50, genesis.chaptersExpected)
        assertEquals(listOf(2, 3, 5), genesis.missingChapters.take(3))
        val gap = required(genesis.chaptersWithGaps.firstOrNull { it.chapter == 4 })
        assertEquals(listOf(1), gap.missingVerses)
        assertTrue(report.summary.contains("3 verses"))
        assertTrue(report.markupShapes.values.all { it == VerseMarkupShape.USFM_MARKERS })
    }

    // MARK: - One entry point, end to end

    @Test fun importsAUSFMZipEndToEnd() {
        val data = ImportFixtures.usfmZip(
            listOf(
                "01-GEN.usfm" to genesis,
                "19-PSA.usfm" to psalm23,
                "40-MAT.usfm" to matthew5,
            ),
        )
        val file = ImportFixtures.write(data, "engasb_usfm.zip")
        val importer = ImportFixtures.importer()
        val preview = importer.preview(file)
        assertEquals(ImportedFileFormat.USFM_ZIP, preview.format)
        assertEquals(3, preview.documentCount)
        assertTrue(preview.hasCopyright)

        val result = importer.importBible(file, directory = ImportFixtures.scratchDirectory())
        assertEquals(ImportedFileFormat.USFM_ZIP, result.format)
        assertEquals(12, result.report.totalVerses) // Genesis 5 + Psalm 3 + Matthew 4
        assertEquals(listOf(BookID.GENESIS, BookID.PSALMS, BookID.MATTHEW), result.report.booksFound)

        ImportedStoreReader(result.storeFile).use { store ->
            assertTrue(store.meta.getValue("copyright").contains("Example Press"))
            assertEquals("Public domain", store.meta["license"])
            assertEquals(5, store.verseCount(ChapterRef(BookID.GENESIS, 1)))
            assertEquals("Jehovah is my shepherd; I shall not want.", store.verses(ref(BookID.PSALMS, 23, 1)).firstOrNull()?.text)

            // The layout the reader draws: heading, poetry levels, red letters, footnotes.
            val layout = store.layout(ChapterRef(BookID.GENESIS, 1))
            assertEquals(ChapterLayout.Kind.HEADING, layout.blocks.firstOrNull()?.kind)
            assertTrue(layout.blocks.any { it.kind == ChapterLayout.Kind.POETRY1 })
            assertTrue(layout.blocks.any { it.kind == ChapterLayout.Kind.STANZA_BREAK })
            val footnote = required(layout.blocks.flatMap { it.fragments }.flatMap { it.footnotes }.firstOrNull())
            assertEquals("Or Let light be", footnote.text)

            val psalm = store.layout(ChapterRef(BookID.PSALMS, 23))
            val smallCaps = psalm.blocks.flatMap { it.fragments }.flatMap { it.spans }
                .filter { it.style == ChapterLayout.Span.Style.SMALL_CAPS }
            assertEquals(1, smallCaps.size)

            val matthew = store.verses(ref(BookID.MATTHEW, 5, 3), ref(BookID.MATTHEW, 5, 4))
            assertTrue(matthew.all { it.red.isNotEmpty() })
            assertEquals(listOf(ref(BookID.PSALMS, 23, 1)), store.search("shepherd"))
        }
    }

    /**
     * The entry point decides by structure, so a USFM zip and an ePub can be handed to the same call
     * without the UI sniffing anything.
     */
    @Test fun tellsTheTwoFormatsApart() {
        val usfm = ImportFixtures.write(ImportFixtures.usfmZip(listOf("01-GEN.usfm" to genesis)), "anything.zip")
        val epub = ImportFixtures.write(
            ImportFixtures.epub(
                listOf(ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>")),
            ),
            "anything-else.zip",
        )
        assertEquals(ImportedFileFormat.USFM_ZIP, ImportFixtures.importer().preview(usfm).format)
        assertEquals(ImportedFileFormat.EPUB, ImportFixtures.importer().preview(epub).format)

        val neither = ImportFixtures.write(ImportFixtures.zip(listOf(ImportFixtures.ZipEntry("a.txt", "hello"))), "neither.zip")
        assertThrowsImport<BibleImportError.UnsupportedFormat>(
            BibleImportError.UnsupportedFormat("it is neither an ePub nor a set of USFM books"),
        ) {
            ImportFixtures.importer().preview(neither)
        }
    }
}
