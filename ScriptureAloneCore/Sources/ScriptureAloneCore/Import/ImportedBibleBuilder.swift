// Importing is an iPhone, iPad and Mac feature: the watch has no file picker and no
// catalogue. It is also 32-bit (arm64_32), where the ZIP64 sentinel 0xFFFF_FFFF does not
// fit in an Int at all — so this code is not merely unused there, it cannot compile.
#if !os(watchOS)
import Foundation
import SQLite3

/// The `meta` row an imported translation carries. The copyright line is not optional: it is the
/// publisher's attribution, the reader prints it, and the share/export gates read it. An import
/// with nothing to attribute is refused rather than quietly stored.
public struct ImportedTranslationIdentity: Sendable, Hashable, Codable {
    public var id: String
    public var name: String
    public var abbreviation: String
    public var copyright: String
    public var license: String
    public var source: String

    public init(id: String, name: String, abbreviation: String, copyright: String,
                license: String = ImportedTranslationIdentity.unknownLicense,
                source: String = "Imported file") {
        self.id = id
        self.name = name
        self.abbreviation = abbreviation
        self.copyright = copyright
        self.license = license
        self.source = source
    }

    /// An imported file's licence is unknown by definition. "Unknown" must behave like "licensed",
    /// never like "public domain", everywhere the app gates on it.
    public static let unknownLicense = "Unknown — imported by the reader"

    /// A starting point the UI can present for the user to correct. Nothing here is trusted: the
    /// import flow is expected to make the user confirm the name and paste the copyright line.
    public static func suggested(from metadata: EPUBMetadata) -> ImportedTranslationIdentity {
        let title = metadata.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let name = title.isEmpty ? String(localized: "Imported Bible", bundle: .module, comment: "Suggested name for an imported Bible translation that has no title") : title
        var rights = metadata.rights?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if rights.isEmpty, let publisher = metadata.publisher, !publisher.isEmpty {
            rights = "© \(publisher)"
        }
        return ImportedTranslationIdentity(id: identifier(for: metadata.identifier ?? name),
                                           name: name,
                                           abbreviation: abbreviation(for: name),
                                           copyright: rights,
                                           source: metadata.publisher.map { "Imported ePub — \($0)" } ?? "Imported ePub")
    }

    /// Letters of the significant words, so "New Example Standard Bible" suggests "NESB".
    public static func abbreviation(for name: String) -> String {
        // "Bible" is kept: the B at the end of most abbreviations comes from it.
        let skip: Set<String> = ["the", "of", "a", "an", "and", "holy", "version", "edition", "translation"]
        let initials = name.lowercased()
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber })
            .filter { !skip.contains(String($0)) }
            .compactMap { $0.first.map { String($0).uppercased() } }
            .joined()
        if initials.count >= 2 { return String(initials.prefix(6)) }
        let letters = name.filter(\.isLetter).uppercased()
        return letters.isEmpty ? "IMP" : String(letters.prefix(4))
    }

    /// A stable, filename-safe id. Imported stores never collide with a bundled one.
    public static func identifier(for seed: String) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in Array(seed.utf8) {
            hash = (hash ^ UInt64(byte)) &* 0x1000_0000_01b3
        }
        return "IMPORT-" + String(hash % 0xFFFF_FFFF, radix: 36, uppercase: true)
    }
}

/// Writes extracted rows into the same SQLite + FTS5 shape `Tools/build_bibles.py` produces for the
/// bundled translations, so `BibleStore` opens an imported translation with no changes at all:
/// same tables, same compact layout JSON, and the same verse key (book × 1,000,000 +
/// chapter × 1,000 + verse) that highlights and notes are stored against.
public enum ImportedBibleBuilder {
    /// Writes the store and returns the coverage report for what was written.
    @discardableResult
    public static func write(_ bible: ExtractedBible, identity: ImportedTranslationIdentity, to url: URL) throws -> ImportCoverageReport {
        guard !bible.isEmpty else { throw BibleImportError.noScriptureFound }
        guard !identity.copyright.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw BibleImportError.missingCopyright
        }
        let report = ImportCoverageReport(bible)
        guard report.quality.isAcceptable else { throw BibleImportError.poorQuality(report.quality.score) }

