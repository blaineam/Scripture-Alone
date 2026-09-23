package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The `meta` row an imported translation carries. Ported from `ImportedTranslationIdentity` in
 * `Import/ImportedBibleBuilder.swift`. The copyright line is not optional: it is the publisher's
 * attribution, the reader prints it, and the share/export gates read it. An import with nothing to
 * attribute is refused rather than quietly stored.
 */
data class ImportedTranslationIdentity(
    val id: String,
    val name: String,
    val abbreviation: String,
    val copyright: String,
    val license: String = UNKNOWN_LICENSE,
    val source: String = "Imported file",
) {
    companion object {
        /**
         * An imported file's licence is unknown by definition. "Unknown" must behave like "licensed",
         * never like "public domain", everywhere the app gates on it.
         */
        const val UNKNOWN_LICENSE = "Unknown — imported by the reader"

        /**
         * A starting point the UI can present for the user to correct. Nothing here is trusted: the
         * import flow is expected to make the user confirm the name and paste the copyright line.
         */
        fun suggested(metadata: EPUBMetadata): ImportedTranslationIdentity {
            val title = metadata.title?.let(SwiftText::trimWhitespaceAndNewlines) ?: ""
            val name = title.ifEmpty { AppText.get(R.string.data_import_untitled_bible) }
            var rights = metadata.rights?.let(SwiftText::trimWhitespaceAndNewlines) ?: ""
            val publisher = metadata.publisher
            if (rights.isEmpty() && !publisher.isNullOrEmpty()) rights = "© $publisher"
            return ImportedTranslationIdentity(
                id = identifier(metadata.identifier ?: name),
                name = name,
                abbreviation = abbreviation(name),
                copyright = rights,
                source = publisher?.let { "Imported ePub — $it" } ?: "Imported ePub",
            )
        }

        /** A starting point taken from a USFM zip's own copyright page and DBL metadata. */
        fun suggested(metadata: USFMMetadata): ImportedTranslationIdentity {
            val name = metadata.title?.let(SwiftText::trimWhitespaceAndNewlines)?.ifEmpty { null } ?: AppText.get(R.string.data_import_untitled_bible)
            val copyright = metadata.copyright?.let(SwiftText::trimWhitespaceAndNewlines) ?: ""
            return ImportedTranslationIdentity(
                id = identifier(metadata.identifier ?: metadata.abbreviation ?: name),
                name = name,
                abbreviation = metadata.abbreviation?.uppercase()?.ifEmpty { null } ?: abbreviation(name),
                copyright = copyright,
                license = metadata.license?.ifEmpty { null } ?: UNKNOWN_LICENSE,
                source = "Imported USFM",
            )
        }

        private val skippedWords = setOf("the", "of", "a", "an", "and", "holy", "version", "edition", "translation")

        /** Letters of the significant words, so "Holman Christian Standard Bible" suggests "HCSB". */
        fun abbreviation(name: String): String {
            // "Bible" is kept: the B in CSB, BSB and ESV comes from it.
            val initials = SwiftText.split(name.lowercase()) { chars, i -> !chars.isLetter(i) && !chars.isNumber(i) }
                .filter { it !in skippedWords }
                .map { word -> SwiftCharacters(word).string(0).uppercase() }
                .joinToString("")
            if (SwiftText.characterCount(initials) >= 2) return SwiftText.prefix(initials, 6)
            val characters = SwiftCharacters(name)
            val letters = buildString {
                for (i in 0 until characters.count) if (characters.isLetter(i)) characters.appendTo(this, i)
            }.uppercase()
            return if (letters.isEmpty()) "IMP" else SwiftText.prefix(letters, 4)
        }

        /** A stable, filename-safe id (FNV-1a over UTF-8). Imported stores never collide with a bundled ASV/BSB/KJV. */
        fun identifier(seed: String): String {
            var hash = 0xcbf2_9ce4_8422_2325uL
            for (byte in seed.toByteArray(Charsets.UTF_8)) {
                hash = (hash xor byte.toUByte().toULong()) * 0x1000_0000_01b3uL
            }
            return "IMPORT-" + (hash % 0xFFFF_FFFFuL).toString(36).uppercase()
        }
    }
}

/**
 * The few SQLite operations writing a store needs. The app writes through the bundled driver
 * ([BundledStoreWriter]), which has FTS5 where Android's framework SQLite does not; the JVM tests
 * write through JDBC. [ImportedBibleBuilder] never sees which one it has.
 *
 * Any failure is thrown as whatever the driver throws; the builder turns it into
 * [BibleImportError.DatabaseWrite].
 */
