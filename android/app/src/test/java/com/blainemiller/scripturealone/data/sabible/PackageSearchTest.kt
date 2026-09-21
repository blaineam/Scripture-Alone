package com.blainemiller.scripturealone.data.sabible

import com.blainemiller.scripturealone.data.PackageChapterSource
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.search.VerseSearch
import com.blainemiller.scripturealone.data.study.JdbcSqlSource
import com.google.crypto.tink.subtle.Ed25519Sign
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Searching the sealed ASV — `PackageSearchTests.swift`, ported, against the package the app ships:
 * that it finds exactly what the app's own FTS5 index finds in the plaintext it was built from, that
 * it costs what it claims to cost, and that the index is as hard to tamper with as the text.
 *
 * The Swift suite builds a fresh package with a random key; there is no writer on Android, so these
 * run against the shipped `ASV.sabible` (whose index the Python tool wrote) with the published seed —
 * which also proves the three implementations agree on the index format. Header edits are re-signed
 * with a throwaway Ed25519 key so the bounds checks behind the signature can be reached.
 */
class PackageSearchTest {

    @get:Rule
    val temp = TemporaryFolder()

    // MARK: - It finds what the store finds

    /**
     * The claim that matters to a reader: a packaged translation searches like any other. Every query
     * is run against `ASV.sqlite`'s FTS5 index through the app's own [VerseSearch] and against the
     * sealed index, and the two must agree verse for verse, in the same order, with the same text.
     */
    @Test
    fun aPackageFindsExactlyWhatTheStoreFinds() {
        val queries = listOf(
            "good shep", "faith hope love", "\"Jesus wept\"", "\"the good shepherd\"", "shepherd", "begotten",
            "believeth", "lord god almighty", "nebuchadnezzar", "jesus wep", "loving kindness", "\"in the beginning\"",
        )
        for (query in queries) {
            val expected = VerseSearch(store).search(query)
            val found = asv.search(query)
            assertEquals("refs for “$query”", expected.map { it.ref }, found.map { it.ref })
            assertEquals("text for “$query”", expected.map { it.text }, found.map { it.text })
        }
    }

    /**
     * The Swift reader itself, run over the same shipped file (`ScriptureAloneCore`'s
     * `TranslationPackage.search`, from a scratch executable on the Mac): hit counts, first and last
     * verse keys, and what each search opened. Recorded, so a drift in either port fails here.
     */
    @Test
    fun theSwiftReaderReturnsTheSameForTheSameQueries() {
        data class Recorded(val query: String, val count: Int, val buckets: Int, val chapters: Int, val first: Int?, val last: Int?)
        val recorded = listOf(
            Recorded("good shep", 2, 2, 1, 43010011, 43010014),
            Recorded("faith hope love", 3, 3, 3, 46013013, 52005008),
            Recorded("\"Jesus wept\"", 1, 2, 1, 43011035, 43011035),
            Recorded("shepherd", 94, 1, 65, 1046032, 66007017),
            Recorded("begotten", 25, 1, 20, 3018011, 62005018),
            Recorded("nebuchadnezzar", 56, 1, 19, 12024001, 27005018),
            Recorded("jesus wep", 3, 2, 3, 40026075, 43011035),
            Recorded("loving kindness", 0, 2, 0, null, null),
            Recorded("\"in the beginning\"", 15, 3, 14, 1001001, 58001010),
            Recorded("the LORD's", 20, 3, 18, 1044008, 66001010),
            Recorded("Bethsaïda", 7, 1, 7, 40011021, 43012021),
            Recorded("tabernacl", 156, 1, 69, 2025009, 66021003),
            Recorded("unsearchable riches", 2, 2, 2, 45011033, 49003008),
            Recorded("the", 300, 1, 15, 1001001, 1015020),
            Recorded("zzzzq", 0, 1, 0, null, null),
            Recorded("   ", 0, 0, 0, null, null),
            Recorded("in th", 0, 2, 0, null, null),
        )
        for (r in recorded) {
            openShipped().use { pkg ->
                val hits = pkg.search(r.query)
                assertEquals("count for “${r.query}”", r.count, hits.size)
                assertEquals("first for “${r.query}”", r.first, hits.firstOrNull()?.ref?.key)
                assertEquals("last for “${r.query}”", r.last, hits.lastOrNull()?.ref?.key)
                assertEquals("cost of “${r.query}”", TranslationPackage.AccessCounts(r.chapters, r.buckets), pkg.accessCounts)
            }
        }
    }

