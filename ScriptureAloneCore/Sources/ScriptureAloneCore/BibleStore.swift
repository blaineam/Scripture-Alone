import Foundation
import SQLite3

public struct TranslationInfo: Hashable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let abbreviation: String
    public let copyright: String
    public let license: String
    /// Terms a publisher granted in a signed package, when the text came from one. Nil for a store
    /// whose terms have to be read out of its licence line — the bundled texts, and imports.
    /// `TranslationRights` (see `TranslationRights.swift`) is what the app actually gates on, and
    /// both cases produce one: this is the same judgement applied to two sources of the same facts.
    public let grantedRights: TranslationRights?

    public init(id: String, name: String, abbreviation: String, copyright: String, license: String,
                grantedRights: TranslationRights? = nil) {
        self.id = id
        self.name = name
        self.abbreviation = abbreviation
        self.copyright = copyright
        self.license = license
        self.grantedRights = grantedRights
    }
}

public struct VerseText: Hashable, Sendable {
    public let ref: VerseRef
    public let text: String
    /// Words of Christ, as UTF-16 ranges into `text`.
    public let red: [NSRange]
}

public enum BibleStoreError: Error, LocalizedError {
    case open(String)
    case query(String)
    case missing(ChapterRef)

    public var errorDescription: String? {
        switch self {
        case .open(let message): "Couldn’t open the Bible text: \(message)"
        case .query(let message): "Couldn’t read the Bible text: \(message)"
        case .missing(let chapter): "\(chapter.display) isn’t in this translation."
        }
    }
}

/// Read-only access to one translation.
///
/// Bundled and imported stores never change while open, so they are opened `immutable=1`: SQLite
/// then takes no locks and caches pages indefinitely, which is the fastest way to read a file
/// that is genuinely fixed. A store that *can* change underneath us — the cache an online
/// translation fills a chapter at a time — must be opened without it, because breaking that
/// promise is undefined behaviour in SQLite, not merely a stale read.
public final class BibleStore: @unchecked Sendable {
    public let info: TranslationInfo
    public let url: URL
    private let db: OpaquePointer
    private let lock = NSLock()
    private let verseCounts: [ChapterRef: Int]

    /// - Parameter immutable: false for a store that is written while the app runs. Costs the
    ///   usual shared locks and gives up SQLite's indefinite page caching, in exchange for being correct.
    public init(url: URL, immutable: Bool = true) throws {
        self.url = url
        var handle: OpaquePointer?
        let uri = "file:\(url.path(percentEncoded: true))" + (immutable ? "?immutable=1" : "")
        guard sqlite3_open_v2(uri, &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK,
              let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown error"
            sqlite3_close(handle)
            throw BibleStoreError.open(message)
        }
        db = handle

        var meta: [String: String] = [:]
        try Self.rows(db, "SELECT key, value FROM meta") { stmt in
            meta[Self.string(stmt, 0)] = Self.string(stmt, 1)
        }
        info = TranslationInfo(id: meta["id"] ?? url.deletingPathExtension().lastPathComponent,
                               name: meta["name"] ?? "",
                               abbreviation: meta["abbreviation"] ?? "",
                               copyright: meta["copyright"] ?? "",
                               license: meta["license"] ?? "")
        var counts: [ChapterRef: Int] = [:]
        try Self.rows(db, "SELECT book, chapter, verses FROM chapters") { stmt in
            if let book = BookID(rawValue: Int(sqlite3_column_int(stmt, 0))) {
                counts[ChapterRef(book, Int(sqlite3_column_int(stmt, 1)))] = Int(sqlite3_column_int(stmt, 2))
            }
        }
        verseCounts = counts
    }

    deinit { sqlite3_close(db) }

    public func verseCount(_ chapter: ChapterRef) -> Int { verseCounts[chapter] ?? 0 }
    public func contains(_ chapter: ChapterRef) -> Bool { verseCounts[chapter] != nil }

    public func layout(for chapter: ChapterRef) throws -> ChapterLayout {
        let json: String? = try locked {
            var result: String?
            try Self.rows(db, "SELECT layout FROM chapters WHERE book = ?1 AND chapter = ?2",
                          bind: [chapter.book.rawValue, chapter.chapter]) { stmt in
                result = Self.string(stmt, 0)
            }
            return result
        }
        guard let json else { throw BibleStoreError.missing(chapter) }
        return try JSONDecoder().decode(ChapterLayout.self, from: Data(json.utf8))
    }

    /// Plain text of each verse in the range (for copying, sharing and speech).
    public func verses(in range: VerseRange) throws -> [VerseText] {
        try locked {
            var result: [VerseText] = []
            try Self.rows(db, "SELECT id, text, red FROM verses WHERE id BETWEEN ?1 AND ?2 ORDER BY id",
                          bind: [range.start.key, range.end.key]) { stmt in
                guard let ref = VerseRef(key: Int(sqlite3_column_int64(stmt, 0))) else { return }
                let text = Self.string(stmt, 1)
                result.append(VerseText(ref: ref, text: text, red: Self.redRanges(Self.optionalString(stmt, 2), in: text)))
            }
            return result
        }
    }

