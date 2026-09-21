package com.blainemiller.scripturealone.data.share

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.net.URLDecoder
import java.text.BreakIterator
import java.util.Base64

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

/** A URL the app knows how to open, ported from `AppLink` in `ShareLinkPayload.swift`. */
sealed class AppLink {
    /** A share link (web or custom scheme with a `#s=` fragment): show the passage and its card. */
    data class Share(val payload: ShareLinkPayload) : AppLink()

    /** `scripturealone://open?ref=43003016-43003017`: go to the passage and select it. */
    data class Open(val ranges: List<VerseRange>) : AppLink()

    companion object {
        fun parse(url: String): AppLink? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase() ?: ""
            val isWeb = (scheme == "https" || scheme == "http") &&
                (host == ShareLinkPayload.WEB_HOST || host == "www.${ShareLinkPayload.WEB_HOST}") &&
                (uri.rawPath ?: "").startsWith(ShareLinkPayload.WEB_PATH)
            val isCustom = scheme == ShareLinkPayload.SCHEME
            if (!isWeb && !isCustom) return null

            runCatching { ShareLinkPayload.fromUrl(url) }.getOrNull()?.let { payload ->
                if (payload.ranges.isNotEmpty()) return Share(payload)
            }
            if (!isCustom) return null
            val ref = (uri.rawQuery ?: return null).split('&')
                .firstOrNull { it.substringBefore('=') == "ref" }
                ?.substringAfter('=', "")
                // A literal "+" stays "+", as URLComponents reads it; URLDecoder alone would make it a space.
                ?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } ?: return null
            val ranges = ShareLinkPayload("", ref, "", "").ranges
            return if (ranges.isEmpty()) null else Open(ranges)
        }

        /** `scripturealone://open?ref=43003016-43003017` */
        fun openUrl(ranges: List<VerseRange>): String =
            "${ShareLinkPayload.SCHEME}://open?ref=${ranges.joinToString(",") { it.storageString }}"
    }
}