    @Test
    fun emptyAndUselessQueriesBehaveTheSame() {
        assertTrue(asv.search("   ").isEmpty())
        assertTrue(asv.search("!!!").isEmpty())
        assertTrue(asv.search("zzzzq").isEmpty())
        assertEquals(1, asv.search("good shepherd", limit = 1).size)
        assertTrue(asv.search("good", limit = 0).isEmpty())
    }

    /** A phrase is settled by comparing word positions, not by scanning text. */
    @Test
    fun aPhraseIsAnAdjacencyCheckOnPositions() {
        assertEquals(listOf(VerseRef(43, 11, 35)), asv.search("\"Jesus wept\"").map { it.ref })
        assertTrue(asv.search("\"wept Jesus\"").isEmpty())
        assertTrue(asv.search("jesus wept").size > 1) // the two words, anywhere
    }

    /** A prefix narrows while typing, the way the store's does. */
    @Test
    fun aPrefixNarrowsWhileTyping() {
        for (query in listOf("good she", "good shep", "good shephe", "good shepherd")) {
            assertEquals("while typing “$query”", VerseSearch(store).search(query).map { it.ref }, asv.search(query).map { it.ref })
        }
    }

    // MARK: - What a search costs

    @Test
    fun aSearchOpensOnlyTheBucketsAndChaptersItNeeds() {
        openShipped().use { pkg ->
            assertEquals(TranslationPackage.AccessCounts(0, 0), pkg.accessCounts)
            val hits = pkg.search("shepherd", limit = 20)
            assertTrue(hits.isNotEmpty())
            val counts = pkg.accessCounts
            assertEquals("one token, one bucket — not 256", 1, counts.buckets)
            val chaptersWithHits = hits.map { it.ref.book to it.ref.chapter }.toSet().size
            assertEquals("decrypted only the chapters holding hits", chaptersWithHits, counts.chapters)
            assertTrue(counts.chapters < 25)
        }
    }

    /** "the" is in most verses of the Bible, and still opens one bucket and a page's chapters. */
    @Test
    fun thePlainestPossibleSearchStillDecryptsAlmostNothing() {
        openShipped().use { pkg ->
            assertEquals(300, pkg.search("the", limit = 300).size)
            val counts = pkg.accessCounts
            assertEquals(1, counts.buckets)
            assertTrue("decrypted ${counts.chapters} chapters of 1,189", counts.chapters <= 30)
            assertTrue(counts.buckets * 2 < PackageSearchIndex.DEFAULT_BUCKET_COUNT)
        }
    }

    @Test
    fun aPhraseSearchCostsOneBucketPerWord() {
        openShipped().use { pkg ->
            assertEquals(listOf(VerseRef(43, 11, 35)), pkg.search("\"Jesus wept\"").map { it.ref })
            assertTrue(pkg.accessCounts.buckets <= 2)
            assertEquals("one hit, one chapter", 1, pkg.accessCounts.chapters)
        }
    }

    @Test
    fun aSearchWithNoMatchesDecryptsNoChapters() {
        openShipped().use { pkg ->
            assertTrue(pkg.search("nebuchadnezzar zzzzq").isEmpty())
            assertEquals(0, pkg.accessCounts.chapters)
            assertTrue(pkg.accessCounts.buckets <= 2)
        }
    }

    // MARK: - The index is sealed like the text