    /// One chapter as a package writer needs it: the layout JSON and the verse rows exactly as this
    /// store keeps them, with no decoding in between. Packaging must not reinterpret the text — a
    /// package is meant to hold what the store held, byte for byte — so this deliberately returns
    /// the stored strings rather than a decoded `ChapterLayout`.
    ///
    /// `Tools/package_translation.py` reads the same three columns from the same tables; this is the
    /// in-process path, used by the test suite and by any future in-app repackaging.
    public func packagingChapter(_ chapter: ChapterRef) throws -> TranslationPackageWriter.SourceChapter {
        try locked {
            var layout: String?
            try Self.rows(db, "SELECT layout FROM chapters WHERE book = ?1 AND chapter = ?2",
                          bind: [chapter.book.rawValue, chapter.chapter]) { stmt in
                layout = Self.string(stmt, 0)
            }
            guard let layout else { throw BibleStoreError.missing(chapter) }
            var rows: [TranslationPackageWriter.SourceVerse] = []
            try Self.rows(db, "SELECT id, text, red FROM verses WHERE id BETWEEN ?1 AND ?2 ORDER BY id",
                          bind: [chapter.keyRange.lowerBound, chapter.keyRange.upperBound]) { stmt in
                var pairs: [[Int]] = []
                if let red = Self.optionalString(stmt, 2),
                   let decoded = try? JSONDecoder().decode([[Int]].self, from: Data(red.utf8)) {
                    pairs = decoded
                }
                rows.append(TranslationPackageWriter.SourceVerse(key: Int(sqlite3_column_int64(stmt, 0)),
                                                                 text: Self.string(stmt, 1),
                                                                 redScalarPairs: pairs))
            }
            return TranslationPackageWriter.SourceChapter(ref: chapter,
                                                          verses: verseCount(chapter),
                                                          layoutJSON: layout,
                                                          verseRows: rows)
        }
    }

    public struct SearchHit: Hashable, Sendable, Identifiable {
        public let ref: VerseRef
        public let text: String
        public var id: Int { ref.key }
    }

    /// Full-text search in canonical order. Words must all appear; the last word matches as a prefix
    /// so results update while typing. A query in double quotes matches as an exact phrase.
    public func search(_ query: String, limit: Int = 300) throws -> [SearchHit] {
        guard let match = Self.ftsQuery(query) else { return [] }
        return try locked {
            var hits: [SearchHit] = []
            try Self.rows(db, "SELECT rowid, text FROM verses_fts WHERE verses_fts MATCH ?1 ORDER BY rowid LIMIT ?2",
                          bind: [match, limit]) { stmt in
                if let ref = VerseRef(key: Int(sqlite3_column_int64(stmt, 0))) {
                    hits.append(SearchHit(ref: ref, text: Self.string(stmt, 1)))
                }
            }
            return hits
        }
    }

    static func ftsQuery(_ query: String) -> String? {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count > 2, trimmed.hasPrefix("\""), trimmed.hasSuffix("\"") {
            let phrase = trimmed.dropFirst().dropLast().replacingOccurrences(of: "\"", with: "")
            return phrase.isEmpty ? nil : "\"\(phrase)\""
        }
        let words = trimmed
            .components(separatedBy: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "'’")).inverted)
            .map { $0.replacingOccurrences(of: "’", with: "'") }
            .filter { !$0.isEmpty }
        guard !words.isEmpty else { return nil }
        return words.enumerated().map { index, word in
            let quoted = "\"\(word)\""
            return index == words.count - 1 ? quoted + " *" : quoted
        }.joined(separator: " ")
    }

    // MARK: - SQLite helpers

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    static func rows(_ db: OpaquePointer, _ sql: String, bind: [Any] = [], _ row: (OpaquePointer) throws -> Void) throws {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw BibleStoreError.query(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (index, value) in bind.enumerated() {
            let position = Int32(index + 1)
            switch value {
            case let int as Int: sqlite3_bind_int64(stmt, position, sqlite3_int64(int))
            case let string as String: sqlite3_bind_text(stmt, position, string, -1, transient)
            default: throw BibleStoreError.query("unsupported bind type")
            }
        }
        while true {
            let status = sqlite3_step(stmt)
            if status == SQLITE_ROW {
                try row(stmt)
            } else if status == SQLITE_DONE {
                return
            } else {
                throw BibleStoreError.query(String(cString: sqlite3_errmsg(db)))
            }
        }
    }

    static func string(_ stmt: OpaquePointer, _ column: Int32) -> String {
        optionalString(stmt, column) ?? ""
    }

    static func optionalString(_ stmt: OpaquePointer, _ column: Int32) -> String? {
        guard let cString = sqlite3_column_text(stmt, column) else { return nil }
        return String(cString: cString)
    }

    /// Words-of-Christ ranges, converting the stored scalar offsets to the UTF-16 ranges the text
    /// view wants. Shared with `TranslationPackage`, whose chapter blobs carry the same pairs, so a
    /// packaged translation renders red letters through identical arithmetic.
    static func redRanges(_ json: String?, in text: String) -> [NSRange] {
        guard let json, let pairs = try? JSONDecoder().decode([[Int]].self, from: Data(json.utf8)) else { return [] }
        return pairs.compactMap { pair in
            guard pair.count == 2 else { return nil }
            let start = text.utf16Offset(ofScalar: pair[0])
            let end = text.utf16Offset(ofScalar: pair[0] + pair[1])
            return NSRange(location: start, length: end - start)
        }
    }
}
