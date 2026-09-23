package com.blainemiller.scripturealone.data.online

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crossway's terms for the ESV API, as the app enforces them — the constants of `ESVClient.swift`
 * that the cache depends on. The client itself (networking, the reader's key) is not ported here.
 */
object EsvTerms {
    /** Crossway's stated ceiling: "You can cache up to 500 verses." A licence obligation, not tuning. */
    const val CACHE_VERSE_LIMIT = 500

    /** Must be shown wherever ESV text is. */
    const val REQUIRED_COPYRIGHT =
        "Scripture quotations are from the ESV® Bible (The Holy Bible, English Standard Version®), " +
            "© 2001 by Crossway, a publishing ministry of Good News Publishers. Used by permission. " +
            "All rights reserved."
}

/** The identity an online translation writes into its cache's `meta` row. */
data class OnlineTranslation(
    val id: String,
    val name: String,
    val abbreviation: String,
    /** The publisher's required notice. The reader prints it wherever the text appears. */
    val copyright: String,
    val license: String = "Licensed — cached from the publisher's API",
) {
    companion object {
        val ESV = OnlineTranslation(
            id = "ESV",
            name = "English Standard Version",
            abbreviation = "ESV",
            copyright = EsvTerms.REQUIRED_COPYRIGHT,
            license = "© Crossway. Cached under Crossway’s API terms, up to ${EsvTerms.CACHE_VERSE_LIMIT} verses.",
        )
    }
}

/** Swift's `OnlineCacheError`; [message] is its `errorDescription`. */
sealed class OnlineCacheException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Open(val detail: String, cause: Throwable? = null) :
        OnlineCacheException("Couldn’t open the offline copy: $detail", cause)

    class Write(val detail: String, cause: Throwable? = null) :
        OnlineCacheException("Couldn’t save that chapter: $detail", cause)
}

/** What one [OnlineChapterCache.store] call did. */
data class CacheWrite(
    val chapter: ChapterRef,
    val versesStored: Int,
    /** Chapters dropped to make room, least recently read first. */
    val evicted: List<ChapterRef>,
    /** Total verses held after the write. */
    val cachedVerses: Int,
    /**
     * True when this one chapter is larger than the whole ceiling. No canonical chapter is (Psalm
     * 119, the longest, is 176 verses), so this is a guard rather than a behaviour.
     */
    val exceedsLimit: Boolean,
)

/**
 * An on-demand chapter cache that is a real translation store — ported from
 * `ScriptureAloneCore/ESV/OnlineChapterCache.swift`.
 *
 * The ESV cannot be bundled, so its text arrives from Crossway's API a chapter at a time. Rather than
 * a bespoke in-memory path that selection, quotation, listening, highlighting, notes and layout would
 * each have to special-case, this writes **the same `meta`/`books`/`chapters`/`verses`/`verses_fts`
 * schema the bundled translations use**, so the reader opens it like any other store.
 *
 * **The publisher's terms are enforced here, not by callers.** Crossway's terms cap what may be kept
 * — "You can cache up to 500 verses" — so the ceiling is checked on every write, evicting whole
 * chapters least-recently-read first. The one exception is a single chapter bigger than the whole
 * ceiling: it is stored alone, because the reader is looking at it. [clear] (for when the reader
 * removes their key) vacuums, so the text is actually gone rather than sitting in free pages.
 *
 * ## Never written in place
 *
 * A reader may hold the file open with `immutable=1` — a promise to SQLite that it will not change,
 * breaking which is undefined behaviour (a torn page, `SQLITE_CORRUPT`), not merely a stale read. So
 * every mutation copies the store to a sibling file, changes the copy, and swaps it in with an atomic
 * rename, which replaces the directory entry and leaves the old inode untouched for anyone still
 * reading it. **The caller must re-open its reader after every successful [store] or [clear]**; a
 * reader opened before a write is finished — it keeps the old contents or fails with an I/O error,
 * never a half-written chapter.
 *
 * Writes are slow enough (a file copy plus SQLite) to belong off the main thread. The methods are
 * synchronous and serialised by a lock, so call them from a background dispatcher.
 */
