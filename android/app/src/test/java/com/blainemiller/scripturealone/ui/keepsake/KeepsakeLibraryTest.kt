package com.blainemiller.scripturealone.ui.keepsake

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.keepsake.KeepsakeArchive
import com.blainemiller.scripturealone.data.keepsake.KeepsakeCrypto
import com.blainemiller.scripturealone.data.keepsake.KeepsakeManifest
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.userdata.Highlight
import com.blainemiller.scripturealone.data.userdata.Note
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** The keepsake library, making a keepsake from the reader's marks, and reading one back. */
class KeepsakeLibraryTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("keepsakes").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/keepsake/$name")?.readBytes() ?: error("missing fixture $name")

    private fun t(seconds: Long) = Instant.ofEpochSecond(seconds)

    @Test fun iphoneKeepsakesAreKeptAndReplacedByTheirBible() {
        val library = KeepsakeLibrary(dir)
        val plain = KeepsakeArchive.decode(fixture("swift-plain.scripturelegacy"))
        assertNull(library.add(plain).previous)
        assertEquals(listOf("Dad’s Bible"), library.entries.map { it.title })

        // The protected one is the same Bible: opening it replaces the copy, stored unprotected.
        val sealed = KeepsakeArchive.decode(fixture("swift-sealed.scripturelegacy"), "Mañana, grace")
        assertEquals(plain.manifest.createdAt, library.add(sealed).previous)
        assertEquals(1, library.entries.size)
        assertFalse(KeepsakeArchive.peek(library.file(sealed.id).readBytes()).isEncrypted)

        // A relaunch finds it again, whole.
        val reopened = KeepsakeLibrary(dir).apply { reload() }
        assertEquals(sealed, reopened.keepsake(sealed.id))

        reopened.remove(sealed.id)
        assertTrue(reopened.entries.isEmpty())
        assertFalse(reopened.file(sealed.id).exists())
    }

    @Test fun aDamagedFileIsSkipped() {
        File(dir, "${UUID.randomUUID()}.${KeepsakeArchive.FILE_EXTENSION}").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(KeepsakeLibrary(dir).reload().isEmpty())
    }

    @Test fun makingAKeepsakeFromTheReadersMarks() {
        val highlights = listOf(
            Highlight(43003016, "yellow", t(100)),
            Highlight(43003016, "blue", t(200)),
            Highlight(19023001, "green", t(50)),
        )
        val notes = listOf(
            Note(title = "Journal", body = "b", createdAt = t(400), updatedAt = t(400)),
            Note(title = "Rom", body = "", anchors = listOf(VerseRange.of(VerseRef(45, 8, 1))), createdAt = t(300), updatedAt = t(900)),
        )
        val id = UUID.randomUUID()
        val keepsake = KeepsakeBuilder.make(highlights, notes, id, "  Dad ", "  ", "BSB", "Scripture Alone 1.0.0", now = t(1_000))
        assertEquals(id, keepsake.manifest.bibleID)
        assertEquals("Dad", keepsake.manifest.ownerName)
        assertNull(keepsake.manifest.dedication)
        assertEquals("BSB", keepsake.manifest.preferredTranslation)
        assertEquals(listOf(19023001 to "green", 43003016 to "blue"), keepsake.highlights.map { it.verse to it.color })
        assertEquals(listOf("Rom", "Journal"), keepsake.notes.map { it.title })
        assertEquals(KeepsakeManifest.Counts(2, 2), keepsake.manifest.counts)
        assertEquals(t(50), keepsake.manifest.dateRange?.start)
        assertEquals(t(900), keepsake.manifest.dateRange?.end)
        assertEquals(2, KeepsakeBuilder.uniqueHighlightCount(highlights))

        // Protected with a passphrase, it opens again to the same keepsake; progress runs to the end.
        val progress = mutableListOf<Float>()
        val data = KeepsakeArchive.encode(keepsake, "Mañana, grace", "hint", iterations = 20_000, progress = { progress += it })
        assertEquals(keepsake, KeepsakeArchive.decode(data, "Mañana, grace"))
        assertEquals(1f, progress.last())
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.size >= 4)
    }

    @Test fun theDefaultKeyDerivationReportsProgress() {
        var calls = 0
        KeepsakeCrypto.deriveKey("x", ByteArray(16), KeepsakeCrypto.DEFAULT_ITERATIONS) { calls++ }
        // One report per 5,000 rounds, and the last at 1.
        assertEquals(KeepsakeCrypto.DEFAULT_ITERATIONS / 5_000, calls)
    }

    @Test fun dateSpanAndSummary() {
        val march = Instant.parse("2019-03-04T12:00:00Z")
        val september = Instant.parse("2026-09-18T12:00:00Z")
        val h = listOf(Highlight(1, "yellow", march))
        val n = listOf(Note(createdAt = september))
        assertEquals("March 2019 to September 2026", KeepsakeBuilder.dateSpan(h, n, Locale.US, ZoneOffset.UTC))
        assertEquals("March 2019", KeepsakeBuilder.dateSpan(h, emptyList(), Locale.US, ZoneOffset.UTC))
        assertNull(KeepsakeBuilder.dateSpan(emptyList(), emptyList()))

        val manifest = KeepsakeArchive.decode(fixture("swift-plain.scripturelegacy")).manifest
        assertEquals("2 highlights · 2 notes · 2023–2025 · read in the ASV", KeepsakeText.summaryDetail(manifest, ZoneOffset.UTC))
    }

    @Test fun readingAKeepsakeShowsItsMarks() {
        val keepsake = KeepsakeArchive.decode(fixture("swift-plain.scripturelegacy"))
        val (colors, markers) = KeepsakeBuilder.marks(keepsake, ChapterRef(43, 3), 36)
        assertEquals(mapOf(43003016 to "yellow"), colors)
        assertEquals(listOf("7A8B9C0D-1E2F-4A5B-8C6D-7E8F90A1B2C3".lowercase()), markers[43003016])
        val (_, romans) = KeepsakeBuilder.marks(keepsake, ChapterRef(45, 8), 39)
        assertEquals(setOf(45008017), romans.keys)
        // Unknown colours read as yellow, as on iOS.
        val odd = keepsake.copy(highlights = keepsake.highlights.map { it.copy(color = "teal") })
        assertEquals(setOf("yellow"), KeepsakeBuilder.highlights(odd).map { it.color }.toSet())
    }
}
