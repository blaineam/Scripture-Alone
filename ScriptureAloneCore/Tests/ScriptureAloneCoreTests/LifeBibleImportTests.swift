import Foundation
import Testing
@testable import ScriptureAloneCore

/// The Life Bible (formerly Tecarta) export, read back.
///
/// The fixtures here are written to the shape of a real export — non-breaking space between book
/// and chapter, the translation trailing the reference, `<br>` between everything — because that
/// shape is the whole difficulty. A parser tested against tidy invented markup would pass and then
/// mangle somebody's study notes.
@Suite struct LifeBibleImportTests {

    static func html(_ body: String) -> String {
        "<!DOCTYPE html><html><head><style>body {margin: 2em;}</style></head><body>\(body)</body></html>"
    }

    /// The separator really is U+00A0. Splitting on a normal space finds nothing, which would make
    /// every reference in the file unresolvable — so this is asserted first and on its own.
    @Test func theBookAndChapterAreSeparatedByANonBreakingSpace() throws {
        let reference = try #require(LifeBibleImport.reference(in: "Genesis\u{00A0}3:1 ABCD"))
        #expect(reference.range?.start == VerseRef(.genesis, 3, 1))
        #expect(reference.translation == "ABCD")
        // And the same string with an ordinary space still works, in case they ever change it.
        #expect(LifeBibleImport.reference(in: "Genesis 3:1 ABCD")?.range?.start == VerseRef(.genesis, 3, 1))
    }

    @Test func numberedBooksAndVersionsWithDigitsSurvive() throws {
        let chronicles = try #require(LifeBibleImport.reference(in: "1 Chronicles\u{00A0}29:14"))
        #expect(chronicles.range?.start == VerseRef(.firstChronicles, 29, 14))
        #expect(chronicles.translation == nil)   // saves carry no translation

        let abc95 = try #require(LifeBibleImport.reference(in: "Genesis\u{00A0}28:1 ABC95"))
        #expect(abc95.range?.start == VerseRef(.genesis, 28, 1))
        #expect(abc95.translation == "ABC95")
    }

    /// A reference this app cannot place must not be approximated into one it can.
    @Test func whatCannotBeResolvedIsReportedRatherThanGuessed() {
        #expect(LifeBibleImport.reference(in: "Enchiridion\u{00A0}4:2 XYZ") == nil)
        #expect(LifeBibleImport.reference(in: "Some Devotional, para. 4") == nil)
    }

    @Test func highlightsCarryTheirVerseAndNearestColor() throws {
        let file = Self.html("""
        Genesis\u{00A0}3:1 ABCD  #ffc9e4<br>Genesis\u{00A0}17:16 ABC  #fff193<br>\
        Genesis\u{00A0}22:1 ABC  #b3e487<br>John\u{00A0}3:16 ABC  #cae1fe<br>\
        underline Psalms\u{00A0}23:1 ABC  #999999  words: 2-5<br>
        """)
        var result = ImportedNotes()
        LifeBibleImport.readHighlights(file, into: &result)

        #expect(result.highlights.count == 5)
        #expect(result.unresolved.isEmpty)
        #expect(result.highlights[0].verse == VerseRef(.genesis, 3, 1))
        #expect(result.highlights[0].color == "pink")
        #expect(result.highlights[1].color == "yellow")
        #expect(result.highlights[2].color == "green")
        #expect(result.highlights[3].color == "blue")
        // Underlines and word offsets are styles this app has no equivalent for. The highlight
        // still arrives — losing it would be the worse trade — in the nearest color it has.
        #expect(result.highlights[4].verse == VerseRef(.psalms, 23, 1))
        #expect(ImportedNotes.highlightColorNames.contains(result.highlights[4].color))
    }

