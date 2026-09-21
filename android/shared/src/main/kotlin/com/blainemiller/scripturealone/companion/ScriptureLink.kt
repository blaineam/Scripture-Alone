package com.blainemiller.scripturealone.companion

import com.blainemiller.scripturealone.data.VerseRange
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * `scripturealone://open?ref=<startKey>-<endKey>` — the link widgets, complications and shared links
 * use to open the reader at a passage. A port of `ScriptureLink` in `VerseSnapshot.swift`.
 */
object ScriptureLink {
    const val SCHEME = "scripturealone"
    const val OPEN_HOST = "open"

    fun url(range: VerseRange): String = "$SCHEME://$OPEN_HOST?ref=${range.storageString}"

    /** Accepts `ref=start-end` or a single verse key `ref=43003016`. */
    fun range(url: String): VerseRange? {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(OPEN_HOST, ignoreCase = true)) return null
        val ref = uri.rawQuery.orEmpty().split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "ref" }
            ?.let { URLDecoder.decode(it[1], Charsets.UTF_8) }
            ?: return null
        VerseRange.parse(ref)?.let { return it }
        return ref.toIntOrNull()?.let { VerseRange.ref(it) }?.let { VerseRange(it, it) }
    }
}

/** Text helpers shared by the widgets, the tile and the complications — Swift's `VerseStyling`. */
object VerseText {
    /** "Jehovah is my shepherd; I shall…" — the opening of a passage for tight spaces. */
    fun openingWords(text: String, maxWords: Int = 6): String {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size <= maxWords) return text
        val head = words.take(maxWords).joinToString(" ")
        return head.trim { it.isPunctuationMark() } + "…"
    }

    /** "1 Cor 13:4–7" → ("1 Cor", "13:4–7"): the two lines of a circular complication. */
    fun split(reference: String): Pair<String, String> {
        val space = reference.lastIndexOf(' ')
        if (space < 0) return reference to ""
        return reference.substring(0, space) to reference.substring(space + 1)
    }
}

/**
 * The widget page, sunrise to parchment — `VerseStyling.Palette` — as ARGB. Matches the reader's
 * Sepia theme and the app icon. The watch uses [DARK], as watch faces are dark.
 */
data class VersePalette(
    val pageTop: Long,
    val pageBottom: Long,
    val ink: Long,
    val secondaryInk: Long,
    val accent: Long,
    val wordsOfChrist: Long,
) {
    companion object {
        val LIGHT = VersePalette(0xFFFFF3DF, 0xFFF7DDBC, 0xFF33261A, 0xFF6E5A45, 0xFF9A6B2F, 0xFFB0271C)
        val DARK = VersePalette(0xFF2E2219, 0xFF1B1410, 0xFFF3E9DC, 0xFFC9B9A6, 0xFFE0B872, 0xFFFF8A7A)

        /** The watch reader's words-of-Christ red (`WatchVerseText`). */
        const val WATCH_RED = 0xFFFF7A6B

        /** The reader's five highlight colors, by stored name. */
        fun highlight(name: String?): Long = when (name) {
            "green" -> 0xFF8CD48A
            "blue" -> 0xFF7FB8F0
            "pink" -> 0xFFF29BB8
            "purple" -> 0xFFB9A2EC
            else -> 0xFFF7D154
        }
    }
}