    /** A flipped byte anywhere in a bucket fails its GCM tag; the text and every other bucket still work. */
    @Test
    fun aFlippedBucketByteFailsItsSeal() {
        val bytes = packageFile.readBytes()
        val layout = Layout.of(bytes)
        val wanted = bucketFor("god")
        val entry = openShipped().use { it.header.index!!.entries.single { e -> e.bucket == wanted } }
        val at = layout.bodyOffset + entry.offset.toInt() + SabibleFormat.NONCE_BYTES + 40
        val tampered = write(bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }, "bucket-flip")
        TranslationPackage.open(tampered, keyring(), contentKey()).use { pkg ->
            assertRefused<TranslationPackageException.BucketTampered>("flipped bucket") { pkg.search("god") }
            // The text is untouched: John 3 still decrypts.
            assertTrue(pkg.chapter(43, 3).verses.isNotEmpty())
            val elsewhere = listOf("beginning", "shepherd", "jerusalem", "covenant").first { bucketFor(it) != wanted }
            assertTrue(pkg.search(elsewhere).isNotEmpty())
        }
    }

    /**
     * One bucket's sealed blob moved into another bucket's slot — same length, same key, same package.
     * It doesn't open, because the bucket number is bound into its associated data: a bucket can't be
     * relabelled, as a chapter can't.
     */
    @Test
    fun aBucketMovedToAnotherSlotFailsItsSeal() {
        val bytes = packageFile.readBytes()
        val layout = Layout.of(bytes)
        val entries = openShipped().use { it.header.index!!.entries }
        val target = bucketFor("god")
        val victim = entries.single { it.bucket == target }
        val donor = entries.first { it.bucket != target && it.length == victim.length }
        val moved = bytes.copyOf()
        System.arraycopy(bytes, layout.bodyOffset + donor.offset.toInt(), moved, layout.bodyOffset + victim.offset.toInt(), victim.length)
        TranslationPackage.open(write(moved, "bucket-moved"), keyring(), contentKey()).use { pkg ->
            assertRefused<TranslationPackageException.BucketTampered>("relabelled bucket") { pkg.search("god") }
        }
    }

    /** The index's parameters are inside the signed header: edit one and the package stops opening. */
    @Test
    fun editingTheIndexParametersFailsTheSignature() {
        val bytes = packageFile.readBytes()
        val headerEnd = Layout.of(bytes).headerEnd
        val at = indexOf(bytes, "\"prefixMax\":10", headerEnd) + "\"prefixMax\":1".length
        val tampered = bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() } // 10 → 11
        assertRefused<TranslationPackageException.SignatureInvalid>("prefixMax") {
            TranslationPackage.open(write(tampered, "prefix-max"), keyring(), contentKey())
        }
    }

    // MARK: - The header's index entries, bounds-checked

    /**
     * Past the signature, every index entry is still held to the file: a signed header only says who
     * wrote a number, not that it is safe to seek to. Each edit is re-signed with a throwaway key, so it
     * is the bounds check — not the signature — that refuses it.
     */
    @Test
    fun indexEntriesOutsideTheFileOrOutOfShapeAreRefused() {
        fun editEntry(edit: (MutableMap<String, JsonElement>) -> Unit): (JsonObject) -> JsonObject = { header ->
            header.editIndex { index ->
                val entries = (index["entries"] as JsonArray).toMutableList()
                entries[7] = JsonObject(entries[7].jsonObject.toMutableMap().also(edit))
                index["entries"] = JsonArray(entries)
            }
        }
        val size = packageFile.length()
        val cases = listOf(
            "offset past the end" to editEntry { it["offset"] = JsonPrimitive(size) },
            "negative offset" to editEntry { it["offset"] = JsonPrimitive(-1) },
            "length running off the end" to editEntry { it["length"] = JsonPrimitive(PackageSearchIndex.MAXIMUM_BUCKET_BYTES) },
            "length over a bucket's maximum" to editEntry { it["length"] = JsonPrimitive(PackageSearchIndex.MAXIMUM_BUCKET_BYTES + 1) },
            "length no bigger than a seal" to editEntry { it["length"] = JsonPrimitive(SabibleFormat.SEALED_CHAPTER_OVERHEAD) },
            "bucket number past the count" to editEntry { it["bucket"] = JsonPrimitive(256) },
            "negative bucket number" to editEntry { it["bucket"] = JsonPrimitive(-1) },
        )
        for ((label, edit) in cases) {
            val signed = resigned(edit, label)
            assertRefused<TranslationPackageException.Truncated>(label) { TranslationPackage.open(signed.file, signed.keyring, contentKey()) }
        }
        // Control: the same re-signing with no edit opens, so the edit is what was refused.
        val control = resigned({ it }, "control")
        TranslationPackage.open(control.file, control.keyring, contentKey()).close()
    }

    @Test
    fun indexParametersThatCannotDescribeAnIndexAreRefused() {
        val cases = listOf<Pair<String, (MutableMap<String, JsonElement>) -> Unit>>(
            "no buckets" to { it["buckets"] = JsonPrimitive(0) },
            "too many buckets" to { it["buckets"] = JsonPrimitive(PackageSearchIndex.MAXIMUM_BUCKET_COUNT + 1) },
            "prefixMin 0" to { it["prefixMin"] = JsonPrimitive(0) },
            "prefixMax below prefixMin" to { it["prefixMax"] = JsonPrimitive(2) },
            "negative padding" to { it["padding"] = JsonPrimitive(-1) },
        )
        for ((label, edit) in cases) {
            val signed = resigned({ header -> header.editIndex(edit) }, label)
            assertRefused<TranslationPackageException.DamagedHeader>(label) { TranslationPackage.open(signed.file, signed.keyring, contentKey()) }
        }
        // A missing or mistyped field is a damaged header too, as Swift's Codable makes it.
        val missing = resigned({ header -> header.editIndex { it.remove("tokenizer") } }, "no tokenizer")
        assertRefused<TranslationPackageException.DamagedHeader>("no tokenizer") { TranslationPackage.open(missing.file, missing.keyring, contentKey()) }
        val mistyped = resigned({ header -> header.editIndex { it["buckets"] = JsonPrimitive("256") } }, "string buckets")
        assertRefused<TranslationPackageException.DamagedHeader>("string buckets") { TranslationPackage.open(mistyped.file, mistyped.keyring, contentKey()) }
    }

    /**
     * An index built by a tokeniser this build doesn't implement is not searched — silently
     * mis-searching would be worse than saying so — and a package without an index says so rather than
     * answering "no matches". Both still open: searching is a capability, not a precondition.
     */
    @Test
    fun aPackageWithoutAReadableIndexRefusesToSearch() {
        val unknown = resigned({ header -> header.editIndex { it["tokenizer"] = JsonPrimitive("sabible-tokens-v0") } }, "tokens-v0")
        TranslationPackage.open(unknown.file, unknown.keyring, contentKey()).use { pkg ->
            assertFalse(pkg.isSearchable)
            assertFalse(PackageChapterSource(pkg).isSearchable)
            assertRefused<TranslationPackageException.NotSearchable>("unknown tokeniser") { pkg.search("god") }
        }
        val none = resigned({ header -> JsonObject(header.toMutableMap().also { it.remove("index") }) }, "no-index")
        TranslationPackage.open(none.file, none.keyring, contentKey()).use { pkg ->
            assertNull(pkg.header.index)
            assertFalse(pkg.isSearchable)
            assertRefused<TranslationPackageException.NotSearchable>("no index") { pkg.search("god") }
        }
    }

    // MARK: - The seam

    /** The reader searches through [com.blainemiller.scripturealone.data.ChapterSource], whatever it holds. */
    @Test
    fun theReadersSourceSearchesThePackage() {
        val source = PackageChapterSource(asv)
        assertTrue(source.isSearchable)
        assertEquals(VerseRef(43, 10, 11), source.search("good shepherd", 5).first().ref)
        assertEquals(listOf(VerseRef(43, 11, 35)), source.search("\"Jesus wept\"").map { it.ref })
    }

    @Test
    fun theShippedHeaderCarriesTheIndexTheAppExpects() {
        val index = assertNotNullAnd(asv.header.index)
        assertEquals(PackageSearchIndex.TOKENIZER, index.tokenizer)
        assertEquals(PackageSearchIndex.ASSOCIATED_DATA_VERSION, index.aad)
        assertEquals(PackageSearchIndex.DEFAULT_BUCKET_COUNT, index.buckets)
        assertEquals(PackageSearchIndex.DEFAULT_PREFIX_MIN, index.prefixMin)
        assertEquals(PackageSearchIndex.DEFAULT_PREFIX_MAX, index.prefixMax)
        assertEquals(256, index.entries.map { it.bucket }.toSet().size)
        assertTrue(asv.isSearchable)
    }

    @Test
    fun indexAssociatedDataIsTheSwiftLayout() {
        val digest = ByteArray(32) { it.toByte() }
        val aad = TranslationPackage.indexAssociatedData("PKG", "ASV", 17, digest)
        val expected = "sabible-index-v1\nPKG\nASV\n17\n" +
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        assertEquals(expected, String(aad, Charsets.UTF_8))
    }

    // MARK: - The tokeniser and the query, stated

    @Test
    fun theTokeniserIsTheOneTheStoreUses() {
        assertEquals(listOf("in", "the", "beginning", "god", "created"), PackageSearchIndex.tokens("In the beginning God created"))
        // An apostrophe separates, as it does in SQLite's unicode61.
        assertEquals(listOf("the", "lord", "s", "anointed"), PackageSearchIndex.tokens("the LORD’s anointed"))
        // Diacritics fold, matching remove_diacritics 2.
        assertEquals(listOf("bethsaida"), PackageSearchIndex.tokens("Bethsaïda"))
        assertEquals(listOf("1", "corinthians", "13", "4", "love"), PackageSearchIndex.tokens("1 Corinthians 13:4—love"))
        assertTrue(PackageSearchIndex.tokens("   ").isEmpty())
    }

    @Test
    fun aQueryIsReadTheSameWayTheStoreReadsIt() {
        val words = assertNotNullAnd(PackagedSearchQuery.parse("faith hope lov", prefixMin = 3))
        assertEquals(listOf(listOf("faith"), listOf("hope"), listOf("lov")), words.groups.map { it.tokens })
        assertEquals(listOf(false, false, true), words.groups.map { it.prefix })

        val phrase = assertNotNullAnd(PackagedSearchQuery.parse("\"Jesus wept\"", prefixMin = 3))
        assertEquals(1, phrase.groups.size)
        assertEquals(listOf("jesus", "wept"), phrase.groups[0].tokens)
        assertFalse(phrase.groups[0].prefix)

        // A word with an apostrophe becomes a phrase of its parts, which is what FTS5 does with it.
        val apostrophe = assertNotNullAnd(PackagedSearchQuery.parse("the LORD's", prefixMin = 3))
        assertEquals(listOf(listOf("the"), listOf("lord", "s")), apostrophe.groups.map { it.tokens })

        // Below the shortest indexed prefix, the last word is matched whole.
        val short = assertNotNullAnd(PackagedSearchQuery.parse("in th", prefixMin = 3))
        assertEquals(false, short.groups.last().prefix)
        assertNull(PackagedSearchQuery.parse("   ", prefixMin = 3))
    }

    /** A bucket's bytes are read with every step bounds-checked, so a short one refuses rather than overruns. */
    @Test
    fun aTruncatedBucketIsDamagedNotAnOverrun() {
        // Claims a 5-byte payload, holds 1.
        assertRefused<TranslationPackageException.DamagedIndex>("payload length") {
            PackageSearchIndex.decodeBucket(byteArrayOf(5, 1), setOf(1L))
        }
        // One entry, whose 8-byte id stops after 3.
        assertRefused<TranslationPackageException.DamagedIndex>("entry id") {
            PackageSearchIndex.decodeBucket(byteArrayOf(4, 1, 0, 0, 0), setOf(1L))
        }
        // A varint that never ends.
        assertRefused<TranslationPackageException.DamagedIndex>("varint") {
            PackageSearchIndex.decodeBucket(ByteArray(12) { 0xff.toByte() }, setOf(1L))
        }
    }

    // MARK: - Helpers

    private class Layout(val headerEnd: Int, val bodyOffset: Int) {
        companion object {
            fun of(bytes: ByteArray): Layout {
                val headerEnd = 14 + ByteBuffer.wrap(bytes, 10, 4).int
                return Layout(headerEnd, headerEnd + 2 + SabibleFormat.SIGNATURE_BYTES)
            }
        }
    }

    /** The bucket a one-word query reads: the *prefix* tag, since the last word prefix-matches. */
    private fun bucketFor(word: String): Int {
        val key = PackageSearchIndex.indexKey(contentKey(), "ASV")
        return PackageSearchIndex.bucket(PackageSearchIndex.tag(key, PackageSearchIndex.TagKind.PREFIX, word), 256)
    }

    private class Signed(val file: File, val keyring: PublisherKeyring)

    /**
     * The shipped package with its header edited and signed again by a throwaway key, whose id replaces
     * the publisher's. The body is untouched, so offsets still point where they did.
     */
    private fun resigned(edit: (JsonObject) -> JsonObject, label: String): Signed {
        val bytes = packageFile.readBytes()
        val layout = Layout.of(bytes)
        val keys = Ed25519Sign.KeyPair.newKeyPair()
        val original = Json.parseToJsonElement(String(bytes, 14, layout.headerEnd - 14, Charsets.UTF_8)).jsonObject
        val rekeyed = original.toMutableMap().also { header ->
            header["crypto"] = JsonObject(header.getValue("crypto").jsonObject.toMutableMap().also {
                it["publisherKeyID"] = JsonPrimitive(SabibleKeys.publisherKeyId(keys.publicKey))
            })
        }
        val header = edit(JsonObject(rekeyed)).toString().toByteArray(Charsets.UTF_8)
        val signature = Ed25519Sign(keys.privateKey).sign(header)
        val out = ByteArrayOutputStream()
        out.write(bytes, 0, 10) // magic and version
        out.write(ByteBuffer.allocate(4).putInt(header.size).array())
        out.write(header)
        out.write(byteArrayOf(0, SabibleFormat.SIGNATURE_BYTES.toByte()))
        out.write(signature)
        out.write(bytes, layout.bodyOffset, bytes.size - layout.bodyOffset)
        return Signed(write(out.toByteArray(), label), PublisherKeyring(listOf(keys.publicKey)))
    }

    private fun JsonObject.editIndex(edit: (MutableMap<String, JsonElement>) -> Unit): JsonObject =
        JsonObject(toMutableMap().also { header -> header["index"] = JsonObject(header.getValue("index").jsonObject.toMutableMap().also(edit)) })

    private fun write(bytes: ByteArray, label: String): File =
        temp.newFile("${label.replace(Regex("[^A-Za-z0-9-]"), "-")}.sabible").apply { writeBytes(bytes) }

    private fun indexOf(haystack: ByteArray, needle: String, before: Int): Int {
        val target = needle.toByteArray()
        outer@ for (i in 0..(before - target.size)) {
            for (j in target.indices) if (haystack[i + j] != target[j]) continue@outer
            return i
        }
        error("\"$needle\" is not in the header")
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
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

    companion object {
        private val resources = File(
            System.getProperty("scripturealone.resources") ?: error("scripturealone.resources is not set — run through Gradle"),
        )
        private val packageFile = File(resources, "Packages/ASV.sabible").also { require(it.isFile) { "missing $it" } }
        private val publisherKey = File(resources, "Packages/bundled-signing.pub")

        private fun keyring() = PublisherKeyring(listOf(publisherKey.readBytes()))
        private fun contentKey() = ContentKey.derive(ContentKey.BUNDLED_SEED, "ASV")
        private fun openShipped() = TranslationPackage.open(packageFile, keyring(), contentKey())

        /** One shared reader for the parity tests; the cost tests open their own, so counts start at zero. */
        private val asv: TranslationPackage by lazy { openShipped() }

        /** `ASV.sqlite` — the plaintext the package was built from, with the FTS5 index iOS's store uses. */
        private val store: JdbcSqlSource by lazy { JdbcSqlSource(File(resources, "Bibles/ASV.sqlite")) }

        @JvmStatic
        @AfterClass
        fun close() {
            runCatching { asv.close() }
            runCatching { store.close() }
        }
    }
}
