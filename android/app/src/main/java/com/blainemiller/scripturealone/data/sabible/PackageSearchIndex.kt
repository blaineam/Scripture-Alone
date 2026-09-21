package com.blainemiller.scripturealone.data.sabible

import com.blainemiller.scripturealone.data.search.VerseSearch
import com.google.crypto.tink.subtle.Hkdf
import java.text.Normalizer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// A packaged translation is searchable, and the index is sealed exactly like the text is — a port of
// `ScriptureAloneCore/Package/PackageSearchIndex.swift`, which the Python packaging tool writes byte
// for byte.
//
// The obvious design — HMAC(word) → postings, left in the clear — is wrong for *this* corpus: the
// attacker knows the file is a Bible, so token frequencies and positions could be aligned against any
// public Bible until every hash was identified, which reconstructs the wording. So the postings are
// encrypted too, in buckets, and a search opens only the buckets its query names. What is left in the
// clear is how many buckets there are and how big each is (`docs/encrypted-translations.md`).

/** Where one sealed bucket sits in the body. Signed, like a chapter's entry. */
data class PackagedBucketEntry(
    val bucket: Int,
    /** Bytes from the start of the body, like a chapter's. */
    val offset: Long,
    val length: Int,
)

/**
 * The index's parameters — inside the signed header, so the bucket count, the prefix lengths and the
 * tokeniser cannot be altered under the app. A package whose index a tokeniser this build doesn't
 * implement built is refused for search rather than silently mis-searched.
 */
data class PackageIndexParameters(
    val tokenizer: String,
    val aad: String,
    val buckets: Int,
    val prefixMin: Int,
    val prefixMax: Int,
    /** Sealed buckets are padded to a multiple of this many bytes. */
    val padding: Int,
    val entries: List<PackagedBucketEntry>,
)

object PackageSearchIndex {
    const val TOKENIZER = "sabible-tokens-v1"
    const val ASSOCIATED_DATA_VERSION = "sabible-index-v1"
    const val DEFAULT_BUCKET_COUNT = 256
    const val DEFAULT_PREFIX_MIN = 3
    const val DEFAULT_PREFIX_MAX = 10

    /** A bucket over this size is not one of ours. */
    const val MAXIMUM_BUCKET_BYTES = 8 * 1024 * 1024

    /** At most this many buckets; more is a damaged header. */
    const val MAXIMUM_BUCKET_COUNT = 65_536

    // Tokeniser v1

    /**
     * NFD, drop nonspacing marks, lowercase — exactly what `Tools/package_translation.py` and the Swift
     * reader do. A divergence shows up as a failing test against the shipped ASV rather than as a
     * search that quietly misses words.
     */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString().lowercase(Locale.ROOT)
    }

    /**
     * Runs of letters and digits, matching SQLite's `unicode61` — an apostrophe separates, so "the
     * LORD's" is three tokens in the store's index and three here. "Letter or digit" is Python's
     * `isalnum` (the L* and N* categories), which built the shipped index.
     */
    fun tokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        val folded = fold(text)
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            if (isLetterOrNumber(cp)) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
            i += Character.charCount(cp)
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun isLetterOrNumber(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        -> true
        else -> false
    }

    // Keys and tags

    /**
     * A key of its own for the index, derived from the content key (HKDF-SHA256, no salt, info
     * `"SABIBLE index v1" ‖ 0x00 ‖ translation id`), so a token tag can never be confused with
     * content-key material, and two translations under one content key don't share a token → bucket
     * mapping.
     */
    fun indexKey(contentKey: ByteArray, translationId: String): ByteArray {
        val info = "SABIBLE index v1".toByteArray(Charsets.UTF_8) + byteArrayOf(0) + translationId.toByteArray(Charsets.UTF_8)
        return Hkdf.computeHkdf("HMACSHA256", contentKey, ByteArray(0), info, 32)
    }

    enum class TagKind(val raw: String) { WORD("w"), PREFIX("p") }

    /** HMAC-SHA256 of `"<kind>:<token>"`. Its first four bytes name the bucket, its first eight the entry. */
    fun tag(key: ByteArray, kind: TagKind, token: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal("${kind.raw}:$token".toByteArray(Charsets.UTF_8))
    }

    fun bucket(tag: ByteArray, count: Int): Int {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (tag[i].toLong() and 0xff)
        return (value % count).toInt()
    }

    /** The first eight bytes, big-endian — an unsigned 64-bit id carried in a [Long]'s bits. */
    fun tokenId(tag: ByteArray): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (tag[i].toLong() and 0xff)
        return value
    }

    // Bucket payload

    /**
     * One token's postings inside a bucket. A word entry carries positions so a phrase is an adjacency
     * check; a prefix entry is verse-level only.
     */
    class Posting(val verse: Int, val positions: IntArray)

    class Entry(val tokenId: Long, val isPrefix: Boolean, val postings: List<Posting>)

    /**
     * `varint(payload length) ‖ varint(entry count) ‖ entries ‖ zero padding`. Reads back only the
     * entries asked for: a bucket holds tens of tokens, a query wants one or two, and the rest are
     * skipped rather than materialised.
     */
    fun decodeBucket(data: ByteArray, wanted: Set<Long>): Map<Long, Entry> {
        val reader = ByteReader(data)
        val payloadLength = reader.varint()
        if (payloadLength > reader.remaining) throw TranslationPackageException.DamagedIndex("bucket length")
        val entryCount = reader.varint()
        val found = HashMap<Long, Entry>()
        for (e in 0 until entryCount) {
            val id = reader.uint64()
            val isPrefix = reader.byte() == 1
            val postingCount = reader.varint()
            val keep = id in wanted
            val postings = if (keep) ArrayList<Posting>(minOf(postingCount, 1 shl 16)) else null
            var verse = 0
            for (p in 0 until postingCount) {
                verse += reader.varint()
                if (isPrefix) {
                    postings?.add(Posting(verse, IntArray(0)))
                    continue
                }
                val positionCount = reader.varint()
                var position = 0
                val positions = if (keep) IntArray(minOf(positionCount, 1 shl 16)) else null
                for (k in 0 until positionCount) {
                    position += reader.varint()
                    if (positions != null && k < positions.size) positions[k] = position
                }
                postings?.add(Posting(verse, positions ?: IntArray(0)))
            }
            if (postings != null) found[id] = Entry(id, isPrefix, postings)
            if (found.size == wanted.size) break
        }
        return found
    }
}