    /// Life Bible's palette is pale where this app's is saturated, so these must be matched by hue.
    /// Matching by RGB distance files their blue under purple — the wrong colour, silently.
    @Test func eachOfLifeBiblesPaletteColorsLandsOnItsOwnHue() {
        #expect(LifeBibleImport.nearestColor(toHex: "#cae1fe") == "blue")
        #expect(LifeBibleImport.nearestColor(toHex: "#fff193") == "yellow")
        #expect(LifeBibleImport.nearestColor(toHex: "#ffc9e4") == "pink")
        #expect(LifeBibleImport.nearestColor(toHex: "#b3e487") == "green")
        // Grey has no hue to match; it goes to the palest colour rather than an arbitrary one.
        #expect(LifeBibleImport.nearestColor(toHex: "#999999") == "purple")
        // A colour nobody's palette has still lands somewhere sensible.
        #expect(LifeBibleImport.nearestColor(toHex: "#ff0000") == "pink")
    }

    @Test func verseNotesKeepTheirReferenceAndTheirText() throws {
        let file = Self.html("""
        <p>Genesis\u{00A0}2:18 ABC<br>God's design for marriage <br>Line two</p>\
        <p>Genesis\u{00A0}22:1 ABC<br>How to pass a test:<br>Will you trust God's will.<br><br>\
        Abraham obeyed God</p>
        """)
        var result = ImportedNotes()
        LifeBibleImport.readVerseNotes(file, into: &result)

        #expect(result.verseNotes.count == 2)
        #expect(result.verseNotes[0].range?.start == VerseRef(.genesis, 2, 18))
        #expect(result.verseNotes[0].translation == "ABC")
        #expect(result.verseNotes[0].body == "God's design for marriage\nLine two")
        // The author's own paragraph break survives: `<br><br>` is how they separated two
        // thoughts, and flattening it would rewrite their note.
        #expect(result.verseNotes[1].body.contains("Will you trust God's will.\n\nAbraham obeyed God"))
    }

    @Test func savedVersesComeAcross() throws {
        var result = ImportedNotes()
        LifeBibleImport.readSaves(Self.html("1 John\u{00A0}1:1<br>Philippians\u{00A0}4:6<br>"), into: &result)
        #expect(result.saved.count == 2)
        #expect(result.saved[0].start == VerseRef(.firstJohn, 1, 1))
        #expect(result.saved[1].start == VerseRef(.philippians, 4, 6))
    }

    @Test func aJournalEntryTakesItsTitleFromItsFirstLine() throws {
        var result = ImportedNotes()
        LifeBibleImport.readJournal(Self.html("The Doctrine of God. <br>The Bible assumes the existence of God"),
                                    leaf: "the-doctrine-of-god.html", folders: [], into: &result)
        let entry = try #require(result.journals.first)
        #expect(entry.range == nil)
        // The slug lost the capitals; the body's own first line kept them.
        #expect(entry.title == "The Doctrine of God")
        #expect(entry.body.contains("The Bible assumes the existence of God"))
    }

    @Test func entitiesAndTagsBecomeOrdinaryText() {
        let text = LifeBibleImport.text(of: "Grace &amp; peace<br><b>bold</b> &rsquo;tis<br>&nbsp;")
        #expect(text == "Grace & peace\nbold ’tis")
    }

    /// Found in a real note, on screen, after the first import ran: the export writes a slash as
    /// `&#47;`, so a sermon dated 06/11/22 arrived as `06&#47;11&#47;22`. A fixed list of named
    /// entities let it through, which is why numeric references are decoded generally.
    @Test func numericEntitiesAreDecodedToo() {
        #expect(LifeBibleImport.text(of: "Disciples Church 06&#47;11&#47;22") == "Disciples Church 06/11/22")
        #expect(LifeBibleImport.text(of: "God&#39;s design") == "God's design")
        #expect(LifeBibleImport.text(of: "hex &#x27;quoted&#x27;") == "hex 'quoted'")
        // A literal, escaped entity in someone's note must not be decoded a second time.
        #expect(LifeBibleImport.text(of: "type &amp;#39; for an apostrophe") == "type &#39; for an apostrophe")
    }

