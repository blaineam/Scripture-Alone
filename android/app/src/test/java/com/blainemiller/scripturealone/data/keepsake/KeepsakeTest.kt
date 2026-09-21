package com.blainemiller.scripturealone.data.keepsake

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater

/**
 * A port of `KeepsakeTests.swift`, plus the cross-platform proof: keepsakes the Swift code made (in
 * `test/resources/keepsake`, plain and passphrase-protected) open here, and the files this code writes
 * are byte-identical to Swift's where the format is deterministic.
 */
class KeepsakeTest {

    private fun date(seconds: Double): Instant =
        Instant.ofEpochSecond(Math.floor(seconds).toLong(), Math.round((seconds - Math.floor(seconds)) * 1e9))

    private val john316 = VerseRef(43, 3, 16)

    /** The Swift test's sample, with the ids the Swift fixtures were written with. */
    private fun sample(): Keepsake = Keepsake(
        manifest = KeepsakeManifest(
            ownerName = "Dad", dedication = "For Anna and Sam — read it slowly.", preferredTranslation = "ASV",
            generator = "Scripture Alone 1.0.0", createdAt = date(1_790_000_000.125),
            exportID = UUID.fromString("11111111-2222-3333-4444-555555555555"),
            bibleID = UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"),
        ),
        highlights = listOf(
            KeepsakeHighlight(john316.key, "yellow", date(1_700_000_000.5)),
            KeepsakeHighlight(VerseRef(19, 23, 1).key, "blue", date(1_710_000_000.0)),
        ),
        notes = listOf(
            KeepsakeNote.of("No condemnation", "Pastor Jim, Sunday.\nLine two — “quoted”.",
                listOf(VerseRange.of(VerseRef(45, 8, 1), VerseRef(45, 8, 17))),
                date(1_720_000_000.25), date(1_730_000_000.0),
                id = UUID.fromString("0E1B5C2A-9F3D-4E6B-8A7C-1D2E3F405162")),
            KeepsakeNote.of("", "", listOf(VerseRange.of(john316)), date(1_740_000_000.0), date(1_740_000_000.0),
                origin = "camera", id = UUID.fromString("7A8B9C0D-1E2F-4A5B-8C6D-7E8F90A1B2C3")),
        ),
    ).withRefreshedSummary()

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/keepsake/$name")?.readBytes() ?: error("missing fixture $name")

    private fun parse(bytes: ByteArray) = Json.parseToJsonElement(String(bytes))

    private fun rezip(files: Map<String, ByteArray>, replacing: String, with: ByteArray): ByteArray =
        ZipArchive.write((files + (replacing to with)).toSortedMap().map { ZipArchive.Entry(it.key, it.value) })

