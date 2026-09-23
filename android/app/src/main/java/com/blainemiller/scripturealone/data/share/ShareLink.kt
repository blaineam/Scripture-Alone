package com.blainemiller.scripturealone.data.share

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookNames
import com.blainemiller.scripturealone.data.reference.OSISReference
import com.blainemiller.scripturealone.data.reference.Passage
import com.blainemiller.scripturealone.data.reference.ReferenceParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.text.BreakIterator
import java.util.Base64
import java.util.UUID

/**
 * One verse ready to share. [red] is in UTF-16 units — Kotlin's string indices — as share links carry
 * it; [fromScalars] converts the Unicode-scalar ranges the bundled translations store.
 */
data class ShareVerse(val ref: VerseRef, val text: String, val red: List<IntRange> = emptyList()) {
    companion object {
        fun fromScalars(ref: VerseRef, text: String, red: List<Pair<Int, Int>>): ShareVerse {
            val scalars = text.codePointCount(0, text.length)
            fun utf16(scalar: Int) = text.offsetByCodePoints(0, scalar.coerceIn(0, scalars))
            return ShareVerse(ref, text, red.filter { it.second > 0 }.map { (start, length) ->
                utf16(start) until utf16(start + length)
            })
        }
    }
}

/**
 * A passage flattened for sharing, ported from `SharePassageText` in
 * `ScriptureAloneCore/ShareLinkPayload.swift`: verses joined with a space, each prefixed by its
 * number when there is more than one ("16 For God… 17 For God…"). [red] and [numbers] are UTF-16
 * ranges into [text], so they line up with JavaScript strings on the web card.
 */
data class SharePassageText(
    val text: String,
    val red: List<IntRange> = emptyList(),
    val numbers: List<IntRange> = emptyList(),
) {
    companion object {
        /**
         * Numbers are added only when [numbered] and there is more than one verse. A verse that starts
         * a new chapter mid-passage is numbered "4:1" so the break stays readable.
         */
        fun of(verses: List<ShareVerse>, numbered: Boolean = true): SharePassageText {
            val showNumbers = numbered && verses.size > 1
            val text = StringBuilder()
            val red = mutableListOf<IntRange>()
            val numbers = mutableListOf<IntRange>()
            var previous: VerseRef? = null
            for (verse in verses) {
                if (text.isNotEmpty()) text.append(' ')
                if (showNumbers) {
                    val newChapter = previous != null &&
                        (previous.book != verse.ref.book || previous.chapter != verse.ref.chapter)
                    val label = if (newChapter) "${verse.ref.chapter}:${verse.ref.verse}" else "${verse.ref.verse}"
                    numbers += text.length until text.length + label.length
                    text.append(label).append(' ')
                }
                val offset = text.length
                for (range in verse.red) {
                    if (range.isEmpty()) continue
                    val shifted = (offset + range.first)..(offset + range.last)
                    val last = red.lastOrNull()
                    if (last != null && last.last + 1 == shifted.first) {
                        red[red.size - 1] = last.first..shifted.last
                    } else {
                        red += shifted
                    }
                }
                text.append(verse.text)
                previous = verse.ref
            }
            return SharePassageText(text.toString(), red, numbers)
        }
    }
}

/** Why a share link could not be read. */
sealed class ShareLinkException(message: String) : Exception(message) {
    class MissingPayload : ShareLinkException("The link carries no passage.")
    class NotBase64 : ShareLinkException("The passage in the link is not base64url.")
    class Malformed : ShareLinkException("The passage in the link is malformed.")
    class TooLong : ShareLinkException("The passage in the link is too long.")
    class UnsupportedVersion(val version: Int) : ShareLinkException("Share link version $version is not supported.")
}

/**
 * The contract for share links, ported from `ShareLinkPayload` in
 * `ScriptureAloneCore/ShareLinkPayload.swift` (see `docs/share-links.md`):
 * `https://wemiller.com/apps/scripture-alone/#s=<payload>`, where the payload is base64url (no
 * padding) of compact UTF-8 JSON with sorted keys. The passage rides in the URL fragment, which
 * browsers never send to a server. An Android link must decode on iOS and the web, and theirs here —
 * [encoded] is tested byte-for-byte against the Swift encoder's output.
 */