    // MARK: The real export

    /// Blaine's own export, if it is on this machine — outside the repository, because it is years
    /// of personal study notes and belongs in version control less than almost anything.
    ///
    /// The fixtures above are written from this file's real shape; this test is what keeps that
    /// claim honest. It asserts nothing about the contents, only that the whole archive parses and
    /// that nothing was quietly dropped.
    static let realExport = URL(fileURLWithPath: NSHomeDirectory())
        .appending(path: ".scripture-alone-import/LifeBibleData.zip")

    @Test func theRealExportParsesWithNothingLeftBehind() throws {
        guard FileManager.default.fileExists(atPath: Self.realExport.path) else { return }
        let result = try LifeBibleImport.read(archive: try Data(contentsOf: Self.realExport))

        #expect(result.verseNotes.count > 400)
        #expect(result.highlights.count > 250)
        #expect(!result.journals.isEmpty)
        #expect(!result.saved.isEmpty)
        // Every note must have landed on a verse, and every note must have text.
        #expect(result.verseNotes.allSatisfy { $0.range != nil && !$0.body.isEmpty })
        #expect(result.journals.allSatisfy { !$0.title.isEmpty && !$0.body.isEmpty })
        // Markup must not have leaked into anybody's notes.
        for note in result.verseNotes + result.journals {
            #expect(!note.body.contains("<br>"))
            #expect(note.body.range(of: "&#?[a-zA-Z0-9]+;", options: .regularExpression) == nil,
                    "undecoded entity in \(note.title)")
        }
        #expect(result.unresolved.isEmpty, "unresolved: \(result.unresolved.prefix(5))")
    }

    /// Genesis 1 has 31 verses. Walking integer keys from 1:30 to 2:2 would pass 1:31…1:999 and 2:0
    /// — nearly a thousand phantom highlights. It must be exactly the five real verses.
    @Test func aHighlightAcrossAChapterBreakCoversOnlyRealVerses() {
        let file = Self.html("Genesis\u{00A0}1:30-2:2 ABC  #b3e487<br>")
        var result = ImportedNotes()
        LifeBibleImport.readHighlights(file, verseCount: { $0 == ChapterRef(.genesis, 1) ? 31 : 25 },
                                       into: &result)
        #expect(result.highlights.map(\.verse.key) == [1_001_030, 1_001_031, 1_002_001, 1_002_002])
        #expect(result.highlights.allSatisfy { $0.color == "green" })
    }

    /// A range over a whole chapter in between, and across a book boundary.
    @Test func versesWalkWholeChaptersAndCrossBooks() {
        let counts: (ChapterRef) -> Int = { [ChapterRef(.genesis, 50): 26, ChapterRef(.exodus, 1): 22,
                                             ChapterRef(.exodus, 2): 25][$0] ?? 0 }
        let range = VerseRange(VerseRef(.genesis, 50, 25), VerseRef(.exodus, 2, 1))
        let keys = LifeBibleImport.verses(in: range, verseCount: counts).map(\.key)
        #expect(keys == [1_050_025, 1_050_026] + (1...22).map { 2_001_000 + $0 } + [2_002_001])
    }

    /// With no counts, nothing is invented: only the verses the reference names come across.
    @Test func withoutVerseCountsOnlyNamedVersesAreHighlighted() {
        let range = VerseRange(VerseRef(.genesis, 1, 30), VerseRef(.genesis, 2, 2))
        #expect(LifeBibleImport.verses(in: range, verseCount: nil).map(\.key)
                == [1_001_030, 1_002_001, 1_002_002])
        let within = VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 18))
        #expect(LifeBibleImport.verses(in: within, verseCount: nil).map(\.key)
                == [43_003_016, 43_003_017, 43_003_018])
    }
}