interface ImportedStoreWriter : AutoCloseable {
    /** Runs one SQL statement, discarding any rows it returns (a `PRAGMA journal_mode` returns one). */
    fun execute(sql: String)

    /** Prepares [sql] once and runs it for every row of positional arguments (String, Long, Int or null). */
    fun insert(sql: String, rows: Sequence<List<Any?>>)

    /** Opens (creating if needed) a read-write database at a path. */
    fun interface Opener {
        fun open(file: File): ImportedStoreWriter
    }
}

/**
 * Writes extracted rows into the same SQLite + FTS5 shape `Tools/build_bibles.py` produces for the
 * bundled translations, so the reader opens an imported translation with no changes at all: same
 * tables, same compact layout JSON, and the same verse key (book × 1,000,000 + chapter × 1,000 +
 * verse) that highlights and notes are stored against. Ported from `Import/ImportedBibleBuilder.swift`.
 */
object ImportedBibleBuilder {

    /** Writes the store and returns the coverage report for what was written. */
    fun write(
        bible: ExtractedBible,
        identity: ImportedTranslationIdentity,
        file: File,
        opener: ImportedStoreWriter.Opener,
    ): ImportCoverageReport {
        if (bible.isEmpty) throw BibleImportError.NoScriptureFound()
        if (SwiftText.trimWhitespaceAndNewlines(identity.copyright).isEmpty()) throw BibleImportError.MissingCopyright()
        val report = ImportCoverageReport(bible)

        val directory = file.absoluteFile.parentFile
        directory?.mkdirs()
        // A half-written store from a previous attempt must not survive, nor a rollback journal a
        // crashed write left beside it: SQLite's is "<partial>-journal"; "<partial>.journal" goes too.
        val temporary = File(directory, ".${file.name}.partial")
        for (stale in listOf(temporary, File(directory, "${temporary.name}.journal"), File(directory, "${temporary.name}-journal"))) {
            stale.delete()
        }

        val writer = try {
            opener.open(temporary)
        } catch (error: Exception) {
            throw BibleImportError.DatabaseWrite(error.message ?: "could not create the file")
        }
        try {
            writer.use { db ->
                // The reader opens stores read-only (iOS: immutable=1), so the store must be a plain
                // rollback-journal database with nothing left beside it.
                db.execute("PRAGMA page_size = 4096")
                db.execute("PRAGMA journal_mode = DELETE")
                for (statement in SCHEMA) db.execute(statement)
                db.execute("BEGIN")
                writeMeta(db, identity, bible)
                writeBooks(db, bible)
                writeChapters(db, bible)
                writeVerses(db, bible)
                db.execute("COMMIT")
                db.execute("INSERT INTO verses_fts(verses_fts) VALUES ('rebuild')")
                db.execute("INSERT INTO verses_fts(verses_fts) VALUES ('optimize')")
                db.execute("VACUUM")
            }
        } catch (error: Exception) {
            temporary.delete()
            if (error is BibleImportError) throw error
            throw BibleImportError.DatabaseWrite(error.message ?: error.toString())
        }

        file.delete()
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw BibleImportError.DatabaseWrite("could not move the store into place")
        }
        return report
    }

    /** Byte-for-byte the shape `Tools/build_bibles.py` creates (Swift runs these as one script). */
    val SCHEMA: List<String> = listOf(
        "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE books (book INTEGER PRIMARY KEY, code TEXT NOT NULL, name TEXT NOT NULL, chapters INTEGER NOT NULL)",
        "CREATE TABLE chapters (book INTEGER NOT NULL, chapter INTEGER NOT NULL, verses INTEGER NOT NULL,\n" +
            "                       layout TEXT NOT NULL, PRIMARY KEY (book, chapter)) WITHOUT ROWID",
        "CREATE TABLE verses (id INTEGER PRIMARY KEY, text TEXT NOT NULL, red TEXT)",
        "CREATE VIRTUAL TABLE verses_fts USING fts5(text, content='verses', content_rowid='id',\n" +
            "                                          tokenize='unicode61 remove_diacritics 2')",
    )

    // MARK: - Tables

    private fun writeMeta(db: ImportedStoreWriter, identity: ImportedTranslationIdentity, bible: ExtractedBible) {
        val rows = listOf(
            "id" to identity.id,
            "name" to identity.name,
            "abbreviation" to identity.abbreviation,
            "copyright" to identity.copyright,
            "license" to identity.license,
            "source" to identity.source,
            // Read by the app to keep imported text out of every path that could carry it off the
            // device: share links, verse images, exports, widgets, the watch and the Studio hand-off.
            "imported" to "1",
            // ISO8601DateFormatter's default: UTC, whole seconds, "Z".
            "importedAt" to DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)),
            "books" to bible.books.size.toString(),
        )
        db.insert("INSERT INTO meta VALUES (?1, ?2)", rows.asSequence().map { (key, value) -> listOf(key, value) })
    }

    private fun writeBooks(db: ImportedStoreWriter, bible: ExtractedBible) {
        db.insert(
            "INSERT INTO books VALUES (?1, ?2, ?3, ?4)",
            bible.books.asSequence().map { book ->
                val chapters = bible.chapterOrder.filter { it.book == book }.map { it.chapter }.toSet().size
                listOf(book.number.toLong(), book.code, book.englishName, chapters.toLong())
            },
        )
    }

    private fun writeChapters(db: ImportedStoreWriter, bible: ExtractedBible) {
        db.insert(
            "INSERT INTO chapters VALUES (?1, ?2, ?3, ?4)",
            bible.chapterOrder.sorted().asSequence().map { chapter ->
                listOf(
                    chapter.book.number.toLong(), chapter.chapter.toLong(),
                    bible.highestVerse(chapter).toLong(), layoutJSON(bible.blocks(chapter)),
                )
            },
        )
    }

    private fun writeVerses(db: ImportedStoreWriter, bible: ExtractedBible) {
        db.insert(
            "INSERT INTO verses VALUES (?1, ?2, ?3)",
            bible.verses.keys.sortedBy { it.key }.asSequence().map { ref ->
                val verse = bible.verses.getValue(ref)
                val red = if (verse.red.isEmpty()) null else JsonArray(verse.red.map { ints(it.start, it.length) }).toString()
                listOf(ref.key.toLong(), verse.text, red)
            },
        )
    }

    /**
     * The compact layout JSON the reader decodes: `{"b":[{"k":…,"t":…}|{"k":…,"f":[…]}]}`.
     *
     * Keys are written in a fixed order ("k", "t"/"f"; "v", "t", "n", "s", "fn"). Swift's
     * `JSONSerialization` writes dictionary keys in no specified order; every reader decodes by key.
     */
    fun layoutJSON(blocks: List<ExtractedBlock>): String {
        val encoded = ArrayList<JsonElement>()
        for (block in blocks) {
            if (block.kind == ExtractedBlock.Kind.STANZA_BREAK) {
                encoded.add(JsonObject(mapOf("k" to JsonPrimitive(block.kind.rawValue))))
                continue
            }
            if (block.kind.isHeading) {
                encoded.add(JsonObject(mapOf("k" to JsonPrimitive(block.kind.rawValue), "t" to JsonPrimitive(block.heading ?: ""))))
                continue
            }
            val fragments = ArrayList<JsonElement>()
            for (fragment in block.fragments) {
                if (fragment.text.isEmpty() && !fragment.numbered) continue
                val row = LinkedHashMap<String, JsonElement>()
                row["v"] = JsonPrimitive(fragment.verse)
                row["t"] = JsonPrimitive(fragment.text)
                if (fragment.numbered) row["n"] = JsonPrimitive(1)
                if (fragment.spans.isNotEmpty()) {
                    row["s"] = JsonArray(fragment.spans.map { JsonArray(listOf(JsonPrimitive(it.start), JsonPrimitive(it.length), JsonPrimitive(it.style.rawValue))) })
                }
                if (fragment.footnotes.isNotEmpty()) {
                    row["fn"] = JsonArray(fragment.footnotes.map { JsonArray(listOf(JsonPrimitive(it.position), JsonPrimitive(it.text))) })
                }
                fragments.add(JsonObject(row))
            }
            if (fragments.isEmpty()) continue
            encoded.add(JsonObject(mapOf("k" to JsonPrimitive(block.kind.rawValue), "f" to JsonArray(fragments))))
        }
        return JsonObject(mapOf("b" to JsonArray(encoded))).toString()
    }

    private fun ints(vararg values: Int): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
}
