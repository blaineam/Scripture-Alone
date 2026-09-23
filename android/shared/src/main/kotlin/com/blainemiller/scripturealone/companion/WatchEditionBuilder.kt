package com.blainemiller.scripturealone.companion

/**
 * Writes the compact watch edition of a full Bible database — `WatchEdition.write(from:to:)` in
 * ScriptureAloneCore, and `build_watch_db` in Tools/build_companion_data.py, statement for statement.
 *
 * A full database carries each chapter's layout JSON and an FTS5 search index, neither of which the
 * watch reads; dropping them takes a Bible from ~15 MB to ~4.5 MB. What is kept is exactly what the
 * watch reader needs: `meta` (with `language`, which names the books), `books`, per-chapter verse
 * counts, the verses with their red-letter spans — and `kjv_map` when the source has one, so the watch
 * draws the Bible's own verse numbers while favorites, highlights and notes stay in KJV keys
 * (`VerseNumbering`).
 *
 * The SQL runs through [Database] so the phone runs it on Android's SQLite and the tests, on the JVM,
 * through JDBC — the same statements either way.
 */
object WatchEditionBuilder {

    /** A freshly created, empty database to write the edition into. */
    interface Database {
        /** Runs one statement; `?` placeholders take [args] in order. */
        fun execute(sql: String, vararg args: Any)

        /** Whether the attached source (`src`) has a table named [name]. */
        fun sourceHasTable(name: String): Boolean

        /** Runs [block] in one transaction, committed only if it returns. */
        fun transaction(block: () -> Unit)
    }

    val SCHEMA = listOf(
        "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE books (book INTEGER PRIMARY KEY, code TEXT NOT NULL, name TEXT NOT NULL, chapters INTEGER NOT NULL)",
        "CREATE TABLE chapters (book INTEGER NOT NULL, chapter INTEGER NOT NULL, verses INTEGER NOT NULL, " +
            "PRIMARY KEY (book, chapter)) WITHOUT ROWID",
        "CREATE TABLE verses (id INTEGER PRIMARY KEY, text TEXT NOT NULL, red TEXT)",
        "CREATE TABLE kjv_map (id INTEGER PRIMARY KEY, kjv INTEGER NOT NULL, kjv_last INTEGER NOT NULL)",
    )

    /** Fills [db] (empty) with the edition of the full database at [sourcePath]. */
    fun write(db: Database, sourcePath: String) {
        db.execute("PRAGMA page_size = 4096")
        for (statement in SCHEMA) db.execute(statement)
        // The path is bound, not interpolated: a quote in it must not become SQL.
        db.execute("ATTACH DATABASE ? AS src", sourcePath)
        val hasMap = db.sourceHasTable("kjv_map")
        db.transaction {
            db.execute("INSERT INTO meta SELECT key, value FROM src.meta")
            db.execute("INSERT OR REPLACE INTO meta VALUES ('edition', 'watch')")
            db.execute("INSERT INTO books SELECT book, code, name, chapters FROM src.books")
            db.execute("INSERT INTO chapters SELECT book, chapter, verses FROM src.chapters")
            db.execute("INSERT INTO verses SELECT id, text, red FROM src.verses")
            // A Bible that numbers its own way carries its map to the KJV keys marks are stored under;
            // the English Bibles have none.
            if (hasMap) db.execute("INSERT INTO kjv_map SELECT id, kjv, kjv_last FROM src.kjv_map")
        }
        db.execute("DETACH DATABASE src")
        db.execute("VACUUM")
    }

    /** The file name an edition travels and is stored under — `<id>-Watch.sqlite`, as on the Apple Watch. */
    fun fileName(id: String): String = "$id-Watch.sqlite"

    /** Bumped when the edition's schema changes, so the phone re-sends editions the watch already holds. */
    const val FORMAT = 1
}