        let directory = url.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // A half-written store from a previous attempt must not survive.
        let temporary = directory.appending(path: ".\(url.lastPathComponent).partial")
        // SQLite's rollback journal is "<partial>-journal" (a hyphen, not an extension). A journal
        // left by a crashed write would be replayed into the fresh file, so it goes too; the old
        // "<partial>.journal" spelling is swept as well, for anything an earlier build left behind.
        let journal = directory.appending(path: ".\(url.lastPathComponent).partial-journal")
        for stale in [temporary, journal, temporary.appendingPathExtension("journal")] {
            try? FileManager.default.removeItem(at: stale)
        }

        var handle: OpaquePointer?
        guard sqlite3_open_v2(temporary.path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK,
              let db = handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "could not create the file"
            sqlite3_close(handle)
            throw BibleImportError.databaseWrite(message)
        }
        do {
            // `BibleStore` opens with immutable=1, so the store must be a plain rollback-journal
            // database with nothing left beside it.
            try exec(db, "PRAGMA page_size = 4096; PRAGMA journal_mode = DELETE;")
            try exec(db, schema)
            try exec(db, "BEGIN")
            try writeMeta(db, identity: identity, bible: bible)
            try writeBooks(db, bible: bible)
            try writeChapters(db, bible: bible)
            try writeVerses(db, bible: bible)
            if !bible.study.isEmpty { try writeStudy(db, study: bible.study, name: identity.name) }
            if bible.redLettersInferred { try exec(db, "INSERT OR REPLACE INTO meta VALUES ('red_letters', 'inferred')") }
            try exec(db, "COMMIT")
            try exec(db, "INSERT INTO verses_fts(verses_fts) VALUES ('rebuild')")
            try exec(db, "INSERT INTO verses_fts(verses_fts) VALUES ('optimize')")
            try exec(db, "VACUUM")
        } catch {
            sqlite3_close(db)
            try? FileManager.default.removeItem(at: temporary)
            throw error
        }
        sqlite3_close(db)

