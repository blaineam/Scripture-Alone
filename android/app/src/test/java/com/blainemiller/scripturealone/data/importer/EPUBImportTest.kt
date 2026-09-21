package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.layout.utf16Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * The ePub front-end. Ported one-for-one from `EPUBImportTests.swift`; Swift's parameterised tests
 * run each argument inside one test here. Fixtures are ASV (public domain) or invented — no
 * copyrighted text.
 */
class EPUBImportTest {
    // MARK: - Package

    @Test fun readsContainerManifestSpineAndDublinCore() {
        val data = ImportFixtures.epub(
            listOf(
                ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"),
                ImportFixtures.Document("gen02.xhtml", "<h1>Genesis 2</h1><p><sup>1</sup>And the heavens were finished.</p>"),
            ),
        )
        val pkg = EPUBPackage(data)
        assertEquals("OEBPS/content.opf", pkg.packagePath)
        assertEquals("Antique Standard Bible", pkg.metadata.title)
        assertEquals("A Committee", pkg.metadata.creator)
        assertEquals("Example Press", pkg.metadata.publisher)
        assertEquals("en", pkg.metadata.language)
        assertEquals("urn:isbn:9780000000001", pkg.metadata.identifier)
        assertEquals(true, pkg.metadata.rights?.startsWith("Text is in the public domain."))
        assertEquals(2, pkg.manifest.size)
        assertEquals(listOf("OEBPS/gen01.xhtml", "OEBPS/gen02.xhtml"), pkg.spine.map { it.path })
        assertTrue(pkg.document(pkg.spine[0]).contains("In the beginning"))
    }

