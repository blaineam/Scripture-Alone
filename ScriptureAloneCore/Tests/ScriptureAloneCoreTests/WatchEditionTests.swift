import Foundation
import SQLite3
import Testing
@testable import ScriptureAloneCore

/// The watch edition the phone writes for an imported translation, checked against the real
/// bundled stores (which have the same schema an imported one does).
@Suite struct WatchEditionTests {

    func edition(of id: String) throws -> (URL, URL) {
        let source = BibleStoreTests.biblesDirectory.appending(path: "\(id).sqlite")
        let destination = FileManager.default.temporaryDirectory
            .appending(path: "watch-edition-\(UUID().uuidString)/\(id)-Watch.sqlite")
        try WatchEdition.write(from: source, to: destination)
        return (source, destination)
    }

    /// The watch reads the edition through the same `BibleStore` as a full store, so every verse
    /// the full store has must still be there — including red-letter spans.
    @Test func keepsEveryVerseAndReadsThroughBibleStore() throws {
        let (source, destination) = try edition(of: "BSB")
        let full = try BibleStore(url: source)
        let watch = try BibleStore(url: destination)

        #expect(watch.info.abbreviation == full.info.abbreviation)
        #expect(watch.verseCount(ChapterRef(.psalms, 119)) == 176)
        let range = VerseRange(VerseRef(.john, 3, 1), VerseRef(.john, 3, 36))
        let a = try full.verses(in: range)
        let b = try watch.verses(in: range)
        #expect(a.count == 36)
        #expect(a.map(\.text) == b.map(\.text))
        #expect(b.contains { !$0.red.isEmpty }, "red letters were dropped")
    }

    /// The whole point: the layout JSON and search index go, which is most of the size.
    @Test func dropsTheLayoutAndSearchIndexAndMarksItsEdition() throws {
        let (source, destination) = try edition(of: "KJV")
        var db: OpaquePointer?
        #expect(sqlite3_open_v2(destination.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK)
        defer { sqlite3_close(db) }

        func scalar(_ sql: String) -> String? {
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { return nil }
            defer { sqlite3_finalize(st) }
            guard sqlite3_step(st) == SQLITE_ROW, let text = sqlite3_column_text(st, 0) else { return nil }
            return String(cString: text)
        }
        #expect(scalar("SELECT value FROM meta WHERE key = 'edition'") == "watch")
        #expect(scalar("SELECT count(*) FROM verses") == "31102")          // the KJV's own count
        #expect(scalar("SELECT count(*) FROM sqlite_master WHERE name LIKE 'verses_fts%'") == "0")
        #expect(scalar("SELECT count(*) FROM pragma_table_info('chapters') WHERE name = 'layout'") == "0")

        let full = try #require(try? source.resourceValues(forKeys: [.fileSizeKey]).fileSize)
        let small = try #require(try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize)
        #expect(small * 2 < full, "watch edition is \(small) bytes against \(full)")
    }

    /// A second write replaces the first rather than failing because the file exists.
    @Test func rewritingReplacesTheExistingEdition() throws {
        let (source, destination) = try edition(of: "BSB")
        try WatchEdition.write(from: source, to: destination)
        #expect(try BibleStore(url: destination).verseCount(ChapterRef(.john, 3)) == 36)
    }

    /// A locale Bible sent to the watch keeps its own numbering: marks on the watch still land on
    /// the right verse (docs/localization.md).
    @Test func aLocaleEditionCarriesItsNumbering() throws {
        let source = BibleStoreTests.biblesDirectory.appending(path: "LSG.sqlite")
        let edition = FileManager.default.temporaryDirectory.appending(path: "LSG-\(UUID().uuidString)-Watch.sqlite")
        defer { try? FileManager.default.removeItem(at: edition) }
        try WatchEdition.write(from: source, to: edition)
        let store = try BibleStore(url: edition)
        #expect(store.numbering.kjv(forNative: VerseRef(.psalms, 51, 12).key) == VerseRef(.psalms, 51, 10).key)
        #expect(store.language == "fr")
    }
}
