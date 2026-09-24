package com.blainemiller.scripturealone.data.study

import com.blainemiller.scripturealone.data.TranslationInfo
import com.blainemiller.scripturealone.data.importer.ImportedTranslationIdentity
import com.blainemiller.scripturealone.data.sql.SqlSource

/** A picture from an imported study Bible, placed beside a verse or at the head of a book. */
data class StudyImage(val id: Long, val caption: String, val anchorKey: Int?, val book: Int?)

/**
 * Reads the study material an import brought with it — notes, introductions, essays and pictures
 * (see `ImportedBibleBuilder.writeStudy`) — in the shape the study panel already shows commentary
 * in, so an imported study Bible sits in the commentary picker beside the bundled commentators.
 * A port of `ScriptureAloneCore/ImportedStudyStore.swift`.
 *
 * Read from the same file as the translation; [open] answers null when that file has no study tables.
 */
class ImportedStudyStore private constructor(private val db: SqlSource, val source: StudySource) {

    /** The notes on a verse, then any essay set beside it. */
    fun commentary(verseKey: Int): List<CommentaryEntry> {
        val book = verseKey / 1_000_000
        val chapter = (verseKey / 1_000) % 1_000
        val notes = db.query(
            "SELECT start_key, end_key, body FROM study_notes WHERE start_key <= ? AND end_key >= ? ORDER BY start_key, end_key",
            verseKey, verseKey,
        ) { row -> CommentaryEntry(source.id, VerseKeyRange(row.long(0).toInt(), row.long(1).toInt()), book, chapter, row.text(2)) }
        val essays = db.query(
            "SELECT title, body FROM study_articles WHERE kind = 'essay' AND anchor_key = ?", verseKey,
        ) { row ->
            val title = row.text(0)
            val body = row.text(1)
            CommentaryEntry(source.id, VerseKeyRange(verseKey, verseKey), book, chapter, if (title.isEmpty()) body else title + "\n\n" + body)
        }
        return notes + essays
    }

    /** A book's introduction and outline, shown with its first chapter. */
    fun introduction(book: Int, chapter: Int): CommentaryEntry? {
        if (chapter != 1) return null
        val parts = db.query(
            "SELECT title, body FROM study_articles WHERE kind = 'introduction' AND book = ? ORDER BY rowid", book,
        ) { row ->
            val title = row.text(0)
            val body = row.text(1)
            if (title.isEmpty()) body else title + "\n\n" + body
        }
        if (parts.isEmpty()) return null
        return CommentaryEntry(source.id, null, book, chapter, parts.joinToString("\n\n"))
    }

    /** Whether any note covers the verse. */
    fun comments(verseKey: Int): Boolean =
        db.query("SELECT 1 FROM study_notes WHERE start_key <= ? AND end_key >= ? LIMIT 1", verseKey, verseKey) { true }.isNotEmpty()

    /** Pictures beside a chapter's verses, and a book's own pictures with its first chapter. */
    fun images(book: Int, chapter: Int): List<StudyImage> {
        val first = book * 1_000_000 + chapter * 1_000
        return db.query(
            "SELECT id, caption, anchor_key, book FROM study_images " +
                "WHERE (anchor_key BETWEEN ? AND ?) OR (anchor_key IS NULL AND book = ? AND ? = 1) " +
                "ORDER BY COALESCE(anchor_key, 0), id",
            first, first + 999, book, chapter,
        ) { row ->
            StudyImage(
                row.long(0), row.text(1),
                if (row.isNull(2)) null else row.long(2).toInt(),
                if (row.isNull(3)) null else row.long(3).toInt(),
            )
        }
    }

    fun imageData(id: Long): ByteArray? = db.query("SELECT data FROM study_images WHERE id = ?", id) { it.blob(0) }.firstOrNull()

    companion object {
        /** The study material in an imported store, or null when it carries none. */
        fun open(db: SqlSource, info: TranslationInfo): ImportedStudyStore? {
            val hasTables = runCatching { db.query("SELECT 1 FROM study_notes LIMIT 1") { true } }.isSuccess
            if (!hasTables) return null
            fun meta(key: String): String? =
                runCatching { db.query("SELECT value FROM meta WHERE key = ?", key) { it.text(0) }.firstOrNull() }.getOrNull()
            val name = meta("study_name") ?: info.name
            // The notes are the study Bible publisher's, not the translation's: credited to them.
            val publisher = meta("study_publisher") ?: ""
            val source = StudySource(
                id = "import-${info.id}", kind = StudySource.Kind.COMMENTARY, name = name,
                shortName = ImportedTranslationIdentity.abbreviation(name), author = publisher, year = "",
                license = info.license, licenseUrl = "", url = "",
                attribution = if (publisher.isEmpty()) info.copyright else "© $publisher",
            )
            return ImportedStudyStore(db, source)
        }
    }
}