/**
 * A query, in the shape the index answers — `PackagedSearchQuery` in Swift: groups that must all
 * appear in one verse, each a phrase of one or more adjacent tokens, with the last token of the last
 * group optionally matching as a prefix. It mirrors what [VerseSearch.ftsQuery] asks SQLite for, so
 * search feels the same whichever kind of translation is open.
 */
data class PackagedSearchQuery(val groups: List<Group>) {
    data class Group(val tokens: List<String>, val prefix: Boolean)

    companion object {
        /**
         * @param prefixMin shorter than this, a trailing word is matched whole — the index holds no
         *   one- or two-character prefixes. Results narrow one keystroke later than a store's, never
         *   wrongly.
         */
        fun parse(query: String, prefixMin: Int): PackagedSearchQuery? {
            val trimmed = query.trim { it.isWhitespace() }
            if (trimmed.codePointCount(0, trimmed.length) > 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                val phrase = trimmed.substring(1, trimmed.length - 1).replace("\"", "")
                val tokens = PackageSearchIndex.tokens(phrase)
                if (tokens.isEmpty()) return null
                return PackagedSearchQuery(listOf(Group(tokens, prefix = false)))
            }
            // Split the way the store's query builder does: words keep their apostrophes here and are
            // then tokenised, so "lord's" becomes the phrase lord + s — what FTS5 does with it too.
            val groups = VerseSearch.words(trimmed).mapNotNull { word ->
                PackageSearchIndex.tokens(word).takeIf { it.isNotEmpty() }?.let { Group(it, prefix = false) }
            }.toMutableList()
            if (groups.isEmpty()) return null
            val last = groups.last().tokens.last()
            if (last.codePointCount(0, last.length) >= prefixMin) groups[groups.lastIndex] = groups.last().copy(prefix = true)
            return PackagedSearchQuery(groups)
        }
    }
}

/**
 * A cursor over a bucket's plaintext. Every read is bounds-checked: a bucket is authenticated before
 * it is parsed, so a malformed one means the writer erred, but it still may not walk off the end.
 */
internal class ByteReader(private val data: ByteArray) {
    private var index = 0

    val remaining: Int get() = data.size - index

    fun byte(): Int {
        if (index >= data.size) throw TranslationPackageException.DamagedIndex("truncated bucket")
        return data[index++].toInt() and 0xff
    }

    /** LEB128, at most nine bytes, and never more than an [Int] holds. */
    fun varint(): Int {
        var result = 0L
        var shift = 0
        while (true) {
            val b = byte()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 56) throw TranslationPackageException.DamagedIndex("varint")
        }
        if (result > Int.MAX_VALUE) throw TranslationPackageException.DamagedIndex("varint out of range")
        return result.toInt()
    }

    fun uint64(): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or byte().toLong() }
        return value
    }
}
