package com.blainemiller.scripturealone.data.sabible

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.search.SearchHit
import com.blainemiller.scripturealone.data.search.VerseSearch
import com.blainemiller.scripturealone.data.sabible.PackageHeaderParser.array
import com.blainemiller.scripturealone.data.sabible.PackageHeaderParser.field
import com.blainemiller.scripturealone.data.sabible.PackageHeaderParser.obj
import com.google.crypto.tink.subtle.Ed25519Verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Words of Christ in one verse: a start and a length counted in **Unicode scalars**, not UTF-16. */
data class ScalarRange(val start: Int, val length: Int)

/**
 * One verse, decrypted. [red] keeps the scalar offsets exactly as the package (and the SQLite
 * `verses.red` column) stores them; converting to UTF-16 is the renderer's job, at the point it
 * builds an `AnnotatedString`, so this layer never holds a second, platform-specific form.
 */
data class PackagedVerse(val ref: VerseRef, val text: String, val red: List<ScalarRange>)

/**
 * One chapter, decrypted: exactly what the reader draws. [layoutJson] is the compact layout JSON
 * byte for byte as the store keeps it in `chapters.layout`, for the layout port to decode.
 */
data class PackagedChapter(val ref: ChapterRef, val layoutJson: String, val verses: List<PackagedVerse>)

/**
 * Reads a signed, encrypted `.sabible` translation package — the read path of
 * `ScriptureAloneCore/Package/TranslationPackage.swift`, ported so the same file opens on Android.
 *
 * **Per chapter, never whole-file.** The only functions that decrypt are [openChapter], which takes
 * one chapter's index entry, and its sibling for one search-index bucket (see [search]); the file is
 * read one byte range at a time. There is no API that
 * returns more than one chapter's plaintext and no cache of decrypted text, so "a whole Bible in the
 * clear never exists" stays a property of this class's shape, as it is on iOS.
 *
 * Opening verifies, in the Swift reader's order: the format, the named primitives, that the signing
 * key is pinned, the Ed25519 signature over the exact header bytes, the expiry, and that the content
 * key is the one the header names. None of that touches the text.
 *
 * **Why Tink for Ed25519.** API 29 has no platform Ed25519 (`Signature.getInstance("Ed25519")`
 * arrives in API 33), so a library is required. Tink's `subtle.Ed25519Verify` is pure Java, so the
 * same code verifies on a device and in a JVM unit test; the library is maintained by Google's
 * security team, is a fraction of BouncyCastle's size, and is the choice the parity ledger already
 * made for Ed25519 and HKDF. BouncyCastle would add several megabytes and a provider that collides
 * with Android's own stripped `BC` provider. AES-GCM stays on JCA, which every Android version has.
 *
 * Pure Kotlin and JDK: no Android framework types, so it is unit-tested on the JVM against the
 * package the app ships. Reads use positional [FileChannel] reads, which are safe to issue from
 * several threads at once.
 */
