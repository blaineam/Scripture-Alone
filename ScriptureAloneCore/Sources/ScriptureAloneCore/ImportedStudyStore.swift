// Imports are an iPhone, iPad and Mac feature; the watch never holds study material.
#if !os(watchOS)
import Foundation
import SQLite3

/// A picture from an imported study Bible, placed beside a verse or at the head of a book.
public struct StudyImage: Hashable, Sendable, Identifiable {
    public let id: Int
    public let caption: String
    public let anchor: VerseRef?
    public let book: BookID?
}

/// Reads the study material an import brought with it — notes, introductions, essays and
/// pictures (see `ImportedBibleBuilder.writeStudy`) — in the shape the study panel already shows
/// commentary in, so an imported study Bible sits in the commentary picker beside the bundled
/// commentators.
///
/// Opened from the same file as the translation; nil when that file has no study tables.
public final class ImportedStudyStore: @unchecked Sendable {
    public let source: StudySource
    public let url: URL
    private let db: OpaquePointer
    private let lock = NSLock()

    public init?(url: URL, info: TranslationInfo) {
        var handle: OpaquePointer?
        let uri = "file:\(url.path(percentEncoded: true))?immutable=1"
        guard sqlite3_open_v2(uri, &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK,
              let handle else {
            sqlite3_close(handle)
            return nil
        }
        var check: OpaquePointer?
        let hasTables = sqlite3_prepare_v2(handle, "SELECT 1 FROM study_notes LIMIT 1", -1, &check, nil) == SQLITE_OK
        sqlite3_finalize(check)
        guard hasTables else {
            sqlite3_close(handle)
            return nil
        }
        self.db = handle
        self.url = url
        func meta(_ key: String) -> String? {
            var statement: OpaquePointer?
            defer { sqlite3_finalize(statement) }
            guard sqlite3_prepare_v2(handle, "SELECT value FROM meta WHERE key = ?1", -1, &statement, nil) == SQLITE_OK else { return nil }
            sqlite3_bind_text(statement, 1, key, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
            guard sqlite3_step(statement) == SQLITE_ROW, let text = sqlite3_column_text(statement, 0) else { return nil }
            return String(cString: text)
        }
        let name = meta("study_name") ?? info.name
        // The notes are the study Bible publisher's, not the translation's: credited to them.
        let publisher = meta("study_publisher") ?? ""
        source = StudySource(id: "import-\(info.id)", kind: .commentary, name: name,
                             shortName: ImportedTranslationIdentity.abbreviation(for: name),
                             author: publisher, year: "", license: info.license, licenseURL: nil, url: nil,
                             attribution: publisher.isEmpty ? info.copyright : "© \(publisher)")
    }

    deinit { sqlite3_close(db) }

    /// The notes on a verse, then any essay set beside it.
    public func commentary(on verse: VerseRef) -> [CommentaryEntry] {
        var entries: [CommentaryEntry] = []
        query("SELECT start_key, end_key, body FROM study_notes WHERE start_key <= ?1 AND end_key >= ?1 ORDER BY start_key, end_key",
              [verse.key]) { row in
            guard let start = VerseRef(key: Int(sqlite3_column_int64(row, 0))),
                  let end = VerseRef(key: Int(sqlite3_column_int64(row, 1))) else { return }
            entries.append(CommentaryEntry(source: source.id, range: VerseRange(start, end), chapter: verse.chapterKey,
                                           text: Self.text(row, 2)))
        }
        query("SELECT title, body FROM study_articles WHERE kind = 'essay' AND anchor_key = ?1", [verse.key]) { row in
            let title = Self.text(row, 0)
            let body = Self.text(row, 1)
            entries.append(CommentaryEntry(source: source.id, range: VerseRange(verse, verse), chapter: verse.chapterKey,
                                           text: title.isEmpty ? body : title + "\n\n" + body))
        }
        return entries
    }

    /// A book's introduction and outline, shown with its first chapter.
    public func introduction(to chapter: ChapterRef) -> CommentaryEntry? {
        guard chapter.chapter == 1 else { return nil }
        var parts: [String] = []
        query("SELECT title, body FROM study_articles WHERE kind = 'introduction' AND book = ?1 ORDER BY rowid",
              [chapter.book.rawValue]) { row in
            let title = Self.text(row, 0)
            let body = Self.text(row, 1)
            parts.append(title.isEmpty ? body : title + "\n\n" + body)
        }
        guard !parts.isEmpty else { return nil }
        return CommentaryEntry(source: source.id, range: nil, chapter: chapter, text: parts.joined(separator: "\n\n"))
    }

    public func comments(on verse: VerseRef) -> Bool {
        var found = false
        query("SELECT 1 FROM study_notes WHERE start_key <= ?1 AND end_key >= ?1 LIMIT 1", [verse.key]) { _ in found = true }
        return found
    }

    /// Pictures beside a chapter's verses, and a book's own pictures with its first chapter.
    public func images(in chapter: ChapterRef) -> [StudyImage] {
        var images: [StudyImage] = []
        let first = VerseRef(chapter.book, chapter.chapter, 0).key
        let last = VerseRef(chapter.book, chapter.chapter, 999).key
        query("""
            SELECT id, caption, anchor_key, book FROM study_images
            WHERE (anchor_key BETWEEN ?1 AND ?2) OR (anchor_key IS NULL AND book = ?3 AND ?4 = 1)
            ORDER BY COALESCE(anchor_key, 0), id
            """, [first, last, chapter.book.rawValue, chapter.chapter]) { row in
            let anchor = sqlite3_column_type(row, 2) == SQLITE_NULL ? nil : VerseRef(key: Int(sqlite3_column_int64(row, 2))) ?? nil
            let book = sqlite3_column_type(row, 3) == SQLITE_NULL ? nil : BookID(rawValue: Int(sqlite3_column_int64(row, 3)))
            images.append(StudyImage(id: Int(sqlite3_column_int64(row, 0)), caption: Self.text(row, 1), anchor: anchor, book: book))
        }
        return images
    }

    public func imageData(_ id: Int) -> Data? {
        var data: Data?
        query("SELECT data FROM study_images WHERE id = ?1", [id]) { row in
            guard let bytes = sqlite3_column_blob(row, 0) else { return }
            data = Data(bytes: bytes, count: Int(sqlite3_column_bytes(row, 0)))
        }
        return data
    }

    // MARK: SQLite

    private func query(_ sql: String, _ values: [Int], row: (OpaquePointer) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else { return }
        defer { sqlite3_finalize(statement) }
        for (index, value) in values.enumerated() {
            sqlite3_bind_int64(statement, Int32(index + 1), sqlite3_int64(value))
        }
        while sqlite3_step(statement) == SQLITE_ROW { row(statement) }
    }

    private static func text(_ statement: OpaquePointer, _ column: Int32) -> String {
        sqlite3_column_text(statement, column).map { String(cString: $0) } ?? ""
    }
}
#endif
