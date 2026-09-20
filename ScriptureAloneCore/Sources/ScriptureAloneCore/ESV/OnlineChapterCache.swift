// The cache writes the bundled-store schema, which lives in `Import/ImportedBibleBuilder`, and
// that whole directory is unavailable on the watch (arm64_32 cannot hold the ZIP64 sentinel).
// The watch does not call Crossway anyway — it receives text through the CloudKit/WCSession
// snapshot — so the same guard applies here. If the watch ever needs to write a cache, move
// `ImportedBibleBuilder.schema` and `layoutJSON` into an unguarded file; nothing else here is
// 64-bit-dependent.
#if !os(watchOS)
import Foundation
import SQLite3

/// The identity an online translation writes into its cache's `meta` row.
public struct OnlineTranslation: Sendable, Hashable {
    public var id: String
    public var name: String
    public var abbreviation: String
    /// The publisher's required notice. The reader prints it wherever the text appears.
    public var copyright: String
    public var license: String

    public init(id: String, name: String, abbreviation: String, copyright: String,
                license: String = "Licensed — cached from the publisher's API") {
        self.id = id
        self.name = name
        self.abbreviation = abbreviation
        self.copyright = copyright
        self.license = license
    }

    public static let esv = OnlineTranslation(
        id: "ESV",
        name: "English Standard Version",
        abbreviation: "ESV",
        copyright: ESVClient.requiredCopyright,
        license: "© Crossway. Cached under Crossway’s API terms, up to \(ESVClient.cacheVerseLimit) verses.")
}

public enum OnlineCacheError: Error, LocalizedError, Equatable {
    case open(String)
    case write(String)

    public var errorDescription: String? {
        switch self {
        case .open(let message): "Couldn’t open the offline copy: \(message)"
        case .write(let message): "Couldn’t save that chapter: \(message)"
        }
    }
}

/// What one `store` call did.
public struct CacheWrite: Sendable, Hashable {
    public let chapter: ChapterRef
    public let versesStored: Int
    /// Chapters dropped to make room, least recently read first.
    public let evicted: [ChapterRef]
    /// Total verses held after the write.
    public let cachedVerses: Int
    /// True when this one chapter is larger than the whole ceiling. No canonical chapter is
    /// (Psalm 119, the longest, is 176 verses), so this is a guard rather than a behaviour.
    public let exceedsLimit: Bool
}

/// An on-demand chapter cache that is a real translation store.
///
/// The ESV cannot be bundled, so its text arrives from Crossway's API a chapter at a time. Rather
/// than a bespoke in-memory path that selection, quotation, listening, highlighting, notes and
/// layout would each have to special-case, this writes **the same `meta`/`books`/`chapters`/
/// `verses`/`verses_fts` schema the bundled translations use** (`ImportedBibleBuilder.schema`,
/// reused verbatim). `BibleStore` then opens it unchanged and nothing downstream knows or cares
/// that the words came over a network.
///
/// Crossway's terms cap what may be kept — "You can cache up to 500 verses" — so the ceiling is
/// enforced on every write, evicting whole chapters least-recently-read first.
///
/// ## Reading while writing: `immutable=1`
///
/// `BibleStore` opens with `file:…?immutable=1`, which is a *promise to SQLite* that the file will
/// not change while it is open. SQLite takes no locks and may keep pages cached indefinitely;
/// SQLite's own documentation says the result of using `immutable` on a file that does change is
/// **undefined** — not "stale", but undefined: missing rows, a torn page, or `SQLITE_CORRUPT`.
///
/// So this type never writes in place. Every mutation copies the store to a sibling file, changes
/// the copy, and atomically replaces the original. A `BibleStore` already open keeps the old inode,
/// which is now unlinked and therefore genuinely immutable for as long as that reader lives — the
/// promise stays true, and the failure mode collapses from "undefined" to "shows the previous
/// contents".
///
/// **The caller must therefore re-open `BibleStore` after every successful `store` or `clear`.**
/// That is true even setting SQLite aside: `BibleStore` reads its chapter/verse-count table once in
/// `init`, so an instance created before a write cannot know about the new chapter whatever the
/// file does. The sequence is: fetch → `store` → construct a new `BibleStore` on the same URL →
/// read. A cached chapter that "didn't load" is almost always a `BibleStore` that predates its
/// write.
///
/// The better long-term arrangement is for the online store to be opened **without** `immutable=1`
/// — plain `SQLITE_OPEN_READONLY`, where SQLite takes shared locks and sees committed changes — but
/// that is a change to `BibleStore`, which is shared with the bundled translations, so it is left
/// for the caller to decide. The copy-and-replace here is correct either way.
///
/// Writes are slow enough (a file copy plus SQLite) to belong off the main actor; the type is
/// `Sendable` and its methods are synchronous, so call them from a detached task.
public final class OnlineChapterCache: @unchecked Sendable {
    public let url: URL
    public let translation: OnlineTranslation
    /// Total verses the cache may hold.
    public let verseLimit: Int

