package com.blainemiller.scripturealone.wear

import android.database.sqlite.SQLiteDatabase
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

/** One verse on the watch: its text and words-of-Christ spans as (start, length) in Unicode scalars. */
data class WatchVerse(val ref: VerseRef, val text: String, val red: List<Pair<Int, Int>>)

/** The few rows the edition reader needs, so it runs on the watch and, through JDBC, on the JVM. */
interface EditionRows {
    fun <T> query(sql: String, vararg args: Any, map: (Row) -> T): List<T>

    interface Row {
        fun long(column: Int): Long
        fun text(column: Int): String
        fun isNull(column: Int): Boolean
    }
}

/**
 * A compact watch edition — `*-Watch.sqlite`, the files the Apple Watch bundles (`WatchEdition.swift`,
 * `Tools/build_companion_data.py`): `meta`, `books`, `chapters` (verse counts) and `verses(id, text,
 * red)`. No layout and no search index, so Android's own SQLite reads it; the watch needs no FTS5.
 */
class WatchEdition(val id: String, private val rows: EditionRows) {

    val meta: Map<String, String> by lazy {
        rows.query("SELECT key, value FROM meta") { it.text(0) to it.text(1) }.toMap()
    }

    /** "American Standard Version". */
    val name: String get() = meta["name"] ?: id

    /** Verses 1… of [range] (headings, verse 0, are the phone's), in order. */
    fun verses(range: VerseRange): List<WatchVerse> =
        rows.query("SELECT id, text, red FROM verses WHERE id BETWEEN ? AND ? ORDER BY id", range.start.key, range.end.key) {
            WatchVerse(VerseRef.fromKey(it.long(0).toInt()), it.text(1), if (it.isNull(2)) emptyList() else parseRed(it.text(2)))
        }.filter { it.ref.verse > 0 }

    fun text(range: VerseRange): String = verses(range).joinToString(" ") { it.text }

    fun verseCount(book: Int, chapter: Int): Int =
        rows.query("SELECT verses FROM chapters WHERE book = ? AND chapter = ?", book, chapter) { it.long(0).toInt() }
            .firstOrNull() ?: 0

    /** A whole chapter as a range — `WatchBible.chapterRange`. */
    fun chapterRange(book: Int, chapter: Int): VerseRange =
        VerseRange(VerseRef(book, chapter, 1), VerseRef(book, chapter, maxOf(1, verseCount(book, chapter))))

    companion object {
        /** `verses.red`: JSON `[[start, length], …]`; a malformed pair is skipped, as in Swift. */
        fun parseRed(json: String): List<Pair<Int, Int>> {
            val root = try {
                Json.parseToJsonElement(json)
            } catch (e: IllegalArgumentException) {
                return emptyList()
            }
            return (root as? JsonArray)?.mapNotNull { pair ->
                val numbers = (pair as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull } ?: return@mapNotNull null
                val start = numbers.getOrNull(0)
                val length = numbers.getOrNull(1)
                if (numbers.size != 2 || start == null || length == null || start < 0 || length <= 0) null else start to length
            }.orEmpty()
        }
    }
}

/** [EditionRows] over Android's SQLite, read-only. */
class AndroidEditionRows(file: File) : EditionRows {
    private val db = SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    override fun <T> query(sql: String, vararg args: Any, map: (EditionRows.Row) -> T): List<T> =
        db.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { cursor ->
            val row = object : EditionRows.Row {
                override fun long(column: Int) = cursor.getLong(column)
                override fun text(column: Int): String = cursor.getString(column)
                override fun isNull(column: Int) = cursor.isNull(column)
            }
            buildList { while (cursor.moveToNext()) add(map(row)) }
        }
}
