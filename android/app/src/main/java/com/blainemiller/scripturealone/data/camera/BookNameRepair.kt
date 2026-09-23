package com.blainemiller.scripturealone.data.camera

import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.reference.ReferenceDetector

/**
 * Mends Bible book names the recognizer misread, before the slide parser sees them.
 *
 * On iOS, Vision is given every book name and abbreviation as custom words, so its language
 * correction leans toward "Ephesians" rather than away from it. ML Kit has no custom lexicon, so this
 * does the same work afterwards — and only where a reference is plainly meant: a word followed by
 * chapter:verse ("Ephesains 2:8"), never a word in a sentence.
 * [com.blainemiller.scripturealone.data.slides.SlideParser.normalize] then fixes the slips common to
 * both recognizers (a "Jn" read as "In", a semicolon for a colon, "vv. 4-7").
 *
 * - A near miss of a book's name, or of an abbreviation of four letters or more — one letter off for
 *   names up to six letters, two from seven — becomes that name, when exactly one name is that close.
 * - A numbered book's ordinal read as `l`, `|` or a run-in `I` ("l Corinthians", "||Timothy") becomes
 *   the digit. A Roman "I " or "II " is left alone: the reference parser reads those already.
 * - `l`, `I`, `O` and `o` inside a book's chapter and verse ("John l0:ll") become digits.
 */
object BookNameRepair {

    // A book-ish word (with an optional ordinal) and then chapter:verse, where the numbers may hold
    // misread letters; a real digit among them is required in [repair].
    private val reference = Regex(
        """(?<![A-Za-z0-9])((?:[1-3]|[lI|]{1,3})\s?)?([A-Za-z]{2,}|Song\s+of\s+(?:Solomon|Songs))(\.?\s*)""" +
            """([0-9lIOo]{1,3})(\s*[:;.]\s*)([0-9lIOo]{1,3})(?:(\s*[-–—]\s*)([0-9lIOo]{1,3}))?(?![A-Za-z0-9])""",
    )

    private val spaces = Regex("""\s+""")
    private val leadingOrdinal = Regex("""^[1-3]\s*""")

    /** Books whose names carry a number: the ordinal before them may be a misread "1". */
    private val numberedNames: Set<String> = BookID.entries
        .filter { it.englishName.first().isDigit() }
        .flatMap { listOf(stripOrdinal(it.englishName), stripOrdinal(it.englishAbbreviation)) }
        .map { it.lowercase() }
        .toSet() + setOf("jn", "jo", "jhn", "sa", "sm", "ki", "kg", "ch", "chron", "co", "th", "ti", "pe", "pt")

    /** Every name a slide might print, without its ordinal: "Corinthians", "Thess", "Psalm", "Psalms", … */
    private val names: Map<String, String> = buildMap {
        for (book in BookID.entries) {
            val name = stripOrdinal(book.englishName)
            put(name.lowercase(), name)
            val abbreviation = stripOrdinal(book.englishAbbreviation)
            if (abbreviation.length >= 4) put(abbreviation.lowercase(), abbreviation)
        }
        put("psalm", "Psalm")
    }

    /** Every accepted spelling, lowercased and without its ordinal — a word here needs no repair. */
    private val known: Set<String> = BookID.entries.flatMap { book ->
        book.aliases.map { it.trimStart('1', '2', '3') }
    }.toSet() + names.keys.map { it.replace(" ", "") }

    fun repair(text: String): String = reference.replace(text) { m ->
        val g = m.groupValues
        if ((g[4] + g[6] + g[8]).none { it.isDigit() }) return@replace m.value
        val word = g[2]
        val fixedWord = if (isKnown(word)) word else nearest(word) ?: word
        // Only a book's numbers and ordinal are mended: "Step O: 1" is left as it was.
        val book = isKnown(fixedWord)
        fun number(s: String) = if (book) digits(s) else s
        val repaired = buildString {
            append(if (book && m.groups[1] != null) fixOrdinal(g[1], fixedWord) else g[1])
            append(fixedWord).append(g[3])
            append(number(g[4])).append(g[5]).append(number(g[6]))
            if (m.groups[7] != null) append(g[7]).append(number(g[8]))
        }
        // A guessed name must make a real reference — "June 9:30" is not Jude 9:30, which has no chapter 9.
        if (fixedWord != word && ReferenceDetector.detect(repaired).isEmpty()) m.value else repaired
    }

    private fun isKnown(word: String) = spaces.replace(word.lowercase(), "") in known

    private fun digits(s: String) = s.map {
        when (it) {
            'l', 'I' -> '1'
            'O', 'o' -> '0'
            else -> it
        }
    }.joinToString("")

    private fun fixOrdinal(ordinal: String, word: String): String {
        val core = ordinal.trim()
        if (core.all { it.isDigit() }) return ordinal
        if (word.lowercase() !in numberedNames) return ordinal
        // "I Corinthians", "II Tim": a Roman numeral, which the reference parser already understands.
        if (core.all { it == 'I' } && ordinal.last().isWhitespace()) return ordinal
        return "${core.length.coerceAtMost(3)} "
    }

    /** The one name within reach of [word], in [word]'s capitalization; null when none or several are. */
    internal fun nearest(word: String): String? {
        val lower = word.lowercase()
        if (lower.length < 4) return null
        val reach = if (lower.length >= 7) 2 else 1
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        var tied = false
        for ((key, name) in names) {
            if (kotlin.math.abs(key.length - lower.length) > reach) continue
            val d = distance(lower, key)
            if (d > reach) continue
            when {
                d < bestDistance -> {
                    best = name
                    bestDistance = d
                    tied = false
                }
                d == bestDistance && name != best -> tied = true
            }
        }
        if (best == null || tied || bestDistance == 0) return null
        // Capitals when most of the word is ("REVELATlON" — the slip is often the lowercase letter).
        val letters = word.filter { it.isLetter() }
        return if (letters.count { it.isUpperCase() } * 2 > letters.length) best.uppercase() else best
    }

    private fun stripOrdinal(name: String) = leadingOrdinal.replace(name, "")

    /** Optimal-string-alignment distance: insertions, deletions, substitutions and adjacent swaps. */
    internal fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