    /// Serialises this process's writers. It does not and cannot coordinate with readers — see the
    /// note above; readers are made safe by the copy-and-replace, not by this lock.
    private let lock = NSLock()

    /// Opens the cache, creating it if it is not there. A cache is disposable: a file that is
    /// missing, corrupt, or not one of ours is replaced with an empty one rather than stranding
    /// the reader.
    public init(url: URL, translation: OnlineTranslation,
                verseLimit: Int = ESVClient.cacheVerseLimit) throws {
        self.url = url
        self.translation = translation
        self.verseLimit = max(1, verseLimit)
        if !Self.isUsable(url) {
            try create()
        }
    }

    // MARK: - Reading

    public func contains(_ chapter: ChapterRef) -> Bool {
        (try? read { db in
            try Self.value(db, "SELECT 1 FROM cache_state WHERE book = ?1 AND chapter = ?2",
                           bind: [chapter.book.rawValue, chapter.chapter]) != nil
        }) ?? false
    }

    public func cachedVerseCount() throws -> Int {
        try read { db in try Self.value(db, "SELECT COALESCE(SUM(verses), 0) FROM cache_state") ?? 0 }
    }

    /// Cached chapters, most recently read first.
    public func cachedChapters() throws -> [ChapterRef] {
        try read { db in
            var chapters: [ChapterRef] = []
            try Self.rows(db, "SELECT book, chapter FROM cache_state ORDER BY lastRead DESC") { statement in
                if let book = BookID(rawValue: Int(sqlite3_column_int(statement, 0))) {
                    chapters.append(ChapterRef(book, Int(sqlite3_column_int(statement, 1))))
                }
            }
            return chapters
        }
    }

    // MARK: - Writing

    /// Inserts or replaces one chapter, evicting whatever must go to stay under the ceiling.
    ///
    /// The API returns prose — no paragraph or poetry marks — so the layout is one paragraph block
    /// with every verse numbered, matching `ChapterLayout.prose(_:)`.
    @discardableResult
    public func store(_ verses: [VerseText], for chapter: ChapterRef) throws -> CacheWrite {
        let rows = verses.filter { $0.ref.chapterKey == chapter }.sorted { $0.ref < $1.ref }
        guard !rows.isEmpty else {
            return CacheWrite(chapter: chapter, versesStored: 0, evicted: [],
                              cachedVerses: try cachedVerseCount(), exceedsLimit: false)
        }
        let layout = try Self.layout(for: rows)

        return try mutate { db in
            let held: Int = try Self.value(db, "SELECT verses FROM cache_state WHERE book = ?1 AND chapter = ?2",
                                           bind: [chapter.book.rawValue, chapter.chapter]) ?? 0
            let total: Int = try Self.value(db, "SELECT COALESCE(SUM(verses), 0) FROM cache_state") ?? 0
            var projected = total - held + rows.count

            // Evict whole chapters, least recently read first, until the write fits. The chapter
            // being written is never a candidate.
            var evicted: [ChapterRef] = []
            while projected > verseLimit {
                guard let victim = try Self.leastRecentlyRead(db, excluding: chapter) else { break }
                try Self.forget(db, victim.chapter)
                projected -= victim.verses
                evicted.append(victim.chapter)
            }
            // One chapter bigger than the whole ceiling is stored anyway: the reader is looking at
            // it, and a cap on what is *kept* is never a reason to fail the chapter in front of
            // them. Everything else has already been evicted, so the cache holds only this.
            let exceeds = projected > verseLimit

            try Self.forget(db, chapter)
            let sequence: Int = (try Self.value(db, "SELECT COALESCE(MAX(lastRead), 0) FROM cache_state") ?? 0) + 1

            let insert = try Self.prepare(db, "INSERT INTO verses VALUES (?1, ?2, ?3)")
            defer { sqlite3_finalize(insert) }
            for row in rows {
                sqlite3_reset(insert)
                sqlite3_bind_int64(insert, 1, sqlite3_int64(row.ref.key))
                Self.bind(insert, 2, row.text)
                let spans = Self.scalarSpans(row.red, in: row.text)
                if spans.isEmpty {
                    sqlite3_bind_null(insert, 3)
                } else {
                    Self.bind(insert, 3, try Self.json(spans.map { [$0.start, $0.length] }))
                }
                try Self.step(db, insert)
            }
            try Self.run(db, "INSERT INTO chapters VALUES (?1, ?2, ?3, ?4)",
                         bind: [chapter.book.rawValue, chapter.chapter, rows.last?.ref.verse ?? rows.count, layout])
            try Self.run(db, "INSERT INTO cache_state VALUES (?1, ?2, ?3, ?4)",
                         bind: [chapter.book.rawValue, chapter.chapter, rows.count, sequence])
            try Self.refreshBooks(db)
            try Self.reindex(db)

            let cached: Int = try Self.value(db, "SELECT COALESCE(SUM(verses), 0) FROM cache_state") ?? 0
            return CacheWrite(chapter: chapter, versesStored: rows.count, evicted: evicted,
                              cachedVerses: cached, exceedsLimit: exceeds)
        }
    }