        try? FileManager.default.removeItem(at: url)
        do {
            try FileManager.default.moveItem(at: temporary, to: url)
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            throw BibleImportError.databaseWrite(error.localizedDescription)
        }
        return report
    }

    /// Byte-for-byte the shape `Tools/build_bibles.py` creates.
    static let schema = """
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE books (book INTEGER PRIMARY KEY, code TEXT NOT NULL, name TEXT NOT NULL, chapters INTEGER NOT NULL);
        CREATE TABLE chapters (book INTEGER NOT NULL, chapter INTEGER NOT NULL, verses INTEGER NOT NULL,
                               layout TEXT NOT NULL, PRIMARY KEY (book, chapter)) WITHOUT ROWID;
        CREATE TABLE verses (id INTEGER PRIMARY KEY, text TEXT NOT NULL, red TEXT);
        CREATE VIRTUAL TABLE verses_fts USING fts5(text, content='verses', content_rowid='id',
                                                  tokenize='unicode61 remove_diacritics 2');
        """

    // MARK: - Tables

    private static func writeMeta(_ db: OpaquePointer, identity: ImportedTranslationIdentity, bible: ExtractedBible) throws {
        var rows: [(String, String)] = [
            ("id", identity.id),
            ("name", identity.name),
            ("abbreviation", identity.abbreviation),
            ("copyright", identity.copyright),
            ("license", identity.license),
            ("source", identity.source),
            // Read by the app to keep imported text out of every path that could carry it off the
            // device: share links, verse images, exports, widgets, the watch and the Studio hand-off.
            ("imported", "1"),
            ("importedAt", ISO8601DateFormatter().string(from: Date())),
        ]
        rows.append(("books", String(bible.books.count)))
        let statement = try prepare(db, "INSERT INTO meta VALUES (?1, ?2)")
        defer { sqlite3_finalize(statement) }
        for (key, value) in rows {
            sqlite3_reset(statement)
            bind(statement, 1, key)
            bind(statement, 2, value)
            try step(db, statement)
        }
    }

    private static func writeBooks(_ db: OpaquePointer, bible: ExtractedBible) throws {
        let statement = try prepare(db, "INSERT INTO books VALUES (?1, ?2, ?3, ?4)")
        defer { sqlite3_finalize(statement) }
        for book in bible.books {
            let chapters = Set(bible.chapterOrder.filter { $0.book == book }.map(\.chapter)).count
            sqlite3_reset(statement)
            sqlite3_bind_int64(statement, 1, sqlite3_int64(book.rawValue))
            bind(statement, 2, book.code)
            bind(statement, 3, book.name)
            sqlite3_bind_int64(statement, 4, sqlite3_int64(chapters))
            try step(db, statement)
        }
    }

    private static func writeChapters(_ db: OpaquePointer, bible: ExtractedBible) throws {
        let statement = try prepare(db, "INSERT INTO chapters VALUES (?1, ?2, ?3, ?4)")
        defer { sqlite3_finalize(statement) }
        for chapter in bible.chapterOrder.sorted() {
            let layout = try layoutJSON(bible.blocks(for: chapter))
            sqlite3_reset(statement)
            sqlite3_bind_int64(statement, 1, sqlite3_int64(chapter.book.rawValue))
            sqlite3_bind_int64(statement, 2, sqlite3_int64(chapter.chapter))
            sqlite3_bind_int64(statement, 3, sqlite3_int64(bible.highestVerse(in: chapter)))
            bind(statement, 4, layout)
            try step(db, statement)
        }
    }

    private static func writeVerses(_ db: OpaquePointer, bible: ExtractedBible) throws {
        let statement = try prepare(db, "INSERT INTO verses VALUES (?1, ?2, ?3)")
        defer { sqlite3_finalize(statement) }
        for ref in bible.verses.keys.sorted() {
            guard let verse = bible.verses[ref] else { continue }
            sqlite3_reset(statement)
            sqlite3_bind_int64(statement, 1, sqlite3_int64(ref.key))
            bind(statement, 2, verse.text)
            if verse.red.isEmpty {
                sqlite3_bind_null(statement, 3)
            } else {
                let red = try json(verse.red.map { [$0.start, $0.length] })
                bind(statement, 3, red)
            }
            try step(db, statement)
        }
    }

    /// The compact layout JSON the reader decodes: `{"b":[{"k":…,"t":…}|{"k":…,"f":[…]}]}`.
    static func layoutJSON(_ blocks: [ExtractedBlock]) throws -> String {
        var encoded: [Any] = []
        for block in blocks {
            if block.kind == .stanzaBreak {
                encoded.append(["k": block.kind.rawValue] as [String: Any])
                continue
            }
            if block.kind.isHeading {
                encoded.append(["k": block.kind.rawValue, "t": block.heading ?? ""] as [String: Any])
                continue
            }
            var fragments: [Any] = []
            for fragment in block.fragments where !fragment.text.isEmpty || fragment.numbered {
                var row: [String: Any] = ["v": fragment.verse, "t": fragment.text]
                if fragment.numbered { row["n"] = 1 }
                if !fragment.spans.isEmpty {
                    row["s"] = fragment.spans.map { [$0.start, $0.length, $0.style.rawValue] as [Any] }
                }
                if !fragment.footnotes.isEmpty {
                    row["fn"] = fragment.footnotes.map { [$0.position, $0.text] as [Any] }
                }
                fragments.append(row)
            }
            guard !fragments.isEmpty else { continue }
            encoded.append(["k": block.kind.rawValue, "f": fragments] as [String: Any])
        }
        return try json(["b": encoded])
    }

    private static func json(_ object: Any) throws -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.withoutEscapingSlashes]),
              let text = String(data: data, encoding: .utf8) else {
            throw BibleImportError.databaseWrite("could not encode the chapter layout")
        }
        return text
    }

    // MARK: - Study material

    /// A study Bible's own material, in tables of its own beside the text (`ImportedStudyStore`
    /// reads them). The store syncs and is removed as one file, so the notes go wherever the
    /// translation goes and nowhere else.
    static let studySchema = """
        CREATE TABLE study_notes (start_key INTEGER NOT NULL, end_key INTEGER NOT NULL, body TEXT NOT NULL);
        CREATE INDEX study_notes_start ON study_notes (start_key);
        CREATE TABLE study_articles (kind TEXT NOT NULL, book INTEGER NOT NULL, anchor_key INTEGER,
                                     title TEXT NOT NULL, body TEXT NOT NULL);
        CREATE TABLE study_images (id INTEGER PRIMARY KEY, book INTEGER, anchor_key INTEGER,
                                   caption TEXT NOT NULL, media_type TEXT NOT NULL, data BLOB NOT NULL);
        """

    private static func writeStudy(_ db: OpaquePointer, study: ExtractedStudy, name: String) throws {
        try exec(db, studySchema)
        let meta = try prepare(db, "INSERT OR REPLACE INTO meta VALUES (?1, ?2)")
        defer { sqlite3_finalize(meta) }
        for (key, value) in [("study_name", name), ("study_publisher", study.publisher ?? "")] where !value.isEmpty {
            sqlite3_reset(meta)
            bind(meta, 1, key)
            bind(meta, 2, value)
            try step(db, meta)
        }

        let notes = try prepare(db, "INSERT INTO study_notes VALUES (?1, ?2, ?3)")
        defer { sqlite3_finalize(notes) }
        for note in study.orderedNotes where !note.text.isEmpty {
            sqlite3_reset(notes)
            sqlite3_bind_int64(notes, 1, sqlite3_int64(note.start.key))
            sqlite3_bind_int64(notes, 2, sqlite3_int64(note.end.key))
            bind(notes, 3, note.text)
            try step(db, notes)
        }
        let articles = try prepare(db, "INSERT INTO study_articles VALUES (?1, ?2, ?3, ?4, ?5)")
        defer { sqlite3_finalize(articles) }
        for article in study.articles {
            sqlite3_reset(articles)
            bind(articles, 1, article.kind.rawValue)
            sqlite3_bind_int64(articles, 2, sqlite3_int64(article.book.rawValue))
            if let anchor = article.anchor { sqlite3_bind_int64(articles, 3, sqlite3_int64(anchor.key)) } else { sqlite3_bind_null(articles, 3) }
            bind(articles, 4, article.title)
            bind(articles, 5, article.text)
            try step(db, articles)
        }
        let images = try prepare(db, "INSERT INTO study_images (book, anchor_key, caption, media_type, data) VALUES (?1, ?2, ?3, ?4, ?5)")
        defer { sqlite3_finalize(images) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for image in study.images {
            guard let data = image.data else { continue }
            sqlite3_reset(images)
            if let book = image.book { sqlite3_bind_int64(images, 1, sqlite3_int64(book.rawValue)) } else { sqlite3_bind_null(images, 1) }
            if let anchor = image.anchor { sqlite3_bind_int64(images, 2, sqlite3_int64(anchor.key)) } else { sqlite3_bind_null(images, 2) }
            bind(images, 3, image.caption)
            bind(images, 4, image.mediaType)
            _ = data.withUnsafeBytes { sqlite3_bind_blob(images, 5, $0.baseAddress, Int32(data.count), transient) }
            try step(db, images)
        }
    }

    // MARK: - SQLite helpers

    private static func exec(_ db: OpaquePointer, _ sql: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, sql, nil, nil, &error) == SQLITE_OK else {
            let message = error.map { String(cString: $0) } ?? String(cString: sqlite3_errmsg(db))
            sqlite3_free(error)
            throw BibleImportError.databaseWrite(message)
        }
        sqlite3_free(error)
    }

    private static func prepare(_ db: OpaquePointer, _ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            throw BibleImportError.databaseWrite(String(cString: sqlite3_errmsg(db)))
        }
        return statement
    }

    private static func bind(_ statement: OpaquePointer, _ position: Int32, _ value: String) {
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        sqlite3_bind_text(statement, position, value, -1, transient)
    }

    private static func step(_ db: OpaquePointer, _ statement: OpaquePointer) throws {
        let status = sqlite3_step(statement)
        guard status == SQLITE_DONE || status == SQLITE_ROW else {
            throw BibleImportError.databaseWrite(String(cString: sqlite3_errmsg(db)))
        }
    }
}
#endif
