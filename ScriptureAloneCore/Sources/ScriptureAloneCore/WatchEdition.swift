import Foundation
import SQLite3

/// The compact edition of a Bible store that the Apple Watch reads.
///
/// A full store carries each chapter's layout JSON and an FTS5 search index, neither of which the
/// watch reader uses; dropping them takes a translation from about 14 MB to about 4.5 MB. What is
/// kept is exactly what `BibleStore` needs to read verses: `meta`, `books`, per-chapter verse counts
/// and the verses with their red-letter spans.
///
/// **This is the same schema `Tools/build_companion_data.py` writes for the bundled ASV, BSB and KJV
/// watch editions**, and the two must stay in step: the watch opens a bundled edition and one the
/// phone sent it through exactly the same code. The phone uses this to send a translation the
/// reader imported, which has no bundled watch edition.
public enum WatchEdition {

    public enum Failure: Error, LocalizedError {
        case sqlite(String)
        public var errorDescription: String? {
            switch self { case .sqlite(let message): String(localized: "The watch edition couldn't be written: \(message)", bundle: .module, comment: "Error. %@ is a technical error message.") }
        }
    }

    /// Writes the watch edition of the store at `source` to `destination`, replacing it.
    ///
    /// Written to a temporary file beside the destination and moved into place, so an interrupted
    /// write never leaves a truncated database for the watch to open.
    public static func write(from source: URL, to destination: URL) throws {
        let directory = destination.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let temporary = directory.appending(path: ".\(destination.lastPathComponent).partial")
        try? FileManager.default.removeItem(at: temporary)

        var db: OpaquePointer?
        guard sqlite3_open_v2(temporary.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil)
                == SQLITE_OK, let db else {
            throw Failure.sqlite(db.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed")
        }
        do {
            try exec(db, """
                PRAGMA page_size = 4096;
                CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE books (book INTEGER PRIMARY KEY, code TEXT NOT NULL, name TEXT NOT NULL,
                                    chapters INTEGER NOT NULL);
                CREATE TABLE chapters (book INTEGER NOT NULL, chapter INTEGER NOT NULL, verses INTEGER NOT NULL,
                                       PRIMARY KEY (book, chapter)) WITHOUT ROWID;
                CREATE TABLE verses (id INTEGER PRIMARY KEY, text TEXT NOT NULL, red TEXT);
                CREATE TABLE kjv_map (id INTEGER PRIMARY KEY, kjv INTEGER NOT NULL, kjv_last INTEGER NOT NULL);
                """)
            // The path is bound, not interpolated: an imported translation's file name is the
            // reader's, and a quote in it must not become SQL.
            try run(db, "ATTACH DATABASE ?1 AS src", bind: source.path)
            try exec(db, """
                BEGIN;
                INSERT INTO meta SELECT key, value FROM src.meta;
                INSERT OR REPLACE INTO meta VALUES ('edition', 'watch');
                INSERT INTO books SELECT book, code, name, chapters FROM src.books;
                INSERT INTO chapters SELECT book, chapter, verses FROM src.chapters;
                INSERT INTO verses SELECT id, text, red FROM src.verses;
                COMMIT;
                """)
            // A Bible that numbers its own way carries its map to the KJV keys marks are stored
            // under (VerseNumbering); imports and the English Bibles have none.
            if Self.sourceHasTable(db, "kjv_map") { try exec(db, "INSERT INTO kjv_map SELECT id, kjv, kjv_last FROM src.kjv_map") }
            try exec(db, """
                DETACH DATABASE src;
                VACUUM;
                """)
            sqlite3_close(db)
        } catch {
            sqlite3_close(db)
            try? FileManager.default.removeItem(at: temporary)
            throw error
        }
        // `replaceItemAt` needs something to replace; the first edition for a translation has none.
        if FileManager.default.fileExists(atPath: destination.path) {
            _ = try FileManager.default.replaceItemAt(destination, withItemAt: temporary)
        } else {
            try FileManager.default.moveItem(at: temporary, to: destination)
        }
    }

    private static func sourceHasTable(_ db: OpaquePointer, _ name: String) -> Bool {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT 1 FROM src.sqlite_master WHERE type = 'table' AND name = ?1",
                                 -1, &stmt, nil) == SQLITE_OK, let stmt else { return false }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, name, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        return sqlite3_step(stmt) == SQLITE_ROW
    }

    private static func exec(_ db: OpaquePointer, _ sql: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, sql, nil, nil, &error) == SQLITE_OK else {
            let message = error.map { String(cString: $0) } ?? String(cString: sqlite3_errmsg(db))
            sqlite3_free(error)
            throw Failure.sqlite(message)
        }
        sqlite3_free(error)
    }

    private static func run(_ db: OpaquePointer, _ sql: String, bind value: String) throws {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
            throw Failure.sqlite(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(statement) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        sqlite3_bind_text(statement, 1, value, -1, transient)
        guard sqlite3_step(statement) == SQLITE_DONE else {
            throw Failure.sqlite(String(cString: sqlite3_errmsg(db)))
        }
    }
}
