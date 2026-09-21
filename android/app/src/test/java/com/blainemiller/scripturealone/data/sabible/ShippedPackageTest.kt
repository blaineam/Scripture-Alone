package com.blainemiller.scripturealone.data.sabible

import com.blainemiller.scripturealone.data.VerseRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.SQLiteConfig
import java.io.File
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager

/**
 * The package the iOS app ships, opened the way the Android app will open it, and checked against
 * the plaintext it was built from.
 *
 * `ASV.sabible` is the default translation on both platforms and there is no `ASV.sqlite` in either
 * app to fall back on — so if this reader and the Swift / Python writers disagree about one byte of
 * the format, a fresh Android install has nothing to read. The full-Bible comparison below is the
 * proof that they agree: every chapter decrypted, every verse compared exactly against
 * `ScriptureAlone/Resources/Bibles/ASV.sqlite`, which is kept in the repository as the packaging
 * tool's input.
 *
 * The files are read from the iOS resources in place (the path comes from the build script), and
 * a missing file **fails** rather than skips: a proof that silently didn't run is not a proof.
 */
class ShippedPackageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val resources = File(
        System.getProperty("scripturealone.resources")
            ?: error("scripturealone.resources is not set — run through Gradle"),
    )
    private val packageFile = File(resources, "Packages/ASV.sabible")
    private val publisherKeyFile = File(resources, "Packages/bundled-signing.pub")
    private val sqliteFile = File(resources, "Bibles/ASV.sqlite")

    private fun keyring() = PublisherKeyring(listOf(publisherKeyFile.readBytes()))
    private fun contentKey() = ContentKey.derive(ContentKey.BUNDLED_SEED, "ASV")
    private fun openShipped(file: File = packageFile) = TranslationPackage.open(file, keyring(), contentKey())

    private fun requireFiles() {
        for (file in listOf(packageFile, publisherKeyFile, sqliteFile)) {
            assertTrue("missing ${file.path}", file.isFile)
        }
    }

    // MARK: - The proof

    @Test
    fun everyChapterDecryptsToExactlyTheSqliteText() {
        requireFiles()
        openShipped().use { pkg ->
            sqlite().use { db ->
                val sqliteChapters = linkedMapOf<ChapterRef, Pair<Int, String>>()
                db.createStatement().use { st ->
                    st.executeQuery("SELECT book, chapter, verses, layout FROM chapters ORDER BY book, chapter").use { rs ->
                        while (rs.next()) {
                            sqliteChapters[ChapterRef(rs.getInt(1), rs.getInt(2))] = rs.getInt(3) to rs.getString(4)
                        }
                    }
                }
                val sqliteVerseTotal = db.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM verses").use { rs -> rs.next(); rs.getInt(1) }
                }

                // The same chapters, no more and no fewer.
                assertEquals("the canon has 1,189 chapters", 1189, sqliteChapters.size)
                assertEquals(sqliteChapters.keys.toList(), pkg.chapters)

                val mismatches = mutableListOf<String>()
                var chaptersCompared = 0
                var versesCompared = 0
                var redVersesCompared = 0
                var layoutsCompared = 0

                val query = db.prepareStatement("SELECT id, text, red FROM verses WHERE id BETWEEN ? AND ? ORDER BY id")
                for ((ref, stored) in sqliteChapters) {
                    val (verseCount, layout) = stored
                    val chapter = pkg.chapter(ref.book, ref.chapter)
                    chaptersCompared++

                    if (pkg.verseCount(ref) != verseCount) {
                        mismatches += "$ref verse count ${pkg.verseCount(ref)} != $verseCount"
                    }
                    if (chapter.layoutJson != layout) mismatches += "$ref layout differs"
                    layoutsCompared++

                    val range = VerseRef.chapterRange(ref.book, ref.chapter)
                    query.setInt(1, range.first)
                    query.setInt(2, range.last)
                    val expected = mutableListOf<Triple<Int, String, List<ScalarRange>>>()
                    query.executeQuery().use { rs ->
                        while (rs.next()) expected += Triple(rs.getInt(1), rs.getString(2), parseRed(rs.getString(3)))
                    }

                    if (chapter.verses.size != expected.size) {
                        mismatches += "$ref has ${chapter.verses.size} verses, sqlite has ${expected.size}"
                    }
                    for ((actual, want) in chapter.verses.zip(expected)) {
                        val (key, text, red) = want
                        versesCompared++
                        if (actual.ref.key != key) mismatches += "$ref: verse key ${actual.ref.key} != $key"
                        if (actual.text != text) mismatches += "${actual.ref}: text differs\n  got  ${actual.text}\n  want $text"
                        if (red.isNotEmpty()) redVersesCompared++
                        if (actual.red != red) mismatches += "${actual.ref}: red ${actual.red} != $red"
                    }
                }
                query.close()

                println(
                    "sabible proof: chapters=$chaptersCompared layouts=$layoutsCompared verses=$versesCompared " +
                        "(sqlite verses table=$sqliteVerseTotal) wordsOfChristVerses=$redVersesCompared " +
                        "mismatches=${mismatches.size}",
                )
                if (mismatches.isNotEmpty()) fail(mismatches.take(25).joinToString("\n"))
                assertEquals(1189, chaptersCompared)
                assertEquals(sqliteVerseTotal, versesCompared)
            }
        }
    }

    @Test
    fun opensWithTheHeaderTheAppExpects() {
        requireFiles()
        openShipped().use { pkg ->
            assertEquals("ASV", pkg.translation.id)
            assertEquals(SabibleFormat.ASSOCIATED_DATA_VERSION, pkg.header.crypto.aad)
            assertTrue(pkg.policy.allowCopy && pkg.policy.allowShare && pkg.policy.allowExternalHandoff)
            assertEquals(Long.MAX_VALUE, pkg.policy.maxQuotationVerses)
            val john316 = pkg.chapter(43, 3).verses.single { it.ref.verse == 16 }
            assertTrue(john316.text, john316.text.startsWith("For God so loved the world"))
        }
    }

    // MARK: - Refusals

    @Test
    fun aFlippedHeaderByteFailsTheSignature() {
        requireFiles()
        val bytes = packageFile.readBytes()
        val headerEnd = 14 + ByteBuffer.wrap(bytes, 10, 4).int

        // Bytes chosen so the JSON still parses — a flip that broke the JSON would be refused as a
        // damaged header before the signature is checked, and would not prove the signature works.
        val nameByte = indexOf(bytes, "\"name\":\"American Standard Version\"", headerEnd) + "\"name\":\"".length
        val offsetDigit = indexOf(bytes, "\"offset\":10318,", headerEnd) + "\"offset\":1031".length
        val signatureByte = headerEnd + 2 + 10

        for ((label, at) in listOf("translation name" to nameByte, "chapter offset" to offsetDigit)) {
            val tampered = bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }
            assertRefused<TranslationPackageException.SignatureInvalid>(label) { openShipped(write(tampered, label)) }
        }
        val badSignature = bytes.copyOf().also { it[signatureByte] = (it[signatureByte].toInt() xor 0x01).toByte() }
        assertRefused<TranslationPackageException.SignatureInvalid>("signature") { openShipped(write(badSignature, "sig")) }

        // The untouched file still opens, so it is the flip that was refused.
        openShipped().close()
    }

    @Test
    fun anUnpinnedPublisherKeyIsRefused() {
        requireFiles()
        val stranger = publisherKeyFile.readBytes().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertRefused<TranslationPackageException.UnknownPublisherKey>("stranger key") {
            TranslationPackage.open(packageFile, PublisherKeyring(listOf(stranger)), contentKey())
        }
    }

    @Test
    fun aWrongContentKeyIsRefusedAtTheDoorAndByGcm() {
        requireFiles()
        val wrong = ContentKey.derive("not-the-seed".toByteArray(), "ASV")
        assertRefused<TranslationPackageException.WrongContentKey>("key ID check") {
            TranslationPackage.open(packageFile, keyring(), wrong)
        }
        // With the courtesy check off, AES-GCM itself refuses the chapter.
        TranslationPackage.open(packageFile, keyring(), wrong, java.time.Instant.now(), checkKeyId = false).use { pkg ->
            assertRefused<TranslationPackageException.ChapterTampered>("GCM with wrong key") { pkg.chapter(1, 1) }
        }
    }

    @Test
    fun aFlippedChapterByteFailsTheGcmTag() {
        requireFiles()
        val bytes = packageFile.readBytes()
        val bodyOffset = 14 + ByteBuffer.wrap(bytes, 10, 4).int + 2 + SabibleFormat.SIGNATURE_BYTES
        val genesis1 = openShipped().use { pkg -> pkg.header.chapters.single { it.book == 1 && it.chapter == 1 } }
        val start = bodyOffset + genesis1.offset.toInt()

        val targets = listOf(
            "nonce" to start + 3,
            "ciphertext" to start + SabibleFormat.NONCE_BYTES + 100,
            "tag" to start + genesis1.length - 1,
        )
        for ((label, at) in targets) {
            val tampered = bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }
            // The header is untouched, so the package opens; only the edited chapter refuses.
            openShipped(write(tampered, label)).use { pkg ->
                assertRefused<TranslationPackageException.ChapterTampered>(label) { pkg.chapter(1, 1) }
                assertFalse(pkg.chapter(1, 2).verses.isEmpty())
            }
        }
    }

    @Test
    fun aChapterOpenedUnderAnotherChaptersBindingFails() {
        requireFiles()
        openShipped().use { pkg ->
            val genesis1 = ChapterRef(1, 1)
            // Control: the real binding opens.
            assertTrue(pkg.openChapter(genesis1, boundAs = genesis1).isNotEmpty())
            // Genesis 1's blob, presented as Genesis 2, as a neighbouring book, and as a far chapter.
            for (other in listOf(ChapterRef(1, 2), ChapterRef(2, 1), ChapterRef(66, 22))) {
                assertRefused<TranslationPackageException.ChapterTampered>("bound as $other") {
                    pkg.openChapter(genesis1, boundAs = other)
                }
            }
        }
    }

    @Test
    fun associatedDataIsTheSwiftLayout() {
        val digest = ByteArray(32) { it.toByte() }
        val aad = TranslationPackage.associatedData("PKG", "ASV", ChapterRef(43, 3), digest)
        val expected = "sabible-chapter-v1\nPKG\nASV\n43003\n" +
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        assertArrayEquals(expected.toByteArray(), aad)
    }

    // MARK: - Helpers

    private fun sqlite(): Connection {
        // Read-only, so the test can never touch the iOS app's resource file.
        val config = SQLiteConfig().apply { setReadOnly(true) }
        return DriverManager.getConnection("jdbc:sqlite:${sqliteFile.absolutePath}", config.toProperties())
    }

    /** `verses.red`: JSON `[[start, length]]` in scalars, or NULL / empty for none. */
    private fun parseRed(json: String?): List<ScalarRange> {
        if (json.isNullOrBlank()) return emptyList()
        return Json.parseToJsonElement(json).jsonArray.map { pair ->
            val values = pair.jsonArray.map { it.jsonPrimitive.int }
            ScalarRange(values[0], values[1])
        }
    }

    private fun write(bytes: ByteArray, label: String): File =
        temp.newFile("tampered-${label.replace(' ', '-')}.sabible").apply { writeBytes(bytes) }

    private fun indexOf(haystack: ByteArray, needle: String, before: Int): Int {
        val target = needle.toByteArray()
        outer@ for (i in 0..(before - target.size)) {
            for (j in target.indices) if (haystack[i + j] != target[j]) continue@outer
            return i
        }
        error("\"$needle\" is not in the header")
    }

    private inline fun <reified E : TranslationPackageException> assertRefused(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: TranslationPackageException) {
            if (e !is E) fail("$label: expected ${E::class.simpleName}, got ${e::class.simpleName}: ${e.message}")
            return
        }
        fail("$label: expected ${E::class.simpleName}, but it was accepted")
    }
}