    /// Records that the reader looked at a chapter, so eviction follows real reading rather than
    /// the order chapters happened to be fetched. A chapter that is not cached is ignored.
    public func markRead(_ chapter: ChapterRef) throws {
        guard contains(chapter) else { return }
        try mutate { db in
            let sequence: Int = (try Self.value(db, "SELECT COALESCE(MAX(lastRead), 0) FROM cache_state") ?? 0) + 1
            try Self.run(db, "UPDATE cache_state SET lastRead = ?3 WHERE book = ?1 AND chapter = ?2",
                         bind: [chapter.book.rawValue, chapter.chapter, sequence])
        }
    }

    /// Drops every cached verse — what to do when the reader removes their API key. The store file
    /// survives, empty, so `BibleStore` still opens it; `VACUUM` makes sure the text is actually
    /// gone rather than sitting in free pages.
    public func clear() throws {
        try mutate { db in
            try Self.exec(db, """
                DELETE FROM verses;
                DELETE FROM chapters;
                DELETE FROM books;
                DELETE FROM cache_state;
                """)
            try Self.reindex(db)
            try Self.exec(db, "VACUUM")
        }
    }

    // MARK: - Layout

    /// One paragraph, every verse numbered — the same shape as `ChapterLayout.prose(_:)`, encoded
    /// with the builder's own writer so the JSON matches a bundled store exactly.
    static func layout(for rows: [VerseText]) throws -> String {
        let fragments = rows.map { row in
            ExtractedFragment(verse: row.ref.verse, numbered: true, text: row.text,
                              spans: scalarSpans(row.red, in: row.text))
        }
        do {
            return try ImportedBibleBuilder.layoutJSON([ExtractedBlock(kind: .paragraph, fragments: fragments)])
        } catch {
            throw OnlineCacheError.write("couldn’t encode the chapter layout")
        }
    }

    /// `VerseText.red` is in UTF-16 ranges; the store's offsets are Unicode scalars. The ESV API
    /// returns no words-of-Christ markup, so this is usually empty — but a silent mis-conversion
    /// would be worse than none, so it is done properly.
    static func scalarSpans(_ ranges: [NSRange], in text: String) -> [StyledSpan] {
        guard !ranges.isEmpty else { return [] }
        var scalarIndex: [Int: Int] = [:]
        var utf16 = 0
        var scalar = 0
        for unit in text.unicodeScalars {
            scalarIndex[utf16] = scalar
            utf16 += unit.utf16.count
            scalar += 1
        }
        scalarIndex[utf16] = scalar
        return ranges.compactMap { range in
            guard let start = scalarIndex[range.location],
                  let end = scalarIndex[range.location + range.length], end > start else { return nil }
            return StyledSpan(start: start, length: end - start, style: .wordsOfChrist)
        }
    }

    // MARK: - Eviction helpers

    private static func leastRecentlyRead(_ db: OpaquePointer,
                                          excluding chapter: ChapterRef) throws -> (chapter: ChapterRef, verses: Int)? {
        var found: (ChapterRef, Int)?
        try rows(db, """
            SELECT book, chapter, verses FROM cache_state
            WHERE NOT (book = ?1 AND chapter = ?2)
            ORDER BY lastRead ASC, book ASC, chapter ASC LIMIT 1
            """, bind: [chapter.book.rawValue, chapter.chapter]) { statement in
            if let book = BookID(rawValue: Int(sqlite3_column_int(statement, 0))) {
                found = (ChapterRef(book, Int(sqlite3_column_int(statement, 1))), Int(sqlite3_column_int(statement, 2)))
            }
        }
        return found
    }