    @Test fun readsDeflatedEntries() {
        val data = ImportFixtures.epub(
            listOf(ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning God created.</p>")),
            deflate = true,
        )
        val pkg = EPUBPackage(data)
        assertTrue(pkg.document(pkg.spine[0]).contains("In the beginning God created"))
    }

    @Test fun refusesAMalformedZip() {
        val data = ImportFixtures.epub(listOf(ImportFixtures.Document("gen01.xhtml", "<p><sup>1</sup>Text.</p>")))
        // Clobber the end-of-central-directory signature.
        for (offset in (data.size - 22) until (data.size - 18)) data[offset] = 0
        assertThrowsImport<BibleImportError.NotAZipArchive>(BibleImportError.NotAZipArchive()) { EPUBPackage(data) }
    }

    @Test fun refusesAZipThatIsNotAnEPUB() {
        val data = ImportFixtures.zip(listOf(ImportFixtures.ZipEntry("notes.txt", "just some notes")))
        assertThrowsImport<BibleImportError> { EPUBPackage(data) }
        assertThrowsImport<BibleImportError> { ImportFixtures.importer().preview(ImportFixtures.write(data, "x.zip")) }
    }

    @Test fun detectsACorruptedEntry() {
        val data = ImportFixtures.epub(
            listOf(ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>")),
        )
        // Flip a byte inside the last entry's stored payload; the CRC must catch it.
        val target = data.size - 300
        data[target] = (data[target].toInt() xor 0xFF).toByte()
        assertThrowsImport<BibleImportError> {
            val pkg = EPUBPackage(data)
            BibleTextExtractor().extract(pkg)
        }
    }

    // MARK: - DRM refusal

    /** Swift: `@Test(arguments:)` over the four protection artifacts. */
    @Test fun refusesProtectedFiles() {
        val cases = listOf(
            "META-INF/encryption.xml" to DRMEvidence.ENCRYPTION_MANIFEST,
            "META-INF/rights.xml" to DRMEvidence.ADOBE_ADEPT,
            "META-INF/license.lcpl" to DRMEvidence.READIUM_LCP,
            "META-INF/sinf.xml" to DRMEvidence.APPLE_FAIRPLAY,
        )
        for ((name, evidence) in cases) {
            val data = ImportFixtures.epub(
                listOf(ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>")),
                extra = listOf(ImportFixtures.ZipEntry(name, "<encryption/>")),
            )
            val expected = BibleImportError.ProtectedByDRM(evidence)
            assertThrowsImport<BibleImportError.ProtectedByDRM>(expected) { EPUBPackage(data) }
            val file = ImportFixtures.write(data, "protected.epub")
            assertThrowsImport<BibleImportError.ProtectedByDRM>(expected) { ImportFixtures.importer().preview(file) }
            val directory = ImportFixtures.scratchDirectory()
            assertThrowsImport<BibleImportError.ProtectedByDRM>(expected) {
                ImportFixtures.importer().importBible(file, directory = directory)
            }
            // Refused, never partially imported: nothing was written.
            assertEquals(emptyList<String>(), directory.list()?.toList())
        }
    }

    /**
     * The refusal has to happen before anything is read. This ePub's package document is garbage: if
     * the engine had opened it first, the error would name the package, not the protection.
     */
    @Test fun refusesProtectedFilesBeforeReadingAnyContent() {
        val data = ImportFixtures.epub(
            listOf(ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>")),
            extra = listOf(ImportFixtures.ZipEntry("META-INF/encryption.xml", "<encryption/>")),
            opfOverride = "this is not xml at all <<<>>>",
        )
        assertThrowsImport<BibleImportError.ProtectedByDRM>(BibleImportError.ProtectedByDRM(DRMEvidence.ENCRYPTION_MANIFEST)) {
            EPUBPackage(data)
        }
    }

    /** Adobe announces itself in the package metadata even when `rights.xml` has been stripped. */
    @Test fun refusesAdeptMetadataInThePackageDocument() {
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Protected</dc:title>
                <meta name="Adept.expected.resource" content="urn:uuid:0000"/>
              </metadata>
              <manifest><item id="d0" href="gen01.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="d0"/></spine>
            </package>
        """.trimIndent()
        val data = ImportFixtures.epub(listOf(ImportFixtures.Document("gen01.xhtml", "<p>x</p>")), opfOverride = opf)
        assertThrowsImport<BibleImportError.ProtectedByDRM>(BibleImportError.ProtectedByDRM(DRMEvidence.ADOBE_ADEPT)) {
            EPUBPackage(data)
        }
    }

    @Test fun aWatermarkIsNotDRM() {
        val data = ImportFixtures.epub(
            listOf(
                ImportFixtures.Document(
                    "gen01.xhtml",
                    """
                    <p class="watermark">Licensed to reader@example.com — not for redistribution.</p>
                    <h1>Genesis 1</h1><p><sup>1</sup>In the beginning God created the heavens and the earth.</p>
                    """.trimIndent(),
                ),
            ),
        )
        val bible = BibleTextExtractor().extract(EPUBPackage(data))
        assertEquals(true, bible.verses[ref(BookID.GENESIS, 1, 1)]?.text?.startsWith("In the beginning"))
    }

    // MARK: - Verse markup shapes

    @Test fun readsSuperscriptVerseNumbers() {
        val bible = extract(
            "gen01.xhtml",
            """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>In the beginning God created the heavens and the earth.
            <sup>2</sup>And the earth was waste and void.</p>
            <h2>The First Day</h2>
            <p><sup>3</sup>And God said, Let there be light: and there was light.</p>
            """.trimIndent(),
        )
        assertEquals(VerseMarkupShape.SUPERSCRIPT, bible.shapesByDocument["OEBPS/gen01.xhtml"])
        assertEquals("In the beginning God created the heavens and the earth.", bible.verses[ref(BookID.GENESIS, 1, 1)]?.text)
        assertEquals("And the earth was waste and void.", bible.verses[ref(BookID.GENESIS, 1, 2)]?.text)
        assertEquals("And God said, Let there be light: and there was light.", bible.verses[ref(BookID.GENESIS, 1, 3)]?.text)
        val headings = bible.blocks(ChapterRef(BookID.GENESIS, 1)).filter { it.kind.isHeading }
        assertEquals(listOf("The First Day"), headings.map { it.heading })
    }

    @Test fun readsVerseNumberClasses() {
        val bible = extract(
            "mat05.xhtml",
            """
            <h1>Matthew 5</h1>
            <p><span class="verse-num">1</span>And seeing the multitudes, he went up into the mountain.
            <span class="vnum">2</span>And he opened his mouth and taught them, saying,</p>
            <p><span class="v-num">3</span>Blessed are the poor in spirit.</p>
            """.trimIndent(),
        )
        assertEquals(VerseMarkupShape.NUMBER_CLASS, bible.shapesByDocument["OEBPS/mat05.xhtml"])
        assertEquals(true, bible.verses[ref(BookID.MATTHEW, 5, 1)]?.text?.startsWith("And seeing the multitudes"))
        assertEquals(true, bible.verses[ref(BookID.MATTHEW, 5, 2)]?.text?.startsWith("And he opened his mouth"))
        assertEquals("Blessed are the poor in spirit.", bible.verses[ref(BookID.MATTHEW, 5, 3)]?.text)
        // The numbers themselves must never leak into the text.
        assertEquals(false, bible.verses[ref(BookID.MATTHEW, 5, 1)]?.text?.contains("1"))
    }

    /**
     * `class="verse"` is used for both the number and the whole verse. Which one it is has to be
     * decided from the content, not from the name.
     */
    @Test fun tellsANumberedVerseSpanFromAVerseContainer() {
        val numbered = extract(
            "mrk01.xhtml",
            """
            <h1>Mark 1</h1>
            <p><span class="verse">1</span>The beginning of the gospel of Jesus Christ.</p>
            """.trimIndent(),
        )
        assertEquals("The beginning of the gospel of Jesus Christ.", numbered.verses[ref(BookID.MARK, 1, 1)]?.text)

        val container = extract(
            "mrk02.xhtml",
            """
            <h1>Mark 2</h1>
            <p><span class="verse" id="Mrk.2.1">And when he entered again into Capernaum.</span>
            <span class="verse" id="Mrk.2.2">And many were gathered together.</span></p>
            """.trimIndent(),
        )
        assertEquals(VerseMarkupShape.REFERENCE_IDENTIFIER, container.shapesByDocument["OEBPS/mrk02.xhtml"])
        assertEquals("And when he entered again into Capernaum.", container.verses[ref(BookID.MARK, 2, 1)]?.text)
        assertEquals("And many were gathered together.", container.verses[ref(BookID.MARK, 2, 2)]?.text)
    }

    @Test fun readsChapterRelativeVerseAnchors() {
        val bible = extract(
            "jhn03.xhtml",
            """
            <h1>John 3</h1>
            <p><a id="v1"></a>Now there was a man of the Pharisees, named Nicodemus.
            <a id="v2"></a>The same came unto him by night.</p>
            """.trimIndent(),
        )
        assertEquals(VerseMarkupShape.VERSE_ANCHOR, bible.shapesByDocument["OEBPS/jhn03.xhtml"])
        assertEquals(true, bible.verses[ref(BookID.JOHN, 3, 1)]?.text?.startsWith("Now there was a man"))
        assertEquals("The same came unto him by night.", bible.verses[ref(BookID.JOHN, 3, 2)]?.text)
    }

    /** Swift: `@Test(arguments:)` over four id spellings. */
    @Test fun readsReferenceIdentifiers() {
        for (identifier in listOf("ESV_Rom.8.1", "csb-Rom-8-1", "ROM.8.1", "Rom_8_1")) {
            val bible = extract("book.xhtml", """<p><span id="$identifier">There is therefore now no condemnation.</span></p>""")
            assertEquals(identifier, VerseMarkupShape.REFERENCE_IDENTIFIER, bible.shapesByDocument["OEBPS/book.xhtml"])
            assertEquals(identifier, "There is therefore now no condemnation.", bible.verses[ref(BookID.ROMANS, 8, 1)]?.text)
        }
    }

    @Test fun readsOrdinalReferenceIdentifiers() {
        val bible = extract("cor.xhtml", """<p><span id="ESV_1Cor.13.4">Love suffereth long, and is kind.</span></p>""")
        assertEquals("Love suffereth long, and is kind.", bible.verses[ref(BookID.FIRST_CORINTHIANS, 13, 4)]?.text)
    }

    /** Several verses sharing one paragraph — the normal shape for prose. */
    @Test fun splitsRunTogetherParagraphs() {
        val bible = extract(
            "gen01.xhtml",
            """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>First. <sup>2</sup>Second. <sup>3</sup>Third. <sup>4</sup>Fourth.</p>
            """.trimIndent(),
        )
        assertEquals(listOf(1, 2, 3, 4), bible.verseNumbers(ChapterRef(BookID.GENESIS, 1)))
        assertEquals("Second.", bible.verses[ref(BookID.GENESIS, 1, 2)]?.text)
        val paragraphs = bible.blocks(ChapterRef(BookID.GENESIS, 1)).filter { !it.kind.isHeading }
        assertEquals(1, paragraphs.size)
        assertEquals(4, paragraphs.firstOrNull()?.fragments?.size)
        assertEquals(true, paragraphs.firstOrNull()?.fragments?.all { it.numbered })
    }

    /** A verse that runs across two paragraphs keeps one row, joined with a single space. */
    @Test fun joinsAVerseThatRunsAcrossParagraphs() {
        val bible = extract(
            "gen01.xhtml",
            """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>The first half of the verse,</p>
            <p>and the second half of it.</p>
            """.trimIndent(),
        )
        assertEquals("The first half of the verse, and the second half of it.", bible.verses[ref(BookID.GENESIS, 1, 1)]?.text)
        val paragraphs = bible.blocks(ChapterRef(BookID.GENESIS, 1)).filter { !it.kind.isHeading }
        assertEquals(2, paragraphs.size)
        assertEquals(false, paragraphs.lastOrNull()?.fragments?.firstOrNull()?.numbered)
    }

    @Test fun keepsWordsOfChrist() {
        val bible = extract(
            "jhn14.xhtml",
            """
            <h1>John 14</h1>
            <p><sup>6</sup>Jesus saith unto him, <span class="wj">I am the way, and the truth, and the life.</span></p>
            """.trimIndent(),
        )
        val verse = required(bible.verses[ref(BookID.JOHN, 14, 6)])
        val span = required(verse.red.firstOrNull())
        val scalars = verse.text.codePoints().toArray()
        assertEquals("I am the way, and the truth, and the life.", String(scalars, span.start, span.length))
        val fragment = required(bible.blocks(ChapterRef(BookID.JOHN, 14)).flatMap { it.fragments }.firstOrNull { it.verse == 6 })
        assertTrue(fragment.spans.any { it.style == StyledSpan.Style.WORDS_OF_CHRIST })
    }

    @Test fun keepsFootnoteMarkersAndBodies() {
        val bible = extract(
            "gen01.xhtml",
            """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>In the beginning God created<a epub:type="noteref" href="#fn1" class="noteref">a</a> the heavens.</p>
            <aside epub:type="footnote" id="fn1"><p>Or <i>When God began to create</i></p></aside>
            """.trimIndent(),
        )
        val fragment = required(bible.blocks(ChapterRef(BookID.GENESIS, 1)).flatMap { it.fragments }.firstOrNull())
        val note = required(fragment.footnotes.firstOrNull())
        assertEquals("Or When God began to create", note.text)
        assertEquals(SwiftText.scalarCount("In the beginning God created"), note.position)
        // The note body must not be part of the verse.
        assertEquals("In the beginning God created the heavens.", bible.verses[ref(BookID.GENESIS, 1, 1)]?.text)
    }

    @Test fun doesNotMistakeFootnoteLettersForVerseNumbers() {
        val bible = extract(
            "gen01.xhtml",
            """
            <h1>Genesis 1</h1>
            <p><span class="verse-num">1</span>In the beginning<sup>a</sup> God created.
            <span class="verse-num">2</span>And the earth was waste<sup>b</sup>.</p>
            """.trimIndent(),
        )
        assertEquals(VerseMarkupShape.NUMBER_CLASS, bible.shapesByDocument["OEBPS/gen01.xhtml"])
        assertEquals(listOf(1, 2), bible.verseNumbers(ChapterRef(BookID.GENESIS, 1)))
    }

    /** Publishers print two verses as one ("1-2") where the translation combines them. */
    @Test fun readsABridgedVerseNumber() {
        val bible = extract(
            "jon01.xhtml",
            """
            <h1>Jonah 1</h1>
            <p><sup>1-2</sup>Now the word of Jehovah came unto Jonah, saying, Arise, go to Nineveh.
            <sup>3</sup>But Jonah rose up to flee.</p>
            """.trimIndent(),
        )
        assertEquals(
            "Now the word of Jehovah came unto Jonah, saying, Arise, go to Nineveh.",
            bible.verses[ref(BookID.JONAH, 1, 1)]?.text,
        )
        assertNull(bible.verses[ref(BookID.JONAH, 1, 2)])
        assertEquals(ref(BookID.JONAH, 1, 1), bible.bridgedVerses[ref(BookID.JONAH, 1, 2)])
        // The number must not survive as text, and the gap must not be reported as missing.
        assertEquals(false, bible.verses[ref(BookID.JONAH, 1, 1)]?.text?.contains("1-2"))
        assertEquals(true, ImportCoverageReport(bible).books.firstOrNull()?.chaptersWithGaps?.isEmpty())
    }

    // MARK: - Books, chapters, spine

    @Test fun readsAMultiBookSpine() {
        val bible = BibleTextExtractor().extract(
            EPUBPackage(
                ImportFixtures.epub(
                    listOf(
                        ImportFixtures.Document("front.xhtml", "<h1>Publisher’s Preface</h1><p>About this edition.</p>"),
                        ImportFixtures.Document("gen.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"),
                        ImportFixtures.Document("exo.xhtml", "<h1>Exodus 1</h1><p><sup>1</sup>Now these are the names.</p>"),
                        ImportFixtures.Document(
                            "mrk.xhtml",
                            "<h1>The Gospel According to St. Mark</h1><p><sup>1</sup>The beginning of the gospel.</p>",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(listOf(BookID.GENESIS, BookID.EXODUS, BookID.MARK), bible.books)
        assertEquals("Now these are the names.", bible.verses[ref(BookID.EXODUS, 1, 1)]?.text)
        assertEquals("The beginning of the gospel.", bible.verses[ref(BookID.MARK, 1, 1)]?.text)
    }

    @Test fun carriesAChapterAcrossTwoSpineFiles() {
        val bible = BibleTextExtractor().extract(
            EPUBPackage(
                ImportFixtures.epub(
                    listOf(
                        ImportFixtures.Document(
                            "psa119a.xhtml",
                            """
                            <h1>Psalm 119</h1>
                            <p><sup>1</sup>Blessed are they that are perfect in the way.
                            <sup>2</sup>Blessed are they that keep his testimonies.</p>
                            """.trimIndent(),
                        ),
                        ImportFixtures.Document(
                            "psa119b.xhtml",
                            """
                            <p><sup>3</sup>Yea, they do no unrighteousness.
                            <sup>4</sup>Thou hast commanded us thy precepts.</p>
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(listOf(BookID.PSALMS), bible.books)
        assertEquals(listOf(1, 2, 3, 4), bible.verseNumbers(ChapterRef(BookID.PSALMS, 119)))
        assertEquals("Thou hast commanded us thy precepts.", bible.verses[ref(BookID.PSALMS, 119, 4)]?.text)
    }

    /** One file, several chapters, with the chapter number in a class rather than a heading. */
    @Test fun readsSeveralChaptersFromOneFile() {
        val bible = extract(
            "gen.xhtml",
            """
            <h1>Genesis</h1>
            <p><span class="chapnum">1</span><sup>1</sup>In the beginning.<sup>2</sup>And the earth was waste.</p>
            <p><span class="chapnum">2</span><sup>1</sup>And the heavens were finished.</p>
            """.trimIndent(),
        )
        assertEquals(listOf(1, 2), bible.verseNumbers(ChapterRef(BookID.GENESIS, 1)))
        assertEquals(listOf(1), bible.verseNumbers(ChapterRef(BookID.GENESIS, 2)))
        assertEquals("And the heavens were finished.", bible.verses[ref(BookID.GENESIS, 2, 1)]?.text)
    }

    /** No chapter marker at all: a verse number that repeats means the text moved on. */
    @Test fun startsANewChapterWhenVerseNumbersRestart() {
        val bible = extract(
            "gen.xhtml",
            """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>Chapter one, verse one.<sup>2</sup>Chapter one, verse two.</p>
            <p><sup>1</sup>Chapter two, verse one.</p>
            """.trimIndent(),
        )
        assertEquals(listOf(1, 2), bible.verseNumbers(ChapterRef(BookID.GENESIS, 1)))
        assertEquals("Chapter two, verse one.", bible.verses[ref(BookID.GENESIS, 2, 1)]?.text)
    }

    @Test fun failsCleanlyOnAnEPUBWithNoScripture() {
        val data = ImportFixtures.epub(
            listOf(
                ImportFixtures.Document("a.xhtml", "<h1>A Cookbook</h1><p>Beat the eggs. Add the flour.</p>"),
                ImportFixtures.Document("b.xhtml", "<h1>Chapter Two</h1><p>Bake for forty minutes.</p>"),
            ),
        )
        assertThrowsImport<BibleImportError.NoScriptureFound>(BibleImportError.NoScriptureFound()) {
            BibleTextExtractor().extract(EPUBPackage(data))
        }
    }

    @Test fun doesNotTurnASectionHeadingIntoABook() {
        val bible = extract(
            "job.xhtml",
            """
            <h1>Job 1</h1>
            <p><sup>1</sup>There was a man in the land of Uz.</p>
            <h2>Job’s Complaint</h2>
            <p><sup>2</sup>And there were born unto him seven sons.</p>
            """.trimIndent(),
        )
        assertEquals(listOf(BookID.JOB), bible.books)
        assertEquals(listOf(1, 2), bible.verseNumbers(ChapterRef(BookID.JOB, 1)))
        assertEquals(listOf("Job’s Complaint"), bible.blocks(ChapterRef(BookID.JOB, 1)).filter { it.kind.isHeading }.map { it.heading })
    }

    // MARK: - Coverage report

    @Test fun reportsMissingChaptersAndVerses() {
        val bible = BibleTextExtractor().extract(
            EPUBPackage(
                ImportFixtures.epub(
                    listOf(
                        ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>One.<sup>2</sup>Two.<sup>3</sup>Three.</p>"),
                        ImportFixtures.Document("gen03.xhtml", "<h1>Genesis 3</h1><p><sup>1</sup>One.<sup>3</sup>Three.</p>"),
                    ),
                ),
            ),
        )
        val report = ImportCoverageReport(bible)
        assertEquals(listOf(BookID.GENESIS), report.booksFound)
        assertEquals(65, report.booksMissing.size)
        assertEquals(5, report.totalVerses)
        assertEquals(2, report.totalChapters)
        assertFalse(report.isWholeBible)

        val genesis = required(report.books.firstOrNull())
        assertEquals(2, genesis.chaptersFound)
        assertEquals(50, genesis.chaptersExpected)
        assertTrue(2 in genesis.missingChapters)
        assertEquals(48, genesis.missingChapters.size)
        val gap = required(genesis.chaptersWithGaps.firstOrNull { it.chapter == 3 })
        assertEquals(listOf(2), gap.missingVerses)
        assertEquals(3, gap.highestVerse)
        assertTrue(report.completeness < 0.01)
        assertTrue(report.problems.any { it.contains("Genesis 3: missing verse 2.") })
        assertTrue(report.summary.contains("1 book"))
    }

    @Test fun reportsVersesThatRanOutOfOrder() {
        val bible = extract(
            "gen01.xhtml",
            """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>One.<sup>3</sup>Three.<sup>2</sup>Two.</p>
            """.trimIndent(),
        )
        val report = ImportCoverageReport(bible)
        assertTrue(ChapterRef(BookID.GENESIS, 1) in bible.outOfOrderChapters)
        val gap = required(report.books.firstOrNull()?.chaptersWithGaps?.firstOrNull())
        assertTrue(gap.outOfOrder)
        assertTrue(report.problems.any { it.contains("out of order") })
    }

    @Test fun aCompleteImportReportsNoProblems() {
        val bible = extract(
            "phm.xhtml",
            """
            <h1>Philemon 1</h1>
            <p><sup>1</sup>Paul, a prisoner of Christ Jesus.<sup>2</sup>And to Apphia our sister.</p>
            """.trimIndent(),
        )
        val report = ImportCoverageReport(bible)
        val philemon = required(report.books.firstOrNull())
        assertEquals(BookID.PHILEMON, philemon.book)
        assertTrue(philemon.isComplete) // Philemon has exactly one chapter
        assertTrue(report.books.none { !it.isComplete })
        // …but the other 65 books are not here, and the report says so rather than claiming success.
        assertFalse(report.isWholeBible)
        assertEquals(listOf("65 books are missing, including Genesis, Exodus, Leviticus, Numbers, Deuteronomy, Joshua."), report.problems)
    }

    // MARK: - Writing the store

    @Test fun writesAStoreTheReaderCanOpen() {
        val bible = BibleTextExtractor().extract(
            EPUBPackage(
                ImportFixtures.epub(
                    listOf(
                        ImportFixtures.Document(
                            "gen01.xhtml",
                            """
                            <h1>Genesis 1</h1>
                            <h2>The Creation</h2>
                            <p><sup>1</sup>In the beginning God created the heavens and the earth.
                            <sup>2</sup>And the earth was waste and void.</p>
                            """.trimIndent(),
                        ),
                        ImportFixtures.Document(
                            "jhn14.xhtml",
                            """
                            <h1>John 14</h1>
                            <p><sup>6</sup>Jesus saith unto him, <span class="wj">I am the way, and the truth, and the life.</span></p>
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        val identity = ImportedTranslationIdentity(
            id = "IMPORT-TEST", name = "Antique Standard Bible", abbreviation = "ASB",
            copyright = "Text is in the public domain. Typesetting © 2026 Example Press.",
        )
        val directory = ImportFixtures.scratchDirectory()
        val file = java.io.File(directory, "IMPORT-TEST.sqlite")
        val report = ImportedBibleBuilder.write(bible, identity, file, ImportFixtures.jdbcWriter)
        assertEquals(3, report.totalVerses)

        ImportedStoreReader(file).use { store ->
            assertEquals("IMPORT-TEST", store.meta["id"])
            assertEquals("ASB", store.meta["abbreviation"])
            // The publisher's line has to survive the trip.
            assertEquals("Text is in the public domain. Typesetting © 2026 Example Press.", store.meta["copyright"])
            assertEquals(ImportedTranslationIdentity.UNKNOWN_LICENSE, store.meta["license"])
            assertTrue(store.contains(ChapterRef(BookID.GENESIS, 1)))
            assertEquals(2, store.verseCount(ChapterRef(BookID.GENESIS, 1)))

            val verses = store.verses(ref(BookID.GENESIS, 1, 1), ref(BookID.GENESIS, 1, 2))
            assertEquals(
                listOf("In the beginning God created the heavens and the earth.", "And the earth was waste and void."),
                verses.map { it.text },
            )
            // The verse key convention the highlights and notes are stored against.
            assertEquals(1_001_001, verses.firstOrNull()?.ref?.key)

            val john = store.verses(ref(BookID.JOHN, 14, 6))
            val verse = required(john.firstOrNull())
            val red = required(verse.red.firstOrNull())
            assertEquals("I am the way, and the truth, and the life.", verse.text.substring(verse.text.utf16Range(red.start, red.length)))

            val layout = store.layout(ChapterRef(BookID.GENESIS, 1))
            assertEquals(ChapterLayout.Kind.HEADING, layout.blocks.firstOrNull()?.kind)
            assertEquals("The Creation", layout.blocks.firstOrNull()?.text)
            assertEquals(listOf(1, 2), layout.blocks.lastOrNull()?.fragments?.map { it.verse })
            assertEquals(true, layout.blocks.lastOrNull()?.fragments?.firstOrNull()?.numbered)

            assertEquals(listOf(ref(BookID.GENESIS, 1, 1)), store.search("beginning"))
        }
        // Nothing beside the store: the reader opens it read-only, so a stray journal would break it.
        assertEquals(listOf("IMPORT-TEST.sqlite"), directory.list()?.toList())
    }

    @Test fun refusesToWriteWithoutACopyrightLine() {
        val bible = extract("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>")
        val identity = ImportedTranslationIdentity(id = "IMPORT-NONE", name = "Nameless", abbreviation = "NL", copyright = "   ")
        val file = java.io.File(ImportFixtures.scratchDirectory(), "x.sqlite")
        assertThrowsImport<BibleImportError.MissingCopyright>(BibleImportError.MissingCopyright()) {
            ImportedBibleBuilder.write(bible, identity, file, ImportFixtures.jdbcWriter)
        }
        assertFalse(file.exists())
    }

    @Test fun suggestsAnIdentityFromDublinCore() {
        val pkg = EPUBPackage(
            ImportFixtures.epub(listOf(ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"))),
        )
        val identity = ImportedTranslationIdentity.suggested(pkg.metadata)
        assertEquals("Antique Standard Bible", identity.name)
        assertEquals("ASB", identity.abbreviation)
        assertEquals("Text is in the public domain. Typesetting © 2026 Example Press.", identity.copyright)
        assertEquals(ImportedTranslationIdentity.UNKNOWN_LICENSE, identity.license)
        assertTrue(identity.id.startsWith("IMPORT-"))
    }

    // MARK: - One entry point

    @Test fun importsAnEPUBEndToEnd() {
        val data = ImportFixtures.epub(
            listOf(ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning God created.</p>")),
            deflate = true,
        )
        val file = ImportFixtures.write(data, "bible.epub")
        val importer = ImportFixtures.importer()
        val preview = importer.preview(file)
        assertEquals(ImportedFileFormat.EPUB, preview.format)
        assertEquals(1, preview.documentCount)
        assertTrue(preview.hasCopyright)

        val result = importer.importBible(file, directory = ImportFixtures.scratchDirectory())
        assertEquals(ImportedFileFormat.EPUB, result.format)
        assertEquals(1, result.report.totalVerses)
        assertEquals(result.identity.id + ".sqlite", result.storeFile.name)
        ImportedStoreReader(result.storeFile).use { store ->
            assertEquals("In the beginning God created.", store.verses(ref(BookID.GENESIS, 1, 1)).firstOrNull()?.text)
        }
    }

    /**
     * Importing is slow and must not block the reader, so the whole engine has to be usable off the
     * main thread. Swift proves it with a detached task (and `Sendable` at compile time); this runs the
     * whole chain on a worker thread.
     */
    @Test fun importsOffTheMainActor() {
        val file = ImportFixtures.write(
            ImportFixtures.epub(
                listOf(ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning God created.</p>")),
            ),
            "bible.epub",
        )
        val directory = ImportFixtures.scratchDirectory()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<BibleImportResult> { ImportFixtures.importer().importBible(file, directory = directory) }.get()
            assertEquals(1, result.report.totalVerses)
            assertTrue(result.report.summary.contains("1 book"))
        } finally {
            executor.shutdown()
        }
    }

    // MARK: - Helper

    private fun extract(path: String, body: String): ExtractedBible {
        val data = ImportFixtures.epub(listOf(ImportFixtures.Document(path, body)))
        return BibleTextExtractor().extract(EPUBPackage(data))
    }
}
