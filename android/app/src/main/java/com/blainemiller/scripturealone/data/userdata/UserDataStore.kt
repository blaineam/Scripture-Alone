package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.notesimport.ImportedNotes
import java.time.Instant
import java.util.UUID

/** What an import added, and what it found already here — `LifeBibleImportView.Outcome`. */
data class NotesImportTally(
    var highlights: Int = 0,
    var notes: Int = 0,
    var journals: Int = 0,
    var favorites: Int = 0,
    var alreadyThere: Int = 0,
) {
    val total: Int get() = highlights + notes + journals + favorites

    companion object {
        /** Marks a note as having come from elsewhere, alongside "manual" and "camera" — as iOS. */
        const val ORIGIN = "lifebible"

        /**
         * What makes two notes the same note, for not importing one twice: title and body, whitespace
         * normalised and lower-cased — not the verse (a reader may write several notes on one) and not
         * the date (an import carries none). `LifeBibleImportView.fingerprint`.
         */
        fun fingerprint(title: String, body: String): String {
            // Swift's `isWhitespace`, which a JVM `\s` misses (a no-break space) and Android's regex
            // engine has no Unicode flag for — so by hand.
            fun flatten(text: String) = buildString {
                var gap = false
                for (c in text) {
                    if (c.isWhitespace()) {
                        gap = isNotEmpty()
                    } else {
                        if (gap) append(' ')
                        gap = false
                        append(c)
                    }
                }
            }.lowercase()
            return flatten(title) + "" + flatten(body)
        }
    }
}

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

    // Import

    /**
     * Writes what a notes import found — `LifeBibleImportView.bring`. Everything checks for its own
     * presence first, so importing the same file twice adds nothing the second time: a highlight by its
     * verse, a saved verse by its range, a note by [NotesImportTally.fingerprint] (title and body). One
     * transaction: all of it lands, or none of it does.
     *
     * As on iOS, only what was here *before* the import counts: two identical entries within one file
     * both come across (two notes reading "Amen" on different verses are two notes).
     */
    fun importNotes(found: ImportedNotes, at: Instant = Instant.now()): NotesImportTally = db.transaction {
        val tally = NotesImportTally()
        val millis = at.toEpochMilli()
        val existingHighlights = highlights().map { it.verseKey }.toSet()
        val existingFavorites = favorites().map { it.range.storageString }.toSet()
        val existingNotes = notes().map { NotesImportTally.fingerprint(it.title, it.body) }.toSet()

        for (highlight in found.highlights) {
            if (highlight.verse.key in existingHighlights) {
                tally.alreadyThere++
                continue
            }
            val color = HighlightColor.fromRaw(highlight.color) ?: HighlightColor.YELLOW
            db.execute("INSERT INTO highlights (verse_key, color, created_at) VALUES (?, ?, ?)", highlight.verse.key, color.raw, millis)
            tally.highlights++
        }
        fun note(title: String, body: String, anchors: List<VerseRange>): Boolean {
            if (NotesImportTally.fingerprint(title, body) in existingNotes) {
                tally.alreadyThere++
                return false
            }
            save(Note(title = title, body = body, anchors = anchors.sortedWith(RANGE_ORDER), createdAt = at, updatedAt = at,
                origin = NotesImportTally.ORIGIN))
            return true
        }
        for (entry in found.verseNotes) if (note(entry.title, entry.body, listOfNotNull(entry.range))) tally.notes++
        for (entry in found.journals) if (note(entry.title, entry.body, emptyList())) tally.journals++
        for (range in found.saved) {
            if (range.storageString in existingFavorites) {
                tally.alreadyThere++
                continue
            }
            add(Favorite(range = range, createdAt = at))
            tally.favorites++
        }
        tally
    }

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
