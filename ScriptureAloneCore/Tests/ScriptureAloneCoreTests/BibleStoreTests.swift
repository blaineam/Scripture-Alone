import Foundation
import Testing
@testable import ScriptureAloneCore

/// Runs against the databases the app bundles (built by Tools/build_bibles.py).
@Suite struct BibleStoreTests {
    static let biblesDirectory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "ScriptureAlone/Resources/Bibles")

    func store(_ id: String) throws -> BibleStore {
        try BibleStore(url: Self.biblesDirectory.appending(path: "\(id).sqlite"))
    }

    @Test func readsMetadataAndCounts() throws {
        let asv = try store("ASV")
        #expect(asv.info.abbreviation == "ASV")
        #expect(asv.verseCount(ChapterRef(.psalms, 119)) == 176)
        #expect(asv.verseCount(ChapterRef(.john, 3)) == 36)
        for book in BookID.allCases {
            #expect(asv.contains(ChapterRef(book, book.chapterCount)), "\(book.name) is missing its last chapter")
        }
    }

    @Test func decodesChapterLayoutWithRedLetters() throws {
        let layout = try store("ASV").layout(for: ChapterRef(.john, 14))
        let fragments = layout.blocks.flatMap(\.fragments)
        let verse6 = try #require(fragments.first { $0.verse == 6 && $0.numbered })
        let red = try #require(verse6.spans.first { $0.style == .wordsOfChrist })
        let scalars = Array(verse6.text.unicodeScalars)
        let spoken = String(String.UnicodeScalarView(scalars[red.start..<(red.start + red.length)]))
        #expect(spoken.hasPrefix("I am the way"))
        #expect(layout.blocks.first?.kind == .heading)
    }

    @Test func psalmTitlesAreUnnumbered() throws {
        let layout = try store("BSB").layout(for: ChapterRef(.psalms, 23))
        let title = try #require(layout.blocks.first { $0.kind == .title })
        #expect(title.fragments.allSatisfy { !$0.numbered })
        let firstLine = try #require(layout.blocks.first { $0.kind == .poetry1 })
        #expect(firstLine.fragments.first?.numbered == true)
        #expect(try store("BSB").verses(in: VerseRange(VerseRef(.psalms, 23, 1))).first?.text == "The LORD is my shepherd; I shall not want.")
    }

    @Test func versesCarryRedRangesInUTF16() throws {
        let verses = try store("BSB").verses(in: VerseRange(VerseRef(.john, 1, 38)))
        let verse = try #require(verses.first)
        let red = try #require(verse.red.first)
        #expect((verse.text as NSString).substring(with: red) == "“What do you want?”")
    }

    @Test func searchesInCanonicalOrder() throws {
        let hits = try store("ASV").search("good shep")
        #expect(hits.first?.ref == VerseRef(.john, 10, 11))
        let phrase = try store("KJV").search("\"Jesus wept\"")
        #expect(phrase.map(\.ref) == [VerseRef(.john, 11, 35)])
        #expect(try store("KJV").search("  ").isEmpty)
    }
}