    private static func forget(_ db: OpaquePointer, _ chapter: ChapterRef) throws {
        let range = chapter.keyRange
        try run(db, "DELETE FROM verses WHERE id BETWEEN ?1 AND ?2", bind: [range.lowerBound, range.upperBound])
        try run(db, "DELETE FROM chapters WHERE book = ?1 AND chapter = ?2",
                bind: [chapter.book.rawValue, chapter.chapter])
        try run(db, "DELETE FROM cache_state WHERE book = ?1 AND chapter = ?2",
                bind: [chapter.book.rawValue, chapter.chapter])
    }

    /// `books` is derived from what is actually cached, so a book whose last chapter was evicted
    /// disappears from it.
    private static func refreshBooks(_ db: OpaquePointer) throws {
        var counts: [BookID: Int] = [:]
        try rows(db, "SELECT book, COUNT(*) FROM cache_state GROUP BY book") { statement in
            if let book = BookID(rawValue: Int(sqlite3_column_int(statement, 0))) {
                counts[book] = Int(sqlite3_column_int(statement, 1))
            }
        }
        try exec(db, "DELETE FROM books")
        for (book, count) in counts.sorted(by: { $0.key < $1.key }) {
            try run(db, "INSERT INTO books VALUES (?1, ?2, ?3, ?4)",
                    bind: [book.rawValue, book.code, book.name, count])
        }
    }

    /// `verses_fts` is an external-content index, so it has to be told when `verses` changes.
    /// A full rebuild is the honest way to do that: at 500 rows it is far cheaper than the network
    /// round trip that produced them, and it cannot drift out of step the way hand-maintained
    /// delete/insert commands can.
    private static func reindex(_ db: OpaquePointer) throws {
        try exec(db, "INSERT INTO verses_fts(verses_fts) VALUES ('rebuild')")
    }

    // MARK: - The file

    /// True when the file exists and is one of our caches.
    static func isUsable(_ url: URL) -> Bool {
        guard FileManager.default.fileExists(atPath: url.path) else { return false }
        var handle: OpaquePointer?
        guard sqlite3_open_v2(url.path, &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK,
              let db = handle else {
            sqlite3_close(handle)
            return false
        }
        defer { sqlite3_close(db) }
        let marker: Int? = try? value(db, "SELECT COUNT(*) FROM cache_state")
        let online: String? = try? text(db, "SELECT value FROM meta WHERE key = 'online'")
        return marker != nil && online == "1"
    }

    private func create() throws {
        let directory = url.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let temporary = directory.appending(path: ".\(url.lastPathComponent).new")
        try? FileManager.default.removeItem(at: temporary)

        let db = try Self.openForWriting(temporary)
        do {
            try Self.exec(db, "PRAGMA page_size = 4096; PRAGMA journal_mode = DELETE;")
            // The bundled-store schema, verbatim, plus one side table. `BibleStore` reads only the
            // tables it knows, so `cache_state` is invisible to it — which is why the read-tracking
            // lives there rather than as a column on `chapters`.
            try Self.exec(db, ImportedBibleBuilder.schema)
            try Self.exec(db, """
                CREATE TABLE cache_state (book INTEGER NOT NULL, chapter INTEGER NOT NULL,
                                          verses INTEGER NOT NULL, lastRead INTEGER NOT NULL,
                                          PRIMARY KEY (book, chapter)) WITHOUT ROWID;
                """)
            for (key, value) in [("id", translation.id), ("name", translation.name),
                                 ("abbreviation", translation.abbreviation),
                                 ("copyright", translation.copyright), ("license", translation.license),
                                 ("source", "Fetched from the publisher’s API"),
                                 ("online", "1"), ("verseLimit", String(verseLimit))] {
                try Self.run(db, "INSERT INTO meta VALUES (?1, ?2)", bind: [key, value])
            }
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
            throw OnlineCacheError.open(error.localizedDescription)
        }
    }

    /// Copy, change the copy, replace the original. See the note on `immutable=1` above: this is
    /// what keeps a `BibleStore` that is already open from reading a file that changes underneath
    /// it. The cost is one copy of a file that holds at most a few hundred verses.
    private func mutate<T>(_ body: (OpaquePointer) throws -> T) throws -> T {
        lock.lock()
        defer { lock.unlock() }
        if !Self.isUsable(url) { try create() }

        let directory = url.deletingLastPathComponent()
        let working = directory.appending(path: ".\(url.lastPathComponent).writing")
        try? FileManager.default.removeItem(at: working)
        do {
            try FileManager.default.copyItem(at: url, to: working)
        } catch {
            throw OnlineCacheError.write(error.localizedDescription)
        }

        let db = try Self.openForWriting(working)
        let result: T
        do {
            try Self.exec(db, "PRAGMA journal_mode = DELETE;")
            try Self.exec(db, "BEGIN")
            result = try body(db)
            try Self.exec(db, "COMMIT")
        } catch {
            sqlite3_close(db)
            try? FileManager.default.removeItem(at: working)
            throw error
        }
        sqlite3_close(db)

        do {
            if (try? FileManager.default.replaceItemAt(url, withItemAt: working)) == nil {
                try? FileManager.default.removeItem(at: url)
                try FileManager.default.moveItem(at: working, to: url)
            }
        } catch {
            try? FileManager.default.removeItem(at: working)
            throw OnlineCacheError.write(error.localizedDescription)
        }
        return result
    }

    private func read<T>(_ body: (OpaquePointer) throws -> T) throws -> T {
        lock.lock()
        defer { lock.unlock() }
        var handle: OpaquePointer?
        // Deliberately not `immutable=1`: this reader is in the same process as the writer.
        guard sqlite3_open_v2(url.path, &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK,
              let db = handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown error"
            sqlite3_close(handle)
            throw OnlineCacheError.open(message)
        }
        defer { sqlite3_close(db) }
        return try body(db)
    }

    private static func openForWriting(_ url: URL) throws -> OpaquePointer {
        var handle: OpaquePointer?
        guard sqlite3_open_v2(url.path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK,
              let db = handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "could not create the file"
            sqlite3_close(handle)
            throw OnlineCacheError.open(message)
        }
        return db
    }

    // MARK: - SQLite helpers

    private static func exec(_ db: OpaquePointer, _ sql: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, sql, nil, nil, &error) == SQLITE_OK else {
            let message = error.map { String(cString: $0) } ?? String(cString: sqlite3_errmsg(db))
            sqlite3_free(error)
            throw OnlineCacheError.write(message)
        }
        sqlite3_free(error)
    }