    private fun jsonBytes(element: JsonElement) = element.toString().toByteArray()

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("expected ${T::class.simpleName}, got $error", error is T)
        return error as T
    }

    // Cross-platform

    @Test fun opensAKeepsakeTheIphoneMade() {
        assertEquals(sample(), KeepsakeArchive.decode(fixture("swift-plain.scripturelegacy")))
    }

    @Test fun opensAProtectedKeepsakeTheIphoneMade() {
        val data = fixture("swift-sealed.scripturelegacy")
        val outer = KeepsakeArchive.peek(data)
        assertTrue(outer.isEncrypted)
        assertEquals("Our first dog", outer.passphraseHint)
        assertEquals(2_000, outer.encryption?.iterations)
        assertEquals(sample(), KeepsakeArchive.decode(data, "Mañana, grace"))
        // Decomposed "ñ" opens it too: the passphrase is NFC-normalized on both platforms.
        assertEquals(sample(), KeepsakeArchive.decode(data, "Mañana, grace"))
        assertThrows<KeepsakeException.WrongPassphrase> { KeepsakeArchive.decode(data, "mañana, grace") }
    }

    /** Where the format is deterministic, Android writes the very bytes the iPhone writes. */
    @Test fun writesTheSameFilesTheIphoneWrites() {
        val swift = ZipArchive.read(fixture("swift-plain.scripturelegacy"))
        val ours = ZipArchive.read(KeepsakeArchive.encode(sample()))
        assertEquals(swift.keys, ours.keys)
        for (name in swift.keys) assertEquals(name, String(swift.getValue(name)), String(ours.getValue(name)))
        val sealedSwift = ZipArchive.read(fixture("swift-sealed.scripturelegacy"))
        assertEquals(String(sealedSwift.getValue("README.txt")), KeepsakeArchive.readme(protected = true))
    }

    @Test fun pbkdf2MatchesRfc7914TestVector() {
        // RFC 7914 §11: PBKDF2-HMAC-SHA256, P="passwd", S="salt", c=1, dkLen=64.
        val key = KeepsakeCrypto.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            key.joinToString("") { "%02x".format(it) },
        )
    }

    // Ported from KeepsakeTests.swift

    @Test fun roundTripPlain() {
        val keepsake = sample()
        val decoded = KeepsakeArchive.decode(KeepsakeArchive.encode(keepsake))
        assertEquals(keepsake, decoded)
        assertEquals(KeepsakeManifest.Counts(2, 2), decoded.manifest.counts)
        assertEquals(date(1_700_000_000.5), decoded.manifest.dateRange?.start)
        assertEquals("Romans 8:1–17", decoded.notes[0].anchors.first().display)
        assertEquals("Dad’s Bible", decoded.manifest.displayTitle)
    }

    @Test fun archiveIsAnOrdinaryZipWithReadableJson() {
        val data = KeepsakeArchive.encode(sample())
        val files = ZipArchive.read(data)
        assertEquals(setOf("manifest.json", "highlights.json", "notes.json", "README.txt"), files.keys)
        val manifest = String(files.getValue("manifest.json"))
        assertTrue(manifest.contains("\"formatVersion\" : 1"))
        assertTrue(manifest.contains("\"format\" : \"com.blainemiller.scripturealone.legacy\""))
        assertTrue(String(files.getValue("highlights.json")).contains("\"reference\" : \"John 3:16\""))

        // The system unzip agrees, so the file is readable without this app.
        val unzip = java.io.File("/usr/bin/unzip")
        if (unzip.exists()) {
            val file = java.io.File.createTempFile("keepsake", ".zip").apply { writeBytes(data); deleteOnExit() }
            val process = ProcessBuilder(unzip.path, "-tq", file.path).redirectErrorStream(true).start()
            assertEquals(0, process.waitFor())
        }
    }

    @Test fun versionFieldsAreEnforced() {
        val files = ZipArchive.read(KeepsakeArchive.encode(sample()))
        val json = parse(files.getValue("manifest.json")).jsonObject
        assertEquals(KeepsakeManifest.CURRENT_VERSION.toString(), json["formatVersion"]!!.jsonPrimitive.content)
        assertEquals("1", json["minimumReaderVersion"]!!.jsonPrimitive.content)

        val newer = JsonObject(json + mapOf(
            "minimumReaderVersion" to JsonPrimitive(KeepsakeManifest.SUPPORTED_READER_VERSION + 1),
            "formatVersion" to JsonPrimitive(7),
        ))
        val error = assertThrows<KeepsakeException.NewerVersion> {
            KeepsakeArchive.decode(rezip(files, "manifest.json", jsonBytes(newer)))
        }
        assertEquals(7, error.version)
    }

    @Test fun unknownFieldsAreIgnored() {
        val keepsake = sample()
        val files = ZipArchive.read(KeepsakeArchive.encode(keepsake))
        val manifest = JsonObject(parse(files.getValue("manifest.json")).jsonObject + mapOf(
            "formatVersion" to JsonPrimitive(3),             // a newer writer…
            "minimumReaderVersion" to JsonPrimitive(1),      // …that says old readers are fine
            "photos" to JsonArray(listOf(JsonPrimitive("cover.jpg"))),
            "counts" to JsonObject(mapOf("highlights" to JsonPrimitive(2), "notes" to JsonPrimitive(2), "bookmarks" to JsonPrimitive(9))),
        ))
        val highlightsJson = parse(files.getValue("highlights.json")).jsonObject
        val rows = highlightsJson["highlights"]!!.jsonArray.mapIndexed { i, row ->
            if (i == 0) JsonObject(row.jsonObject + ("inkStyle" to JsonPrimitive("fountain pen"))) else row
        }
        val highlights = JsonObject(highlightsJson + mapOf("highlights" to JsonArray(rows), "schemaNote" to JsonPrimitive("future")))
        val notesJson = parse(files.getValue("notes.json")).jsonObject
        val noteRows = notesJson["notes"]!!.jsonArray.mapIndexed { i, row ->
            if (i == 0) JsonObject(row.jsonObject + ("audio" to JsonObject(mapOf("file" to JsonPrimitive("sermon.m4a"))))) else row
        }
        val rewritten = files + mapOf(
            "manifest.json" to jsonBytes(manifest),
            "highlights.json" to jsonBytes(highlights),
            "notes.json" to jsonBytes(JsonObject(notesJson + ("notes" to JsonArray(noteRows)))),
            "future/extra.bin" to byteArrayOf(1, 2, 3),
        )
        val decoded = KeepsakeArchive.decode(ZipArchive.write(rewritten.map { ZipArchive.Entry(it.key, it.value) }))
        assertEquals(3, decoded.manifest.formatVersion)
        assertEquals(keepsake.highlights, decoded.highlights)
        assertEquals(keepsake.notes, decoded.notes)
        assertEquals("Dad", decoded.manifest.ownerName)
    }

    @Test fun missingOptionalFieldsDecode() {
        val data = ZipArchive.write(listOf(
            ZipArchive.Entry("manifest.json", """{"format":"com.blainemiller.scripturealone.legacy"}""".toByteArray()),
            ZipArchive.Entry("notes.json", """{"notes":[{"body":"Just a thought","passages":[{"start":43003016}]}]}""".toByteArray()),
        ))
        val decoded = KeepsakeArchive.decode(data)
        assertTrue(decoded.highlights.isEmpty())
        assertEquals(listOf(VerseRange.of(john316)), decoded.notes.first().anchors)
        assertEquals("A Keepsake Bible", decoded.manifest.displayTitle)
        assertEquals(decoded.manifest.exportID, decoded.manifest.bibleID)
    }

    @Test fun encryptedRoundTrip() {
        val keepsake = sample()
        val data = KeepsakeArchive.encode(keepsake, "Mañana, grace", "Our first dog", iterations = 2_000)
        val outer = KeepsakeArchive.peek(data)
        assertTrue(outer.isEncrypted)
        assertEquals("Our first dog", outer.passphraseHint)
        assertNull(outer.ownerName)
        assertEquals("PBKDF2-HMAC-SHA256", outer.encryption?.kdf)
        assertEquals(2_000, outer.encryption?.iterations)

        val files = ZipArchive.read(data)
        assertNull(files["notes.json"])
        assertFalse(String(files.getValue("payload.sealed"), Charsets.ISO_8859_1).contains("condemnation"))

        val decoded = KeepsakeArchive.decode(data, "Mañana, grace")
        assertEquals(keepsake, decoded)
        assertNull(decoded.manifest.encryption)
    }

    @Test fun defaultIterationsRoundTrip() {
        val keepsake = sample()
        val data = KeepsakeArchive.encode(keepsake, "psalm 23")
        assertEquals(KeepsakeCrypto.DEFAULT_ITERATIONS, KeepsakeArchive.peek(data).encryption?.iterations)
        assertEquals(keepsake, KeepsakeArchive.decode(data, "psalm 23"))
    }

    @Test fun wrongPassphraseFailsCleanly() {
        val data = KeepsakeArchive.encode(sample(), "correct horse", iterations = 1_000)
        assertThrows<KeepsakeException.WrongPassphrase> { KeepsakeArchive.decode(data, "Correct horse") }
        assertThrows<KeepsakeException.PassphraseRequired> { KeepsakeArchive.decode(data) }
        assertThrows<KeepsakeException.PassphraseRequired> { KeepsakeArchive.decode(data, "") }
    }

    @Test fun tamperedEncryptionSettingsFail() {
        val data = KeepsakeArchive.encode(sample(), "correct horse", iterations = 1_000)
        val files = ZipArchive.read(data)
        val json = JsonObject(parse(files.getValue("manifest.json")).jsonObject + ("passphraseHint" to JsonPrimitive("It's 'password'")))
        // The manifest is authenticated, so even the right passphrase refuses an altered one.
        assertThrows<KeepsakeException.WrongPassphrase> {
            KeepsakeArchive.decode(rezip(files, "manifest.json", jsonBytes(json)), "correct horse")
        }
    }

    @Test fun rejectsOtherFiles() {
        assertThrows<KeepsakeException.NotAKeepsake> { KeepsakeArchive.decode("hello".toByteArray()) }
        val other = ZipArchive.write(listOf(ZipArchive.Entry("manifest.json", """{"format":"com.example.other"}""".toByteArray())))
        assertThrows<KeepsakeException.NotAKeepsake> { KeepsakeArchive.decode(other) }
        val encoded = KeepsakeArchive.encode(sample())
        val truncated = encoded.copyOfRange(0, 40) + encoded.copyOfRange(200, encoded.size)
        assertThrows<Exception> { KeepsakeArchive.decode(truncated) }
    }

    @Test fun zipReadsDeflatedEntries() {
        val text = "hello hello hello hello hello hello hello hello\n".toByteArray()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true).apply { setInput(text); finish() }
        val buffer = ByteArray(256)
        val deflated = buffer.copyOf(deflater.deflate(buffer)).also { deflater.end() }
        val archive = java.io.ByteArrayOutputStream()
        fun le16(v: Int) { archive.write(v and 0xFF); archive.write(v shr 8 and 0xFF) }
        fun le32(v: Long) { for (k in 0 until 4) archive.write((v shr (8 * k) and 0xFF).toInt()) }
        val crc = ZipArchive.crc32(text)
        le32(0x0403_4B50); le16(20); le16(0); le16(8); le16(0); le16(0)
        le32(crc); le32(deflated.size.toLong()); le32(text.size.toLong()); le16(5); le16(0)
        archive.write("a.txt".toByteArray()); archive.write(deflated)
        val central = archive.size()
        le32(0x0201_4B50); le16(20); le16(20); le16(0); le16(8); le16(0); le16(0)
        le32(crc); le32(deflated.size.toLong()); le32(text.size.toLong()); le16(5); le16(0); le16(0); le16(0); le16(0); le32(0); le32(0)
        archive.write("a.txt".toByteArray())
        val size = archive.size() - central
        le32(0x0605_4B50); le16(0); le16(0); le16(1); le16(1); le32(size.toLong()); le32(central.toLong()); le16(0)
        assertArrayEquals(text, ZipArchive.read(archive.toByteArray())["a.txt"])
    }

    @Test fun markdownAndTextExport() {
        val notes = sample().notes
        val verseText: (VerseRange) -> String? = { if (it.display == "John 3:16") "For God so loved the world" else null }
        val options = NotesTextExport.Options(title = "Dad’s Notes", translation = "ASV", locale = Locale.US, zone = ZoneId.of("UTC"))
        val markdown = NotesTextExport.markdown(notes, options, verseText)
        assertTrue(markdown.startsWith("# Dad’s Notes"))
        assertTrue(markdown.contains("## No condemnation"))
        assertTrue(markdown.contains("**Romans 8:1–17**"))
        assertTrue(markdown.contains("> For God so loved the world\n> — John 3:16 (ASV)"))
        assertTrue(markdown.contains("*Written July 3, 2024 · Edited October 27, 2024*"))

        val files = NotesTextExport.markdownFiles(notes + notes[0], options, verseText)
        assertEquals(3, files.size)
        assertEquals(3, files.map { it.first }.toSet().size)
        assertTrue(files.all { !it.first.contains("/") && it.first.endsWith(".md") })
        assertEquals("2024-07-03 No condemnation.md", files[0].first)
        assertEquals("2024-07-03 No condemnation 2.md", files[2].first)

        val plain = NotesTextExport.plainText(notes, NotesTextExport.Options(title = "Notes", translation = null), verseText)
        assertTrue(plain.contains("No condemnation\nRomans 8:1–17"))
        assertFalse(plain.contains("For God so loved"))
    }

    @Test fun exportCarriesThePublishersNotice() {
        val notes = sample().notes.take(1)
        val options = NotesTextExport.Options(translation = "CSB", notice = " Copyright © 2017 Holman ")
        assertTrue(NotesTextExport.markdown(notes, options) { null }.endsWith("\n---\n\nCopyright © 2017 Holman\n"))
        assertTrue(NotesTextExport.plainText(notes, options) { null }.endsWith("—\nCopyright © 2017 Holman\n"))
        assertTrue(NotesTextExport.markdown(sample().notes, options) { null }.endsWith("\n---\n\nCopyright © 2017 Holman\n"))
        assertTrue(NotesTextExport.markdownFiles(sample().notes, options) { null }.all { it.second.endsWith("Copyright © 2017 Holman\n") })
        // No notice, no trailing block: the bytes Swift writes for a public-domain export.
        assertFalse(NotesTextExport.markdown(notes, options.copy(notice = null)) { null }.endsWith("\n"))
    }
}
