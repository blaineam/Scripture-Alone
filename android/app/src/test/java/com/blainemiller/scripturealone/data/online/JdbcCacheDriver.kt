package com.blainemiller.scripturealone.data.online

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.layout.utf16Range
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.sql.SqlRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** [CacheDatabaseDriver] over JDBC, so the cache is proven on the JVM against a real SQLite file. */
class JdbcCacheDriver : CacheDatabaseDriver {
    override fun openReadOnly(file: File): CacheDatabase {
        require(file.exists()) { "no such file: $file" } // SQLITE_OPEN_READONLY never creates
        return JdbcCacheDatabase(SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:${file.path}"))
    }

    override fun openForWriting(file: File): CacheDatabase =
        JdbcCacheDatabase(DriverManager.getConnection("jdbc:sqlite:${file.path}"))
}

private class JdbcCacheDatabase(private val connection: Connection) : CacheDatabase {
    override fun execute(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.execute()
        }
    }

    override fun <T> query(sql: String, vararg args: Any, map: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeQuery().use { rs ->
                val row = Row(rs)
                buildList { while (rs.next()) add(map(row)) }
            }
        }

    override fun close() = connection.close()

    private class Row(private val rs: ResultSet) : SqlRow {
        override fun long(column: Int) = rs.getLong(column + 1)
        override fun text(column: Int): String = rs.getString(column + 1) ?: ""
        override fun blob(column: Int): ByteArray = rs.getBytes(column + 1) ?: ByteArray(0)
        override fun isNull(column: Int): Boolean {
            rs.getObject(column + 1)
            return rs.wasNull()
        }
    }
}

/**
 * What the Swift tests read the cache back through: `BibleStore`, reduced to the calls they make.
 *
 * Opened exactly as `BibleStore` opens a store — read-only, `immutable=1` — and, like it, reading the
 * chapter table once, when it is made. That is what makes "a reader opened before a write must be
 * reopened" a real test rather than a tautology.
 */
class CachedStore(file: File) : AutoCloseable {
    private val connection: Connection =
        SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:file:${file.path}?immutable=1")

    val meta: Map<String, String> = rows("SELECT key, value FROM meta") { it.getString(1) to it.getString(2) }.toMap()
    val id: String get() = meta["id"] ?: ""
    val abbreviation: String get() = meta["abbreviation"] ?: ""
    val copyright: String get() = meta["copyright"] ?: ""

    private val verseCounts: Map<ChapterRef, Int> =
        rows("SELECT book, chapter, verses FROM chapters") { ChapterRef(it.getInt(1), it.getInt(2)) to it.getInt(3) }.toMap()

    fun contains(chapter: ChapterRef): Boolean = chapter in verseCounts
    fun verseCount(chapter: ChapterRef): Int = verseCounts[chapter] ?: 0

    fun layout(chapter: ChapterRef): ChapterLayout =
        ChapterLayout.parse(rows("SELECT layout FROM chapters WHERE book = ? AND chapter = ?", chapter.book, chapter.chapter) {
            it.getString(1)
        }.single())

    /** Verse rows with `red` turned back from stored scalars into UTF-16, as `BibleStore` does. */
    fun verses(range: VerseRange): List<VerseText> =
        rows("SELECT id, text, red FROM verses WHERE id BETWEEN ? AND ? ORDER BY id", range.start.key, range.end.key) {
            val text = it.getString(2)
            VerseText(VerseRef.fromKey(it.getInt(1)), text, red(it.getString(3), text))
        }

    /** Full-text search for one word, the last word matched as a prefix — `BibleStore.ftsQuery`'s shape. */
    fun search(word: String): List<VerseRef> =
        rows("SELECT rowid FROM verses_fts WHERE verses_fts MATCH ? ORDER BY rowid", "\"$word\" *") {
            VerseRef.fromKey(it.getInt(1))
        }

    override fun close() = connection.close()

    private fun red(json: String?, text: String): List<Utf16Range> {
        if (json == null) return emptyList()
        return (Json.parseToJsonElement(json) as JsonArray).map { pair ->
            val (start, length) = (pair as JsonArray).map { (it as JsonPrimitive).intOrNull!! }
            val r = text.utf16Range(start, length)
            Utf16Range(r.first, r.last + 1 - r.first)
        }
    }

    private fun <T> rows(sql: String, vararg args: Any, map: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
}