    private static func prepare(_ db: OpaquePointer, _ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            throw OnlineCacheError.write(String(cString: sqlite3_errmsg(db)))
        }
        return statement
    }

    private static func bind(_ statement: OpaquePointer, _ position: Int32, _ value: String) {
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        sqlite3_bind_text(statement, position, value, -1, transient)
    }

    private static func bindAll(_ statement: OpaquePointer, _ values: [Any]) throws {
        for (index, value) in values.enumerated() {
            let position = Int32(index + 1)
            switch value {
            case let number as Int: sqlite3_bind_int64(statement, position, sqlite3_int64(number))
            case let string as String: bind(statement, position, string)
            default: throw OnlineCacheError.write("unsupported bind type")
            }
        }
    }

    private static func step(_ db: OpaquePointer, _ statement: OpaquePointer) throws {
        let status = sqlite3_step(statement)
        guard status == SQLITE_DONE || status == SQLITE_ROW else {
            throw OnlineCacheError.write(String(cString: sqlite3_errmsg(db)))
        }
    }

    private static func run(_ db: OpaquePointer, _ sql: String, bind values: [Any] = []) throws {
        let statement = try prepare(db, sql)
        defer { sqlite3_finalize(statement) }
        try bindAll(statement, values)
        try step(db, statement)
    }

    private static func rows(_ db: OpaquePointer, _ sql: String, bind values: [Any] = [],
                             _ row: (OpaquePointer) throws -> Void) throws {
        let statement = try prepare(db, sql)
        defer { sqlite3_finalize(statement) }
        try bindAll(statement, values)
        while true {
            let status = sqlite3_step(statement)
            if status == SQLITE_ROW {
                try row(statement)
            } else if status == SQLITE_DONE {
                return
            } else {
                throw OnlineCacheError.write(String(cString: sqlite3_errmsg(db)))
            }
        }
    }

    private static func value(_ db: OpaquePointer, _ sql: String, bind values: [Any] = []) throws -> Int? {
        var result: Int?
        try rows(db, sql, bind: values) { statement in
            result = Int(sqlite3_column_int64(statement, 0))
        }
        return result
    }

    private static func text(_ db: OpaquePointer, _ sql: String, bind values: [Any] = []) throws -> String? {
        var result: String?
        try rows(db, sql, bind: values) { statement in
            if let raw = sqlite3_column_text(statement, 0) { result = String(cString: raw) }
        }
        return result
    }

    private static func json(_ object: Any) throws -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.withoutEscapingSlashes]),
              let encoded = String(data: data, encoding: .utf8) else {
            throw OnlineCacheError.write("couldn’t encode the verse styling")
        }
        return encoded
    }
}
#endif