class OnlineChapterCache(
    val file: File,
    val translation: OnlineTranslation,
    private val driver: CacheDatabaseDriver,
    verseLimit: Int = EsvTerms.CACHE_VERSE_LIMIT,
) {
    /** Total verses the cache may hold. Never less than one. */
    val verseLimit: Int = maxOf(1, verseLimit)

    /**
     * Serialises this process's writers. It does not and cannot coordinate with readers — readers
     * are made safe by the copy-and-replace, not by this lock.
     */
    private val lock = Any()

    init {
        // A cache is disposable: a file that is missing, corrupt, or not one of ours is replaced
        // with an empty one rather than stranding the reader.
        if (!isUsable()) create()
    }

    // ---- Reading ---------------------------------------------------------------------------------

    fun contains(chapter: ChapterRef): Boolean = try {
        read { db ->
            db.value("SELECT 1 FROM cache_state WHERE book = ?1 AND chapter = ?2", chapter.book, chapter.chapter) != null
        }
    } catch (e: Exception) {
        false // Swift's `try?`: a cache that cannot be read holds nothing
    }

    fun cachedVerseCount(): Int = read { db -> db.totalVerses() }

    /** Cached chapters, most recently read first. */
    fun cachedChapters(): List<ChapterRef> = read { db ->
        db.query("SELECT book, chapter FROM cache_state ORDER BY lastRead DESC") {
            it.long(0).toInt() to it.long(1).toInt()
        }.filter { (book, _) -> BookID.of(book) != null }.map { (book, chapter) -> ChapterRef(book, chapter) }
    }

    // ---- Writing ---------------------------------------------------------------------------------

    /**
     * Inserts or replaces one chapter, with whatever structure the provider supplied, evicting
     * whatever must go to stay under the ceiling.
     *
     * Both services are asked for HTML, which names its blocks — so an online psalm arrives as poetry
     * lines with its superscription, and the words of Christ arrive as red. [blocks] is empty only
     * for a source that cannot say, and then the chapter is one numbered paragraph.
     *
     * Verses outside [chapter] are ignored.
     */
    fun store(verses: List<VerseText>, chapter: ChapterRef, blocks: List<ChapterLayout.Block> = emptyList()): CacheWrite {
        val rows = verses.filter { it.ref.book == chapter.book && it.ref.chapter == chapter.chapter }
            .sortedBy { it.ref.key }
        if (rows.isEmpty()) {
            return CacheWrite(chapter, versesStored = 0, evicted = emptyList(),
                cachedVerses = cachedVerseCount(), exceedsLimit = false)
        }
        val layout = layout(rows, blocks)

        return mutate { db ->
            val held = db.value("SELECT verses FROM cache_state WHERE book = ?1 AND chapter = ?2",
                chapter.book, chapter.chapter) ?: 0
            val total = db.totalVerses()
            var projected = total - held + rows.size

            // Evict whole chapters, least recently read first, until the write fits. The chapter
            // being written is never a candidate.
            val evicted = mutableListOf<ChapterRef>()
            while (projected > verseLimit) {
                val (victim, victimVerses) = leastRecentlyRead(db, excluding = chapter) ?: break
                forget(db, victim)
                projected -= victimVerses
                evicted += victim
            }
            // One chapter bigger than the whole ceiling is stored anyway: the reader is looking at
            // it, and a cap on what is *kept* is never a reason to fail the chapter in front of
            // them. Everything else has already been evicted, so the cache holds only this.
            val exceeds = projected > verseLimit

            forget(db, chapter)
            val sequence = (db.value("SELECT COALESCE(MAX(lastRead), 0) FROM cache_state") ?: 0) + 1

            for (row in rows) {
                val spans = scalarSpans(row.red, row.text)
                val red = if (spans.isEmpty()) null else redJson(spans)
                db.execute("INSERT INTO verses VALUES (?1, ?2, ?3)", row.ref.key, row.text, red)
            }
            db.execute("INSERT INTO chapters VALUES (?1, ?2, ?3, ?4)",
                chapter.book, chapter.chapter, rows.last().ref.verse, layout)
            db.execute("INSERT INTO cache_state VALUES (?1, ?2, ?3, ?4)",
                chapter.book, chapter.chapter, rows.size, sequence)
            refreshBooks(db)
            reindex(db)

            CacheWrite(chapter, versesStored = rows.size, evicted = evicted,
                cachedVerses = db.totalVerses(), exceedsLimit = exceeds)
        }
    }

    /**
     * Records that the reader looked at a chapter, so eviction follows real reading rather than the
     * order chapters happened to be fetched. A chapter that is not cached is ignored.
     */
    fun markRead(chapter: ChapterRef) {
        if (!contains(chapter)) return
        mutate { db ->
            val sequence = (db.value("SELECT COALESCE(MAX(lastRead), 0) FROM cache_state") ?: 0) + 1
            db.execute("UPDATE cache_state SET lastRead = ?3 WHERE book = ?1 AND chapter = ?2",
                chapter.book, chapter.chapter, sequence)
        }
    }

    /**
     * Drops every cached verse — what to do when the reader removes their API key. The store file
     * survives, empty, so the reader still opens it; `VACUUM` makes sure the text is actually gone
     * rather than sitting in free pages.
     */
    fun clear() {
        mutate(vacuum = true) { db ->
            for (table in listOf("verses", "chapters", "books", "cache_state")) db.execute("DELETE FROM $table")
            reindex(db)
        }
    }

    // ---- The file --------------------------------------------------------------------------------

    /** True when the file exists and is one of our caches, written under the current text format. */
    private fun isUsable(): Boolean {
        if (!file.exists()) return false
        val db = try {
            driver.openReadOnly(file)
        } catch (e: Exception) {
            return false
        }
        return db.use {
            fun <T> attempt(body: () -> T): T? = try { body() } catch (e: Exception) { null }
            val marker = attempt { it.value("SELECT COUNT(*) FROM cache_state") }
            val online = attempt { it.text("SELECT value FROM meta WHERE key = 'online'") }
            // A cache written under older formatting rules holds text this build would not produce,
            // and the reader would keep seeing the old shape until every chapter happened to be
            // re-fetched. Folding the stamp into "usable" discards that cache and re-fetches.
            val format = attempt { it.text("SELECT value FROM meta WHERE key = 'textFormat'") }
            marker != null && online == "1" && format == TEXT_FORMAT_VERSION
        }
    }

    private fun create() {
        val directory = file.absoluteFile.parentFile
        directory?.mkdirs() // a failure surfaces below, as the open of the temporary file
        val temporary = File(directory, ".${file.name}.new")
        temporary.delete()

        val db = open(temporary, forWriting = true)
        try {
            sql {
                db.execute("PRAGMA page_size = 4096")
                db.execute("PRAGMA journal_mode = DELETE")
                // The bundled-store schema, verbatim, plus one side table. The reader reads only the
                // tables it knows, so `cache_state` is invisible to it — which is why the
                // read-tracking lives there rather than as a column on `chapters`.
                for (statement in BIBLE_SCHEMA) db.execute(statement)
                db.execute(CACHE_STATE_SCHEMA)
                for ((key, value) in listOf(
                    "id" to translation.id, "name" to translation.name,
                    "abbreviation" to translation.abbreviation,
                    "copyright" to translation.copyright, "license" to translation.license,
                    "source" to "Fetched from the publisher’s API",
                    "online" to "1", "verseLimit" to verseLimit.toString(),
                    "textFormat" to TEXT_FORMAT_VERSION,
                )) {
                    db.execute("INSERT INTO meta VALUES (?1, ?2)", key, value)
                }
            }
        } catch (e: Exception) {
            db.closeQuietly()
            temporary.delete()
            throw e
        }
        db.closeQuietly()
        file.delete()
        try {
            Files.move(temporary.toPath(), file.toPath())
        } catch (e: IOException) {
            temporary.delete()
            throw OnlineCacheException.Open(e.message ?: e.toString(), e)
        }
    }

    /**
     * Copy, change the copy, replace the original — see the class note: this is what keeps a reader
     * that is already open from reading a file that changes underneath it. The cost is one copy of a
     * file that holds at most a few hundred verses.
     */
    private fun <T> mutate(vacuum: Boolean = false, body: (CacheDatabase) -> T): T = synchronized(lock) {
        if (!isUsable()) create()

        val working = File(file.absoluteFile.parentFile, ".${file.name}.writing")
        working.delete()
        try {
            Files.copy(file.toPath(), working.toPath())
        } catch (e: IOException) {
            throw OnlineCacheException.Write(e.message ?: e.toString(), e)
        }

        val db = open(working, forWriting = true)
        val result = try {
            sql {
                db.execute("PRAGMA journal_mode = DELETE")
                db.execute("BEGIN")
                val result = body(db)
                db.execute("COMMIT")
                // VACUUM cannot run inside a transaction.
                if (vacuum) db.execute("VACUUM")
                result
            }
        } catch (e: Exception) {
            db.closeQuietly()
            working.delete()
            throw e
        }
        db.closeQuietly()

        // An atomic move is `rename(2)`: it replaces the directory entry in one step and unlinks the
        // old inode, which stays alive and unchanged for any descriptor still open on it. Copying
        // over the original instead would write through to the very file an open reader promised
        // SQLite would not change.
        try {
            Files.move(working.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            working.delete()
            throw OnlineCacheException.Write("couldn’t replace the cache (${e.message ?: e})", e)
        }
        result
    }

    private fun <T> read(body: (CacheDatabase) -> T): T = synchronized(lock) {
        // Deliberately not `immutable=1`: this reader is in the same process as the writer.
        open(file, forWriting = false).use { db -> sql { body(db) } }
    }

    private fun open(target: File, forWriting: Boolean): CacheDatabase = try {
        if (forWriting) driver.openForWriting(target) else driver.openReadOnly(target)
    } catch (e: Exception) {
        throw OnlineCacheException.Open(e.message ?: e.toString(), e)
    }

    /** Any failure inside a statement is a write error, as every Swift SQLite helper reports it. */
    private inline fun <T> sql(body: () -> T): T = try {
        body()
    } catch (e: OnlineCacheException) {
        throw e
    } catch (e: Exception) {
        throw OnlineCacheException.Write(e.message ?: e.toString(), e)
    }

    private fun CacheDatabase.closeQuietly() {
        try { close() } catch (e: Exception) { /* the file is discarded or already committed */ }
    }

    companion object {
        /**
         * Bumped whenever text written into the cache would come out differently. See [isUsable].
         * "4": headings carry their words (they were written as `"t":""`), and red spans ending in
         * whitespace are no longer dropped.
         */
        internal const val TEXT_FORMAT_VERSION = "4"

        /**
         * `ImportedBibleBuilder.schema` — byte for byte the shape `Tools/build_bibles.py` creates —
         * split into single statements for the driver.
         */
        internal val BIBLE_SCHEMA = listOf(
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE books (book INTEGER PRIMARY KEY, code TEXT NOT NULL, name TEXT NOT NULL, chapters INTEGER NOT NULL)",
            "CREATE TABLE chapters (book INTEGER NOT NULL, chapter INTEGER NOT NULL, verses INTEGER NOT NULL,\n" +
                "                       layout TEXT NOT NULL, PRIMARY KEY (book, chapter)) WITHOUT ROWID",
            "CREATE TABLE verses (id INTEGER PRIMARY KEY, text TEXT NOT NULL, red TEXT)",
            "CREATE VIRTUAL TABLE verses_fts USING fts5(text, content='verses', content_rowid='id',\n" +
                "                                          tokenize='unicode61 remove_diacritics 2')",
        )

        private const val CACHE_STATE_SCHEMA =
            "CREATE TABLE cache_state (book INTEGER NOT NULL, chapter INTEGER NOT NULL,\n" +
                "                          verses INTEGER NOT NULL, lastRead INTEGER NOT NULL,\n" +
                "                          PRIMARY KEY (book, chapter)) WITHOUT ROWID"

        /**
         * The provider's own structure when it gave one — paragraphs, poetry lines, psalm titles,
         * headings (as `{"k":"s1","t":…}`, exactly as a bundled store writes them) — or, when it did
         * not, one paragraph with every verse numbered: the same shape as [ChapterLayout.prose],
         * encoded with the same writer a bundled store's layout comes from.
         */
        internal fun layout(rows: List<VerseText>, blocks: List<ChapterLayout.Block> = emptyList()): String {
            val shape = blocks.ifEmpty {
                listOf(ChapterLayout.Block(ChapterLayout.Kind.PARAGRAPH, fragments = rows.map { row ->
                    ChapterLayout.Fragment(row.ref.verse, numbered = true, text = row.text,
                        spans = scalarSpans(row.red, row.text))
                }))
            }
            return LayoutJson.encode(shape)
        }

        /**
         * [VerseText.red] is in UTF-16 ranges; the store's offsets are Unicode scalars. A range whose
         * either end does not fall on a scalar boundary (inside a surrogate pair, or past the end),
         * or that is empty, is dropped — a silent mis-conversion would be worse than none.
         */
        internal fun scalarSpans(ranges: List<Utf16Range>, text: String): List<ChapterLayout.Span> {
            if (ranges.isEmpty()) return emptyList()
            val scalarIndex = HashMap<Int, Int>()
            var utf16 = 0
            var scalar = 0
            while (utf16 < text.length) {
                scalarIndex[utf16] = scalar
                utf16 += Character.charCount(text.codePointAt(utf16))
                scalar++
            }
            scalarIndex[utf16] = scalar
            return ranges.mapNotNull { range ->
                val start = scalarIndex[range.location] ?: return@mapNotNull null
                val end = scalarIndex[range.location + range.length] ?: return@mapNotNull null
                if (end <= start) null else ChapterLayout.Span(start, end - start, ChapterLayout.Span.Style.WORDS_OF_CHRIST)
            }
        }

        /** `verses.red`: `[[start, length], …]` in scalars. */
        private fun redJson(spans: List<ChapterLayout.Span>): String =
            JsonArray(spans.map { JsonArray(listOf(JsonPrimitive(it.start), JsonPrimitive(it.length))) }).toString()

        // ---- Eviction and bookkeeping ----

        private fun leastRecentlyRead(db: CacheDatabase, excluding: ChapterRef): Pair<ChapterRef, Int>? =
            db.query(
                """
                SELECT book, chapter, verses FROM cache_state
                WHERE NOT (book = ?1 AND chapter = ?2)
                ORDER BY lastRead ASC, book ASC, chapter ASC LIMIT 1
                """.trimIndent(),
                excluding.book, excluding.chapter,
            ) { Triple(it.long(0).toInt(), it.long(1).toInt(), it.long(2).toInt()) }
                .lastOrNull()
                // Swift builds the victim only when the book is one of the 66; a row that is not
                // leaves the loop, exactly as finding no row does.
                ?.takeIf { BookID.of(it.first) != null }
                ?.let { (book, chapter, verses) -> ChapterRef(book, chapter) to verses }

        private fun forget(db: CacheDatabase, chapter: ChapterRef) {
            val range = VerseRef.chapterRange(chapter.book, chapter.chapter)
            db.execute("DELETE FROM verses WHERE id BETWEEN ?1 AND ?2", range.first, range.last)
            db.execute("DELETE FROM chapters WHERE book = ?1 AND chapter = ?2", chapter.book, chapter.chapter)
            db.execute("DELETE FROM cache_state WHERE book = ?1 AND chapter = ?2", chapter.book, chapter.chapter)
        }

        /** `books` is derived from what is actually cached, so a book whose last chapter was evicted disappears. */
        private fun refreshBooks(db: CacheDatabase) {
            val counts = db.query("SELECT book, COUNT(*) FROM cache_state GROUP BY book") {
                it.long(0).toInt() to it.long(1).toInt()
            }.mapNotNull { (number, count) -> BookID.of(number)?.let { it to count } }
            db.execute("DELETE FROM books")
            for ((book, count) in counts.sortedBy { it.first.number }) {
                db.execute("INSERT INTO books VALUES (?1, ?2, ?3, ?4)", book.number, book.code, book.englishName, count)
            }
        }

        /**
         * `verses_fts` is an external-content index, so it has to be told when `verses` changes. A
         * full rebuild is the honest way: at 500 rows it is far cheaper than the network round trip
         * that produced them, and it cannot drift out of step the way hand-kept deletes can.
         */
        private fun reindex(db: CacheDatabase) = db.execute("INSERT INTO verses_fts(verses_fts) VALUES ('rebuild')")

        private fun CacheDatabase.totalVerses(): Int = value("SELECT COALESCE(SUM(verses), 0) FROM cache_state") ?: 0

        /** The last row's first column as an integer, or null for no rows — Swift's `value` helper. */
        private fun CacheDatabase.value(sql: String, vararg args: Any): Int? =
            query(sql, *args) { it.long(0).toInt() }.lastOrNull()

        private fun CacheDatabase.text(sql: String): String? =
            query(sql) { if (it.isNull(0)) null else it.text(0) }.lastOrNull()
    }
}

/**
 * The compact layout JSON the reader decodes — `ImportedBibleBuilder.layoutJSON` in Swift:
 * `{"b":[{"k":…,"t":…}|{"k":…,"f":[{"v":…,"n"?:1,"t":…,"s"?:[[start,length,style]],"fn"?:[[pos,note]]}]}]}`.
 * [ChapterLayout.parse] is its inverse.
 */
internal object LayoutJson {
    fun encode(blocks: List<ChapterLayout.Block>): String {
        val encoded = mutableListOf<JsonObject>()
        for (block in blocks) {
            val kind = JsonPrimitive(block.kind.code)
            if (block.kind == ChapterLayout.Kind.STANZA_BREAK) {
                encoded += JsonObject(mapOf("k" to kind))
                continue
            }
            if (block.kind.isHeading) {
                encoded += JsonObject(mapOf("k" to kind, "t" to JsonPrimitive(block.text ?: "")))
                continue
            }
            val fragments = block.fragments.filter { it.text.isNotEmpty() || it.numbered }.map { fragment ->
                val row = linkedMapOf<String, JsonElement>(
                    "v" to JsonPrimitive(fragment.verse),
                    "t" to JsonPrimitive(fragment.text),
                )
                if (fragment.numbered) row["n"] = JsonPrimitive(1)
                // A span whose style this build doesn't know (null) cannot be written back; Swift's
                // type has no such case.
                val spans = fragment.spans.mapNotNull { span ->
                    span.style?.let { JsonArray(listOf(JsonPrimitive(span.start), JsonPrimitive(span.length), JsonPrimitive(it.code))) }
                }
                if (spans.isNotEmpty()) row["s"] = JsonArray(spans)
                if (fragment.footnotes.isNotEmpty()) {
                    row["fn"] = JsonArray(fragment.footnotes.map { JsonArray(listOf(JsonPrimitive(it.position), JsonPrimitive(it.text))) })
                }
                JsonObject(row)
            }
            if (fragments.isEmpty()) continue
            encoded += JsonObject(mapOf("k" to kind, "f" to JsonArray(fragments)))
        }
        return JsonObject(mapOf("b" to JsonArray(encoded))).toString()
    }
}
