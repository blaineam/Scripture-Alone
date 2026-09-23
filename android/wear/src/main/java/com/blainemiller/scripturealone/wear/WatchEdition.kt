package com.blainemiller.scripturealone.wear

import android.database.sqlite.SQLiteDatabase
import com.blainemiller.scripturealone.data.VerseNumbering
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * One verse on the watch: its text and words-of-Christ spans as (start, length) in Unicode scalars.
 * [ref] is the verse as its Bible numbers it — what is drawn; [kjv] the KJV key it is stored and routed
 * under (the same for the English Bibles).
 */
data class WatchVerse(val ref: VerseRef, val text: String, val red: List<Pair<Int, Int>>, val kjv: VerseRef = ref)

/** The few rows the edition reader needs, so it runs on the watch and, through JDBC, on the JVM. */
interface EditionRows {
    fun <T> query(sql: String, vararg args: Any, map: (Row) -> T): List<T>

    /** Releases the file — a received edition being replaced by a newer copy. */
    fun close() {}

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
 *
 * **Numbering.** A locale Bible the phone sent ([WatchEditionBuilder]) also carries `kjv_map`: it keeps
 * its own verse numbers (Segond's Psalm 51:12 is the KJV's 51:10), while everything the watch stores or
 * is handed — the phone's favorites, highlights and notes, Verse of the Day, routes — is in KJV keys.
 * So [verses] takes KJV keys and [nativeVerses] the Bible's own; both return verses carrying both.
 */
class WatchEdition(val id: String, private val rows: EditionRows) {

    val meta: Map<String, String> by lazy {
        rows.query("SELECT key, value FROM meta") { it.text(0) to it.text(1) }.toMap()
    }

    /** "American Standard Version". */
    val name: String get() = meta["name"] ?: id

    /** The Bible's language (`meta.language`, BCP 47), or null for the English Bibles, which have none. */
    val language: String? get() = meta["language"]?.takeIf { it.isNotEmpty() }

    /** How this Bible's verse numbers map to the KJV keys marks are stored under; identity without a map. */
    val numbering: VerseNumbering by lazy {
        val hasMap = rows.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'kjv_map'") { true }.isNotEmpty()
        if (!hasMap) {
            VerseNumbering.IDENTITY
        } else {
            VerseNumbering(rows.query("SELECT id, kjv, kjv_last FROM kjv_map") { VerseNumbering.Row(it.long(0).toInt(), it.long(1).toInt(), it.long(2).toInt()) })
        }
    }

    /** Verses holding the **KJV** range [range] — a favorite, a note's passage, the verse of the day. */
    fun verses(range: VerseRange): List<WatchVerse> {
        val native = numbering.nativeRange(range) ?: return emptyList()
        return nativeVerses(native)
    }

    /** Verses 1… of a range in this Bible's own numbers (headings, verse 0, are the phone's), in order. */
    fun nativeVerses(range: VerseRange): List<WatchVerse> {
        val numbering = numbering
        return rows.query("SELECT id, text, red FROM verses WHERE id BETWEEN ? AND ? ORDER BY id", range.start.key, range.end.key) {
            val key = it.long(0).toInt()
            WatchVerse(
                VerseRef.fromKey(key), it.text(1), if (it.isNull(2)) emptyList() else parseRed(it.text(2)),
                VerseRef.fromKey(numbering.kjv(key)),
            )
        }.filter { it.ref.verse > 0 }
    }

    /** [range] (KJV keys) as this Bible numbers it, for drawing its reference; itself when nothing maps. */
    fun nativeRange(range: VerseRange): VerseRange = numbering.nativeRange(range) ?: range

    fun close() = rows.close()

    fun text(range: VerseRange): String = verses(range).joinToString(" ") { it.text }

    fun verseCount(book: Int, chapter: Int): Int =
        rows.query("SELECT verses FROM chapters WHERE book = ? AND chapter = ?", book, chapter) { it.long(0).toInt() }
            .firstOrNull() ?: 0

    /** A whole chapter, in this Bible's own numbers, as a range — `WatchBible.chapterRange`. */
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
