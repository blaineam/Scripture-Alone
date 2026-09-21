package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.sql.SqlSource
import com.blainemiller.scripturealone.data.study.JdbcSqlSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Synthetic ePub and USFM archives, built in the test, ported from `ImportFixtures.swift`.
 *
 * Every scrap of scripture here is either the American Standard Version (1901, public domain) or
 * invented for the test. No copyrighted translation appears in this repository.
 */
object ImportFixtures {
    // MARK: - ZIP

    /**
     * [zip64Size], when set, is claimed by the central directory through a ZIP64 extra field (the
     * 32-bit field saturated); [claimedSize], when set, is claimed by both headers instead of the real size.
     */
    class ZipEntry(
        val name: String,
        val data: ByteArray,
        val deflate: Boolean = false,
        val zip64Size: Long? = null,
        val claimedSize: Long? = null,
    ) {
        constructor(name: String, text: String, deflate: Boolean = false) : this(name, text.toByteArray(Charsets.UTF_8), deflate)
    }

    /**
     * A minimal ZIP, with per-entry choice of stored or DEFLATE, so the reader's inflate path is
     * exercised by real compressed bytes rather than by a stored entry pretending. Laid out byte for
     * byte as the Swift fixture lays it out (only DEFLATE output differs between zlib builds).
     */
    fun zip(entries: List<ZipEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        for (entry in entries) {
            val name = entry.name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(entry.data) }.value
            var payload = entry.data
            var method = 0
            if (entry.deflate) {
                payload = rawDeflate(entry.data)
                method = 8
            }
            val offset = output.size()
            val size = entry.claimedSize ?: entry.data.size.toLong()
            val extra = ByteArrayOutputStream()
            entry.zip64Size?.let { extra.le16(0x0001); extra.le16(8); extra.le32(it and 0xFFFF_FFFFL); extra.le32(it ushr 32) }
            val extraBytes = extra.toByteArray()

            output.le32(0x0403_4B50); output.le16(20); output.le16(0x0800); output.le16(method)
            output.le16(0); output.le16(0); output.le32(crc)
            output.le32(payload.size.toLong()); output.le32(size)
            output.le16(name.size); output.le16(0)
            output.write(name); output.write(payload)

            central.le32(0x0201_4B50); central.le16(20); central.le16(20); central.le16(0x0800); central.le16(method)
            central.le16(0); central.le16(0); central.le32(crc)
            central.le32(payload.size.toLong()); central.le32(if (entry.zip64Size == null) size else 0xFFFF_FFFFL)
            central.le16(name.size); central.le16(extraBytes.size); central.le16(0); central.le16(0); central.le16(0)
            central.le32(0); central.le32(offset.toLong())
            central.write(name); central.write(extraBytes)
        }
        val centralOffset = output.size()
        val centralBytes = central.toByteArray()
        output.write(centralBytes)
        output.le32(0x0605_4B50); output.le16(0); output.le16(0)
        output.le16(entries.size); output.le16(entries.size)
        output.le32(centralBytes.size.toLong()); output.le32(centralOffset.toLong()); output.le16(0)
        return output.toByteArray()
    }

    /** Headerless DEFLATE, which is what Apple's `.zlib` algorithm produces and what ZIP stores. */
    private fun rawDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun ByteArrayOutputStream.le16(value: Int) {
        write(value and 0xFF); write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(value: Long) {
        for (shift in 0 until 32 step 8) write(((value shr shift) and 0xFF).toInt())
    }

    // MARK: - ePub

    class Document(val path: String, body: String) {
        /** The file's exact bytes, when a test needs an encoding or a prefix [xhtml] cannot carry. */
        var raw: ByteArray? = null
            private set

        companion object {
            /** A document whose bytes are given as they are, not wrapped. */
            fun raw(path: String, bytes: ByteArray): Document = Document(path, "").also { it.raw = bytes }
        }

        val xhtml: String = listOf(
            """<?xml version="1.0" encoding="utf-8"?>""",
            "<!DOCTYPE html>",
            """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""",
            "<head><title>$path</title></head>",
            "<body>",
            body,
            "</body></html>",
        ).joinToString("\n")
    }

    val defaultMetadata: Map<String, String> = mapOf(
        "title" to "Antique Standard Bible",
        "creator" to "A Committee",
        "publisher" to "Example Press",
        "rights" to "Text is in the public domain. Typesetting © 2026 Example Press.",
        "language" to "en",
        "identifier" to "urn:isbn:9780000000001",
    )

    /** A whole ePub: mimetype, container, package document and the documents given, in spine order. */
    fun epub(
        documents: List<Document>,
        metadata: Map<String, String> = defaultMetadata,
        extra: List<ZipEntry> = emptyList(),
        opfOverride: String? = null,
        deflate: Boolean = false,
    ): ByteArray {
        val entries = ArrayList<ZipEntry>()
        entries.add(ZipEntry("mimetype", "application/epub+zip"))
        entries.add(
            ZipEntry(
                "META-INF/container.xml",
                listOf(
                    """<?xml version="1.0"?>""",
                    """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""",
                    """  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>""",
                    "</container>",
                ).joinToString("\n"),
            ),
        )
        val manifest = documents.mapIndexed { index, document ->
            """<item id="d$index" href="${document.path}" media-type="application/xhtml+xml"/>"""
        }.joinToString("\n    ")
        val spine = documents.indices.joinToString("\n    ") { """<itemref idref="d$it"/>""" }
        val dublinCore = metadata.entries.sortedBy { it.key }
            .joinToString("\n    ") { "<dc:${it.key}>${escape(it.value)}</dc:${it.key}>" }
        val opf = opfOverride ?: listOf(
            """<?xml version="1.0" encoding="utf-8"?>""",
            """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">""",
            """  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""",
            "    $dublinCore",
            "  </metadata>",
            "  <manifest>",
            "    $manifest",
            "  </manifest>",
            "  <spine>",
            "    $spine",
            "  </spine>",
            "</package>",
        ).joinToString("\n")
        entries.add(ZipEntry("OEBPS/content.opf", opf, deflate))
        for (document in documents) {
            entries.add(document.raw?.let { ZipEntry("OEBPS/${document.path}", it, deflate) } ?: ZipEntry("OEBPS/${document.path}", document.xhtml, deflate))
        }
        entries.addAll(extra)
        return zip(entries)
    }

    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // MARK: - USFM

    fun usfmZip(
        books: List<Pair<String, String>>,
        copyright: String? = "Copyright © 2026 Example Press. Released into the Public Domain.",
        metadataXML: String? = null,
    ): ByteArray {
        val entries = books.map { ZipEntry(it.first, it.second, deflate = true) }.toMutableList()
        if (copyright != null) entries.add(ZipEntry("copr.htm", "<html><body><p>${escape(copyright)}</p></body></html>"))
        if (metadataXML != null) entries.add(ZipEntry("metadata.xml", metadataXML))
        return zip(entries)
    }

    // MARK: - Files on disk

    fun write(data: ByteArray, named: String): File {
        val directory = Files.createTempDirectory("scripture-import-tests-").toFile()
        return File(directory, named).also { it.writeBytes(data) }
    }

    fun scratchDirectory(): File = Files.createTempDirectory("scripture-import-store-").toFile()

    // MARK: - Writing and reading stores on the JVM

    /** The store writer the tests use: JDBC, where the app uses the bundled driver. */
    val jdbcWriter = ImportedStoreWriter.Opener { file -> JdbcStoreWriter(file) }

    fun importer(options: BibleTextExtractor.Options = BibleTextExtractor.Options()) = BibleFileImporter(jdbcWriter, options)
}

