import Foundation
import SQLite3
import Testing
@testable import ScriptureAloneCore

/// The Study context in the reader's language (docs/localization.md): a copy of the bundled
/// Context.sqlite given a few French rows, read back through ContextStore.
@Suite struct ContextLocalizationTests {
    static func frenchCopy() throws -> URL {
        let source = ContextStoreTests.studyDirectory.appending(path: "Context.sqlite")
        let copy = FileManager.default.temporaryDirectory.appending(path: "ctx-\(UUID().uuidString).sqlite")
        try FileManager.default.copyItem(at: source, to: copy)
        var db: OpaquePointer?
        guard sqlite3_open(copy.path, &db) == SQLITE_OK, let db else { throw BibleStoreError.open("copy") }
        defer { sqlite3_close(db) }
        let sql = """
            DROP TABLE IF EXISTS translations;
            CREATE TABLE translations (lang TEXT NOT NULL, kind TEXT NOT NULL, source TEXT NOT NULL,
                text TEXT NOT NULL, PRIMARY KEY (lang, kind, source)) WITHOUT ROWID;
            INSERT INTO translations VALUES ('fr', 'place', 'a15257a', 'Jérusalem');
            INSERT INTO translations VALUES ('fr', 'string', 'Exodus & Wilderness', 'L’Exode et le désert');
            INSERT INTO translations VALUES ('fr', 'string', 'March–April', 'mars–avril');
            INSERT INTO translations VALUES ('fr', 'string', 'Mediterranean Sea', 'Mer Méditerranée');
            """
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw BibleStoreError.query("seed") }
        return copy
    }

    func feasts(_ store: ContextStore) throws -> FeastsChart {
        let chart = try #require(try store.charts().first { $0.kind == .feasts })
        return try JSONDecoder().decode(FeastsChart.self, from: chart.body)
    }

    @Test func frenchNamesAndProse() throws {
        let store = try ContextStore(url: Self.frenchCopy(), language: "fr-CA")
        #expect(store.language == "fr")
        let jerusalem = try #require(try store.searchPlaces("Jérusalem").first)
        #expect(jerusalem.name == "Jérusalem")
        #expect(try store.eras().first { $0.id == "exodus" }?.name == "L’Exode et le désert")
        #expect(try store.labels().contains { $0.text == "Mer Méditerranée" })
        // Untranslated text falls back to the English rather than disappearing.
        #expect(try store.eras().first { $0.id == "patriarchs" }?.name == "The Patriarchs")
    }

    @Test func feastSeasonsSurviveTranslation() throws {
        let french = try feasts(ContextStore(url: Self.frenchCopy(), language: "fr"))
        #expect(french.feasts.contains { $0.season == "mars–avril" && $0.seasonGroup == "spring" })
        #expect(french.feasts.filter { $0.seasonGroup == "autumn" }.count == 3)
        // The English quotation gives way to the reader's own Bible.
        #expect(french.feasts.allSatisfy { $0.ntText == nil })
    }

    @Test func englishIsUntouched() throws {
        let english = try ContextStore(url: Self.frenchCopy(), language: nil)
        #expect(english.language == nil)
        #expect(try english.eras().first { $0.id == "exodus" }?.name == "Exodus & Wilderness")
        #expect(try feasts(english).feasts.contains { $0.ntText != nil })
    }

    @Test func traditionalChineseGetsNoSimplifiedRows() throws {
        let store = try ContextStore(url: Self.frenchCopy(), language: "zh-Hant")
        #expect(store.language == nil)
    }
}