class TranslationPackage private constructor(
    private val channel: FileChannel,
    val header: TranslationPackageHeader,
    /**
     * SHA-256 of the exact header bytes in the file. Bound into every chapter's associated data, so a
     * chapter sealed under one header cannot be replayed under another.
     */
    val headerDigest: ByteArray,
    private val bodyOffset: Long,
    private val contentKey: SecretKeySpec,
    private val index: Map<ChapterRef, PackagedChapterEntry>,
    private val buckets: Map<Int, PackagedBucketEntry>,
) : Closeable {

    /**
     * What has been decrypted through this reader: chapters and index buckets. So the tests can assert
     * the cost of a search rather than assume it.
     */
    data class AccessCounts(val chapters: Int, val buckets: Int)

    private val chaptersOpened = AtomicInteger()
    private val bucketsOpened = AtomicInteger()

    val accessCounts: AccessCounts get() = AccessCounts(chaptersOpened.get(), bucketsOpened.get())

    val translation: PackagedTranslationIdentity get() = header.translation
    val policy: PackagePolicy get() = header.policy

    /** Every chapter in the package, in canonical order. */
    val chapters: List<ChapterRef> get() = index.keys.sorted()

    fun contains(chapter: ChapterRef): Boolean = chapter in index

    fun verseCount(chapter: ChapterRef): Int = index[chapter]?.verses ?: 0

    /** One chapter, decrypted in memory. The only way to get text out of a package. */
    fun chapter(book: Int, chapter: Int): PackagedChapter = chapter(ChapterRef(book, chapter))

    fun chapter(ref: ChapterRef): PackagedChapter =
        decodeChapter(openChapter(ref, boundAs = ref), ref)

    /**
     * Reads [stored]'s sealed blob and opens it with the associated data of [boundAs]. The app only
     * ever passes the same chapter twice; the test suite passes a different one to prove the AAD
     * binding is what refuses a blob relabelled as another chapter.
     */
    internal fun openChapter(stored: ChapterRef, boundAs: ChapterRef): ByteArray {
        val entry = index[stored] ?: throw TranslationPackageException.ChapterMissing(stored)
        val sealed = readBody(entry.offset, entry.length)
        val associated = associatedData(header.packageId, header.translation.id, boundAs, headerDigest)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                contentKey,
                GCMParameterSpec(SabibleFormat.TAG_BYTES * 8, sealed, 0, SabibleFormat.NONCE_BYTES),
            )
            cipher.updateAAD(associated)
            cipher.doFinal(sealed, SabibleFormat.NONCE_BYTES, sealed.size - SabibleFormat.NONCE_BYTES)
                .also { chaptersOpened.incrementAndGet() }
        } catch (e: GeneralSecurityException) {
            // GCM cannot distinguish a wrong key from tampering. Either way this chapter is not shown,
            // and the caller is told which chapter refused.
            throw TranslationPackageException.ChapterTampered(boundAs, e)
        }
    }

    // Search — `TranslationPackage.search` in Swift.

    /** Whether this package carries an index this build can read. */
    val isSearchable: Boolean
        get() {
            val parameters = header.index ?: return false
            return parameters.tokenizer == PackageSearchIndex.TOKENIZER &&
                parameters.aad == PackageSearchIndex.ASSOCIATED_DATA_VERSION &&
                buckets.isNotEmpty()
        }

    /**
     * Full-text search, answering the same call a plain store answers: every word must appear, the
     * last word matches as a prefix so results narrow while typing, and a query in double quotes is an
     * exact phrase. Results are in canonical order.
     *
     * What a search costs, and why: the query's tokens are hashed with a key derived from the content
     * key, which names the buckets holding their postings. Only those buckets are opened — one or two,
     * tens of kilobytes. The postings are intersected, a phrase is settled by comparing word positions
     * rather than by scanning text, and only then are chapters decrypted, one at a time, for the verses
     * that actually matched, because a hit has to carry its text. A search never opens the whole index
     * and never decrypts the whole Bible; [accessCounts] says exactly how much it did open.
     */
    fun search(query: String, limit: Int = VerseSearch.DEFAULT_LIMIT): List<SearchHit> {
        val parameters = header.index
        if (parameters == null || buckets.isEmpty()) {
            throw TranslationPackageException.NotSearchable("this package was built without a search index")
        }
        if (parameters.tokenizer != PackageSearchIndex.TOKENIZER || parameters.aad != PackageSearchIndex.ASSOCIATED_DATA_VERSION) {
            throw TranslationPackageException.NotSearchable("its index was built by ${parameters.tokenizer}, which this app doesn’t implement")
        }
        if (limit <= 0) return emptyList()
        val parsed = PackagedSearchQuery.parse(query, parameters.prefixMin) ?: return emptyList()

        val key = PackageSearchIndex.indexKey(contentKey.encoded, header.translation.id)

        // 1. Name the postings this query needs, and the buckets they live in.
        val groups = mutableListOf<List<Lookup>>()
        val longPrefixes = mutableListOf<String>()
        val wanted = HashMap<Int, MutableSet<Long>>()
        for (group in parsed.groups) {
            val lookups = mutableListOf<Lookup>()
            group.tokens.forEachIndexed { offset, token ->
                val isPrefix = group.prefix && offset == group.tokens.lastIndex
                val tag = if (isPrefix) {
                    val scalars = token.codePointCount(0, token.length)
                    val cut = minOf(parameters.prefixMax, scalars)
                    if (cut < scalars) longPrefixes += token
                    PackageSearchIndex.tag(key, PackageSearchIndex.TagKind.PREFIX, token.substring(0, token.offsetByCodePoints(0, cut)))
                } else {
                    PackageSearchIndex.tag(key, PackageSearchIndex.TagKind.WORD, token)
                }
                val id = PackageSearchIndex.tokenId(tag)
                wanted.getOrPut(PackageSearchIndex.bucket(tag, parameters.buckets)) { HashSet() } += id
                lookups += Lookup(id, isPrefix)
            }
            groups += lookups
        }

        // 2. Open only those buckets.
        val entries = HashMap<Long, PackageSearchIndex.Entry>()
        for ((bucket, ids) in wanted) {
            val entry = buckets[bucket] ?: continue
            entries.putAll(PackageSearchIndex.decodeBucket(openBucket(entry), ids))
        }

        // 3. Intersect: every group must be satisfied in the same verse.
        var candidates: Set<Int>? = null
        for (lookups in groups) {
            val verses = versesSatisfying(lookups, entries)
            candidates = candidates?.intersect(verses) ?: verses
            if (candidates.isEmpty()) return emptyList()
        }
        if (candidates.isNullOrEmpty()) return emptyList()

        // 4. Only now decrypt chapters — those that hold the matches, in canonical order, stopping at
        //    the limit. A prefix longer than the longest indexed one is a superset, so those hits are
        //    verified against the verse's own tokens; every other query needs no verification at all.
        val hits = mutableListOf<SearchHit>()
        var opened = 0
        var text: Map<Int, String> = emptyMap()
        var loaded: ChapterRef? = null
        for (verseKey in candidates.sorted()) {
            val ref = VerseRef.fromKey(verseKey)
            if (ref.book !in SabibleFormat.BOOKS) continue
            val chapterRef = ChapterRef(ref.book, ref.chapter)
            if (loaded != chapterRef) {
                if (opened >= SEARCH_CHAPTER_BUDGET) break
                if (chapterRef !in index) continue
                val chapter = chapter(chapterRef)
                opened += 1
                loaded = chapterRef
                text = chapter.verses.associate { it.ref.key to it.text }
            }
            val verse = text[verseKey] ?: continue
            if (longPrefixes.isNotEmpty()) {
                val tokens = PackageSearchIndex.tokens(verse)
                if (!longPrefixes.all { prefix -> tokens.any { it.startsWith(prefix) } }) continue
            }
            hits += SearchHit(ref, verse)
            if (hits.size == limit) break
        }
        return hits
    }

    private class Lookup(val id: Long, val isPrefix: Boolean)

    /**
     * Reads one index bucket's sealed blob and opens it — the sibling of the chapter path, under the
     * same discipline: one bucket, from its own byte range, bound to this package, this translation,
     * this bucket number and this exact header. The domain string differs from a chapter's, so a
     * chapter blob can never be opened as a bucket or the reverse.
     */
    private fun openBucket(entry: PackagedBucketEntry): ByteArray {
        val sealed = readBody(entry.offset, entry.length)
        val associated = indexAssociatedData(header.packageId, header.translation.id, entry.bucket, headerDigest)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                contentKey,
                GCMParameterSpec(SabibleFormat.TAG_BYTES * 8, sealed, 0, SabibleFormat.NONCE_BYTES),
            )
            cipher.updateAAD(associated)
            cipher.doFinal(sealed, SabibleFormat.NONCE_BYTES, sealed.size - SabibleFormat.NONCE_BYTES)
                .also { bucketsOpened.incrementAndGet() }
        } catch (e: GeneralSecurityException) {
            throw TranslationPackageException.BucketTampered(entry.bucket, e)
        }
    }

    private fun readBody(offset: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var position = bodyOffset + offset
        try {
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read < 0) throw TranslationPackageException.Truncated()
                position += read
            }
        } catch (e: IOException) {
            throw TranslationPackageException.Unreadable(e.message ?: "read failed", e)
        }
        return buffer.array()
    }

    override fun close() = channel.close()

    companion object {

        /**
         * Opens and authenticates a package. Only the header is read and verified here; chapters are
         * decrypted when asked for, so this costs a signature check, not a 16 MB decryption.
         *
         * @param keyring the Ed25519 keys this build pins; a package signed by anything else is refused.
         * @param contentKey the 32-byte AES key — see [ContentKey].
         * @param now for the expiry check; explicit so tests can state the rule.
         */
        fun open(
            file: File,
            keyring: PublisherKeyring,
            contentKey: ByteArray,
            now: Instant = Instant.now(),
        ): TranslationPackage = open(file, keyring, contentKey, now, checkKeyId = true)

        /**
         * `checkKeyId = false` skips the courtesy check that the header names the key we hold, so a
         * test can prove that AES-GCM — not that check — is what refuses a wrong key.
         */
        internal fun open(
            file: File,
            keyring: PublisherKeyring,
            contentKey: ByteArray,
            now: Instant,
            checkKeyId: Boolean,
        ): TranslationPackage {
            require(contentKey.size == 32) { "an AES-256 content key is 32 bytes" }
            val channel = try {
                FileChannel.open(file.toPath(), StandardOpenOption.READ)
            } catch (e: IOException) {
                throw TranslationPackageException.Unreadable(e.message ?: "open failed", e)
            }
            try {
                return authenticate(channel, keyring, contentKey, now, checkKeyId)
            } catch (e: Throwable) {
                channel.close()
                throw e
            }
        }

        private fun authenticate(
            channel: FileChannel,
            keyring: PublisherKeyring,
            contentKey: ByteArray,
            now: Instant,
            checkKeyId: Boolean,
        ): TranslationPackage {
            val size = try {
                channel.size()
            } catch (e: IOException) {
                throw TranslationPackageException.Unreadable(e.message ?: "size failed", e)
            }

            fun read(count: Int, at: Long): ByteArray {
                if (count < 0 || at > size || count.toLong() > size - at) {
                    throw TranslationPackageException.Truncated()
                }
                val buffer = ByteBuffer.allocate(count)
                var position = at
                try {
                    while (buffer.hasRemaining()) {
                        val n = channel.read(buffer, position)
                        if (n < 0) throw TranslationPackageException.Truncated()
                        position += n
                    }
                } catch (e: IOException) {
                    throw TranslationPackageException.Unreadable(e.message ?: "read failed", e)
                }
                return buffer.array()
            }

            val magicLength = SabibleFormat.MAGIC.size
            // A file shorter than the preamble is "not a package", as the Swift reader's `truncated`
            // would be; either way it is refused before anything is parsed.
            val preamble = read(magicLength + 6, 0)
            if (!preamble.copyOfRange(0, magicLength).contentEquals(SabibleFormat.MAGIC)) {
                throw TranslationPackageException.NotAPackage()
            }
            val version = uint16(preamble, magicLength)
            if (version != SabibleFormat.VERSION) throw TranslationPackageException.UnsupportedFormat(version)
            val headerLength = uint32(preamble, magicLength + 2)
            if (headerLength <= 0 || headerLength > SabibleFormat.MAXIMUM_HEADER_BYTES) {
                throw TranslationPackageException.DamagedHeader("header length $headerLength")
            }
            val headerStart = (magicLength + 6).toLong()
            val headerBytes = read(headerLength.toInt(), headerStart)
            val signatureLength = uint16(read(2, headerStart + headerLength), 0)
            if (signatureLength != SabibleFormat.SIGNATURE_BYTES) {
                throw TranslationPackageException.DamagedHeader("signature length $signatureLength")
            }
            val signature = read(signatureLength, headerStart + headerLength + 2)
            val bodyOffset = headerStart + headerLength + 2 + signatureLength

            val headerDigest = SabibleKeys.sha256(headerBytes)
            val header = PackageHeaderParser.parse(headerBytes)

            // Order matters, and is the Swift reader's: who signed this, then is the signature good,
            // then are the terms still in force, then do we hold the key.
            if (header.format != SabibleFormat.VERSION) {
                throw TranslationPackageException.UnsupportedFormat(header.format)
            }
            if (header.crypto.cipher != SabibleFormat.CIPHER ||
                header.crypto.signature != SabibleFormat.SIGNATURE_ALGORITHM ||
                header.crypto.aad != SabibleFormat.ASSOCIATED_DATA_VERSION
            ) {
                throw TranslationPackageException.DamagedHeader("unsupported cipher, signature or binding")
            }
            val publicKey = keyring.rawPublicKey(header.crypto.publisherKeyId)
                ?.takeIf { SabibleKeys.publisherKeyId(it) == header.crypto.publisherKeyId }
                ?: throw TranslationPackageException.UnknownPublisherKey(header.crypto.publisherKeyId)
            try {
                Ed25519Verify(publicKey).verify(signature, headerBytes)
            } catch (e: GeneralSecurityException) {
                throw TranslationPackageException.SignatureInvalid()
            }

            val expires = header.policy.expiryInstant()
            if (expires != null && !now.isBefore(expires)) throw TranslationPackageException.Expired(expires)
            if (checkKeyId && SabibleKeys.contentKeyId(contentKey) != header.crypto.keyId) {
                throw TranslationPackageException.WrongContentKey()
            }

            val index = LinkedHashMap<ChapterRef, PackagedChapterEntry>()
            for (entry in header.chapters) {
                if (entry.book !in SabibleFormat.BOOKS) {
                    throw TranslationPackageException.DamagedHeader("book ${entry.book} is not a book of the Bible")
                }
                if (entry.offset < 0 ||
                    entry.length <= SabibleFormat.SEALED_CHAPTER_OVERHEAD ||
                    entry.length > SabibleFormat.MAXIMUM_CHAPTER_BYTES ||
                    bodyOffset + entry.offset + entry.length > size
                ) {
                    throw TranslationPackageException.Truncated()
                }
                index[entry.ref] = entry
            }
            if (index.isEmpty()) throw TranslationPackageException.DamagedHeader("no chapters")

            // The search index's entries, held to the same bounds as a chapter's: every bucket inside
            // the file, sealed-sized, and no larger than a bucket can be. Signed, but a signature only
            // says who wrote a number — not that it is safe to seek to.
            val buckets = HashMap<Int, PackagedBucketEntry>()
            header.index?.let { parameters ->
                if (parameters.buckets <= 0 || parameters.buckets > PackageSearchIndex.MAXIMUM_BUCKET_COUNT ||
                    parameters.prefixMin < 1 || parameters.prefixMax < parameters.prefixMin ||
                    parameters.padding < 0
                ) {
                    throw TranslationPackageException.DamagedHeader("index parameters")
                }
                for (entry in parameters.entries) {
                    if (entry.bucket < 0 || entry.bucket >= parameters.buckets ||
                        entry.offset < 0 ||
                        entry.length <= SabibleFormat.SEALED_CHAPTER_OVERHEAD ||
                        entry.length > PackageSearchIndex.MAXIMUM_BUCKET_BYTES ||
                        bodyOffset + entry.offset + entry.length > size
                    ) {
                        throw TranslationPackageException.Truncated()
                    }
                    buckets[entry.bucket] = entry
                }
            }

            return TranslationPackage(
                channel = channel,
                header = header,
                headerDigest = headerDigest,
                bodyOffset = bodyOffset,
                contentKey = SecretKeySpec(contentKey.copyOf(), "AES"),
                index = index,
                buckets = buckets,
            )
        }

        /**
         * Chapters one search may decrypt. Only a prefix longer than the longest indexed one can produce
         * candidates that turn out not to match, and with prefixes indexed to ten characters that is
         * rare — this is the ceiling that keeps even a pathological query from walking the whole book.
         */
        const val SEARCH_CHAPTER_BUDGET = 256

        /**
         * One group of a query: adjacent word positions for a phrase, and a verse-level check for a
         * trailing prefix, which has no positions by design.
         */
        private fun versesSatisfying(lookups: List<Lookup>, entries: Map<Long, PackageSearchIndex.Entry>): Set<Int> {
            val positional = mutableListOf<PackageSearchIndex.Entry>()
            var prefixVerses: Set<Int>? = null
            for (lookup in lookups) {
                val entry = entries[lookup.id] ?: return emptySet()
                if (lookup.isPrefix) prefixVerses = entry.postings.mapTo(HashSet()) { it.verse } else positional += entry
            }
            val first = positional.firstOrNull() ?: return prefixVerses ?: emptySet()

            var running = HashMap<Int, Set<Int>>()
            for (posting in first.postings) running[posting.verse] = posting.positions.toSet()
            for (entry in positional.drop(1)) {
                val next = HashMap<Int, Set<Int>>()
                for (posting in entry.postings) {
                    val previous = running[posting.verse] ?: continue
                    val adjacent = posting.positions.filterTo(HashSet()) { (it - 1) in previous }
                    if (adjacent.isNotEmpty()) next[posting.verse] = adjacent
                }
                running = next
                if (running.isEmpty()) return emptySet()
            }
            val matched = running.keys
            return prefixVerses?.let { matched.intersect(it) } ?: matched.toSet()
        }

        /**
         * `sabible-index-v1 ‖ package id ‖ translation id ‖ bucket ‖ header digest (hex)`, newline
         * separated, UTF-8 — what `TranslationPackage.indexAssociatedData` and the packaging tool write.
         */
        fun indexAssociatedData(packageId: String, translationId: String, bucket: Int, headerDigest: ByteArray): ByteArray =
            listOf(
                PackageSearchIndex.ASSOCIATED_DATA_VERSION,
                packageId,
                translationId,
                bucket.toString(),
                SabibleKeys.hex(headerDigest),
            ).joinToString("\n").toByteArray(Charsets.UTF_8)

        /**
         * `sabible-chapter-v1 ‖ package id ‖ translation id ‖ chapter key ‖ header digest (hex)`,
         * newline separated, UTF-8 — byte for byte what `TranslationPackage.associatedData` and
         * `package_translation.py` write.
         */
        fun associatedData(
            packageId: String,
            translationId: String,
            chapter: ChapterRef,
            headerDigest: ByteArray,
        ): ByteArray = listOf(
            SabibleFormat.ASSOCIATED_DATA_VERSION,
            packageId,
            translationId,
            chapter.key.toString(),
            SabibleKeys.hex(headerDigest),
        ).joinToString("\n").toByteArray(Charsets.UTF_8)

        /**
         * A chapter blob is JSON: `{"layout": "<layout JSON as a string>", "verses": [{"i", "t", "r"?}]}`.
         *
         * A payload that is not that shape is treated as tampering, as in Swift: it authenticated,
         * so it came from whoever holds the key, but it is not a chapter this app can show. Rows
         * whose key names no book are dropped and a red pair that is not two numbers is skipped —
         * the Swift reader's `compactMap`s, kept so the two platforms show the same verses.
         *
         * The layout string is checked to be a JSON object but not decoded into a layout here; the
         * Swift reader decodes it into `ChapterLayout` at this point, and the Android equivalent
         * belongs to the layout port.
         */
        internal fun decodeChapter(plaintext: ByteArray, ref: ChapterRef): PackagedChapter {
            try {
                val root = Json.parseToJsonElement(PackageHeaderParser.strictUtf8(plaintext)).obj("chapter")
                val layout = root.field("layout").let {
                    if (it !is JsonPrimitive || !it.isString) throw TranslationPackageException.ChapterTampered(ref)
                    it.content
                }
                if (Json.parseToJsonElement(layout) !is JsonObject) throw TranslationPackageException.ChapterTampered(ref)

                val verses = root.field("verses").array("verses").mapNotNull { element ->
                    val row = element.obj("verse")
                    val key = row.integer("i") ?: throw TranslationPackageException.ChapterTampered(ref)
                    val text = (row["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw TranslationPackageException.ChapterTampered(ref)
                    val red = when (val r = row["r"]) {
                        null, JsonNull -> emptyList()
                        is JsonArray -> r.mapNotNull { pair -> redPair(pair, ref) }
                        else -> throw TranslationPackageException.ChapterTampered(ref)
                    }
                    val verse = VerseRef.fromKey(key.toInt())
                    if (verse.book !in SabibleFormat.BOOKS) null else PackagedVerse(verse, text, red)
                }
                return PackagedChapter(ref, layout, verses)
            } catch (e: TranslationPackageException.DamagedHeader) {
                throw TranslationPackageException.ChapterTampered(ref, e)
            } catch (e: CharacterCodingException) {
                throw TranslationPackageException.ChapterTampered(ref, e)
            } catch (e: IllegalArgumentException) {
                throw TranslationPackageException.ChapterTampered(ref, e)
            }
        }

        private fun redPair(pair: JsonElement, ref: ChapterRef): ScalarRange? {
            val numbers = pair as? JsonArray ?: throw TranslationPackageException.ChapterTampered(ref)
            val values = numbers.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull
                ?: throw TranslationPackageException.ChapterTampered(ref) }
            if (values.size != 2) return null
            return ScalarRange(values[0].toInt(), values[1].toInt())
        }

        private fun JsonObject.integer(name: String): Long? =
            (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        private fun uint16(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)

        /** Unsigned, so returned as a [Long]: a u32 above 2³¹ must read as large, not negative. */
        private fun uint32(bytes: ByteArray, at: Int): Long {
            var value = 0L
            for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toLong() and 0xff)
            return value
        }
    }
}
