package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.data.VerseRange
import java.time.Instant
import java.util.UUID

/**
 * Highlights, notes and favorites on disk — the SwiftData store of `Persistence/Models.swift`, as
 * three plain tables, and a fourth for the slide photos kept with camera notes.
 *
 * Plain SQLite over the bundled driver rather than Room: three small tables don't need an ORM, the
 * bundled driver is already in the app for FTS5, and the same code runs on the JVM through JDBC
 * (see [UserDatabase]), with no annotation processor in the build.
 *
 * Synchronous: callers keep it off the main thread. Times are stored as epoch milliseconds.
 */
class UserDataStore(private val db: UserDatabase) {

    init {
        migrate()
    }

    private fun migrate() {
        val version = db.query("PRAGMA user_version") { it.long(0) }.firstOrNull() ?: 0L
        if (version < 1) {
            db.transaction {
                // One row per verse; no uniqueness, as in SwiftData — the newest row wins on read — so a
                // later merge of another device's rows (Drive sync) needs no conflict handling here.
                db.execute(
                    """CREATE TABLE IF NOT EXISTS highlights (
                        verse_key INTEGER NOT NULL,
                        color TEXT NOT NULL,
                        created_at INTEGER NOT NULL)""",
                )
                db.execute("CREATE INDEX IF NOT EXISTS highlights_verse ON highlights(verse_key)")
                db.execute(
                    """CREATE TABLE IF NOT EXISTS notes (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL DEFAULT '',
                        body TEXT NOT NULL DEFAULT '',
                        anchors TEXT NOT NULL DEFAULT '',
                        first_verse_key INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        origin TEXT NOT NULL DEFAULT 'manual')""",
                )
                db.execute(
                    """CREATE TABLE IF NOT EXISTS favorites (
                        id TEXT PRIMARY KEY NOT NULL,
                        range TEXT NOT NULL,
                        start_key INTEGER NOT NULL,
                        end_key INTEGER NOT NULL,
                        created_at INTEGER NOT NULL)""",
                )
                db.execute("PRAGMA user_version = 1")
            }
        }
        if (version < 2) {
            db.transaction {
                // A camera note's slide photo, kept only when the reader asks — `Note.slidePhoto`, which
                // SwiftData keeps as external storage. Its own table, so listing notes never reads photos.
                db.execute(
                    """CREATE TABLE IF NOT EXISTS slide_photos (
                        note_id TEXT PRIMARY KEY NOT NULL,
                        jpeg BLOB NOT NULL)""",
                )
                db.execute("PRAGMA user_version = 2")
            }
        }
    }

    // Highlights

    fun highlights(): List<Highlight> =
        db.query("SELECT verse_key, color, created_at FROM highlights ORDER BY verse_key, created_at") {
            Highlight(it.long(0).toInt(), it.text(1), Instant.ofEpochMilli(it.long(2)))
        }

    fun highlights(inKeys: IntRange): List<Highlight> =
        db.query(
            "SELECT verse_key, color, created_at FROM highlights WHERE verse_key BETWEEN ? AND ? ORDER BY verse_key, created_at",
            inKeys.first, inKeys.last,
        ) { Highlight(it.long(0).toInt(), it.text(1), Instant.ofEpochMilli(it.long(2))) }

    /** Colours every verse in [keys], replacing whatever colour it had — `SelectionBar.highlight`. */
    fun highlight(keys: Collection<Int>, color: HighlightColor, at: Instant = Instant.now()) = db.transaction {
        for (key in keys) {
            db.execute("DELETE FROM highlights WHERE verse_key = ?", key)
            db.execute("INSERT INTO highlights (verse_key, color, created_at) VALUES (?, ?, ?)", key, color.raw, at.toEpochMilli())
        }
    }

    /** The eraser — `SelectionBar.removeHighlights`. */
    fun removeHighlights(keys: Collection<Int>) = db.transaction {
        for (key in keys) db.execute("DELETE FROM highlights WHERE verse_key = ?", key)
    }

