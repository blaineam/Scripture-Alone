import Foundation
import Testing
@testable import ScriptureAloneCore

/// The ePub front-end. Fixtures are public-domain text or invented — no copyrighted text.
@Suite struct EPUBImportTests {
    // MARK: - Package

    @Test func readsContainerManifestSpineAndDublinCore() throws {
        let data = ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"),
            ImportFixtures.Document("gen02.xhtml", "<h1>Genesis 2</h1><p><sup>1</sup>And the heavens were finished.</p>"),
        ])
        let package = try EPUBPackage(data: data)
        #expect(package.packagePath == "OEBPS/content.opf")
        #expect(package.metadata.title == "Antique Standard Bible")
        #expect(package.metadata.creator == "A Committee")
        #expect(package.metadata.publisher == "Example Press")
        #expect(package.metadata.language == "en")
        #expect(package.metadata.identifier == "urn:isbn:9780000000001")
        #expect(package.metadata.rights?.hasPrefix("Text is in the public domain.") == true)
        #expect(package.manifest.count == 2)
        #expect(package.spine.map(\.path) == ["OEBPS/gen01.xhtml", "OEBPS/gen02.xhtml"])
        #expect(try package.document(package.spine[0]).contains("In the beginning"))
    }

    @Test func readsDeflatedEntries() throws {
        let data = ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning God created.</p>"),
        ], deflate: true)
        let package = try EPUBPackage(data: data)
        #expect(try package.document(package.spine[0]).contains("In the beginning God created"))
    }

    @Test func refusesAMalformedZip() throws {
        var data = ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml", "<p><sup>1</sup>Text.</p>"),
        ])
        // Clobber the end-of-central-directory signature.
        for offset in (data.count - 22)..<(data.count - 18) { data[offset] = 0x00 }
        #expect(throws: BibleImportError.notAZipArchive) { _ = try EPUBPackage(data: data) }
    }

    @Test func refusesAZipThatIsNotAnEPUB() throws {
        let data = ImportFixtures.zip([ImportFixtures.ZipEntry("notes.txt", "just some notes")])
        #expect(throws: BibleImportError.self) { _ = try EPUBPackage(data: data) }
        #expect(throws: BibleImportError.self) { _ = try BibleFileImporter().preview(ImportFixtures.write(data, named: "x.zip")) }
    }

    @Test func detectsACorruptedEntry() throws {
        var data = ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"),
        ])
        // Flip a byte inside the last entry's stored payload; the CRC must catch it.
        let target = data.count - 300
        data[target] = data[target] ^ 0xFF
        #expect(throws: BibleImportError.self) {
            let package = try EPUBPackage(data: data)
            _ = try BibleTextExtractor().extract(from: package)
        }
    }

    // MARK: - DRM refusal

    @Test(arguments: [
        ("META-INF/encryption.xml", DRMEvidence.encryptionManifest),
        ("META-INF/rights.xml", DRMEvidence.adobeADEPT),
        ("META-INF/license.lcpl", DRMEvidence.readiumLCP),
        ("META-INF/sinf.xml", DRMEvidence.appleFairPlay),
    ])
    func refusesProtectedFiles(name: String, evidence: DRMEvidence) throws {
        let data = ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"),
        ], extra: [ImportFixtures.ZipEntry(name, "<encryption/>")])
        #expect(throws: BibleImportError.protectedByDRM(evidence)) { _ = try EPUBPackage(data: data) }
        let url = try ImportFixtures.write(data, named: "protected.epub")
        #expect(throws: BibleImportError.protectedByDRM(evidence)) { _ = try BibleFileImporter().preview(url) }
        #expect(throws: BibleImportError.protectedByDRM(evidence)) {
            _ = try BibleFileImporter().importBible(at: url, into: try ImportFixtures.scratchDirectory())
        }
    }

    /// The refusal has to happen before anything is read. This ePub's package document is garbage:
    /// if the engine had opened it first, the error would name the package, not the protection.
    @Test func refusesProtectedFilesBeforeReadingAnyContent() throws {
        let data = ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"),
        ], extra: [ImportFixtures.ZipEntry("META-INF/encryption.xml", "<encryption/>")],
           opfOverride: "this is not xml at all <<<>>>")
        #expect(throws: BibleImportError.protectedByDRM(.encryptionManifest)) { _ = try EPUBPackage(data: data) }
    }

    /// Adobe announces itself in the package metadata even when `rights.xml` has been stripped.
    @Test func refusesAdeptMetadataInThePackageDocument() throws {
        let opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Protected</dc:title>
                <meta name="Adept.expected.resource" content="urn:uuid:0000"/>
              </metadata>
              <manifest><item id="d0" href="gen01.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="d0"/></spine>
            </package>
            """
        let data = ImportFixtures.epub(documents: [ImportFixtures.Document("gen01.xhtml", "<p>x</p>")],
                                       opfOverride: opf)
        #expect(throws: BibleImportError.protectedByDRM(.adobeADEPT)) { _ = try EPUBPackage(data: data) }
    }

    @Test func aWatermarkIsNotDRM() throws {
        let data = ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml", """
                <p class="watermark">Licensed to reader@example.com — not for redistribution.</p>
                <h1>Genesis 1</h1><p><sup>1</sup>In the beginning God created the heavens and the earth.</p>
                """),
        ])
        let bible = try BibleTextExtractor().extract(from: try EPUBPackage(data: data))
        #expect(bible.verses[VerseRef(.genesis, 1, 1)]?.text.hasPrefix("In the beginning") == true)
    }

    // MARK: - Verse markup shapes

    @Test func readsSuperscriptVerseNumbers() throws {
        let bible = try extract("gen01.xhtml", """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>In the beginning God created the heavens and the earth.
            <sup>2</sup>And the earth was waste and void.</p>
            <h2>The First Day</h2>
            <p><sup>3</sup>And God said, Let there be light: and there was light.</p>
            """)
        #expect(bible.shapesByDocument["OEBPS/gen01.xhtml"] == .superscript)
        #expect(bible.verses[VerseRef(.genesis, 1, 1)]?.text
                == "In the beginning God created the heavens and the earth.")
        #expect(bible.verses[VerseRef(.genesis, 1, 2)]?.text == "And the earth was waste and void.")
        #expect(bible.verses[VerseRef(.genesis, 1, 3)]?.text
                == "And God said, Let there be light: and there was light.")
        let headings = bible.blocks(for: ChapterRef(.genesis, 1)).filter(\.kind.isHeading)
        #expect(headings.map(\.heading) == ["The First Day"])
    }

    @Test func readsVerseNumberClasses() throws {
        let bible = try extract("mat05.xhtml", """
            <h1>Matthew 5</h1>
            <p><span class="verse-num">1</span>And seeing the multitudes, he went up into the mountain.
            <span class="vnum">2</span>And he opened his mouth and taught them, saying,</p>
            <p><span class="v-num">3</span>Blessed are the poor in spirit.</p>
            """)
        #expect(bible.shapesByDocument["OEBPS/mat05.xhtml"] == .numberClass)
        #expect(bible.verses[VerseRef(.matthew, 5, 1)]?.text.hasPrefix("And seeing the multitudes") == true)
        #expect(bible.verses[VerseRef(.matthew, 5, 2)]?.text.hasPrefix("And he opened his mouth") == true)
        #expect(bible.verses[VerseRef(.matthew, 5, 3)]?.text == "Blessed are the poor in spirit.")
        // The numbers themselves must never leak into the text.
        #expect(bible.verses[VerseRef(.matthew, 5, 1)]?.text.contains("1") == false)
    }

    /// `class="verse"` is used for both the number and the whole verse. Which one it is has to be
    /// decided from the content, not from the name.
    @Test func tellsANumberedVerseSpanFromAVerseContainer() throws {
        let numbered = try extract("mrk01.xhtml", """
            <h1>Mark 1</h1>
            <p><span class="verse">1</span>The beginning of the gospel of Jesus Christ.</p>
            """)
        #expect(numbered.verses[VerseRef(.mark, 1, 1)]?.text == "The beginning of the gospel of Jesus Christ.")

        let container = try extract("mrk02.xhtml", """
            <h1>Mark 2</h1>
            <p><span class="verse" id="Mrk.2.1">And when he entered again into Capernaum.</span>
            <span class="verse" id="Mrk.2.2">And many were gathered together.</span></p>
            """)
        #expect(container.shapesByDocument["OEBPS/mrk02.xhtml"] == .referenceIdentifier)
        #expect(container.verses[VerseRef(.mark, 2, 1)]?.text == "And when he entered again into Capernaum.")
        #expect(container.verses[VerseRef(.mark, 2, 2)]?.text == "And many were gathered together.")
    }

    @Test func readsChapterRelativeVerseAnchors() throws {
        let bible = try extract("jhn03.xhtml", """
            <h1>John 3</h1>
            <p><a id="v1"></a>Now there was a man of the Pharisees, named Nicodemus.
            <a id="v2"></a>The same came unto him by night.</p>
            """)
        #expect(bible.shapesByDocument["OEBPS/jhn03.xhtml"] == .verseAnchor)
        #expect(bible.verses[VerseRef(.john, 3, 1)]?.text.hasPrefix("Now there was a man") == true)
        #expect(bible.verses[VerseRef(.john, 3, 2)]?.text == "The same came unto him by night.")
    }

    @Test(arguments: ["ABC_Rom.8.1", "xyz-Rom-8-1", "ROM.8.1", "Rom_8_1"])
    func readsReferenceIdentifiers(identifier: String) throws {
        let bible = try extract("book.xhtml", """
            <p><span id="\(identifier)">There is therefore now no condemnation.</span></p>
            """)
        #expect(bible.shapesByDocument["OEBPS/book.xhtml"] == .referenceIdentifier)
        #expect(bible.verses[VerseRef(.romans, 8, 1)]?.text == "There is therefore now no condemnation.")
    }

    @Test func readsOrdinalReferenceIdentifiers() throws {
        let bible = try extract("cor.xhtml", """
            <p><span id="ABC_1Cor.13.4">Love suffereth long, and is kind.</span></p>
            """)
        #expect(bible.verses[VerseRef(.firstCorinthians, 13, 4)]?.text == "Love suffereth long, and is kind.")
    }

    /// Several verses sharing one paragraph — the normal shape for prose.
    @Test func splitsRunTogetherParagraphs() throws {
        let bible = try extract("gen01.xhtml", """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>First. <sup>2</sup>Second. <sup>3</sup>Third. <sup>4</sup>Fourth.</p>
            """)
        #expect(bible.verseNumbers(in: ChapterRef(.genesis, 1)) == [1, 2, 3, 4])
        #expect(bible.verses[VerseRef(.genesis, 1, 2)]?.text == "Second.")
        let paragraphs = bible.blocks(for: ChapterRef(.genesis, 1)).filter { !$0.kind.isHeading }
        #expect(paragraphs.count == 1)
        #expect(paragraphs.first?.fragments.count == 4)
        #expect(paragraphs.first?.fragments.allSatisfy(\.numbered) == true)
    }

    /// A verse that runs across two paragraphs keeps one row, joined with a single space.
    @Test func joinsAVerseThatRunsAcrossParagraphs() throws {
        let bible = try extract("gen01.xhtml", """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>The first half of the verse,</p>
            <p>and the second half of it.</p>
            """)
        #expect(bible.verses[VerseRef(.genesis, 1, 1)]?.text
                == "The first half of the verse, and the second half of it.")
        let paragraphs = bible.blocks(for: ChapterRef(.genesis, 1)).filter { !$0.kind.isHeading }
        #expect(paragraphs.count == 2)
        #expect(paragraphs.last?.fragments.first?.numbered == false)
    }

    @Test func keepsWordsOfChrist() throws {
        let bible = try extract("jhn14.xhtml", """
            <h1>John 14</h1>
            <p><sup>6</sup>Jesus saith unto him, <span class="wj">I am the way, and the truth, and the life.</span></p>
            """)
        let verse = try #require(bible.verses[VerseRef(.john, 14, 6)])
        let span = try #require(verse.red.first)
        let scalars = Array(verse.text.unicodeScalars)
        let spoken = String(String.UnicodeScalarView(scalars[span.start..<(span.start + span.length)]))
        #expect(spoken == "I am the way, and the truth, and the life.")
        let fragment = try #require(bible.blocks(for: ChapterRef(.john, 14)).flatMap(\.fragments).first { $0.verse == 6 })
        #expect(fragment.spans.contains { $0.style == .wordsOfChrist })
    }

    @Test func keepsFootnoteMarkersAndBodies() throws {
        let bible = try extract("gen01.xhtml", """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>In the beginning God created<a epub:type="noteref" href="#fn1" class="noteref">a</a> the heavens.</p>
            <aside epub:type="footnote" id="fn1"><p>Or <i>When God began to create</i></p></aside>
            """)
        let fragment = try #require(bible.blocks(for: ChapterRef(.genesis, 1)).flatMap(\.fragments).first)
        let note = try #require(fragment.footnotes.first)
        #expect(note.text == "Or When God began to create")
        #expect(note.position == "In the beginning God created".unicodeScalars.count)
        // The note body must not be part of the verse.
        #expect(bible.verses[VerseRef(.genesis, 1, 1)]?.text == "In the beginning God created the heavens.")
    }

    @Test func doesNotMistakeFootnoteLettersForVerseNumbers() throws {
        let bible = try extract("gen01.xhtml", """
            <h1>Genesis 1</h1>
            <p><span class="verse-num">1</span>In the beginning<sup>a</sup> God created.
            <span class="verse-num">2</span>And the earth was waste<sup>b</sup>.</p>
            """)
        #expect(bible.shapesByDocument["OEBPS/gen01.xhtml"] == .numberClass)
        #expect(bible.verseNumbers(in: ChapterRef(.genesis, 1)) == [1, 2])
    }

    /// Publishers print two verses as one ("1-2") where the translation combines them.
    @Test func readsABridgedVerseNumber() throws {
        let bible = try extract("jon01.xhtml", """
            <h1>Jonah 1</h1>
            <p><sup>1-2</sup>Now the word of Jehovah came unto Jonah, saying, Arise, go to Nineveh.
            <sup>3</sup>But Jonah rose up to flee.</p>
            """)
        #expect(bible.verses[VerseRef(.jonah, 1, 1)]?.text
                == "Now the word of Jehovah came unto Jonah, saying, Arise, go to Nineveh.")
        #expect(bible.verses[VerseRef(.jonah, 1, 2)] == nil)
        #expect(bible.bridgedVerses[VerseRef(.jonah, 1, 2)] == VerseRef(.jonah, 1, 1))
        // The number must not survive as text, and the gap must not be reported as missing.
        #expect(bible.verses[VerseRef(.jonah, 1, 1)]?.text.contains("1-2") == false)
        #expect(ImportCoverageReport(bible).books.first?.chaptersWithGaps.isEmpty == true)
    }

    // MARK: - Books, chapters, spine

    @Test func readsAMultiBookSpine() throws {
        let bible = try BibleTextExtractor().extract(from: try EPUBPackage(data: ImportFixtures.epub(documents: [
            ImportFixtures.Document("front.xhtml", "<h1>Publisher’s Preface</h1><p>About this edition.</p>"),
            ImportFixtures.Document("gen.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"),
            ImportFixtures.Document("exo.xhtml", "<h1>Exodus 1</h1><p><sup>1</sup>Now these are the names.</p>"),
            ImportFixtures.Document("mrk.xhtml",
                                    "<h1>The Gospel According to St. Mark</h1><p><sup>1</sup>The beginning of the gospel.</p>"),
        ])))
        #expect(bible.books == [.genesis, .exodus, .mark])
        #expect(bible.verses[VerseRef(.exodus, 1, 1)]?.text == "Now these are the names.")
        #expect(bible.verses[VerseRef(.mark, 1, 1)]?.text == "The beginning of the gospel.")
    }

    @Test func carriesAChapterAcrossTwoSpineFiles() throws {
        let bible = try BibleTextExtractor().extract(from: try EPUBPackage(data: ImportFixtures.epub(documents: [
            ImportFixtures.Document("psa119a.xhtml", """
                <h1>Psalm 119</h1>
                <p><sup>1</sup>Blessed are they that are perfect in the way.
                <sup>2</sup>Blessed are they that keep his testimonies.</p>
                """),
            ImportFixtures.Document("psa119b.xhtml", """
                <p><sup>3</sup>Yea, they do no unrighteousness.
                <sup>4</sup>Thou hast commanded us thy precepts.</p>
                """),
        ])))
        #expect(bible.books == [.psalms])
        #expect(bible.verseNumbers(in: ChapterRef(.psalms, 119)) == [1, 2, 3, 4])
        #expect(bible.verses[VerseRef(.psalms, 119, 4)]?.text == "Thou hast commanded us thy precepts.")
    }

    /// One file, several chapters, with the chapter number in a class rather than a heading.
    @Test func readsSeveralChaptersFromOneFile() throws {
        let bible = try extract("gen.xhtml", """
            <h1>Genesis</h1>
            <p><span class="chapnum">1</span><sup>1</sup>In the beginning.<sup>2</sup>And the earth was waste.</p>
            <p><span class="chapnum">2</span><sup>1</sup>And the heavens were finished.</p>
            """)
        #expect(bible.verseNumbers(in: ChapterRef(.genesis, 1)) == [1, 2])
        #expect(bible.verseNumbers(in: ChapterRef(.genesis, 2)) == [1])
        #expect(bible.verses[VerseRef(.genesis, 2, 1)]?.text == "And the heavens were finished.")
    }

    /// No chapter marker at all: a verse number that repeats means the text moved on.
    @Test func startsANewChapterWhenVerseNumbersRestart() throws {
        let bible = try extract("gen.xhtml", """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>Chapter one, verse one.<sup>2</sup>Chapter one, verse two.</p>
            <p><sup>1</sup>Chapter two, verse one.</p>
            """)
        #expect(bible.verseNumbers(in: ChapterRef(.genesis, 1)) == [1, 2])
        #expect(bible.verses[VerseRef(.genesis, 2, 1)]?.text == "Chapter two, verse one.")
    }

    @Test func failsCleanlyOnAnEPUBWithNoScripture() throws {
        let data = ImportFixtures.epub(documents: [
            ImportFixtures.Document("a.xhtml", "<h1>A Cookbook</h1><p>Beat the eggs. Add the flour.</p>"),
            ImportFixtures.Document("b.xhtml", "<h1>Chapter Two</h1><p>Bake for forty minutes.</p>"),
        ])
        #expect(throws: BibleImportError.noScriptureFound) {
            _ = try BibleTextExtractor().extract(from: try EPUBPackage(data: data))
        }
    }

    @Test func doesNotTurnASectionHeadingIntoABook() throws {
        let bible = try extract("job.xhtml", """
            <h1>Job 1</h1>
            <p><sup>1</sup>There was a man in the land of Uz.</p>
            <h2>Job’s Complaint</h2>
            <p><sup>2</sup>And there were born unto him seven sons.</p>
            """)
        #expect(bible.books == [.job])
        #expect(bible.verseNumbers(in: ChapterRef(.job, 1)) == [1, 2])
        #expect(bible.blocks(for: ChapterRef(.job, 1)).filter(\.kind.isHeading).map(\.heading) == ["Job’s Complaint"])
    }

    // MARK: - Coverage report

    @Test func reportsMissingChaptersAndVerses() throws {
        let bible = try BibleTextExtractor().extract(from: try EPUBPackage(data: ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml",
                                    "<h1>Genesis 1</h1><p><sup>1</sup>One.<sup>2</sup>Two.<sup>3</sup>Three.</p>"),
            ImportFixtures.Document("gen03.xhtml", "<h1>Genesis 3</h1><p><sup>1</sup>One.<sup>3</sup>Three.</p>"),
        ])))
        let report = ImportCoverageReport(bible)
        #expect(report.booksFound == [.genesis])
        #expect(report.booksMissing.count == 65)
        #expect(report.totalVerses == 5)
        #expect(report.totalChapters == 2)
        #expect(report.isWholeBible == false)

        let genesis = try #require(report.books.first)
        #expect(genesis.chaptersFound == 2)
        #expect(genesis.chaptersExpected == 50)
        #expect(genesis.missingChapters.contains(2))
        #expect(genesis.missingChapters.count == 48)
        let gap = try #require(genesis.chaptersWithGaps.first { $0.chapter == 3 })
        #expect(gap.missingVerses == [2])
        #expect(gap.highestVerse == 3)
        #expect(report.completeness < 0.01)
        #expect(report.problems.contains { $0.contains("Genesis 3: missing verse 2.") })
        #expect(report.summary.contains("1 book"))
    }

    @Test func reportsVersesThatRanOutOfOrder() throws {
        let bible = try extract("gen01.xhtml", """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>One.<sup>3</sup>Three.<sup>2</sup>Two.</p>
            """)
        let report = ImportCoverageReport(bible)
        #expect(bible.outOfOrderChapters.contains(ChapterRef(.genesis, 1)))
        let gap = try #require(report.books.first?.chaptersWithGaps.first)
        #expect(gap.outOfOrder)
        #expect(report.problems.contains { $0.contains("out of order") })
    }

    @Test func aCompleteImportReportsNoProblems() throws {
        let bible = try extract("phm.xhtml", """
            <h1>Philemon 1</h1>
            <p><sup>1</sup>Paul, a prisoner of Christ Jesus.<sup>2</sup>And to Apphia our sister.</p>
            """)
        let report = ImportCoverageReport(bible)
        let philemon = try #require(report.books.first)
        #expect(philemon.book == .philemon)
        #expect(philemon.isComplete)          // Philemon has exactly one chapter
        #expect(report.books.filter { !$0.isComplete }.isEmpty)
        // …but the other 65 books are not here, and the report says so rather than claiming success.
        #expect(report.isWholeBible == false)
        #expect(report.problems == ["65 books are missing, including Genesis, Exodus, Leviticus, Numbers, Deuteronomy, Joshua."])
    }

    // MARK: - Writing the store

    @Test func writesAStoreTheReaderCanOpen() throws {
        let bible = try BibleTextExtractor().extract(from: try EPUBPackage(data: ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml", """
                <h1>Genesis 1</h1>
                <h2>The Creation</h2>
                <p><sup>1</sup>In the beginning God created the heavens and the earth.
                <sup>2</sup>And the earth was waste and void.</p>
                """),
            ImportFixtures.Document("jhn14.xhtml", """
                <h1>John 14</h1>
                <p><sup>6</sup>Jesus saith unto him, <span class="wj">I am the way, and the truth, and the life.</span></p>
                """),
        ])))
        let identity = ImportedTranslationIdentity(id: "IMPORT-TEST", name: "Antique Standard Bible",
                                                   abbreviation: "ASB",
                                                   copyright: "Text is in the public domain. Typesetting © 2026 Example Press.")
        let directory = try ImportFixtures.scratchDirectory()
        let url = directory.appending(path: "IMPORT-TEST.sqlite")
        let report = try ImportedBibleBuilder.write(bible, identity: identity, to: url)
        #expect(report.totalVerses == 3)

        let store = try BibleStore(url: url)
        #expect(store.info.id == "IMPORT-TEST")
        #expect(store.info.abbreviation == "ASB")
        // The publisher's line has to survive the trip.
        #expect(store.info.copyright == "Text is in the public domain. Typesetting © 2026 Example Press.")
        #expect(store.info.license == ImportedTranslationIdentity.unknownLicense)
        #expect(store.contains(ChapterRef(.genesis, 1)))
        #expect(store.verseCount(ChapterRef(.genesis, 1)) == 2)

        let verses = try store.verses(in: VerseRange(VerseRef(.genesis, 1, 1), VerseRef(.genesis, 1, 2)))
        #expect(verses.map(\.text) == ["In the beginning God created the heavens and the earth.",
                                       "And the earth was waste and void."])
        // The verse key convention the highlights and notes are stored against.
        #expect(verses.first?.ref.key == 1_001_001)

        let john = try store.verses(in: VerseRange(VerseRef(.john, 14, 6)))
        let red = try #require(john.first?.red.first)
        #expect((john.first?.text as NSString?)?.substring(with: red) == "I am the way, and the truth, and the life.")

        let layout = try store.layout(for: ChapterRef(.genesis, 1))
        #expect(layout.blocks.first?.kind == .heading)
        #expect(layout.blocks.first?.text == "The Creation")
        #expect(layout.blocks.last?.fragments.map(\.verse) == [1, 2])
        #expect(layout.blocks.last?.fragments.first?.numbered == true)

        let hits = try store.search("beginning")
        #expect(hits.map(\.ref) == [VerseRef(.genesis, 1, 1)])
        // Nothing beside the store: `BibleStore` opens immutable, so a stray journal would break it.
        let files = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        #expect(files == ["IMPORT-TEST.sqlite"])
    }

    @Test func refusesToWriteWithoutACopyrightLine() throws {
        let bible = try extract("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>")
        let identity = ImportedTranslationIdentity(id: "IMPORT-NONE", name: "Nameless", abbreviation: "NL",
                                                   copyright: "   ")
        let url = try ImportFixtures.scratchDirectory().appending(path: "x.sqlite")
        #expect(throws: BibleImportError.missingCopyright) {
            _ = try ImportedBibleBuilder.write(bible, identity: identity, to: url)
        }
        #expect(FileManager.default.fileExists(atPath: url.path) == false)
    }

    @Test func suggestsAnIdentityFromDublinCore() throws {
        let package = try EPUBPackage(data: ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"),
        ]))
        let identity = ImportedTranslationIdentity.suggested(from: package.metadata)
        #expect(identity.name == "Antique Standard Bible")
        #expect(identity.abbreviation == "ASB")
        #expect(identity.copyright == "Text is in the public domain. Typesetting © 2026 Example Press.")
        #expect(identity.license == ImportedTranslationIdentity.unknownLicense)
        #expect(identity.id.hasPrefix("IMPORT-"))
    }

    // MARK: - One entry point

    @Test func importsAnEPUBEndToEnd() throws {
        let data = ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml",
                                    "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning God created.</p>"),
        ], deflate: true)
        let url = try ImportFixtures.write(data, named: "bible.epub")
        let importer = BibleFileImporter()
        let preview = try importer.preview(url)
        #expect(preview.format == .epub)
        #expect(preview.documentCount == 1)
        #expect(preview.hasCopyright)

        let result = try importer.importBible(at: url, into: try ImportFixtures.scratchDirectory())
        #expect(result.format == .epub)
        #expect(result.report.totalVerses == 1)
        #expect(result.storeURL.lastPathComponent == result.identity.id + ".sqlite")
        let store = try BibleStore(url: result.storeURL)
        #expect(try store.verses(in: VerseRange(VerseRef(.genesis, 1, 1))).first?.text
                == "In the beginning God created.")
    }

    /// Importing is slow and must not block the reader, so the whole engine has to be usable from a
    /// detached task. This test would not compile if anything in the chain were main-actor bound or
    /// not `Sendable`.
    @Test func importsOffTheMainActor() async throws {
        let url = try ImportFixtures.write(ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml",
                                    "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning God created.</p>"),
        ]), named: "bible.epub")
        let directory = try ImportFixtures.scratchDirectory()
        let result = try await Task.detached {
            try BibleFileImporter().importBible(at: url, into: directory)
        }.value
        #expect(result.report.totalVerses == 1)
        #expect(result.report.summary.contains("1 book"))
    }

    // MARK: - Helper

    // MARK: - Regressions

    /// `class` and `epub:type` values are matched without regard to case: `class="WJ"` is words of
    /// Christ, and `epub:type="NoteRef"`/`"Footnote"` are a note and its body.
    @Test func matchesClassAndTypeValuesWithoutCase() throws {
        let bible = try extract("jhn14.xhtml", """
            <h1>John 14</h1>
            <p><sup>6</sup>Jesus saith unto him, <span class="WJ">I am the way.</span><a epub:type="NoteRef" href="#n1">*</a></p>
            <aside epub:type="Footnote" id="n1">Or the road.</aside>
            """)
        let verse = try #require(bible.verses[VerseRef(.john, 14, 6)])
        #expect(verse.text == "Jesus saith unto him, I am the way.")
        let span = try #require(verse.red.first)
        let scalars = Array(verse.text.unicodeScalars)
        #expect(String(String.UnicodeScalarView(scalars[span.start..<(span.start + span.length)])) == "I am the way.")
        let fragment = try #require(bible.blocks(for: ChapterRef(.john, 14)).flatMap(\.fragments).first { $0.verse == 6 })
        #expect(fragment.footnotes.map(\.text) == ["Or the road."])
    }

    /// A superscript footnote letter in a file numbered by class is dropped, not glued onto the
    /// word ("saidb"); and a bridge split across superscripts ("7–") is verse 7, not text.
    @Test func dropsFootnoteLettersAndReadsADanglingBridge() throws {
        let byClass = try extract("gen03.xhtml", """
            <h1>Genesis 3</h1>
            <p><span class="verse-num">1</span>Now the serpent was more subtle.
            <span class="verse-num">2</span>And the woman said<sup>b</sup>, We may eat.</p>
            """)
        #expect(byClass.shapesByDocument["OEBPS/gen03.xhtml"] == .numberClass)
        #expect(byClass.verses[VerseRef(.genesis, 3, 2)]?.text == "And the woman said, We may eat.")

        let bySuperscript = try extract("gen01.xhtml", """
            <h1>Genesis 1</h1>
            <p><sup>6</sup>And God said.<sup>7–</sup>And God made the firmament.<sup>8</sup>And God called.</p>
            """)
        #expect(bySuperscript.verseNumbers(in: ChapterRef(.genesis, 1)) == [6, 7, 8])
        #expect(bySuperscript.verses[VerseRef(.genesis, 1, 6)]?.text == "And God said.")
        #expect(bySuperscript.verses[VerseRef(.genesis, 1, 7)]?.text == "And God made the firmament.")
        // An ordinal ending is text, not a caller.
        #expect(DocumentScanner.isFootnoteLabel("b"))
        #expect(DocumentScanner.isFootnoteLabel("[c]"))
        #expect(DocumentScanner.isFootnoteLabel("†"))
        #expect(!DocumentScanner.isFootnoteLabel("th"))
    }

    @Test func aLineBreakSeparatesWords() throws {
        let bible = try extract("1jn02.xhtml", """
            <h1>1 John 2</h1>
            <p><sup>1</sup>My little children,<br/>these things write I unto you.</p>
            """)
        #expect(bible.verses[VerseRef(.firstJohn, 2, 1)]?.text == "My little children, these things write I unto you.")
    }

    /// Nothing outside a document's elements reaches the verse the previous file ended on: not a
    /// UTF-16 byte-order mark, and not stray bytes before `<html>`.
    @Test func textBeforeTheRootElementIsNotScripture() throws {
        let second = ImportFixtures.Document("gen04.xhtml", "<h1>Genesis 4</h1><p><sup>1</sup>And the man knew Eve.</p>")
        var utf16 = Data([0xFF, 0xFE])
        utf16.append(try #require(second.xhtml.data(using: .utf16LittleEndian)))
        let third = ImportFixtures.Document("gen05.xhtml", "<h1>Genesis 5</h1><p><sup>1</sup>This is the book.</p>")
        let data = ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen03.xhtml", "<h1>Genesis 3</h1><p><sup>4</sup>Ye shall not surely die.</p>"),
            ImportFixtures.Document("gen04.xhtml", raw: utf16),
            ImportFixtures.Document("gen05.xhtml", raw: Data(("stray bytes" + third.xhtml).utf8)),
        ])
        let bible = try BibleTextExtractor().extract(from: try EPUBPackage(data: data))
        #expect(bible.verses[VerseRef(.genesis, 3, 4)]?.text == "Ye shall not surely die.")
        #expect(bible.verses[VerseRef(.genesis, 4, 1)]?.text == "And the man knew Eve.")
        #expect(bible.verses[VerseRef(.genesis, 5, 1)]?.text == "This is the book.")
        #expect(!bible.verses.values.contains { $0.text.unicodeScalars.contains("\u{FEFF}") || $0.text.contains("stray") })
    }

    /// A soft hyphen is a line-break hint, not text. FTS5's unicode61 tokenizer splits on it, so
    /// kept, "be&shy;ginning" would never be found by a search for "beginning".
    @Test func stripsSoftHyphens() throws {
        let bible = try extract("gen01.xhtml", """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>In the be&shy;ginning God cre\u{00AD}ated the hea&#173;vens.</p>
            """)
        #expect(bible.verses[VerseRef(.genesis, 1, 1)]?.text == "In the beginning God created the heavens.")
        let identity = ImportedTranslationIdentity(id: "IMPORT-SHY", name: "Soft", abbreviation: "SH", copyright: "Public domain.")
        let url = try ImportFixtures.scratchDirectory().appending(path: "IMPORT-SHY.sqlite")
        try ImportedBibleBuilder.write(bible, identity: identity, to: url)
        #expect(try BibleStore(url: url).search("beginning").map(\.ref) == [VerseRef(.genesis, 1, 1)])
    }

    /// The problems list says "ran out of order" once per chapter, not once from the coverage and
    /// again from the importer's own note.
    @Test func reportsAnOutOfOrderChapterOnce() throws {
        let bible = try extract("gen01.xhtml", """
            <h1>Genesis 1</h1>
            <p><sup>1</sup>One.<sup>3</sup>Three.<sup>2</sup>Two.</p>
            """)
        let problems = ImportCoverageReport(bible).problems
        #expect(problems.filter { $0 == "Genesis 1: verse numbers ran out of order." }.count == 1)
    }

    /// SQLite's rollback journal is "<partial>-journal"; one left by a crashed write (here a
    /// directory, which SQLite cannot use) must be cleared, or the next write fails.
    @Test func clearsAStaleRollbackJournal() throws {
        let bible = try extract("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>")
        let identity = ImportedTranslationIdentity(id: "IMPORT-JRNL", name: "Journal", abbreviation: "JR", copyright: "Public domain.")
        let directory = try ImportFixtures.scratchDirectory()
        try FileManager.default.createDirectory(at: directory.appending(path: ".IMPORT-JRNL.sqlite.partial-journal"),
                                                withIntermediateDirectories: true)
        let url = directory.appending(path: "IMPORT-JRNL.sqlite")
        try ImportedBibleBuilder.write(bible, identity: identity, to: url)
        #expect(try BibleStore(url: url).verseCount(ChapterRef(.genesis, 1)) == 1)
        #expect(try FileManager.default.contentsOfDirectory(atPath: directory.path) == ["IMPORT-JRNL.sqlite"])
    }

    /// Inflating stops once the output passes the declared size, so a header that understates it
    /// cannot make the reader hold a whole bomb in memory.
    @Test func stopsInflatingPastTheDeclaredSize() throws {
        let bomb = Data(repeating: 0x20, count: 8 * 1024 * 1024)
        let deflated = try (bomb as NSData).compressed(using: .zlib) as Data
        let partial = try #require(ZipReader.inflate(deflated, limit: 100))
        #expect(partial.count > 100)
        #expect(partial.count <= 100 + 64 * 1024)
        #expect(ZipReader.inflate(deflated, limit: bomb.count) == bomb)
        #expect(ZipReader.inflate(Data([0xFF, 0xFF, 0xFF]), limit: 100) == nil)

        let data = ImportFixtures.zip([ImportFixtures.ZipEntry("OEBPS/big.xhtml", data: bomb, deflate: true, claimedSize: 10)])
        let zip = try ZipReader(data: data)
        #expect(throws: BibleImportError.damagedArchive("OEBPS/big.xhtml is the wrong size")) {
            _ = try zip.data(for: "OEBPS/big.xhtml")
        }
    }

    /// A ZIP64 size of 2^63 or more cannot be an `Int`; it is refused, not trapped on.
    @Test func refusesAZIP64SizeBeyondInt() throws {
        let data = ImportFixtures.zip([ImportFixtures.ZipEntry("OEBPS/a.xhtml", data: Data("<p>x</p>".utf8),
                                                               zip64Size: 1 << 63)])
        let zip = try ZipReader(data: data)
        #expect(ZipReader.int(1 << 63) == -1)
        #expect(ZipReader.int(UInt64.max) == -1)
        #expect(throws: BibleImportError.entryTooLarge("OEBPS/a.xhtml")) { _ = try zip.data(for: "OEBPS/a.xhtml") }
    }

    private func extract(_ path: String, _ body: String) throws -> ExtractedBible {
        let data = ImportFixtures.epub(documents: [ImportFixtures.Document(path, body)])
        return try BibleTextExtractor().extract(from: try EPUBPackage(data: data))
    }
}