data class ShareLinkPayload(
    /** "John 3:16–17" */
    val reference: String,
    /** Verse ranges in storage form, comma separated: "43003016-43003017,43003020-43003020". */
    val keys: String,
    /** Translation abbreviation ("ASV"). */
    val translation: String,
    val text: String,
    /** Words of Christ as UTF-16 ranges into [text]. */
    val red: List<IntRange> = emptyList(),
    /** Card template ("parchment"), typeface ("serif") and aspect ("square"); the web has defaults. */
    val template: String? = null,
    val font: String? = null,
    val aspect: String? = null,
) {
    /** The verse ranges named by [keys]. A lone key ("43003016") is one verse. */
    val ranges: List<VerseRange>
        get() = keys.split(',').filter { it.isNotEmpty() }.mapNotNull { raw ->
            val part = raw.trim()
            VerseRange.parse(part) ?: part.toIntOrNull()?.let(VerseRange::ref)?.let { VerseRange.of(it) }
        }

    /** Whether the passage is short enough to travel in a link. Counted in characters, as Swift does. */
    val fitsInLink: Boolean get() = characterCount(text) <= MAX_TEXT_LENGTH

    /** The fragment payload (base64url, no padding). */
    fun encoded(): String {
        // Keys in sorted order, exactly as Swift's `.sortedKeys` writes them; optional keys omitted.
        val fields = sortedMapOf<String, JsonElement>(
            "v" to JsonPrimitive(VERSION), "ref" to JsonPrimitive(reference), "k" to JsonPrimitive(keys),
            "tr" to JsonPrimitive(translation), "t" to JsonPrimitive(text),
        )
        if (red.isNotEmpty()) {
            fields["red"] = JsonArray(red.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.last - it.first + 1))) })
        }
        template?.let { fields["tp"] = JsonPrimitive(it) }
        font?.let { fields["f"] = JsonPrimitive(it) }
        aspect?.let { fields["a"] = JsonPrimitive(it) }
        return base64UrlEncode(Json.encodeToString(JsonElement.serializer(), JsonObject(fields)).toByteArray(Charsets.UTF_8))
    }

    fun webUrl(): String = "$WEB_BASE#s=${encoded()}"

    companion object {
        const val VERSION = 1
        const val WEB_BASE = "https://wemiller.com/apps/scripture-alone/"
        const val WEB_HOST = "wemiller.com"
        const val WEB_PATH = "/apps/scripture-alone"
        const val SCHEME = "scripturealone"
        /** Longest text (in characters) a link carries; longer passages are shared as an image only. */
        const val MAX_TEXT_LENGTH = 1_500
        /** Decoding refuses anything wildly larger than a link we would ever make. */
        internal const val MAX_DECODED_TEXT_LENGTH = 6_000

        fun of(
            ranges: List<VerseRange>, reference: String, translation: String, passage: SharePassageText,
            template: String? = null, font: String? = null, aspect: String? = null,
        ) = ShareLinkPayload(
            reference, ranges.joinToString(",") { it.storageString }, translation, passage.text, passage.red,
            template, font, aspect,
        )

        /** Reads a payload, throwing [ShareLinkException] for anything that isn't one. */
        fun decode(payload: String): ShareLinkPayload {
            val data = base64UrlDecode(payload) ?: throw ShareLinkException.NotBase64()
            val fields = try {
                Json.parseToJsonElement(String(data, Charsets.UTF_8)) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: throw ShareLinkException.Malformed()
            return fromJson(fields)
        }

        /** Reads the `s=` parameter from a URL's fragment (`#s=…`, also `#…&s=…`). */
        fun fromUrl(url: String): ShareLinkPayload {
            val fragment = runCatching { URI(url).fragment }.getOrNull()
                ?: url.substringAfter('#', "").ifEmpty { null }
                ?: throw ShareLinkException.MissingPayload()
            val value = fragment.split('&').firstOrNull { it.startsWith("s=") }?.drop(2)
            if (value.isNullOrEmpty()) throw ShareLinkException.MissingPayload()
            return decode(value)
        }

        private fun fromJson(fields: JsonObject): ShareLinkPayload {
            fun string(key: String, required: Boolean): String? {
                val element = fields[key] ?: return if (required) throw ShareLinkException.Malformed() else null
                val primitive = element as? JsonPrimitive
                if (primitive == null || !primitive.isString) throw ShareLinkException.Malformed()
                return primitive.content
            }
            val versionElement = fields["v"] as? JsonPrimitive
            if (versionElement == null || versionElement.isString) throw ShareLinkException.Malformed()
            val version = versionElement.intOrNull ?: throw ShareLinkException.Malformed()
            if (version != VERSION) throw ShareLinkException.UnsupportedVersion(version)

            val text = string("t", required = true)!!
            if (characterCount(text) > MAX_DECODED_TEXT_LENGTH) throw ShareLinkException.TooLong()
            // Drop malformed or out-of-bounds ranges rather than failing the whole link.
            val length = text.length
            val pairs = when (val red = fields["red"]) {
                null -> emptyList()
                is JsonArray -> red.map { pair ->
                    (pair as? JsonArray)?.map { number ->
                        val primitive = number as? JsonPrimitive
                        if (primitive == null || primitive.isString) throw ShareLinkException.Malformed()
                        primitive.intOrNull ?: throw ShareLinkException.Malformed()
                    } ?: throw ShareLinkException.Malformed()
                }
                else -> throw ShareLinkException.Malformed()
            }
            val red = pairs.mapNotNull { pair ->
                if (pair.size != 2 || pair[0] < 0 || pair[1] <= 0 || pair[0] >= length) null
                else pair[0] until pair[0] + minOf(pair[1], length - pair[0])
            }
            return ShareLinkPayload(
                reference = string("ref", required = true)!!,
                keys = string("k", required = false) ?: "",
                translation = string("tr", required = false) ?: "",
                text = text, red = red,
                template = string("tp", required = false),
                font = string("f", required = false),
                aspect = string("a", required = false),
            )
        }

        internal fun base64UrlEncode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

        /** As Swift decodes it: either alphabet, padding optional, surrounding whitespace ignored. */
        internal fun base64UrlDecode(string: String): ByteArray? {
            var base64 = string.trim().replace('-', '+').replace('_', '/')
            val remainder = base64.length % 4
            if (remainder == 1) return null
            if (remainder > 0) base64 += "=".repeat(4 - remainder)
            return try {
                Base64.getDecoder().decode(base64)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** Grapheme clusters — what Swift's `String.count` counts — so both apps agree on the cap. */
        internal fun characterCount(text: String): Int {
            val breaks = BreakIterator.getCharacterInstance()
            breaks.setText(text)
            var count = 0
            while (breaks.next() != BreakIterator.DONE) count++
            return count
        }
    }
}

/**
 * A URL the app knows how to open, ported from `AppLink` in `ShareLinkPayload.swift`.
 *
 * Custom scheme (`scripturealone://`):
 * - `open?ref=<ref>` and `passage/<ref>` — a passage, where `<ref>` is stored keys
 *   (`43003016-43003017,45008028`), OSIS (`John.3.16`, `Gen.1.1-Gen.1.3`, `Ps.23`), either with a
 *   `urn:osis:` / `osis:` prefix, or a plain reference in any language the app reads ("John 3:16",
 *   "Jean 3:16", "요한복음 3:16");
 * - `search?q=<words>`, `note/<uuid>`, `notes`, `favorites`;
 * - a share link's `#s=` fragment on any of them.
 *
 * Web (`https://wemiller.com/apps/scripture-alone/…`, opened as an App Link): a share link's `#s=`
 * fragment, or a passage as `?ref=<ref>`, `#ref=<ref>` or `/apps/scripture-alone/passage/<ref>`.
 *
 * The Android-only commands (Verse of the Day, Continue Reading, new notes, favoriting) are
 * [AppCommand]s, a layer over this, so the two apps' link contract stays the same.
 */
sealed class AppLink {
    /** A share link (web or custom scheme with a `#s=` fragment): show the passage and its card. */
    data class Share(val payload: ShareLinkPayload) : AppLink()

    /** Stored KJV keys, `scripturealone://open?ref=43003016-43003017`: go to the passage and select it. */
    data class Open(val ranges: List<VerseRange>) : AppLink()

    /** OSIS references — KJV numbering, like [Open]. A whole chapter (`Ps.23`) has no start verse. */
    data class Osis(val passages: List<Passage>) : AppLink()

    /** A reference as a person writes it, in the numbering of the translation being read (Swift's `.passage`). */
    data class Typed(val passages: List<Passage>) : AppLink()

    /** Search the Bible for words. */
    data class Search(val words: String) : AppLink()

    /** A note, by its id. */
    data class NoteLink(val id: UUID) : AppLink()

    /** The notes list, or its Favorites scope. */
    data object Notes : AppLink()
    data object Favorites : AppLink()

    companion object {
        fun parse(url: String): AppLink? {
            val parts = LinkParts.of(url) ?: return null
            val isWeb = parts.isWeb
            val isCustom = parts.scheme == ShareLinkPayload.SCHEME
            if (!isWeb && !isCustom) return null

            runCatching { ShareLinkPayload.fromUrl(url) }.getOrNull()?.let { payload ->
                if (payload.ranges.isNotEmpty()) return Share(payload)
            }

            // "#ref=John.3.16" on the web: a fragment never reaches the server, like the share payload.
            val fragmentRef = parts.fragment?.let(LinkParts::decode)?.split('&')
                ?.firstOrNull { it.startsWith("ref=") }?.drop(4)
            val ref = parts.query("ref") ?: fragmentRef
            if (ref != null) return reference(ref)

            // Path segments after the host (custom scheme) or after the web page's path.
            val segments: List<String> = if (isCustom) {
                (listOf(parts.authority) + parts.path.split('/')).filter { it.isNotEmpty() }
            } else {
                parts.path.drop(ShareLinkPayload.WEB_PATH.length).split('/').filter { it.isNotEmpty() }
            }
            val head = segments.firstOrNull()?.let(LinkParts::decode)?.lowercase() ?: return null
            val tail = segments.drop(1).joinToString("/")
            return when {
                head in setOf("passage", "open", "verse", "ref") -> reference(tail)
                !isCustom -> null
                head == "search" -> {
                    // A "+" is a space here, as a search form writes it; nobody searches for a plus sign.
                    val words = (parts.query("q")?.replace('+', ' ') ?: tail).let { LinkParts.decode(it) ?: it }.trim()
                    if (words.isEmpty()) null else Search(words)
                }
                head == "note" -> {
                    val raw = segments.getOrNull(1)?.let(LinkParts::decode) ?: parts.query("id")?.let(LinkParts::decode)
                    uuid(raw)?.let(::NoteLink)
                }
                head == "notes" -> Notes
                head == "favorites" -> Favorites
                else -> null
            }
        }

        /**
         * A passage from any form a link may carry: stored keys, OSIS (optionally `urn:osis:`), or a
         * plain reference in any of the app's languages. Null when it is none of them.
         */
        fun reference(raw: String, language: String? = BookNames.current): AppLink? {
            val text = OSISReference.stripUrnPrefix(LinkParts.decode(raw) ?: raw)
            if (text.isEmpty()) return null
            // Stored keys first: digits and separators only, so a reference is never mistaken for one.
            if (text.all { it.isDigit() || it in "-, " }) {
                val ranges = ShareLinkPayload("", text, "", "").ranges
                return if (ranges.isEmpty()) null else Open(ranges)
            }
            OSISReference.parse(text)?.takeIf { it.isNotEmpty() }?.let { return Osis(it) }
            val passages = ReferenceParser.parseList(text, language)
            return if (passages.isEmpty()) null else Typed(passages)
        }

        /** `scripturealone://open?ref=43003016-43003017` */
        fun openUrl(ranges: List<VerseRange>): String =
            "${ShareLinkPayload.SCHEME}://open?ref=${ranges.joinToString(",") { it.storageString }}"

        /** `scripturealone://note/<uuid>` */
        fun noteUrl(id: UUID): String = "${ShareLinkPayload.SCHEME}://note/$id"

        private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** A UUID in its canonical 36-character form only, as Swift's `UUID(uuidString:)` reads it. */
        internal fun uuid(raw: String?): UUID? =
            raw?.takeIf { uuidPattern.matches(it) }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }
}

/**
 * A URL taken apart leniently. `java.net.URI` refuses what links arrive with in practice — a space
 * or 约翰福音 unencoded in `?ref=` from `adb` or a note — so the pieces are split by hand and each is
 * percent-decoded only when read.
 */
internal class LinkParts(
    val scheme: String,
    /** The host (and any port), lowercased — `open`, `note` for the custom scheme. Raw otherwise. */
    val authority: String,
    /** Still percent-encoded. */
    val path: String,
    val rawQuery: String?,
    val fragment: String?,
) {
    val host: String get() = authority.substringAfterLast('@').substringBefore(':').lowercase()

    val isWeb: Boolean
        get() = (scheme == "https" || scheme == "http") &&
            (host == ShareLinkPayload.WEB_HOST || host == "www.${ShareLinkPayload.WEB_HOST}") &&
            path.startsWith(ShareLinkPayload.WEB_PATH)

    /**
     * A query parameter, percent-decoded. A literal "+" stays "+", as `URLComponents` reads it —
     * `+` in a reference is never a space.
     */
    fun query(name: String): String? = rawQuery?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.let { decode(it) ?: it }

    companion object {
        private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

        fun of(url: String): LinkParts? {
            val text = url.trim()
            val colon = text.indexOf(':')
            if (colon <= 0) return null
            val scheme = text.substring(0, colon)
            if (!schemePattern.matches(scheme)) return null
            var rest = text.substring(colon + 1)
            val fragment = rest.indexOf('#').takeIf { it >= 0 }?.let { index ->
                rest.substring(index + 1).also { rest = rest.substring(0, index) }
            }
            val query = rest.indexOf('?').takeIf { it >= 0 }?.let { index ->
                rest.substring(index + 1).also { rest = rest.substring(0, index) }
            }
            var authority = ""
            if (rest.startsWith("//")) {
                rest = rest.substring(2)
                val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
                authority = rest.substring(0, slash)
                rest = rest.substring(slash)
            }
            return LinkParts(scheme.lowercase(), authority.lowercase(), rest, query, fragment)
        }

        /** Percent-decodes UTF-8; null when the escapes are malformed (as `removingPercentEncoding`). */
        fun decode(text: String): String? {
            if ('%' !in text) return text
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 2 >= text.length) return null
                    val value = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    bytes.write(value)
                    i += 3
                } else {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            val decoder = Charsets.UTF_8.newDecoder()
            return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString() }.getOrNull()
        }
    }
}
