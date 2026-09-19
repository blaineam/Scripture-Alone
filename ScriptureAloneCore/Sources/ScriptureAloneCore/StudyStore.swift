import Foundation
import SQLite3

/// One dataset in the study database, with the license and attribution it ships under.
public struct StudySource: Hashable, Sendable, Identifiable {
    public enum Kind: String, Sendable { case crossReferences = "crossrefs", commentary }

    public let id: String
    public let kind: Kind
    public let name: String
    public let shortName: String
    public let author: String
    public let year: String
    public let license: String
    public let licenseURL: URL?
    public let url: URL?
    public let attribution: String
}

/// A verse (or passage) that another verse points to, ranked by reader votes.
public struct CrossReference: Hashable, Sendable, Identifiable {
    public let target: VerseRange
    public let votes: Int
    public var id: String { target.storageString }
}

/// A comment on a passage, or a chapter's introduction.
public struct CommentaryEntry: Hashable, Sendable, Identifiable {
    public let source: String
    /// The verses the comment covers; nil for a chapter introduction.
    public let range: VerseRange?
    public let chapter: ChapterRef
    public let text: String

    public var isIntroduction: Bool { range == nil }
    public var id: String { "\(source):\(range?.storageString ?? "intro-\(chapter.book.rawValue)-\(chapter.chapter)")" }

    /// Paragraphs, split on blank lines.
    public var paragraphs: [String] {
        text.components(separatedBy: "\n\n").filter { !$0.isEmpty }
    }
}

public enum StudyStoreError: Error, LocalizedError {
    case open(String)
    case query(String)

    public var errorDescription: String? {
        switch self {
        case .open(let message): "Couldn’t open the study resources: \(message)"
        case .query(let message): "Couldn’t read the study resources: \(message)"
        }
    }
}

/// Read-only access to the bundled study database (built by Tools/build_study.py): cross
/// references and public-domain commentary. Opened immutable; the lock serializes the connection.
public final class StudyStore: @unchecked Sendable {
    public let url: URL
    public let sources: [StudySource]
    private let db: OpaquePointer
    private let lock = NSLock()

    public init(url: URL) throws {
        self.url = url
        var handle: OpaquePointer?
        let uri = "file:\(url.path(percentEncoded: true))?immutable=1"
        guard sqlite3_open_v2(uri, &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK,
              let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown error"
            sqlite3_close(handle)
            throw StudyStoreError.open(message)
        }
        db = handle
        var sources: [StudySource] = []
        try Self.rows(db, """
            SELECT id, kind, name, short_name, author, year, license, license_url, url, attribution
            FROM sources ORDER BY sort
            """) { stmt in
            guard let kind = StudySource.Kind(rawValue: Self.string(stmt, 1)) else { return }
            sources.append(StudySource(id: Self.string(stmt, 0), kind: kind, name: Self.string(stmt, 2),
                                       shortName: Self.string(stmt, 3), author: Self.string(stmt, 4),
                                       year: Self.string(stmt, 5), license: Self.string(stmt, 6),
                                       licenseURL: URL(string: Self.string(stmt, 7)), url: URL(string: Self.string(stmt, 8)),
                                       attribution: Self.string(stmt, 9)))
        }
        self.sources = sources
    }

    deinit { sqlite3_close(db) }

    public var commentarySources: [StudySource] { sources.filter { $0.kind == .commentary } }
    public var crossReferenceSource: StudySource? { sources.first { $0.kind == .crossReferences } }
    public func source(_ id: String) -> StudySource? { sources.first { $0.id == id } }

    // MARK: Cross references

    /// Every verse this one points to, strongest first.
    public func crossReferences(for verse: VerseRef) throws -> [CrossReference] {
        let blob: Data? = try locked {
            var result: Data?
            try Self.rows(db, "SELECT refs FROM crossrefs WHERE from_key = ?1", bind: [verse.key]) { stmt in
                result = Self.blob(stmt, 0)
            }
            return result
        }
        guard let blob else { return [] }
        return Self.decodeCrossReferences(blob)
    }

    /// How many cross references each verse in the chapter has (verses without any are absent).
    public func crossReferenceCounts(in chapter: ChapterRef) throws -> [Int: Int] {
        try locked {
            var counts: [Int: Int] = [:]
            try Self.rows(db, "SELECT from_key, length(refs) / 10 FROM crossrefs WHERE from_key BETWEEN ?1 AND ?2",
                          bind: [chapter.keyRange.lowerBound, chapter.keyRange.upperBound]) { stmt in
                counts[Int(sqlite3_column_int64(stmt, 0))] = Int(sqlite3_column_int64(stmt, 1))
            }
            return counts
        }
    }

    /// Packed little-endian records: to_start (UInt32), to_end (UInt32), votes (UInt16).
    static func decodeCrossReferences(_ blob: Data) -> [CrossReference] {
        let bytes = [UInt8](blob)
        let stride = 10
        var result: [CrossReference] = []
        result.reserveCapacity(bytes.count / stride)
        var offset = 0
        func uint(_ at: Int, _ width: Int) -> Int {
            (0..<width).reduce(0) { $0 | Int(bytes[at + $1]) << (8 * $1) }
        }
        while offset + stride <= bytes.count {
            let start = uint(offset, 4), end = uint(offset + 4, 4), votes = uint(offset + 8, 2)
            offset += stride
            guard let a = VerseRef(key: start), let b = VerseRef(key: end) else { continue }
            result.append(CrossReference(target: VerseRange(a, b), votes: votes))
        }
        return result
    }