    // Notes

    /** Every note, most recently edited first — the Notes panel's order. */
    fun notes(): List<Note> =
        db.query("SELECT id, title, body, anchors, created_at, updated_at, origin FROM notes ORDER BY updated_at DESC") {
            Note(
                id = UUID.fromString(it.text(0)),
                title = it.text(1),
                body = it.text(2),
                anchors = Note.parseAnchors(it.text(3)),
                createdAt = Instant.ofEpochMilli(it.long(4)),
                updatedAt = Instant.ofEpochMilli(it.long(5)),
                origin = it.text(6),
            )
        }

    fun note(id: UUID): Note? = notes().firstOrNull { it.id == id }

    /** Inserts or replaces by id. */
    fun save(note: Note) = db.execute(
        """INSERT OR REPLACE INTO notes (id, title, body, anchors, first_verse_key, created_at, updated_at, origin)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        note.id.toString(), note.title, note.body, Note.encodeAnchors(note.anchors), note.firstVerseKey,
        note.createdAt.toEpochMilli(), note.updatedAt.toEpochMilli(), note.origin,
    )

    fun deleteNote(id: UUID) = db.transaction {
        db.execute("DELETE FROM notes WHERE id = ?", id.toString())
        db.execute("DELETE FROM slide_photos WHERE note_id = ?", id.toString())
    }

    // Slide photos

    /** The JPEG kept with a camera note, or null. */
    fun slidePhoto(noteId: UUID): ByteArray? =
        db.query("SELECT jpeg FROM slide_photos WHERE note_id = ?", noteId.toString()) { it.blob(0) }.firstOrNull()

    /** The notes that have a photo kept with them. */
    fun slidePhotoIds(): Set<UUID> = db.query("SELECT note_id FROM slide_photos") { UUID.fromString(it.text(0)) }.toSet()

    /** Keeps [jpeg] with the note, replacing any photo it had; null removes it ("Remove Photo"). */
    fun setSlidePhoto(noteId: UUID, jpeg: ByteArray?) {
        if (jpeg == null) db.execute("DELETE FROM slide_photos WHERE note_id = ?", noteId.toString())
        else db.execute("INSERT OR REPLACE INTO slide_photos (note_id, jpeg) VALUES (?, ?)", noteId.toString(), jpeg)
    }

    // Favorites

    /** Newest first — the Favorites list's order. */
    fun favorites(): List<Favorite> =
        db.query("SELECT id, range, created_at FROM favorites ORDER BY created_at DESC") { row ->
            VerseRange.parse(row.text(1))?.let { Favorite(UUID.fromString(row.text(0)), it, Instant.ofEpochMilli(row.long(2))) }
        }.filterNotNull()

    fun add(favorite: Favorite) = db.execute(
        "INSERT OR REPLACE INTO favorites (id, range, start_key, end_key, created_at) VALUES (?, ?, ?, ?, ?)",
        favorite.id.toString(), favorite.range.storageString, favorite.range.start.key, favorite.range.end.key,
        favorite.createdAt.toEpochMilli(),
    )

    fun deleteFavorite(id: UUID) = db.execute("DELETE FROM favorites WHERE id = ?", id.toString())

    /**
     * The heart — `FavoriteButton.toggle`: when every range is already a favorite, removes them;
     * otherwise favorites whichever aren't yet. Returns whether the ranges are favorites afterwards.
     */
    fun toggleFavorite(ranges: List<VerseRange>, at: Instant = Instant.now()): Boolean = db.transaction {
        val existing = favorites()
        if (Selection.isFavorite(ranges, existing)) {
            val raws = ranges.map { it.storageString }.toSet()
            existing.filter { it.range.storageString in raws }.forEach { deleteFavorite(it.id) }
            false
        } else {
            val stored = existing.map { it.range.storageString }.toSet()
            ranges.filter { it.storageString !in stored }.forEach { add(Favorite(range = it, createdAt = at)) }
            true
        }
    }
}
