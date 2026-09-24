import Foundation
import Testing
@testable import ScriptureAloneCore

/// The USFM front-end — the shape eBible.org publishes. Every fixture is public-domain text
/// or invented for the test.
@Suite struct USFMImportTests {
    static let genesis = """
        \\id GEN Antique Standard Bible
        \\h Genesis
        \\toc2 Genesis
        \\mt1 The First Book of Moses
        \\c 1
        \\s1 The Creation
        \\p
        \\v 1 In the beginning God created the heavens and the earth.
        \\v 2 And the earth was waste and void; and darkness was upon the face of the deep:
        and the Spirit of God moved upon the face of the waters.
        \\s2 The First Day
        \\q1
        \\v 3 And God said, Let there be light:\\f + \\fr 1:3 \\ft Or \\fqa Let light be\\f* and there was light.
        \\q2
        \\v 4 And God saw the light, that it was good.
        \\b
        \\m
        \\v 5 And God called the light Day.
        """

    static let psalm23 = """
        \\id PSA
        \\c 23
        \\d A Psalm of David.
        \\q1
        \\v 1 \\nd Jehovah\\nd* is my shepherd; I shall \\add not\\add* want.
        \\q2
        \\v 2 He \\w maketh|strong="H7257"\\w* me to lie down in green pastures.
        \\q1
        \\v 3 He restoreth my soul:\\x + \\xo 23:3 \\xt Ps 19:7\\x* he guideth me in the paths of righteousness.
        """

    static let matthew5 = """
        \\id MAT
        \\c 5
        \\p
        \\v 1 And seeing the multitudes, he went up into the mountain.
        \\p
        \\v 3 \\wj Blessed are the poor in spirit:
        \\v 4 Blessed are they that mourn:\\wj*
        \\v 5 Blessed are the meek.
        """

    private func extract(_ books: [String]) throws -> ExtractedBible {
        try USFMImporter().extract(files: books.enumerated().map { ("\($0.offset).usfm", $0.element) })
    }

    // MARK: - Markers

