package com.blainemiller.scripturealone.data.reference

import com.blainemiller.scripturealone.data.canon.BookID

/**
 * Finds scripture references inside free text — a photographed sermon slide, pasted notes,
 * commentary — without guessing at ordinary words. Ported from `ReferenceDetector.swift`.
 *
 * Ranges are UTF-16 indices into the original string, which is what both Kotlin strings and the
 * Swift original (`NSString`) count in, so a match's range can be used directly — unlike the layout
 * JSON's offsets, which are Unicode scalars.
 */
object ReferenceDetector {

    data class Match(val passage: Passage, val range: IntRange)

    private val pattern: Regex = run {
        val ordinal = """(?:(?:[1-3]|iii|ii|i|first|second|third|1st|2nd|3rd)\s*)?"""
        // Only Song of Solomon/Songs spans words; a general "x of y" would swallow "book of John".
        val name = """(?:song\s+of\s+(?:solomon|songs)|[a-z]+)"""
        val numbers = """(\d{1,3})(?:\s*[:.]\s*(\d{1,3}))?(?:\s*[-–—]\s*(\d{1,3})(?:\s*[:.]\s*(\d{1,3}))?)?"""
        Regex("""\b($ordinal$name)\.?\s*$numbers(?!\d)""", RegexOption.IGNORE_CASE)
    }

    private val firstWord = Regex("""^\S+""")

    fun detect(text: String): List<Match> {
        val matches = mutableListOf<Match>()
        var location = 0
        while (location < text.length) {
            val m = pattern.find(text, location) ?: break
            val match = validate(m)
            if (match != null) {
                matches += match
                location = m.range.last + 1
            } else {
                // "also 1 Pet. 2:25" first matches as book "also", chapter 1. Retry after the first
                // word so the real reference isn't swallowed.
                val word = firstWord.find(m.value)
                location = if (word == null) m.range.last + 1 else m.range.first + word.value.length
            }
        }
        return matches
    }

    private fun validate(m: MatchResult): Match? {
        val bookText = m.groups[1]?.value ?: return null
        val token = BookID.normalize(ReferenceParser.normalizeOrdinals(bookText.lowercase()))
        // Only exact names or abbreviations count here — no prefix guessing inside prose.
        val book = BookID.entries.firstOrNull { token in it.aliases } ?: return null
        val hasVerse = m.groups[3] != null
        // Two-letter abbreviations ("Jn", "Ps") need a verse to count.
        if (token.length <= 2 && !hasVerse) return null
        val passage = ReferenceParser.parse(m.value) ?: return null
        if (passage.book != book || passage.startChapter > book.chapterCount) return null
        return Match(passage, m.range)
    }
}