/** [ImportedStoreWriter] over JDBC (sqlite-jdbc bundles FTS5, as the app's bundled driver does). */
class JdbcStoreWriter(file: File) : ImportedStoreWriter {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")

    override fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    override fun insert(sql: String, rows: Sequence<List<Any?>>) {
        connection.prepareStatement(sql).use { statement ->
            for (row in rows) {
                statement.clearParameters()
                row.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    override fun close() = connection.close()
}

/**
 * The questions the Swift tests ask `BibleStore`, answered from a written store through the same
 * [SqlSource] seam the app's stores use, and the app's own [ChapterLayout] decoder — so "the reader
 * can open it" is checked with the reader's code, not a second decoder.
 */
class ImportedStoreReader(file: File) : AutoCloseable {
    private val source = JdbcSqlSource(file)
    private val sql: SqlSource get() = source

    data class Verse(val ref: VerseRef, val text: String, val red: List<ScalarSpan>)

    val meta: Map<String, String> = sql.query("SELECT key, value FROM meta") { it.text(0) to it.text(1) }.toMap()

    fun contains(chapter: ChapterRef): Boolean =
        sql.query("SELECT 1 FROM chapters WHERE book = ? AND chapter = ?", chapter.book.number, chapter.chapter) { it.long(0) }.isNotEmpty()

    fun verseCount(chapter: ChapterRef): Int =
        sql.query("SELECT verses FROM chapters WHERE book = ? AND chapter = ?", chapter.book.number, chapter.chapter) { it.long(0).toInt() }
            .firstOrNull() ?: 0

    fun verses(from: VerseRef, to: VerseRef = from): List<Verse> =
        sql.query("SELECT id, text, red FROM verses WHERE id BETWEEN ? AND ? ORDER BY id", from.key, to.key) { row ->
            Verse(VerseRef.fromKey(row.long(0).toInt()), row.text(1), if (row.isNull(2)) emptyList() else parseRed(row.text(2)))
        }

    fun layout(chapter: ChapterRef): ChapterLayout =
        ChapterLayout.parse(
            sql.query("SELECT layout FROM chapters WHERE book = ? AND chapter = ?", chapter.book.number, chapter.chapter) { it.text(0) }.single(),
        )

    fun search(term: String): List<VerseRef> =
        sql.query("SELECT rowid FROM verses_fts WHERE verses_fts MATCH ? ORDER BY rowid", term) { VerseRef.fromKey(it.long(0).toInt()) }

    override fun close() = source.close()

    private fun parseRed(json: String): List<ScalarSpan> =
        Regex("""\[(\d+),(\d+)]""").findAll(json).map { ScalarSpan(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.toList()
}

// Swift-style shorthands, so the ported tests read like the originals.
internal fun ref(book: BookID, chapter: Int, verse: Int): VerseRef = VerseRef(book.number, chapter, verse)

/** Swift's `try #require(x)`: fails the test when [value] is null, and hands it back otherwise. */
internal fun <T : Any> required(value: T?): T = required("required value was null", value)

internal fun <T : Any> required(message: String, value: T?): T {
    org.junit.Assert.assertNotNull(message, value)
    return value!!
}

/**
 * Swift's `#expect(throws: BibleImportError.x)`: the block must throw a [BibleImportError] of type
 * [T], equal to [expected] when one is given.
 */
internal inline fun <reified T : BibleImportError> assertThrowsImport(expected: BibleImportError? = null, block: () -> Unit) {
    try {
        block()
    } catch (error: BibleImportError) {
        org.junit.Assert.assertTrue("expected ${T::class.simpleName}, got $error", error is T)
        if (expected != null) org.junit.Assert.assertEquals(expected, error)
        return
    }
    org.junit.Assert.fail("expected ${T::class.simpleName} to be thrown")
}