    // MARK: Commentary

    /// Comments from one source whose passage includes the verse, in canonical order.
    public func commentary(_ source: String, on verse: VerseRef) throws -> [CommentaryEntry] {
        try entries(source, where: "start_key BETWEEN ?2 AND ?3 AND end_key >= ?3 AND start_key % 1000 != 0",
                    bind: [source, verse.chapterKey.keyRange.lowerBound, verse.key])
    }

    /// Every comment from one source on the chapter, introduction first.
    public func commentary(_ source: String, in chapter: ChapterRef) throws -> [CommentaryEntry] {
        try entries(source, where: "start_key BETWEEN ?2 AND ?3",
                    bind: [source, chapter.keyRange.lowerBound, chapter.keyRange.upperBound])
    }

    /// The source's introduction to the chapter, when it has one.
    public func introduction(_ source: String, to chapter: ChapterRef) throws -> CommentaryEntry? {
        try entries(source, where: "start_key = ?2 AND end_key = ?2", bind: [source, chapter.keyRange.lowerBound]).first
    }

    /// Commentary sources that say something about this verse.
    public func sourcesCommenting(on verse: VerseRef) throws -> Set<String> {
        try locked {
            var ids: Set<String> = []
            try Self.rows(db, """
                SELECT DISTINCT source FROM commentary
                WHERE start_key BETWEEN ?1 AND ?2 AND end_key >= ?2 AND start_key % 1000 != 0
                """, bind: [verse.chapterKey.keyRange.lowerBound, verse.key]) { stmt in
                ids.insert(Self.string(stmt, 0))
            }
            return ids
        }
    }

    /// Runs one commentary query. Each chapter's comments share a compressed body (joined by
    /// U+0000); `part` picks the piece. Bodies are inflated once per call.
    private func entries(_ source: String, where condition: String, bind: [Any]) throws -> [CommentaryEntry] {
        let sql = """
            SELECT start_key, end_key, part, text_id FROM commentary
            WHERE source = ?1 AND \(condition) ORDER BY start_key, end_key
            """
        return try locked {
            var rows: [(start: Int, end: Int, part: Int, textID: Int)] = []
            try Self.rows(db, sql, bind: bind) { stmt in
                rows.append((Int(sqlite3_column_int64(stmt, 0)), Int(sqlite3_column_int64(stmt, 1)),
                             Int(sqlite3_column_int64(stmt, 2)), Int(sqlite3_column_int64(stmt, 3))))
            }
            var bodies: [Int: [Substring]] = [:]
            var result: [CommentaryEntry] = []
            for row in rows {
                if bodies[row.textID] == nil {
                    var data = Data()
                    try Self.rows(db, "SELECT body FROM commentary_text WHERE id = ?1", bind: [row.textID]) { stmt in
                        data = Self.blob(stmt, 0)
                    }
                    bodies[row.textID] = Self.inflate(data).map { $0.split(separator: "\0", omittingEmptySubsequences: false) } ?? []
                }
                guard let parts = bodies[row.textID], row.part < parts.count,
                      let chapter = VerseRef(key: row.start)?.chapterKey else { continue }
                let text = String(parts[row.part])
                // Verse 0 is a chapter introduction.
                if row.start % 1000 == 0 {
                    result.append(CommentaryEntry(source: source, range: nil, chapter: chapter, text: text))
                } else if let a = VerseRef(key: row.start), let b = VerseRef(key: row.end) {
                    result.append(CommentaryEntry(source: source, range: VerseRange(a, b), chapter: chapter, text: text))
                }
            }
            return result
        }
    }

    /// Bodies are raw DEFLATE (zlib.compressobj(wbits=-15)), which is what Apple's `.zlib` reads.
    static func inflate(_ data: Data) -> String? {
        guard !data.isEmpty, let inflated = try? (data as NSData).decompressed(using: .zlib) else { return nil }
        return String(data: inflated as Data, encoding: .utf8)
    }

    // MARK: SQLite helpers

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    private static func rows(_ db: OpaquePointer, _ sql: String, bind: [Any] = [], _ row: (OpaquePointer) throws -> Void) throws {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw StudyStoreError.query(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (index, value) in bind.enumerated() {
            let position = Int32(index + 1)
            switch value {
            case let int as Int: sqlite3_bind_int64(stmt, position, sqlite3_int64(int))
            case let string as String: sqlite3_bind_text(stmt, position, string, -1, transient)
            default: throw StudyStoreError.query("unsupported bind type")
            }
        }
        while true {
            let status = sqlite3_step(stmt)
            if status == SQLITE_ROW {
                try row(stmt)
            } else if status == SQLITE_DONE {
                return
            } else {
                throw StudyStoreError.query(String(cString: sqlite3_errmsg(db)))
            }
        }
    }

    private static func string(_ stmt: OpaquePointer, _ column: Int32) -> String {
        guard let cString = sqlite3_column_text(stmt, column) else { return "" }
        return String(cString: cString)
    }

    private static func blob(_ stmt: OpaquePointer, _ column: Int32) -> Data {
        let count = Int(sqlite3_column_bytes(stmt, column))
        guard count > 0, let pointer = sqlite3_column_blob(stmt, column) else { return Data() }
        return Data(bytes: pointer, count: count)
    }
}