    @Test func readsChaptersVersesAndParagraphs() throws {
        let bible = try extract([Self.genesis])
        #expect(bible.books == [.genesis])
        #expect(bible.verseNumbers(in: ChapterRef(.genesis, 1)) == [1, 2, 3, 4, 5])
        #expect(bible.verses[VerseRef(.genesis, 1, 1)]?.text
                == "In the beginning God created the heavens and the earth.")
        // A verse wrapped across source lines is one row, joined with a single space.
        #expect(bible.verses[VerseRef(.genesis, 1, 2)]?.text
                == "And the earth was waste and void; and darkness was upon the face of the deep: "
                + "and the Spirit of God moved upon the face of the waters.")
        #expect(bible.shapesByDocument["0.usfm"] == .usfmMarkers)
        // File metadata (\\id, \\h, \\toc2, \\mt1) never reaches the text.
        #expect(bible.verses.values.contains { $0.text.contains("First Book of Moses") } == false)
    }

    @Test func readsSectionHeadingsAndPoetryLevels() throws {
        let bible = try extract([Self.genesis])
        let blocks = bible.blocks(for: ChapterRef(.genesis, 1))
        #expect(blocks.first?.kind == .heading)
        #expect(blocks.first?.heading == "The Creation")
        #expect(blocks.contains { $0.kind == .subheading && $0.heading == "The First Day" })
        #expect(blocks.contains { $0.kind == .poetry1 })
        #expect(blocks.contains { $0.kind == .poetry2 })
        #expect(blocks.contains { $0.kind == .stanzaBreak })
        #expect(blocks.contains { $0.kind == .continuation })
    }

    @Test func keepsFootnotesAttachedToTheirVerse() throws {
        let bible = try extract([Self.genesis])
        let fragment = try #require(bible.blocks(for: ChapterRef(.genesis, 1))
            .flatMap(\.fragments).first { $0.verse == 3 })
        let note = try #require(fragment.footnotes.first)
        // \\fr (the reference) and the caller are dropped; \\ft and \\fqa are kept.
        #expect(note.text == "Or Let light be")
        #expect(note.position == "And God said, Let there be light:".unicodeScalars.count)
        #expect(bible.verses[VerseRef(.genesis, 1, 3)]?.text
                == "And God said, Let there be light: and there was light.")
    }

    @Test func dropsCrossReferencesWithoutLeakingTheirText() throws {
        let bible = try extract([Self.psalm23])
        let verse = try #require(bible.verses[VerseRef(.psalms, 23, 3)])
        #expect(verse.text == "He restoreth my soul: he guideth me in the paths of righteousness.")
        let fragment = try #require(bible.blocks(for: ChapterRef(.psalms, 23))
            .flatMap(\.fragments).first { $0.verse == 3 })
        #expect(fragment.footnotes.isEmpty)
    }

    @Test func stripsCharacterMarkupButKeepsItsStyle() throws {
        let bible = try extract([Self.psalm23])
        #expect(bible.verses[VerseRef(.psalms, 23, 1)]?.text == "Jehovah is my shepherd; I shall not want.")
        // \\w ... |strong="…" must not leak its attributes.
        #expect(bible.verses[VerseRef(.psalms, 23, 2)]?.text == "He maketh me to lie down in green pastures.")
        let fragment = try #require(bible.blocks(for: ChapterRef(.psalms, 23))
            .flatMap(\.fragments).first { $0.verse == 1 })
        let scalars = Array(fragment.text.unicodeScalars)
        func styled(_ style: StyledSpan.Style) -> [String] {
            fragment.spans.filter { $0.style == style }.map {
                String(String.UnicodeScalarView(scalars[$0.start..<($0.start + $0.length)]))
            }
        }
        #expect(styled(.smallCaps) == ["Jehovah"])
        #expect(styled(.supplied) == ["not"])
    }

    @Test func psalmTitlesAreUnnumbered() throws {
        let bible = try extract([Self.psalm23])
        let title = try #require(bible.blocks(for: ChapterRef(.psalms, 23)).first { $0.kind == .title })
        #expect(title.fragments.map(\.text) == ["A Psalm of David."])
        #expect(title.fragments.allSatisfy { !$0.numbered })
        // The superscription is not verse 1's text.
        #expect(bible.verses[VerseRef(.psalms, 23, 1)]?.text.contains("A Psalm of David") == false)
        // …and verse 1 still prints its number, on the line that follows.
        let lines = bible.blocks(for: ChapterRef(.psalms, 23)).filter { $0.kind != .title }
        let first = try #require(lines.flatMap(\.fragments).first { $0.verse == 1 })
        #expect(first.numbered)
    }

    @Test func redLettersRunAcrossAVerseBoundary() throws {
        let bible = try extract([Self.matthew5])
        for verse in [3, 4] {
            let row = try #require(bible.verses[VerseRef(.matthew, 5, verse)])
            let span = try #require(row.red.first, "verse \(verse) lost its red letters")
            #expect(span.start == 0)
            #expect(span.length == row.text.unicodeScalars.count)
        }
        // The style closes with \\wj*, so verse 5 is not red.
        #expect(bible.verses[VerseRef(.matthew, 5, 5)]?.red.isEmpty == true)
        #expect(bible.verses[VerseRef(.matthew, 5, 1)]?.red.isEmpty == true)
    }

    @Test func redLettersCanBeTurnedOff() throws {
        let bible = try USFMImporter(options: .init(redLetters: false))
            .extract(files: [("mat.usfm", Self.matthew5)])
        #expect(bible.verses[VerseRef(.matthew, 5, 3)]?.text == "Blessed are the poor in spirit:")
        #expect(bible.verses[VerseRef(.matthew, 5, 3)]?.red.isEmpty == true)
    }

    // MARK: - Verse numbering decisions

    @Test func aBridgedVerseIsStoredUnderItsFirstNumber() throws {
        let bible = try extract(["""
            \\id JON
            \\c 1
            \\p
            \\v 1-2 Now the word of Jehovah came unto Jonah, saying, Arise, go to Nineveh.
            \\v 3 But Jonah rose up to flee.
            """])
        #expect(bible.verses[VerseRef(.jonah, 1, 1)]?.text.hasPrefix("Now the word of Jehovah") == true)
        #expect(bible.verses[VerseRef(.jonah, 1, 2)] == nil)
        #expect(bible.bridgedVerses[VerseRef(.jonah, 1, 2)] == VerseRef(.jonah, 1, 1))
        // A combined verse is not a gap.
        let report = ImportCoverageReport(bible)
        #expect(report.books.first?.chaptersWithGaps.isEmpty == true)
        #expect(report.notes.contains { $0.message.contains("printed combined") })
    }

    @Test func aPartialVerseKeepsOneRowAndOneNumber() throws {
        let bible = try extract(["""
            \\id JOL
            \\c 2
            \\p
            \\v 28a And it shall come to pass afterward,
            \\v 28b that I will pour out my Spirit upon all flesh.
            """])
        #expect(bible.verses[VerseRef(.joel, 2, 28)]?.text
                == "And it shall come to pass afterward, that I will pour out my Spirit upon all flesh.")
        let fragments = bible.blocks(for: ChapterRef(.joel, 2)).flatMap(\.fragments).filter { $0.verse == 28 }
        #expect(fragments.count == 2)
        #expect(fragments.filter(\.numbered).count == 1)
    }

    @Test func reportsVersesThatRanBackwards() throws {
        let bible = try extract(["""
            \\id OBA
            \\c 1
            \\p
            \\v 1 The vision of Obadiah.
            \\v 3 The pride of thy heart hath deceived thee.
            \\v 2 Behold, I have made thee small among the nations.
            """])
        #expect(bible.outOfOrderChapters.contains(ChapterRef(.obadiah, 1)))
        let report = ImportCoverageReport(bible)
        #expect(report.problems.contains { $0.contains("out of order") })
    }

    // MARK: - Books

    @Test func readsAMultiBookZipInCanonicalOrder() throws {
        let data = ImportFixtures.usfmZip([
            ("70-JHNasb.usfm", "\\id JHN\n\\c 1\n\\p\n\\v 1 In the beginning was the Word.\n"),
            ("01-GENasb.usfm", Self.genesis),
            ("19-PSAasb.usfm", Self.psalm23),
        ])
        let package = try USFMPackage(data: data)
        #expect(package.files.count == 3)
        let bible = try USFMImporter().extract(from: package)
        #expect(bible.books == [.genesis, .psalms, .john])
        #expect(bible.chapterOrder.first == ChapterRef(.genesis, 1))
        #expect(bible.verses[VerseRef(.john, 1, 1)]?.text == "In the beginning was the Word.")
    }

    @Test func reportsAnUnknownBookCodeRatherThanGuessing() throws {
        let bible = try extract([
            "\\id ZZZ Some Apocryphal Book\n\\c 1\n\\p\n\\v 1 Invented text.\n",
            "\\id GEN\n\\c 1\n\\p\n\\v 1 In the beginning God created.\n",
        ])
        #expect(bible.books == [.genesis])
        #expect(bible.notes.contains { $0.message.contains("unknown book code “ZZZ”") })
        #expect(bible.verses.count == 1)
    }

    @Test func mapsEveryCanonicalCode() throws {
        for book in BookID.allCases {
            #expect(USFMBookParser.book(forCode: book.code) == book, "\(book.code) did not map back to \(book.name)")
        }
        #expect(USFMBookParser.bookCode(in: "\\id 1JN - Antique\n\\c 1\n") == "1JN")
        #expect(USFMBookParser.book(forCode: "1JN") == .firstJohn)
    }

    @Test func failsCleanlyWhenNothingIsScripture() throws {
        let data = ImportFixtures.usfmZip([("notes.usfm", "\\id ZZZ\n\\c 1\n\\p\n\\v 1 Nothing canonical.\n")])
        #expect(throws: BibleImportError.noScriptureFound) {
            _ = try USFMImporter().extract(from: try USFMPackage(data: data))
        }
    }

    @Test func refusesAProtectedUSFMArchive() throws {
        var entries = [ImportFixtures.ZipEntry("01-GEN.usfm", Self.genesis)]
        entries.append(ImportFixtures.ZipEntry("META-INF/encryption.xml", "<encryption/>"))
        let data = ImportFixtures.zip(entries)
        #expect(throws: BibleImportError.protectedByDRM(.encryptionManifest)) { _ = try USFMPackage(data: data) }
    }

    // MARK: - Metadata

    @Test func carriesTheCopyrightPageIntoTheIdentity() throws {
        let data = ImportFixtures.usfmZip([("01-GEN.usfm", Self.genesis)],
                                          copyright: "Copyright © 2026 Example Press. Released into the Public Domain.")
        let package = try USFMPackage(data: data)
        #expect(package.metadata.copyright == "Copyright © 2026 Example Press. Released into the Public Domain.")
        #expect(package.metadata.license == "Public domain")
        let identity = ImportedTranslationIdentity.suggested(from: package.metadata)
        #expect(identity.copyright == "Copyright © 2026 Example Press. Released into the Public Domain.")
        #expect(identity.license == "Public domain")
        #expect(identity.source == "Imported USFM")
    }

    @Test func readsDigitalBibleLibraryMetadata() throws {
        let metadataXML = """
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
            """
        let data = ImportFixtures.usfmZip([("01-GEN.usfm", Self.genesis)],
                                          copyright: nil, metadataXML: metadataXML)
        let package = try USFMPackage(data: data)
        #expect(package.metadata.title == "Antique Standard Bible")
        #expect(package.metadata.abbreviation == "asb")
        #expect(package.metadata.language == "eng")
        #expect(package.metadata.copyright == "Public Domain")
        let identity = ImportedTranslationIdentity.suggested(from: package.metadata)
        #expect(identity.name == "Antique Standard Bible")
        #expect(identity.abbreviation == "ASB")
        #expect(identity.license == "Public domain")
    }

    @Test func aLicenceLineIsRequiredBeforeAnythingIsWritten() throws {
        let data = ImportFixtures.usfmZip([("01-GEN.usfm", Self.genesis)], copyright: nil)
        let url = try ImportFixtures.write(data, named: "translation_usfm.zip")
        let importer = BibleFileImporter()
        #expect(try importer.preview(url).hasCopyright == false)
        #expect(throws: BibleImportError.missingCopyright) {
            _ = try importer.importBible(at: url, into: try ImportFixtures.scratchDirectory())
        }
    }

    // MARK: - Coverage

    @Test func reportsPartialCoverage() throws {
        let bible = try extract(["""
            \\id GEN
            \\c 1
            \\p
            \\v 1 One.
            \\v 2 Two.
            \\c 4
            \\p
            \\v 2 Two.
            """])
        let report = ImportCoverageReport(bible)
        let genesis = try #require(report.books.first)
        #expect(genesis.chaptersFound == 2)
        #expect(genesis.chaptersExpected == 50)
        #expect(genesis.missingChapters.prefix(3) == [2, 3, 5])
        let gap = try #require(genesis.chaptersWithGaps.first { $0.chapter == 4 })
        #expect(gap.missingVerses == [1])
        #expect(report.summary.contains("3 verses"))
        #expect(report.markupShapes.values.allSatisfy { $0 == .usfmMarkers })
    }

    // MARK: - One entry point, end to end

    @Test func importsAUSFMZipEndToEnd() throws {
        let data = ImportFixtures.usfmZip([
            ("01-GEN.usfm", Self.genesis),
            ("19-PSA.usfm", Self.psalm23),
            ("40-MAT.usfm", Self.matthew5),
        ])
        let url = try ImportFixtures.write(data, named: "engasb_usfm.zip")
        let importer = BibleFileImporter()
        let preview = try importer.preview(url)
        #expect(preview.format == .usfmZip)
        #expect(preview.documentCount == 3)
        #expect(preview.hasCopyright)

        let result = try importer.importBible(at: url, into: try ImportFixtures.scratchDirectory())
        #expect(result.format == .usfmZip)
        #expect(result.report.totalVerses == 12)   // Genesis 5 + Psalm 3 + Matthew 4
        #expect(result.report.booksFound == [.genesis, .psalms, .matthew])

        let store = try BibleStore(url: result.storeURL)
        #expect(store.info.copyright.contains("Example Press"))
        #expect(store.info.license == "Public domain")
        #expect(store.verseCount(ChapterRef(.genesis, 1)) == 5)
        #expect(try store.verses(in: VerseRange(VerseRef(.psalms, 23, 1))).first?.text
                == "Jehovah is my shepherd; I shall not want.")

        // The layout the reader draws: heading, poetry levels, red letters, footnotes.
        let layout = try store.layout(for: ChapterRef(.genesis, 1))
        #expect(layout.blocks.first?.kind == .heading)
        #expect(layout.blocks.contains { $0.kind == .poetry1 })
        #expect(layout.blocks.contains { $0.kind == .stanzaBreak })
        let footnote = try #require(layout.blocks.flatMap(\.fragments).flatMap(\.footnotes).first)
        #expect(footnote.text == "Or Let light be")

        let psalm = try store.layout(for: ChapterRef(.psalms, 23))
        let smallCaps = psalm.blocks.flatMap(\.fragments).flatMap(\.spans).filter { $0.style == .smallCaps }
        #expect(smallCaps.count == 1)

        let matthew = try store.verses(in: VerseRange(VerseRef(.matthew, 5, 3), VerseRef(.matthew, 5, 4)))
        #expect(matthew.allSatisfy { !$0.red.isEmpty })
        #expect(try store.search("shepherd").map(\.ref) == [VerseRef(.psalms, 23, 1)])
    }

    // MARK: - Regressions

    /// `\d` then `\v 1` with no paragraph marker between: verse 1 is stored, whether the title had
    /// its own text first or the verse was all the title held.
    @Test func aVerseAfterATitleWithNoParagraphMarkerIsStored() throws {
        let bible = try extract(["""
            \\id PSA
            \\c 3
            \\d A Psalm of David, when he fled.
            \\v 1 Jehovah, how are mine adversaries increased!
            \\v 2 Many there are that say of my soul.
            \\c 4
            \\d \\v 1 For the Chief Musician; on stringed instruments.
            \\q1 \\v 2 Answer me when I call.
            \\c 5
            \\d \\v 1 For the Chief Musician.
            \\b
            \\q1 Give ear to my words, O Jehovah.
            \\q1 \\v 2 Hearken unto the voice of my cry.
            """])
        #expect(bible.verses[VerseRef(.psalms, 3, 1)]?.text == "Jehovah, how are mine adversaries increased!")
        #expect(bible.verseNumbers(in: ChapterRef(.psalms, 3)) == [1, 2])
        let psalm3 = bible.blocks(for: ChapterRef(.psalms, 3))
        #expect(psalm3.first { $0.kind == .title }?.fragments.map(\.text) == ["A Psalm of David, when he fled."])
        #expect(psalm3.flatMap(\.fragments).first { $0.verse == 1 }?.numbered == true)
        // A title that was the whole of verse 1 is that verse's text.
        #expect(bible.verses[VerseRef(.psalms, 4, 1)]?.text == "For the Chief Musician; on stringed instruments.")
        #expect(bible.verses[VerseRef(.psalms, 4, 2)]?.text == "Answer me when I call.")
        // The usual shape is unchanged: the superscription is not verse 1, the next line is.
        #expect(bible.verses[VerseRef(.psalms, 5, 1)]?.text == "Give ear to my words, O Jehovah.")
    }

    /// A backslash before digits is not a marker, so the digits are not lost.
    @Test func aBackslashBeforeDigitsIsText() throws {
        let bible = try extract(["""
            \\id GEN
            \\c 1
            \\p
            \\v 1 In the \\123 beginning.
            """])
        #expect(bible.verses[VerseRef(.genesis, 1, 1)]?.text == "In the \\123 beginning.")
    }

    @Test func stripsSoftHyphens() throws {
        let bible = try extract(["\\id GEN\n\\c 1\n\\s1 The Cre\u{00AD}ation\n\\p\n\\v 1 In the be\u{00AD}ginning God created."])
        #expect(bible.verses[VerseRef(.genesis, 1, 1)]?.text == "In the beginning God created.")
        #expect(bible.blocks(for: ChapterRef(.genesis, 1)).first?.heading == "The Creation")
    }

    /// The licence tail is cut from the original text, found without regard to case — not at an
    /// index found in a lowercased copy, which "İ" (two scalars once lowercased) throws off.
    @Test func findsACreativeCommonsLicenceAfterALengthChangingLetter() throws {
        let line = USFMPackage.licenseLine("İİİ İstanbul Bible Society. Licensed under a CREATIVE COMMONS Attribution 4.0 licence.")
        #expect(line == "CREATIVE COMMONS Attribution 4.0 licence.")
    }

    /// The entry point decides by structure, so a USFM zip and an ePub can be handed to the same
    /// call without the UI sniffing anything.
    @Test func tellsTheTwoFormatsApart() throws {
        let usfm = try ImportFixtures.write(ImportFixtures.usfmZip([("01-GEN.usfm", Self.genesis)]),
                                            named: "anything.zip")
        let epub = try ImportFixtures.write(ImportFixtures.epub(documents: [
            ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"),
        ]), named: "anything-else.zip")
        #expect(try BibleFileImporter().preview(usfm).format == .usfmZip)
        #expect(try BibleFileImporter().preview(epub).format == .epub)

        let neither = try ImportFixtures.write(ImportFixtures.zip([ImportFixtures.ZipEntry("a.txt", "hello")]),
                                               named: "neither.zip")
        #expect(throws: BibleImportError.unsupportedFormat("it is neither an ePub nor a set of USFM books")) {
            _ = try BibleFileImporter().preview(neither)
        }
    }
}
